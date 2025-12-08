package eidolon.game.action

import eidolon.game.models.entity.Game
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
import com.minare.core.utils.EventStateFlow
import com.minare.core.utils.StateFlowContext
import com.minare.core.utils.vertx.EventBusUtils
import io.vertx.core.Vertx
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CoroutineScope

// TODO: Rename this class since it's no longer a handler really
@Singleton
class GameTurnHandler @Inject constructor(
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val scope: CoroutineScope,
    private val eventBusUtils: EventBusUtils,
    private val vertx: Vertx
) {
    private var log = LoggerFactory.getLogger(GameTurnHandler::class.java)

    private val turnStateMachine: EventStateFlow

    private val actAction: suspend (StateFlowContext) -> Unit = { context ->
        log.info("TURN_LOOP: ACT Phase Start")
        setGameProperties(TurnPhase.ACT, true)
        //characterTurnHandler.handleTurn(TurnPhase.ACT)
    }

    private val executeAction: suspend (StateFlowContext) -> Unit = { context ->
        log.info("TURN_LOOP: EXECUTE Phase Start")
        setGameProperties(TurnPhase.EXECUTE, true)
        //characterTurnHandler.handleTurn(TurnPhase.EXECUTE)
    }

    private val resolveAction: suspend (StateFlowContext) -> Unit = { context ->
        log.info("TURN_LOOP: RESOLVE Phase Start")
        setGameProperties(TurnPhase.RESOLVE, true)
        //characterTurnHandler.handleTurn(TurnPhase.RESOLVE)
    }

    private val turnEndAction: suspend (StateFlowContext) -> Unit = { _ ->
        log.info("TURN_LOOP: Turn End Start (Cleanup)")

        setGameProperties(null, false)
        incrementGameTurn()

        eventBusUtils.sendWithTracing(ADDRESS_TURN_COMPLETE, JsonObject())

        // Since the state machine is looping=true, the next tryNext() call
        // will cycle back to ACT_PHASE.
    }

    init {
        turnStateMachine = EventStateFlow(
            eventKey = "GAME_TURN_LOOP",
            coroutineScope = scope,
            vertx = vertx,
            looping = true // The sequence must loop indefinitely
        )

        turnStateMachine.registerState("ACT_PHASE", actAction)
        turnStateMachine.registerState("EXECUTE_PHASE", executeAction)
        turnStateMachine.registerState("RESOLVE_PHASE", resolveAction)
        turnStateMachine.registerState("TURN_END", turnEndAction)

        // TODO: Have invoking class say when
        turnStateMachine.start()
    }

    /**
     * Called each frame/tick. It attempts to advance the state ONLY if the previous state is finished.
     */
    suspend fun handleFrame() {
        turnStateMachine.tryNext()
    }

    private suspend fun setGameProperties(turnPhase: TurnPhase?, isProcessing: Boolean?) {
        val game = getGame()

        val properties = JsonObject()

        if (turnPhase !== null) properties.put("turnPhase", turnPhase.name)
        if (isProcessing !== null) properties.put("turnProcessing", isProcessing)

        entityController.saveProperties(game._id!!, properties)
    }

    private suspend fun incrementGameTurn() {
        val game = getGame()

        val properties = JsonObject().put("currentTurn", (game.currentTurn + 1))

        try {
            entityController.saveProperties(game._id!!, properties)
        }
        finally {
            val gameTest = getGame()
            log.info("TURN_LOOP: New turn ${gameTest.currentTurn}")
        }
    }

    private suspend fun getGame(): Game {
        return entityController
            .findByIds(stateStore.findAllKeysForType("Game"))
            .firstNotNullOf { it.value } as Game
    }

    companion object {
        const val ADDRESS_TURN_COMPLETE = "turn.handler.turn.complete"

        enum class TurnPhase { ACT, EXECUTE, RESOLVE }
    }
}