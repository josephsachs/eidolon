package eidolon.game.models.entity.agent

import com.eidolon.clients.ModelAPI
import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.models.entity.EvenniaObject
import com.minare.controller.EntityController
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class TalkConfig(
    val topics: Map<String, String> = emptyMap()
)

class TalkBrain(
    private val entityController: EntityController,
    private val modelAPI: ModelAPI,
    private val crossLinkRegistry: CrossLinkRegistry
) : Brain {
    private val log = LoggerFactory.getLogger(TalkBrain::class.java)
    private val scope = CoroutineScope(Dispatchers.Default)

    override val brainType: String = "talk"

    private val configs = ConcurrentHashMap<String, TalkConfig>()

    companion object {
        const val TALK_COOLDOWN_MS = 60_000L
    }

    fun registerConfig(characterId: String, config: TalkConfig) {
        configs[characterId] = config
        log.info("Registered talk config for character {} ({} topics)", characterId, config.topics.size)
    }

    override suspend fun onTurn(character: EvenniaCharacter, phase: String, context: JsonObject) {
        // TalkBrain NPCs don't act on their own during turns
    }

    override suspend fun onPlayerInteraction(character: EvenniaCharacter, interaction: JsonObject) {
        val interactionType = interaction.getString("interaction_type", "talk")

        when (interactionType) {
            "talk" -> handleTalk(character, interaction)
            "ask" -> handleAsk(character, interaction)
            else -> log.debug("TalkBrain: unhandled interaction type '{}'", interactionType)
        }
    }

    private suspend fun handleTalk(character: EvenniaCharacter, interaction: JsonObject) {
        val roomId = character.currentRoomId
        if (roomId.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - character.lastActed < TALK_COOLDOWN_MS) {
            character.say(roomId, "Hm? Give me a moment.")
            return
        }

        entityController.saveProperties(character._id,
            JsonObject().put("lastActed", now))

        val playerName = interaction.getString("player_name", "someone")
        val text = interaction.getString("text", "")

        val systemPrompt = buildSystemPrompt(character,
            "A player is speaking to you directly. Respond in character (one to two short sentences, no quotes).")
        val message = "$playerName says to you: $text"
        val charName = character.evenniaName

        scope.launch {
            try {
                val response = modelAPI.query(systemPrompt, message).await()
                character.say(roomId, response.trim())
                log.info("TalkBrain: {} replied to {}: '{}'", charName, playerName, response.trim())
            } catch (e: Exception) {
                log.warn("TalkBrain: ModelAPI failed for {}: {}", charName, e.message)
                character.emote(roomId, "seems distracted and doesn't respond.")
            }
        }
    }

    private suspend fun handleAsk(character: EvenniaCharacter, interaction: JsonObject) {
        val roomId = character.currentRoomId
        if (roomId.isEmpty()) return

        val topic = interaction.getString("topic", "").lowercase().trim()
        val playerName = interaction.getString("player_name", "someone")

        if (topic.isEmpty()) {
            character.say(roomId, "What did you want to know about?")
            return
        }

        val config = configs[character._id]

        // Check canned topics first
        if (config != null) {
            val cannedResponse = config.topics.entries.firstOrNull { (key, _) ->
                topic.contains(key.lowercase()) || key.lowercase().contains(topic)
            }?.value

            if (cannedResponse != null) {
                character.say(roomId, cannedResponse)
                log.info("TalkBrain: {} answered {} about '{}' (canned)", character.evenniaName, playerName, topic)
                return
            }
        }

        // Check character knowledge for a match
        val knowledgeMatch = character.characterKnowledge.firstOrNull { ck ->
            topic.contains(ck.name.lowercase()) || ck.name.lowercase().contains(topic)
                    || ck.tags.any { tag -> topic.contains(tag.lowercase()) }
        }

        if (knowledgeMatch != null) {
            val response = knowledgeMatch.longDescription.ifEmpty { knowledgeMatch.description }
            character.say(roomId, response)
            log.info("TalkBrain: {} answered {} about '{}' (knowledge)", character.evenniaName, playerName, topic)
            return
        }

        // Fall through to LLM
        val now = System.currentTimeMillis()
        entityController.saveProperties(character._id,
            JsonObject().put("lastActed", now))

        val systemPrompt = buildSystemPrompt(character,
            "A player is asking you about a specific topic. If you have relevant knowledge, share it in character. " +
            "If not, deflect naturally in character. One to two short sentences, no quotes.")
        val message = "$playerName asks you about: $topic"
        val charName = character.evenniaName

        scope.launch {
            try {
                val response = modelAPI.query(systemPrompt, message).await()
                character.say(roomId, response.trim())
                log.info("TalkBrain: {} answered {} about '{}' (LLM)", charName, playerName, response.trim())
            } catch (e: Exception) {
                log.warn("TalkBrain: ModelAPI failed for {}: {}", charName, e.message)
                character.emote(roomId, "furrows their brow, thinking.")
            }
        }
    }

    private suspend fun buildSystemPrompt(character: EvenniaCharacter, instruction: String): String {
        val description = getCharacterDescription(character)
        val knowledgeContext = if (character.characterKnowledge.isNotEmpty()) {
            val items = character.characterKnowledge.joinToString("; ") { it.description }
            "\nThings you know about: $items"
        } else ""
        return "You are ${character.evenniaName}. $description$knowledgeContext\n$instruction"
    }

    private suspend fun getCharacterDescription(character: EvenniaCharacter): String {
        val evenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", character._id) ?: return ""
        val eoMinareId = crossLinkRegistry.getMinareId("EvenniaObject", evenniaId) ?: return ""
        val entities = entityController.findByIds(listOf(eoMinareId))
        val eo = entities[eoMinareId] as? EvenniaObject ?: return ""
        return eo.description
    }
}
