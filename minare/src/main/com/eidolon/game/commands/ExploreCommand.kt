package com.eidolon.game.commands

import com.eidolon.game.models.entity.ExplorableExit
import com.eidolon.game.models.entity.Room
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

@Singleton
class ExploreCommand @Inject constructor(
    private val entityController: EntityController
) : EvenniaCommand {
    private val log = LoggerFactory.getLogger(ExploreCommand::class.java)

    override suspend fun execute(message: JsonObject): JsonObject {
        val characterId = message.getString("character_id", "")
        val roomId = message.getString("room_id", "")
        val direction = message.getString("direction", "")

        if (characterId.isEmpty() || roomId.isEmpty() || direction.isEmpty()) {
            return JsonObject().put("status", "error").put("error", "Missing required fields")
        }

        val room = entityController.findByIds(listOf(roomId))[roomId] as? Room
            ?: return JsonObject().put("status", "error").put("error", "Room not found")

        val exitId = room.exits.getString(direction, "")
        if (exitId.isEmpty()) {
            return JsonObject().put("status", "error").put("error", "No exit in that direction")
        }

        val exit = entityController.findByIds(listOf(exitId))[exitId] as? ExplorableExit
            ?: return JsonObject().put("status", "error").put("error", "That exit cannot be explored")

        if (exit.unlocked) {
            return JsonObject().put("status", "error").put("error", "That path is already open")
        }

        // Add character to explorers if not present
        if (characterId !in exit.explorers) {
            val updatedExplorers = exit.explorers + characterId
            entityController.saveState(exitId, JsonObject()
                .put("explorers", updatedExplorers.toList()))
        }

        log.info("ExploreCommand: {} exploring {} in room {}", characterId, direction, roomId)

        return JsonObject()
            .put("status", "success")
            .put("progress", exit.contributions.size())
            .put("threshold", exit.threshold)
    }
}
