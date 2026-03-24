package eidolon.game.models.entity.agent

import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

class IdleBrain : Brain {
    private val log = LoggerFactory.getLogger(IdleBrain::class.java)

    override val brainType: String = "idle"

    override suspend fun onTurn(character: EvenniaCharacter, phase: String, context: JsonObject) {
        // Idle brain does nothing on turn
    }

    override suspend fun onPlayerInteraction(character: EvenniaCharacter, interaction: JsonObject) {
        val playerName = interaction.getString("player_name", "someone")
        val text = interaction.getString("text", "")
        val roomId = character.currentRoomId

        if (roomId.isNotEmpty()) {
            character.emote(roomId, "acknowledges $playerName with a nod.")
            log.info("IdleBrain: {} acknowledged interaction from {}", character.evenniaName, playerName)
        }
    }
}
