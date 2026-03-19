package com.eidolon.game.commands

import com.google.inject.Singleton
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Handles player_disconnect messages from Evennia.
 * Stub: logs disconnection. Will update EvenniaCharacter connection
 * state once connection tracking is implemented.
 */
@Singleton
class PlayerDisconnect : EvenniaCommand {
    private val log = LoggerFactory.getLogger(PlayerDisconnect::class.java)

    override suspend fun execute(message: JsonObject): JsonObject {
        val characterId = message.getString("character_id", "")
        val roomId = message.getString("room_id", "")
        val accountId = message.getString("account_id", "")
        log.info("PlayerDisconnect: character={} room={} account={}", characterId, roomId, accountId)
        return JsonObject().put("status", "received")
    }
}
