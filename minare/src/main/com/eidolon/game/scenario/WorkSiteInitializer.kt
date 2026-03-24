package com.eidolon.game.scenario

import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.models.entity.WorkSite
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
class WorkSiteInitializer @Inject constructor(
    private val gameChannelController: GameChannelController,
    private val entityFactory: EntityFactory,
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val crossLinkRegistry: CrossLinkRegistry,
    private val evenniaCommUtils: EvenniaCommUtils,
    private val coroutineScope: CoroutineScope,
    private val vertx: Vertx
) {
    private val log = LoggerFactory.getLogger(WorkSiteInitializer::class.java)
    private val initialized = AtomicBoolean(false)

    fun initialize() {
        vertx.eventBus().consumer<JsonObject>(RoomInitializer.ADDRESS_ROOMS_INITIALIZED) { _ ->
            if (initialized.compareAndSet(false, true)) {
                coroutineScope.launch {
                    try {
                        initializeWorkSites()
                    } catch (e: Exception) {
                        log.error("WorkSiteInitializer: Failed: $e")
                    }
                }
            }
        }
    }

    private suspend fun initializeWorkSites() {
        val defaultChannelId = gameChannelController.getDefaultChannel()
            ?: throw IllegalStateException("Default channel must exist before WorkSiteInitializer runs")

        val siteData = readWorkSiteData()
        if (siteData.isEmpty()) return

        val roomData = readRoomData()
        val scenarioIdToRoomKey = roomData.associate {
            it.getString("id") to it.getString("shortDescription", "")
        }
        val roomKeyToMinareId = buildRoomKeyToMinareId(scenarioIdToRoomKey)
        val roomKeyToEvenniaId = buildRoomKeyToEvenniaId(scenarioIdToRoomKey)

        val workSites = mutableListOf<WorkSite>()
        val objectCommands = mutableListOf<JsonObject>()

        for (data in siteData) {
            val name = data.getString("name", "")
            val templateId = data.getString("templateId", "")
            val roomScenarioId = data.getString("room", "")
            val skillName = data.getString("skillName", "")
            val intervalMs = data.getLong("intervalMs", 120_000L)
            val objectDescription = data.getString("objectDescription", "")

            val roomKey = scenarioIdToRoomKey[roomScenarioId] ?: continue
            val roomMinareId = roomKeyToMinareId[roomKey] ?: continue
            val roomEvenniaId = roomKeyToEvenniaId[roomKey] ?: continue

            val workSite = entityFactory.createEntity(WorkSite::class.java) as WorkSite
            entityController.create(workSite)
            entityController.saveState(workSite._id!!, JsonObject()
                .put("name", name)
                .put("templateId", templateId)
                .put("roomId", roomMinareId)
                .put("skillName", skillName))
            entityController.saveProperties(workSite._id!!, JsonObject()
                .put("intervalMs", intervalMs))

            workSites.add(workSite)

            // Create the visible object in Evennia
            objectCommands.add(JsonObject()
                .put("action", "create_object")
                .put("object_name", name)
                .put("room_evennia_id", roomEvenniaId)
                .put("description", objectDescription))

            log.info("Created WorkSite '$name' (template=$templateId, skill=$skillName) in room $roomMinareId")
        }

        if (objectCommands.isNotEmpty()) {
            evenniaCommUtils.sendBatchCommands(objectCommands)
        }

        if (workSites.isNotEmpty()) {
            gameChannelController.addEntitiesToChannel(workSites, defaultChannelId)
        }

        log.info("WorkSiteInitializer: initialized ${workSites.size} work sites")
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

    private suspend fun readWorkSiteData(): List<JsonObject> {
        return try {
            val buffer = vertx.fileSystem().readFile("scenario/worksites.json").await()
            val array = JsonArray(buffer.toString())
            array.map { it as JsonObject }
        } catch (e: Exception) {
            log.error("Failed to read worksites.json: $e")
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
