package com.eidolon.game.commands

import io.vertx.core.json.JsonObject

interface EvenniaCommand {
    suspend fun execute(message: JsonObject): JsonObject
}