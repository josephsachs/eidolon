package com.eidolon.game.scenario

import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
import io.vertx.core.Vertx
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@Singleton
class ObjectInitializer @Inject constructor(
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val evenniaCommUtils: EvenniaCommUtils,
    private val crossLinkRegistry: CrossLinkRegistry,
    private val coroutineScope: CoroutineScope,
    private val vertx: Vertx
) {
    private val log = LoggerFactory.getLogger(ObjectInitializer::class.java)
    private val initialized = AtomicBoolean(false)

    fun initialize() {
        log.info("ObjectInitializer: Registering deferred initialization (waiting for rooms)")
        vertx.eventBus().consumer<JsonObject>(RoomInitializer.ADDRESS_ROOMS_INITIALIZED) { msg ->
            if (initialized.compareAndSet(false, true)) {
                log.info("ObjectInitializer: Rooms initialized, creating scenario objects")
                coroutineScope.launch {
                    try {
                        initializeObjects()
                    } catch (e: Exception) {
                        log.error("ObjectInitializer: Failed to initialize objects: $e")
                    }
                }
            }
        }
    }

    private suspend fun initializeObjects() {
        val objectData = readObjectData()
        if (objectData.isEmpty()) {
            log.info("ObjectInitializer: No object data found in objects.json")
            return
        }

        val roomData = readRoomData()
        val scenarioIdToRoomKey = roomData.associate {
            it.getString("id") to it.getString("shortDescription", "")
        }
        val roomKeyToEvenniaId = buildRoomKeyToEvenniaId(scenarioIdToRoomKey)

        val commands = mutableListOf<JsonObject>()
        for (obj in objectData) {
            val name = obj.getString("name")
            val roomScenarioId = obj.getString("room", "")
            val roomKey = scenarioIdToRoomKey[roomScenarioId]
            val roomEvenniaId = if (roomKey != null) roomKeyToEvenniaId[roomKey] else null

            if (roomEvenniaId == null) {
                log.warn("ObjectInitializer: Room '$roomScenarioId' not found for object '$name', skipping")
                continue
            }

            val cmd = JsonObject()
                .put("action", "create_object")
                .put("object_name", name)
                .put("room_evennia_id", roomEvenniaId)
                .put("description", obj.getString("description", ""))
            commands.add(cmd)
        }

        if (commands.isNotEmpty()) {
            log.info("ObjectInitializer: Creating ${commands.size} objects")
            evenniaCommUtils.sendBatchCommands(commands)
        }
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

    private suspend fun readObjectData(): List<JsonObject> {
        return try {
            val buffer = vertx.fileSystem().readFile("scenario/objects.json").await()
            val array = JsonArray(buffer.toString())
            array.map { it as JsonObject }
        } catch (e: Exception) {
            log.error("Failed to read objects.json: $e")
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
