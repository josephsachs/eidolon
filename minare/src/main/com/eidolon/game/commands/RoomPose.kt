package com.eidolon.game.commands

import com.eidolon.game.models.entity.Room
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Handles room_pose messages from Evennia.
 * Appends a pose echo to the Room entity.
 */
@Singleton
class RoomPose @Inject constructor(
    private val entityController: EntityController
) : EvenniaCommand {
    private val log = LoggerFactory.getLogger(RoomPose::class.java)

    override suspend fun execute(message: JsonObject): JsonObject {
        val characterId = message.getString("character_id", "")
        val roomId = message.getString("room_id", "")
        val text = message.getString("message", "")

        if (roomId.isEmpty()) {
            log.warn("RoomPose: missing room_id")
            return JsonObject().put("status", "error").put("error", "Missing room_id")
        }

        appendEcho(roomId, characterId, text, "pose")
        log.info("RoomPose: character={} room={} '{}'", characterId, roomId, text)
        return JsonObject().put("status", "received")
    }

    private suspend fun appendEcho(roomId: String, characterId: String, text: String, type: String) {
        val entities = entityController.findByIds(listOf(roomId, characterId))
        val room = entities[roomId] as? Room ?: return
        val character = entities[characterId] as? eidolon.game.models.entity.agent.EvenniaCharacter
        val characterName = character?.evenniaName ?: ""

        val echo = JsonObject()
            .put("character", characterId)
            .put("characterName", characterName)
            .put("message", text)
            .put("type", type)
            .put("timestamp", System.currentTimeMillis())

        val updatedEchoes = room.echoes.copy().add(echo)
        entityController.saveProperties(roomId, JsonObject().put("echoes", updatedEchoes))
    }
}
