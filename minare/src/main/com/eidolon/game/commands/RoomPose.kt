package com.eidolon.game.commands

import com.eidolon.game.models.entity.Room
import com.eidolon.game.models.entity.RoomMemory
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Handles room_pose messages from Evennia.
 * Appends a pose echo to the Room's RoomMemory entity.
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

        val memoryId = resolveRoomMemoryId(roomId) ?: run {
            log.warn("RoomPose: no RoomMemory for room {}", roomId)
            return JsonObject().put("status", "error").put("error", "No room memory")
        }

        appendEcho(memoryId, characterId, text, "pose")
        log.info("RoomPose: character={} room={} '{}'", characterId, roomId, text)
        return JsonObject().put("status", "received")
    }

    private suspend fun resolveRoomMemoryId(roomId: String): String? {
        val entities = entityController.findByIds(listOf(roomId))
        val room = entities[roomId] as? Room ?: return null
        return room.roomMemoryId.ifEmpty { null }
    }

    private suspend fun appendEcho(memoryId: String, characterId: String, text: String, type: String) {
        val entities = entityController.findByIds(listOf(memoryId))
        val memory = entities[memoryId] as? RoomMemory ?: return

        val echo = JsonObject()
            .put("character", characterId)
            .put("message", text)
            .put("type", type)
            .put("timestamp", System.currentTimeMillis())

        val updatedEchoes = memory.echoes.copy().add(echo)
        entityController.saveState(memoryId, JsonObject().put("echoes", updatedEchoes))
    }
}
