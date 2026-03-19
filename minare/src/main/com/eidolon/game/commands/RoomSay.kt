package com.eidolon.game.commands

import com.eidolon.game.models.entity.Room
import com.eidolon.game.models.entity.RoomMemory
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Handles room_say messages from Evennia.
 * Appends a say echo to the Room's RoomMemory entity.
 */
@Singleton
class RoomSay @Inject constructor(
    private val entityController: EntityController
) : EvenniaCommand {
    private val log = LoggerFactory.getLogger(RoomSay::class.java)

    override suspend fun execute(message: JsonObject): JsonObject {
        val characterId = message.getString("character_id", "")
        val roomId = message.getString("room_id", "")
        val text = message.getString("message", "")

        if (roomId.isEmpty()) {
            log.warn("RoomSay: missing room_id")
            return JsonObject().put("status", "error").put("error", "Missing room_id")
        }

        val memoryId = resolveRoomMemoryId(roomId) ?: run {
            log.warn("RoomSay: no RoomMemory for room {}", roomId)
            return JsonObject().put("status", "error").put("error", "No room memory")
        }

        appendEcho(memoryId, characterId, text, "say")
        log.info("RoomSay: character={} room={} '{}'", characterId, roomId, text)
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
