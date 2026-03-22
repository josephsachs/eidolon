package eidolon.game.models.entity.agent

import io.vertx.core.json.JsonObject

interface Brain {
    val brainType: String

    suspend fun onTurn(character: EvenniaCharacter, phase: String, context: JsonObject)

    suspend fun onPlayerInteraction(character: EvenniaCharacter, interaction: JsonObject)

    suspend fun onPresence(character: EvenniaCharacter, event: JsonObject) {}
}
