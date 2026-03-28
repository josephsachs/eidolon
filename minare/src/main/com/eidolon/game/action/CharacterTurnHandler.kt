package chieftain.game.action

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

@Singleton
class CharacterTurnHandler @Inject constructor(
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val characterSkillService: CharacterSkillService,
    private val brainRegistry: BrainRegistry
) {
    private val log = LoggerFactory.getLogger(CharacterTurnHandler::class.java)

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
}