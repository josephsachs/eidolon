package com.eidolon.game.scenario

import com.eidolon.game.controller.GameChannelController
import com.eidolon.game.models.entity.Room
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.entity.factories.EntityFactory
import com.minare.core.entity.models.Entity
import io.vertx.core.Vertx
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await

@Singleton
class RoomInitializer @Inject constructor(
    private val gameChannelController: GameChannelController,
    private val entityFactory: EntityFactory,
    private val entityController: EntityController,
    private val vertx: Vertx
) {
    private val log = LoggerFactory.getLogger(RoomInitializer::class.java)

    suspend fun initialize() {
        val entities = mutableListOf<Entity>()
        val defaultChannelId = gameChannelController.getDefaultChannel()

        log.info("Set default channel: $defaultChannelId")

        readRoomData().forEach { jsonObject ->
            val room = entityFactory.createEntity(Room::class.java) as Room

            /**
            room.evenniaId = jsonObject.getString("evenniaId")

             etc.*/

            entityController.create(room) as Room
            entities.add(room)
        }

        gameChannelController.addEntitiesToChannel(entities.toList(), defaultChannelId!!)
    }

    private suspend fun readRoomData(): List<JsonObject> {
        return try {
            val buffer = vertx.fileSystem().readFile("scenario/rooms.json").await()

            val mapZonesArray = JsonArray(buffer.toString())
            mapZonesArray.map { it as JsonObject }
        } catch (e: Exception) {
            log.error("Failed to read rooms.json: $e")
            emptyList()
        }
    }
}