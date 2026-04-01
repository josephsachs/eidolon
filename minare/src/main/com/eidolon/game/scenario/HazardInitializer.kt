package com.eidolon.game.scenario

import com.eidolon.game.commands.LinkDomainEntity
import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.models.entity.ObjectActor
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.entity.factories.EntityFactory
import com.minare.core.storage.interfaces.StateStore
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
class HazardInitializer @Inject constructor(
    private val gameChannelController: GameChannelController,
    private val entityFactory: EntityFactory,
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val evenniaCommUtils: EvenniaCommUtils,
    private val crossLinkRegistry: CrossLinkRegistry,
    private val linkDomainEntity: LinkDomainEntity,
    private val coroutineScope: CoroutineScope,
    private val vertx: Vertx
) {
    private val log = LoggerFactory.getLogger(HazardInitializer::class.java)
    private val initialized = AtomicBoolean(false)

    fun initialize() {
        log.info("HazardInitializer: Registering deferred initialization (waiting for rooms)")
        vertx.eventBus().consumer<JsonObject>(RoomInitializer.ADDRESS_ROOMS_INITIALIZED) { msg ->
            if (initialized.compareAndSet(false, true)) {
                log.info("HazardInitializer: Rooms initialized, starting hazard initialization")
                coroutineScope.launch {
                    try {
                        initializeHazards()
                    } catch (e: Exception) {
                        log.error("HazardInitializer: Failed to initialize hazards: $e")
                    }
                }
            }
        }
    }

    private suspend fun initializeHazards() {
        val defaultChannelId = gameChannelController.getDefaultChannel()
            ?: throw IllegalStateException("Default channel must exist before HazardInitializer runs")

        val hazardData = readHazardData()
        if (hazardData.isEmpty()) {
            log.warn("HazardInitializer: No hazard data found in hazards.json")
            return
        }

        // Resolve room scenario IDs to Minare room IDs
        val roomData = readRoomData()
        val scenarioIdToRoomKey = roomData.associate {
            it.getString("id") to it.getString("shortDescription", "")
        }

        val roomKeyToMinareId = buildRoomKeyToMinareId(scenarioIdToRoomKey)
        val roomKeyToEvenniaId = buildRoomKeyToEvenniaId(scenarioIdToRoomKey)

        // Create Evennia objects for hazards
        val hazardNameToEvenniaId = createEvenniaHazards(
            hazardData, scenarioIdToRoomKey, roomKeyToEvenniaId)

        // Create Minare entities
        createMinareEntities(
            hazardData, hazardNameToEvenniaId, scenarioIdToRoomKey,
            roomKeyToMinareId, roomKeyToEvenniaId, defaultChannelId)

        log.info("HazardInitializer: initialized ${hazardNameToEvenniaId.size} hazards")
    }

    private suspend fun buildRoomKeyToMinareId(
        scenarioIdToRoomKey: Map<String, String>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val roomKeys = stateStore.findAllKeysForType("Room")
        val rooms = entityController.findByIds(roomKeys)
        for ((id, entity) in rooms) {
            val room = entity as? com.eidolon.game.models.entity.Room ?: continue
            if (room.shortDescription.isNotEmpty()) {
                result[room.shortDescription] = id
            }
        }
        return result
    }

    private suspend fun buildRoomKeyToEvenniaId(
        scenarioIdToRoomKey: Map<String, String>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val roomKeys = stateStore.findAllKeysForType("Room")
        val rooms = entityController.findByIds(roomKeys)
        for ((id, entity) in rooms) {
            val room = entity as? com.eidolon.game.models.entity.Room ?: continue
            val evenniaId = crossLinkRegistry.getEvenniaId("Room", id)
            if (evenniaId != null && room.shortDescription.isNotEmpty()) {
                result[room.shortDescription] = evenniaId
            }
        }
        return result
    }

    private suspend fun createEvenniaHazards(
        hazardData: List<JsonObject>,
        scenarioIdToRoomKey: Map<String, String>,
        roomKeyToEvenniaId: Map<String, String>
    ): Map<String, String> {
        val hazardNameToEvenniaId = mutableMapOf<String, String>()
        val expectedNames = hazardData.map { it.getString("name") }.toSet()
        val pendingCount = AtomicInteger(hazardData.size)
        val latch = CompletableDeferred<Unit>()

        val consumer = vertx.eventBus().consumer<JsonObject>("eidolon.evennia_object.registered") { msg ->
            val body = msg.body()
            val key = body.getString("key", "")
            val evenniaId = body.getString("evennia_id", "")
            if (key in expectedNames && evenniaId.isNotEmpty()) {
                hazardNameToEvenniaId[key] = evenniaId
                log.info("Hazard registered: key=$key, evenniaId=$evenniaId")
                if (pendingCount.decrementAndGet() == 0) {
                    latch.complete(Unit)
                }
            }
        }

        val createCommands = mutableListOf<JsonObject>()
        for (hazard in hazardData) {
            val name = hazard.getString("name")
            val roomScenarioId = hazard.getString("room", "")
            val roomKey = scenarioIdToRoomKey[roomScenarioId]
            val roomEvenniaId = if (roomKey != null) roomKeyToEvenniaId[roomKey] else null

            if (roomEvenniaId == null) {
                log.warn("HazardInitializer: Room '$roomScenarioId' not found for hazard '$name', skipping")
                pendingCount.decrementAndGet()
                continue
            }

            createCommands.add(JsonObject()
                .put("action", "create_object")
                .put("object_name", name)
                .put("room_evennia_id", roomEvenniaId)
                .put("description", hazard.getString("description", "")))
        }

        if (createCommands.isNotEmpty()) {
            log.info("HazardInitializer: Sending batch create for ${createCommands.size} hazards")
            evenniaCommUtils.sendBatchCommands(createCommands)
        }

        try {
            withTimeout(30_000) { latch.await() }
        } catch (e: Exception) {
            log.error("HazardInitializer: Timed out waiting for hazard registrations. " +
                "Received ${hazardNameToEvenniaId.size}/${hazardData.size}")
        } finally {
            consumer.unregister()
        }

        return hazardNameToEvenniaId
    }

    private suspend fun createMinareEntities(
        hazardData: List<JsonObject>,
        hazardNameToEvenniaId: Map<String, String>,
        scenarioIdToRoomKey: Map<String, String>,
        roomKeyToMinareId: Map<String, String>,
        roomKeyToEvenniaId: Map<String, String>,
        defaultChannelId: String
    ) {
        val hazards = mutableListOf<ObjectActor>()

        for (hazard in hazardData) {
            val name = hazard.getString("name")
            val evenniaId = hazardNameToEvenniaId[name] ?: continue
            val roomScenarioId = hazard.getString("room", "")
            val roomKey = scenarioIdToRoomKey[roomScenarioId] ?: ""
            val roomMinareId = roomKeyToMinareId.getOrDefault(roomKey, "")
            val roomEvenniaId = roomKeyToEvenniaId.getOrDefault(roomKey, "")

            val entity = entityFactory.createEntity(ObjectActor::class.java) as ObjectActor
            entityController.create(entity)

            entityController.saveState(entity._id, JsonObject()
                .put("evenniaId", evenniaId)
                .put("roomId", roomMinareId)
                .put("roomEvenniaId", roomEvenniaId)
                .put("actorType", "exploding_hazard")
                .put("hardpointDamage", hazard.getInteger("hardpointDamage", 8))
                .put("vitalityDamage", hazard.getInteger("vitalityDamage", 4))
                .put("burnDuration", hazard.getLong("burnDuration", 15000L))
                .put("burnTickDamage", hazard.getInteger("burnTickDamage", 3))
                .put("intervalMin", hazard.getLong("intervalMin", 8000L))
                .put("intervalMax", hazard.getLong("intervalMax", 20000L))
                .put("explosionMessages", hazard.getJsonArray("explosionMessages",
                    JsonArray().add("The barrel explodes!"))))

            linkDomainEntity.link(evenniaId, entity._id, "ObjectActor")

            hazards.add(entity)
            log.info("Created ObjectActor '$name' (id=${entity._id}, room=$roomMinareId)")
        }

        if (hazards.isNotEmpty()) {
            gameChannelController.addEntitiesToChannel(hazards, defaultChannelId)
        }
    }

    private suspend fun readHazardData(): List<JsonObject> {
        return try {
            val buffer = vertx.fileSystem().readFile("scenario/hazards.json").await()
            val array = JsonArray(buffer.toString())
            array.map { it as JsonObject }
        } catch (e: Exception) {
            log.error("Failed to read hazards.json: $e")
            emptyList()
        }
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
