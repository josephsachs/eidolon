package com.eidolon.game.scenario

import com.eidolon.game.controller.GameConnectionController
import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.models.entity.Room
import com.eidolon.game.models.entity.RoomMemory
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.entity.factories.EntityFactory
import eidolon.game.controller.GameChannelController
import io.vertx.core.Vertx
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Singleton
class RoomInitializer @Inject constructor(
    private val gameChannelController: GameChannelController,
    private val entityFactory: EntityFactory,
    private val entityController: EntityController,
    private val evenniaCommUtils: EvenniaCommUtils,
    private val crossLinkRegistry: CrossLinkRegistry,
    private val coroutineScope: CoroutineScope,
    private val vertx: Vertx
) {
    private val log = LoggerFactory.getLogger(RoomInitializer::class.java)
    private val initialized = AtomicBoolean(false)

    /**
     * Register a deferred initialization that fires when Evennia connects.
     * Room creation happens via agent commands, so Evennia must be connected first.
     */
    fun initialize() {
        log.info("RoomInitializer: Registering deferred initialization (waiting for Evennia)")
        vertx.eventBus().consumer<JsonObject>(GameConnectionController.ADDRESS_EVENNIA_READY) { msg ->
            if (initialized.compareAndSet(false, true)) {
                log.info("RoomInitializer: Evennia connected, starting room initialization")
                coroutineScope.launch {
                    try {
                        initializeRooms()
                    } catch (e: Exception) {
                        log.error("RoomInitializer: Failed to initialize rooms: $e")
                    }
                }
            }
        }
    }

    private suspend fun initializeRooms() {
        val defaultChannelId = gameChannelController.getDefaultChannel()
            ?: throw IllegalStateException("Default channel must be created before RoomInitializer runs")
        val roomData = readRoomData()
        if (roomData.isEmpty()) {
            log.warn("RoomInitializer: No room data found in rooms.json")
            return
        }

        // Phase 1: Send dig commands to Evennia, wait for confirmations
        val scenarioIdToEvenniaId = digRooms(roomData)

        // Phase 2: Create exits between rooms
        createExits(roomData, scenarioIdToEvenniaId)

        // Phase 3: Create lightweight Room entities + RoomMemory in Minare
        createMinareEntities(roomData, scenarioIdToEvenniaId, defaultChannelId)

        log.info("RoomInitializer: initialized ${roomData.size} rooms via agent commands")
    }

    /**
     * Phase 1: Send batch dig commands to Evennia and wait for room_created confirmations.
     * Returns a map of scenarioId -> evenniaId.
     */
    private suspend fun digRooms(roomData: List<JsonObject>): Map<String, String> {
        val scenarioIdToEvenniaId = mutableMapOf<String, String>()
        val pendingCount = AtomicInteger(roomData.size)
        val latch = CompletableDeferred<Unit>()

        // Listen for room_created events on the Vert.x event bus
        val consumer = vertx.eventBus().consumer<JsonObject>("eidolon.room.created") { msg ->
            val body = msg.body()
            val scenarioId = body.getString("scenario_id", "")
            val evenniaId = body.getString("evennia_id", "")
            if (scenarioId.isNotEmpty() && evenniaId.isNotEmpty()) {
                scenarioIdToEvenniaId[scenarioId] = evenniaId
                log.info("Room confirmed: scenarioId={}, evenniaId={}", scenarioId, evenniaId)
            }
            if (pendingCount.decrementAndGet() == 0) {
                latch.complete(Unit)
            }
        }

        // Build and send batch dig commands
        val digCommands = roomData.map { json ->
            evenniaCommUtils.buildDigCommand(
                roomKey = json.getString("shortDescription", "New Room"),
                description = json.getString("description", ""),
                scenarioId = json.getString("id")
            )
        }
        log.info("RoomInitializer: Sending batch dig for ${digCommands.size} rooms")
        evenniaCommUtils.sendBatchCommands(digCommands)

        // Wait for all confirmations
        try {
            withTimeout(30_000) { latch.await() }
        } catch (e: Exception) {
            log.error("RoomInitializer: Timed out waiting for room confirmations. " +
                "Received ${scenarioIdToEvenniaId.size}/${roomData.size}")
        } finally {
            consumer.unregister()
        }

        return scenarioIdToEvenniaId
    }

    /**
     * Phase 2: Create one-way exits between rooms using the evenniaId map.
     */
    private suspend fun createExits(
        roomData: List<JsonObject>,
        scenarioIdToEvenniaId: Map<String, String>
    ) {
        val exitCommands = mutableListOf<JsonObject>()

        for (json in roomData) {
            val sourceEvenniaId = scenarioIdToEvenniaId[json.getString("id")] ?: continue

            for (exitObj in json.getJsonArray("exits", JsonArray()).map { it as JsonObject }) {
                val direction = exitObj.getString("direction")
                val destScenarioId = exitObj.getString("destination")
                val destEvenniaId = scenarioIdToEvenniaId[destScenarioId]
                if (destEvenniaId == null) {
                    log.warn("RoomInitializer: Exit '$direction' destination '$destScenarioId' not found, skipping")
                    continue
                }

                exitCommands.add(evenniaCommUtils.buildCreateExitCommand(
                    exitName = direction,
                    fromRoomEvenniaId = sourceEvenniaId,
                    toRoomEvenniaId = destEvenniaId
                ))
            }
        }

        if (exitCommands.isNotEmpty()) {
            log.info("RoomInitializer: Sending batch create_exit for ${exitCommands.size} exits")
            evenniaCommUtils.sendBatchCommands(exitCommands)
        }
    }

    /**
     * Phase 3: Create lightweight Room entities and RoomMemory entities in Minare.
     * Room entities store shortDescription and roomMemoryId — full description lives in Evennia.
     */
    private suspend fun createMinareEntities(
        roomData: List<JsonObject>,
        scenarioIdToEvenniaId: Map<String, String>,
        defaultChannelId: String
    ) {
        val rooms = mutableListOf<Room>()
        val memories = mutableListOf<RoomMemory>()

        for (json in roomData) {
            val scenarioId = json.getString("id")
            val evenniaId = scenarioIdToEvenniaId[scenarioId] ?: continue

            // Create Room entity (lightweight — description lives in Evennia)
            val room = entityFactory.createEntity(Room::class.java) as Room
            room.shortDescription = json.getString("shortDescription", "")
            room.description = "" // Evennia is authoritative for descriptions
            entityController.create(room)

            // Create RoomMemory child
            val memory = entityFactory.createEntity(RoomMemory::class.java) as RoomMemory
            memory.roomId = room._id!!
            entityController.create(memory)
            entityController.saveState(room._id!!, JsonObject().put("roomMemoryId", memory._id))

            // Register cross-link: Room entity <-> Evennia room
            crossLinkRegistry.link("Room", room._id!!, evenniaId)

            rooms.add(room)
            memories.add(memory)
            log.info("Created Minare Room '${room.shortDescription}' (id=${room._id}, evenniaId=$evenniaId)")
        }

        gameChannelController.addEntitiesToChannel(rooms, defaultChannelId)
        gameChannelController.addEntitiesToChannel(memories, defaultChannelId)
        log.info("RoomInitializer: created ${rooms.size} Room entities and ${memories.size} RoomMemory entities")
    }

    private suspend fun readRoomData(): List<JsonObject> {
        return try {
            val buffer = vertx.fileSystem().readFile("scenario/rooms.json").await()
            val array = JsonArray(buffer.toString())
            array.map { it as JsonObject }
        } catch (e: Exception) {
            log.error("Failed to read rooms.json: $e")
            emptyList()
        }
    }
}
