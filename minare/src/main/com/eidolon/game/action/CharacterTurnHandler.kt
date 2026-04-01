package chieftain.game.action

import com.eidolon.game.commands.SkillEvent
import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.models.CharacterKnowledge
import com.eidolon.game.models.entity.CharacterSkillService
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
import eidolon.game.action.GameTurnHandler
import eidolon.game.action.cache.TurnContext
import eidolon.game.models.entity.agent.BrainRegistry
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import kotlin.random.Random

@Singleton
class CharacterTurnHandler @Inject constructor(
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val characterSkillService: CharacterSkillService,
    private val brainRegistry: BrainRegistry,
    private val evenniaCommUtils: EvenniaCommUtils,
    private val crossLinkRegistry: CrossLinkRegistry,
    private val skillEvent: SkillEvent
) {
    private val log = LoggerFactory.getLogger(CharacterTurnHandler::class.java)

    companion object {
        const val GOSSIP_INTERVAL_MS = 90_000L
    }

    suspend fun handleTurn(turnPhase: GameTurnHandler.Companion.TurnPhase, tc: TurnContext): JsonObject {
        val evenniaCharacters = tc.findAllOfType("EvenniaCharacter")
        var dataResponse = JsonObject()

        for ((key, character) in evenniaCharacters) {
            character as EvenniaCharacter

            try {
                character.regenerate()
                character.processStatuses()

                when (turnPhase) {
                    GameTurnHandler.Companion.TurnPhase.BEFORE -> {
                        characterSkillService.doSkillTurnCalcBefore(character)
                    }
                    GameTurnHandler.Companion.TurnPhase.DURING -> {
                        characterSkillService.doSkillTurnCalcDuring(character)
                    }
                    GameTurnHandler.Companion.TurnPhase.AFTER -> {
                        characterSkillService.doSkillTurnCalcAfter(character)
                        if (!character.isNpc && character.gossiping) {
                            tickGossip(character, tc)
                        }
                    }
                }
            } catch (e: Exception) {
                log.error("Character turn error for ${character.evenniaName} phase $turnPhase: ${e.message}")
            }

            if (character.isNpc && character.brainType.isNotEmpty()) {
                val brain = brainRegistry.get(character.brainType)
                if (brain != null) {
                    try {
                        brain.onTurn(character, turnPhase.name, JsonObject())
                    } catch (e: Exception) {
                        log.error("Brain '${character.brainType}' onTurn error for ${character.evenniaName}: ${e.message}")
                    }
                }
            }
        }

        return dataResponse
    }

    private suspend fun tickGossip(character: EvenniaCharacter, tc: TurnContext) {
        val now = System.currentTimeMillis()
        if (now - character.lastGossipEvent < GOSSIP_INTERVAL_MS) return

        val roomId = character.currentRoomId
        if (roomId.isEmpty() || roomId != character.gossipRoomId) {
            // Left the room — stop gossiping
            character.gossiping = false
            character.gossipRoomId = ""
            entityController.saveProperties(character._id, JsonObject()
                .put("gossiping", false)
                .put("gossipRoomId", ""))
            sendGossipFeedback(character, "|yYou wander off and lose the thread of conversation.|n")
            return
        }

        // Find NPCs in the same room who have knowledge
        val allChars = tc.findAllOfType("EvenniaCharacter")
        val npcsWithKnowledge = allChars.values
            .filterIsInstance<EvenniaCharacter>()
            .filter { it.isNpc && it.currentRoomId == roomId && it.characterKnowledge.isNotEmpty() }

        if (npcsWithKnowledge.isEmpty()) {
            sendGossipFeedback(character, "|yThere's nobody here worth gossiping with.|n")
            character.gossiping = false
            character.gossipRoomId = ""
            entityController.saveProperties(character._id, JsonObject()
                .put("gossiping", false)
                .put("gossipRoomId", ""))
            return
        }

        // Pick a random NPC and a random knowledge entry, weighted
        val npc = npcsWithKnowledge.random()
        val knowledge = weightedPickKnowledge(npc.characterKnowledge) ?: return

        // Roll a social stat check
        val event = rollGossipEvent(character, npc, knowledge)

        character.lastGossipEvent = now
        entityController.saveProperties(character._id, JsonObject()
            .put("lastGossipEvent", now))

        // Skill event for Gossip
        val outcome = if (event.success) "success" else "failure"
        skillEvent.execute(JsonObject()
            .put("character_id", character._id)
            .put("skill_name", "Gossip")
            .put("outcome", outcome))

        sendGossipFeedback(character, event.message)
    }

    private data class GossipEvent(
        val success: Boolean,
        val message: String
    )

    private fun rollGossipEvent(
        player: EvenniaCharacter,
        npc: EvenniaCharacter,
        knowledge: CharacterKnowledge
    ): GossipEvent {
        // Pick a social stat to test
        val stats = listOf(
            "charisma" to player.attributes.charisma,
            "empathy" to player.attributes.empathy,
            "wits" to player.attributes.wits
        )
        val (statName, statValue) = stats.random()

        // Gossip skill modifies the roll
        val gossipSkill = player.skills.firstOrNull { it.name == "Gossip" }
        val skillBonus = (gossipSkill?.level ?: 0.0) * 3.0

        // Difficulty scales with knowledge weight
        val difficulty = (knowledge.weight * 100).toInt().coerceIn(10, 95)

        // Roll: stat + skill bonus + d100 vs difficulty threshold
        val roll = Random.nextInt(100)
        val effective = statValue + skillBonus.toInt() + roll
        val success = effective > difficulty + 50

        return if (success) {
            val reveal = knowledge.longDescription.ifEmpty { knowledge.description }
            GossipEvent(true,
                "|gYou engage ${npc.evenniaName} with your ${statName}. " +
                "They let something slip:|n |w${reveal}|n")
        } else {
            val deflections = listOf(
                "${npc.evenniaName} deflects your question with a shrug.",
                "${npc.evenniaName} changes the subject.",
                "${npc.evenniaName} gives you a look that says 'nice try.'",
                "You probe ${npc.evenniaName} but they clam up.",
                "${npc.evenniaName} laughs it off."
            )
            GossipEvent(false, "|y${deflections.random()}|n")
        }
    }

    private fun weightedPickKnowledge(knowledge: List<CharacterKnowledge>): CharacterKnowledge? {
        if (knowledge.isEmpty()) return null
        // Higher weight = harder to extract = less likely to be selected
        // Invert weights so low-weight (easy) knowledge comes up more often
        val inverseWeights = knowledge.map { 1.0 / (it.weight.coerceAtLeast(0.1)) }
        val totalWeight = inverseWeights.sum()
        var roll = Random.nextDouble() * totalWeight
        for (i in knowledge.indices) {
            roll -= inverseWeights[i]
            if (roll <= 0) return knowledge[i]
        }
        return knowledge.last()
    }

    private suspend fun sendGossipFeedback(character: EvenniaCharacter, message: String) {
        val evenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", character._id!!) ?: return
        evenniaCommUtils.sendAgentCommand(JsonObject()
            .put("action", "combat_feedback")
            .put("character_evennia_id", evenniaId)
            .put("message", message))
    }
}