package com.eidolon.game.scenario

import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.models.entity.Spawner
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@Singleton
class SpawnerInitializer @Inject constructor(
    private val gameChannelController: GameChannelController,
    private val entityFactory: EntityFactory,
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val crossLinkRegistry: CrossLinkRegistry,
    private val coroutineScope: CoroutineScope,
    private val vertx: Vertx
) {
    private val log = LoggerFactory.getLogger(SpawnerInitializer::class.java)
    private val initialized = AtomicBoolean(false)

    fun initialize() {
        vertx.eventBus().consumer<JsonObject>(RoomInitializer.ADDRESS_ROOMS_INITIALIZED) { _ ->
            if (initialized.compareAndSet(false, true)) {
                coroutineScope.launch {
                    try {
                        initializeSpawners()
                    } catch (e: Exception) {
                        log.error("SpawnerInitializer: Failed: $e")
                    }
                }
            }
        }
    }

    private suspend fun initializeSpawners() {
        val defaultChannelId = gameChannelController.getDefaultChannel()
            ?: throw IllegalStateException("Default channel must exist before SpawnerInitializer runs")

        val spawnerData = readSpawnerData()
        if (spawnerData.isEmpty()) return

        // Resolve room scenario IDs to Minare room IDs
        val roomData = readRoomData()
        val scenarioIdToRoomKey = roomData.associate {
            it.getString("id") to it.getString("shortDescription", "")
        }
        val roomKeyToMinareId = buildRoomKeyToMinareId(scenarioIdToRoomKey)

        val spawners = mutableListOf<Spawner>()

        for (data in spawnerData) {
            val templateId = data.getString("templateId", "")
            val roomScenarioId = data.getString("room", "")
            val intervalMs = data.getLong("intervalMs", 120_000L)

            val roomKey = scenarioIdToRoomKey[roomScenarioId] ?: continue
            val roomMinareId = roomKeyToMinareId[roomKey] ?: continue

            val spawner = entityFactory.createEntity(Spawner::class.java) as Spawner
            entityController.create(spawner)
            entityController.saveState(spawner._id!!, JsonObject()
                .put("templateId", templateId)
                .put("roomId", roomMinareId))
            entityController.saveProperties(spawner._id!!, JsonObject()
                .put("intervalMs", intervalMs))

            spawners.add(spawner)
            log.info("Created Spawner '${templateId}' in room $roomMinareId (interval=${intervalMs}ms)")
        }

        if (spawners.isNotEmpty()) {
            gameChannelController.addEntitiesToChannel(spawners, defaultChannelId)
        }

        log.info("SpawnerInitializer: initialized ${spawners.size} spawners")
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

    private suspend fun readSpawnerData(): List<JsonObject> {
        return try {
            val buffer = vertx.fileSystem().readFile("scenario/spawners.json").await()
            val array = JsonArray(buffer.toString())
            array.map { it as JsonObject }
        } catch (e: Exception) {
            log.error("Failed to read spawners.json: $e")
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
