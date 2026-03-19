package com.eidolon.game.scenario

import eidolon.game.controller.GameChannelController
import com.eidolon.game.models.entity.Exit
import com.eidolon.game.models.entity.Room
import com.eidolon.game.models.entity.RoomMemory
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

        // Pass 1: create Room entities + RoomMemory children, build lookup by JSON id
        val roomById = mutableMapOf<String, Room>()
        val allMemories = mutableListOf<RoomMemory>()
        for (json in roomData) {
            val id = json.getString("id")
            val room = entityFactory.createEntity(Room::class.java) as Room
            room.shortDescription = json.getString("shortDescription", "")
            room.description = json.getString("description", "")
            entityController.create(room)

            // Create RoomMemory child and link
            val memory = entityFactory.createEntity(RoomMemory::class.java) as RoomMemory
            memory.roomId = room._id!!
            entityController.create(memory)
            entityController.saveState(room._id!!, JsonObject().put("roomMemoryId", memory._id))
            allMemories.add(memory)

            roomById[id] = room
            log.info("Created Room '${room.shortDescription}' (id=${room._id}, memory=${memory._id})")
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
        gameChannelController.addEntitiesToChannel(allMemories, defaultChannelId)
        log.info("RoomInitializer: created ${roomById.size} rooms, ${allExits.size} exits, ${allMemories.size} room memories")
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
