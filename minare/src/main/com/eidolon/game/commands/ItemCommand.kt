package com.eidolon.game.commands

import com.google.inject.Singleton
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Handles command_get, command_drop, command_give messages from Evennia.
 * Stub: returns queued ACK. Will create OperationSet sagas once the
 * inventory entity model exists.
 */
@Singleton
class ItemCommand : EvenniaCommand {
    private val log = LoggerFactory.getLogger(ItemCommand::class.java)

    override suspend fun execute(message: JsonObject): JsonObject {
        val commandType = message.getString("type", "")
        val characterId = message.getString("character_id", "")
        val target = message.getString("target", "")
        log.info("ItemCommand: type={} character={} target='{}'", commandType, characterId, target)
        return JsonObject()
            .put("status", "queued")
    }
}
