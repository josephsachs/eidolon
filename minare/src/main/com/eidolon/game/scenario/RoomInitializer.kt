package com.eidolon.game.scenario

import eidolon.game.controller.GameChannelController
import com.eidolon.game.models.entity.Exit
import com.eidolon.game.models.entity.Room
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.entity.factories.EntityFactory
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
        val defaultChannelId = gameChannelController.getDefaultChannel()
            ?: throw IllegalStateException("Default channel must be created before RoomInitializer runs")
        val roomData = readRoomData()

        // Pass 1: create Room entities, build lookup by JSON id
        val roomById = mutableMapOf<String, Room>()
        for (json in roomData) {
            val id = json.getString("id")
            val room = entityFactory.createEntity(Room::class.java) as Room
            room.shortDescription = json.getString("shortDescription", "")
            room.description = json.getString("description", "")
            entityController.create(room)
            roomById[id] = room
            log.info("Created Room '${room.shortDescription}' (id=${room._id})")
        }

        // Pass 2: create Exit entities and wire Room.exits
        val allExits = mutableListOf<Exit>()
        for (json in roomData) {
            val sourceRoom = roomById[json.getString("id")] ?: continue
            val exitsJson = JsonObject()

            for (exitObj in json.getJsonArray("exits", JsonArray()).map { it as JsonObject }) {
                val direction = exitObj.getString("direction")
                val destId = exitObj.getString("destination")
                val destRoom = roomById[destId]
                if (destRoom == null) {
                    log.warn("Room '${json.getString("id")}': exit '$direction' references unknown room '$destId', skipping")
                    continue
                }

                val exit = entityFactory.createEntity(Exit::class.java) as Exit
                exit.direction = direction
                exit.destination = destRoom._id
                exit.description = exitObj.getString("description", "")
                entityController.create(exit)
                exitsJson.put(direction, exit._id)
                allExits.add(exit)
            }

            entityController.saveState(sourceRoom._id!!, JsonObject().put("exits", exitsJson))
        }

        gameChannelController.addEntitiesToChannel(roomById.values.toList(), defaultChannelId)
        gameChannelController.addEntitiesToChannel(allExits, defaultChannelId)
        log.info("RoomInitializer: created ${roomById.size} rooms and ${allExits.size} exits")
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
