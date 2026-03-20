package chieftain.game.action

import com.eidolon.game.models.entity.CharacterSkillService
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
import eidolon.game.action.GameTurnHandler
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonObject

@Singleton
class CharacterTurnHandler @Inject constructor(
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val characterSkillService: CharacterSkillService
) {
    private val log = LoggerFactory.getLogger(CharacterTurnHandler::class.java)

    suspend fun handleTurn(turnPhase: GameTurnHandler.Companion.TurnPhase): JsonObject {
        val evenniaCharacters = entityController.findByIds(
            stateStore.findAllKeysForType("EvenniaCharacter")
        )
        var dataResponse = JsonObject()

        for ((key, character) in evenniaCharacters) {
            character as EvenniaCharacter

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
        }

        return dataResponse
    }
}