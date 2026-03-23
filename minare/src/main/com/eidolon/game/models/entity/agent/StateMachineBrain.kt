package eidolon.game.models.entity.agent

import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class StateConfig(
    val type: String,
    val args: String = "",
    val delay: Int = 1,
    val next: String? = null
)

data class StateMachineConfig(
    val initial: String,
    val states: Map<String, StateConfig>
)

class StateMachineBrain(
    private val evenniaCommUtils: EvenniaCommUtils,
    private val crossLinkRegistry: CrossLinkRegistry
) : Brain {
    private val log = LoggerFactory.getLogger(StateMachineBrain::class.java)

    override val brainType: String = "state_machine"

    private val configs = ConcurrentHashMap<String, StateMachineConfig>()

    private data class CharacterState(
        var currentState: String,
        var turnsInState: Int = 0
    )

    private val characterStates = ConcurrentHashMap<String, CharacterState>()

    fun registerConfig(characterId: String, config: StateMachineConfig) {
        configs[characterId] = config
        log.info("Registered state machine config for character {}, initial={}", characterId, config.initial)
    }

    override suspend fun onTurn(character: EvenniaCharacter, phase: String, context: JsonObject) {
        if (phase != "BEFORE") return
        if (character.dead || character.downed) return

        val id = character._id ?: return
        val config = configs[id] ?: return

        val charState = characterStates.getOrPut(id) {
            CharacterState(currentState = config.initial)
        }

        val stateConfig = config.states[charState.currentState]
        if (stateConfig == null) {
            log.warn("State machine for {}: unknown state '{}'", character.evenniaName, charState.currentState)
            return
        }

        if (charState.turnsInState == 0) {
            executeState(character, stateConfig)
        }

        charState.turnsInState++

        if (charState.turnsInState >= stateConfig.delay) {
            val nextState = stateConfig.next
            if (nextState != null && config.states.containsKey(nextState)) {
                charState.currentState = nextState
                charState.turnsInState = 0
            } else if (nextState == null) {
                log.info("State machine for {} halted at state '{}'", character.evenniaName, charState.currentState)
            } else {
                log.warn("State machine for {}: next state '{}' not found, halting", character.evenniaName, nextState)
            }
        }
    }

    private suspend fun executeState(character: EvenniaCharacter, state: StateConfig) {
        val evenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", character._id!!) ?: return

        when (state.type) {
            "command" -> {
                evenniaCommUtils.sendAgentCommand(JsonObject()
                    .put("action", "npc_command")
                    .put("character_evennia_id", evenniaId)
                    .put("command", state.args))
                log.debug("{} executes command: {}", character.evenniaName, state.args)
            }
            "emote" -> {
                character.emote(character.currentRoomId, state.args)
                log.debug("{} emotes: {}", character.evenniaName, state.args)
            }
            "say" -> {
                character.say(character.currentRoomId, state.args)
                log.debug("{} says: {}", character.evenniaName, state.args)
            }
            "wait" -> {
                // Do nothing, just wait for delay
            }
            else -> {
                log.warn("State machine for {}: unknown state type '{}'", character.evenniaName, state.type)
            }
        }
    }

    override suspend fun onPlayerInteraction(character: EvenniaCharacter, interaction: JsonObject) {
        // State machine NPCs don't respond to player interaction by default
    }

    override suspend fun onPresence(character: EvenniaCharacter, event: JsonObject) {
        // State machine NPCs don't react to presence by default
    }
}
