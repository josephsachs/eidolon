package com.eidolon.game.evennia

import com.eidolon.game.commands.CharacterLook
import com.google.inject.Inject
import com.google.inject.Singleton
import io.vertx.core.json.JsonObject

@Singleton
class EvenniaCommandHandler @Inject constructor(
    private val characterLook: CharacterLook
) {
    suspend fun dispatch(message: JsonObject): JsonObject {
        return when {
            isMove(message) -> {
                JsonObject()
            }
            isLook(message) -> {
                characterLook.execute(message)
                JsonObject()
            }
            else -> {
                throw NotImplementedError("Not implemented yet")
            }
        }
    }

    private fun isMove(message: JsonObject): Boolean {
        return message.getString("command") == "move"
    }

    private fun isLook(message: JsonObject): Boolean {
        return message.getString("command") == "look"
    }
}