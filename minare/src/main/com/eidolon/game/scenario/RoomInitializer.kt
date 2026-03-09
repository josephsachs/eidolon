package com.eidolon.game.scenario

import com.eidolon.game.controller.GameChannelController
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

    private val directionDeltas = mapOf(
        "north" to Pair(0, -1),
        "south" to Pair(0, 1),
        "east"  to Pair(1, 0),
        "west"  to Pair(-1, 0)
    )

    suspend fun initialize() {
        val defaultChannelId = gameChannelController.getDefaultChannel()
        val roomData = readRoomData()

        // Pass 1: create Room entities, build coordinate lookup
        val roomByCoord = mutableMapOf<Pair<Int, Int>, Room>()
        for (json in roomData) {
            val room = entityFactory.createEntity(Room::class.java) as Room
            room.shortDescription = json.getString("shortDescription", "")
            room.description = json.getString("description", "")
            entityController.create(room)
            roomByCoord[Pair(json.getInteger("x"), json.getInteger("y"))] = room
            log.info("Created Room '${room.shortDescription}' (id=${room._id})")
        }

        // Pass 2: create Exit entities and wire Room.exits
        for (json in roomData) {
            val x = json.getInteger("x")
            val y = json.getInteger("y")
            val sourceRoom = roomByCoord[Pair(x, y)] ?: continue
            val exitsJson = JsonObject()

            for (dir in json.getJsonArray("exits", JsonArray())) {
                val direction = dir as String
                val (dx, dy) = directionDeltas[direction] ?: continue
                val destRoom = roomByCoord[Pair(x + dx, y + dy)] ?: continue

                val exit = entityFactory.createEntity(Exit::class.java) as Exit
                exit.destination = destRoom._id
                entityController.create(exit)
                exitsJson.put(direction, exit._id)
            }

            entityController.saveState(sourceRoom._id!!, JsonObject().put("exits", exitsJson))
        }

        gameChannelController.addEntitiesToChannel(roomByCoord.values.toList(), defaultChannelId!!)
        log.info("RoomInitializer: created ${roomByCoord.size} rooms")
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
