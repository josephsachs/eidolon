package eidolon.game.models.entity.agent

import com.eidolon.clients.ModelAPI
import com.eidolon.game.models.entity.Room
import com.minare.controller.EntityController
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import kotlin.random.Random

class KibitzBrain(
    private val entityController: EntityController,
    private val modelAPI: ModelAPI
) : Brain {
    private val log = LoggerFactory.getLogger(KibitzBrain::class.java)

    override val brainType: String = "kibitz"

    companion object {
        const val COMMENTARY_CHANCE = 15
    }

    override suspend fun onTurn(character: EvenniaCharacter, phase: String, context: JsonObject) {
        if (phase != "AFTER") return

        val time = System.currentTimeMillis()
        log.info("lastAction ${character.lastActed} , " +
                "compared to ${time}. Hence the check is " +
                "${time - character.lastActed} which `< 60000` is ${time - character.lastActed < 60000}"
        )
        if (System.currentTimeMillis() - character.lastActed < 60000) return

        val roomId = character.currentRoomId
        if (roomId.isEmpty()) return

        val roomEntities = entityController.findByIds(listOf(roomId))
        val room = roomEntities[roomId] as? Room ?: return

        /**entityController.saveProperties(character._id,
            JsonObject()
                .put("lastThought", System.currentTimeMillis())
        )**/ // But not really, because the real use of lastThought is for a more
        // complex and rare decision-making thought process that doesn't exist yet.

        val echoCount = room.echoes.size()
        if (echoCount <= 3) return
        if (Random.nextDouble() > COMMENTARY_CHANCE) return

        val systemPrompt = buildSystemPrompt(character,
            "You are overhearing conversation in a room. React briefly in character (one short sentence, no quotes).")

        val echoSummary = formatEchoes(room.echoes, 8)

        try {
            val response = modelAPI.query(systemPrompt, echoSummary).await()
            character.say(roomId, response.trim())
            log.info("KibitzBrain: {} said '{}'", character.evenniaName, response.trim())
            entityController.saveProperties(character._id,
                JsonObject()
                    .put("lastActed", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            log.warn("KibitzBrain: ModelAPI failed for {}: {}", character.evenniaName, e.message)
        }
    }

    override suspend fun onPlayerInteraction(character: EvenniaCharacter, interaction: JsonObject) {
        val roomId = character.currentRoomId
        if (roomId.isEmpty()) return

        val playerName = interaction.getString("player_name", "someone")
        val text = interaction.getString("text", "")

        val systemPrompt = buildSystemPrompt(character,
            "A player is speaking to you directly. Respond briefly in character (one short sentence, no quotes).")

        val message = "$playerName says to you: $text"

        try {
            val response = modelAPI.query(systemPrompt, message).await()
            character.say(roomId, response.trim())
            log.info("KibitzBrain: {} replied to {}: '{}'", character.evenniaName, playerName, response.trim())
        } catch (e: Exception) {
            log.warn("KibitzBrain: ModelAPI failed for {}: {}", character.evenniaName, e.message)
            character.say(roomId, "Hmm? What was that?")
        }
    }

    private fun buildSystemPrompt(character: EvenniaCharacter, instruction: String): String {
        return "You are ${character.evenniaName}. ${character.description}\n$instruction"
    }

    private fun formatEchoes(echoes: io.vertx.core.json.JsonArray, limit: Int): String {
        val start = maxOf(0, echoes.size() - limit)
        val lines = mutableListOf<String>()
        for (i in start until echoes.size()) {
            val echo = echoes.getJsonObject(i)
            val type = echo.getString("type", "say")
            val message = echo.getString("message", "")
            val character = echo.getString("characterName", echo.getString("character", "someone"))
            when (type) {
                "say" -> lines.add("$character says: $message")
                "pose" -> lines.add("$character $message")
                else -> lines.add("$character: $message")
            }
        }
        return lines.joinToString("\n")
    }
}
