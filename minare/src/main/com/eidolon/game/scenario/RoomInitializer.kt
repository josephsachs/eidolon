package com.eidolon.game.scenario

import com.eidolon.game.commands.LinkDomainEntity
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
    private val linkDomainEntity: LinkDomainEntity,
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

        // Build lookup: scenarioId -> roomKey (shortDescription)
        val scenarioIdToRoomKey = roomData.associate {
            it.getString("id") to it.getString("shortDescription", "")
        }

        // Phase 1: Send dig commands to Evennia, wait for ObjectParent registrations
        val roomKeyToEvenniaId = digRooms(roomData, scenarioIdToRoomKey.values.toSet())

        // Phase 2: Send describe commands to set room descriptions
        describeRooms(roomData, scenarioIdToRoomKey, roomKeyToEvenniaId)

        // Phase 3: Create exits between rooms
        createExits(roomData, scenarioIdToRoomKey, roomKeyToEvenniaId)

        // Phase 4: Create lightweight Room entities + RoomMemory in Minare
        createMinareEntities(roomData, scenarioIdToRoomKey, roomKeyToEvenniaId, defaultChannelId)

        log.info("RoomInitializer: initialized ${roomData.size} rooms via agent commands")
    }

    /**
     * Phase 1: Send batch dig commands to Evennia and wait for ObjectParent registrations.
     * CmdDig creates rooms; ObjectParent.at_object_creation fires register_evennia_object.
     * Returns a map of roomKey -> evenniaId.
     */
    private suspend fun digRooms(
        roomData: List<JsonObject>,
        expectedRoomKeys: Set<String>
    ): Map<String, String> {
        val roomKeyToEvenniaId = mutableMapOf<String, String>()
        val pendingCount = AtomicInteger(roomData.size)
        val latch = CompletableDeferred<Unit>()

        // Listen for register_evennia_object events on the Vert.x event bus
        val consumer = vertx.eventBus().consumer<JsonObject>("eidolon.evennia_object.registered") { msg ->
            val body = msg.body()
            val key = body.getString("key", "")
            val evenniaId = body.getString("evennia_id", "")
            if (key in expectedRoomKeys && evenniaId.isNotEmpty()) {
                roomKeyToEvenniaId[key] = evenniaId
                log.info("Room registered: key=$key, evenniaId=$evenniaId")
                if (pendingCount.decrementAndGet() == 0) {
                    latch.complete(Unit)
                }
            }
        }

        // Build and send batch dig commands via generic dispatcher
        val digCommands = roomData.map { json ->
            JsonObject()
                .put("action", "dig")
                .put("text", json.getString("shortDescription", "New Room"))
        }
        log.info("RoomInitializer: Sending batch dig for ${digCommands.size} rooms")
        evenniaCommUtils.sendBatchCommands(digCommands)

        // Wait for all confirmations
        try {
            withTimeout(30_000) { latch.await() }
        } catch (e: Exception) {
            log.error("RoomInitializer: Timed out waiting for room registrations. " +
                "Received ${roomKeyToEvenniaId.size}/${roomData.size}")
        } finally {
            consumer.unregister()
        }

        return roomKeyToEvenniaId
    }

    /**
     * Phase 2: Send describe commands to set room descriptions.
     * CmdDig creates rooms but doesn't set descriptions.
     */
    private suspend fun describeRooms(
        roomData: List<JsonObject>,
        scenarioIdToRoomKey: Map<String, String>,
        roomKeyToEvenniaId: Map<String, String>
    ) {
        val describeCommands = mutableListOf<JsonObject>()

        for (json in roomData) {
            val roomKey = scenarioIdToRoomKey[json.getString("id")] ?: continue
            val evenniaId = roomKeyToEvenniaId[roomKey] ?: continue
            val description = json.getString("description", "")
            if (description.isNotEmpty()) {
                describeCommands.add(JsonObject()
                    .put("action", "describe")
                    .put("room_evennia_id", evenniaId)
                    .put("text", description))
            }
        }

        if (describeCommands.isNotEmpty()) {
            log.info("RoomInitializer: Sending batch describe for ${describeCommands.size} rooms")
            evenniaCommUtils.sendBatchCommands(describeCommands)
        }
    }

    /**
     * Phase 3: Create one-way exits between rooms using the roomKey map.
     */
    private suspend fun createExits(
        roomData: List<JsonObject>,
        scenarioIdToRoomKey: Map<String, String>,
        roomKeyToEvenniaId: Map<String, String>
    ) {
        val exitCommands = mutableListOf<JsonObject>()

        for (json in roomData) {
            val sourceRoomKey = scenarioIdToRoomKey[json.getString("id")] ?: continue
            val sourceEvenniaId = roomKeyToEvenniaId[sourceRoomKey] ?: continue

            for (exitObj in json.getJsonArray("exits", JsonArray()).map { it as JsonObject }) {
                val direction = exitObj.getString("direction")
                val destScenarioId = exitObj.getString("destination")
                val destRoomKey = scenarioIdToRoomKey[destScenarioId]
                val destEvenniaId = if (destRoomKey != null) roomKeyToEvenniaId[destRoomKey] else null
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
     * Phase 4: Create lightweight Room entities and RoomMemory entities in Minare.
     * Room entities store shortDescription and roomMemoryId — full description lives in Evennia.
     */
    private suspend fun createMinareEntities(
        roomData: List<JsonObject>,
        scenarioIdToRoomKey: Map<String, String>,
        roomKeyToEvenniaId: Map<String, String>,
        defaultChannelId: String
    ) {
        val rooms = mutableListOf<Room>()
        val memories = mutableListOf<RoomMemory>()

        for (json in roomData) {
            val roomKey = scenarioIdToRoomKey[json.getString("id")] ?: continue
            val evenniaId = roomKeyToEvenniaId[roomKey] ?: continue

            // Create Room entity (lightweight — description lives in Evennia)
            val room = entityFactory.createEntity(Room::class.java) as Room
            room.shortDescription = json.getString("shortDescription", "")
            room.description = "" // Evennia is authoritative for descriptions
            room.dayDesc = json.getString("dayDesc", "")
            room.nightDesc = json.getString("nightDesc", "")
            entityController.create(room)

            // Create RoomMemory child
            val memory = entityFactory.createEntity(RoomMemory::class.java) as RoomMemory
            memory.roomId = room._id!!
            entityController.create(memory)
            entityController.saveState(room._id!!, JsonObject().put("roomMemoryId", memory._id))

            // Link EvenniaObject stub <-> Room domain entity
            val eoMinareId = crossLinkRegistry.getMinareId("EvenniaObject", evenniaId) ?: ""
            linkDomainEntity.execute(JsonObject()
                .put("evennia_id", evenniaId)
                .put("eo_minare_id", eoMinareId)
                .put("domain_entity_id", room._id)
                .put("domain_entity_type", "Room"))

            rooms.add(room)
            memories.add(memory)
            log.info("Created Minare Room '${room.shortDescription}' (id=${room._id}, evenniaId=$evenniaId)")
        }

        gameChannelController.addEntitiesToChannel(rooms, defaultChannelId)
        gameChannelController.addEntitiesToChannel(memories, defaultChannelId)

        // Notify Evennia of domain entity links so it can match incoming updates
        for (json in roomData) {
            val roomKey = scenarioIdToRoomKey[json.getString("id")] ?: continue
            val evenniaId = roomKeyToEvenniaId[roomKey] ?: continue
            val room = rooms.find { it.shortDescription == roomKey } ?: continue
            gameChannelController.broadcast(defaultChannelId, JsonObject()
                .put("type", "set_domain_link")
                .put("evennia_id", evenniaId)
                .put("domain_entity_id", room._id)
                .put("domain_entity_type", "Room"))
        }

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
