package eidolon.game.action

import chieftain.game.action.CharacterTurnHandler
import eidolon.game.models.entity.Game
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
import com.minare.core.utils.types.esf.EventStateFlow
import com.minare.core.utils.types.esf.StateFlowContext
import com.minare.core.utils.vertx.EventBusUtils
import eidolon.game.action.cache.TurnContext
import io.vertx.core.Vertx
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CoroutineScope

@Singleton
class GameTurnHandler @Inject constructor(
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val scope: CoroutineScope,
    private val eventBusUtils: EventBusUtils,
    private val characterTurnHandler: CharacterTurnHandler,
    private val combatTurnHandler: CombatTurnHandler,
    private val worldTurnHandler: WorldTurnHandler,
    private val ingameTimeController: IngameTimeController,
    private val sharedGameState: eidolon.game.action.cache.SharedGameState,
    private val vertx: Vertx
) {
    private var log = LoggerFactory.getLogger(GameTurnHandler::class.java)

    private var frameCounter: Int = 0

    private val turnStateMachine: EventStateFlow = EventStateFlow(
        eventKey = "GAME_TURN_LOOP",
        coroutineScope = scope,
        vertx = vertx,
        looping = true // The sequence must loop indefinitely
    )

    private fun createTurnContext(): TurnContext = TurnContext(entityController, stateStore)

    private val actAction: suspend (StateFlowContext) -> Unit = { context ->
        val start = System.currentTimeMillis()
        val tc = createTurnContext()
        setGameProperties(TurnPhase.BEFORE, true)
        characterTurnHandler.handleTurn(TurnPhase.BEFORE, tc)
        combatTurnHandler.handleTurn(TurnPhase.BEFORE, tc)
        worldTurnHandler.handleTurn(tc)
        val elapsed = System.currentTimeMillis() - start
        if (elapsed > 100) log.warn("TURN_LOOP: ACT phase took ${elapsed}ms")
    }

    private val executeAction: suspend (StateFlowContext) -> Unit = { context ->
        val start = System.currentTimeMillis()
        val tc = createTurnContext()
        setGameProperties(TurnPhase.DURING, true)
        characterTurnHandler.handleTurn(TurnPhase.DURING, tc)
        combatTurnHandler.handleTurn(TurnPhase.DURING, tc)
        worldTurnHandler.handleTurn(tc)
        val elapsed = System.currentTimeMillis() - start
        if (elapsed > 100) log.warn("TURN_LOOP: EXECUTE phase took ${elapsed}ms")
    }

    private val resolveAction: suspend (StateFlowContext) -> Unit = { context ->
        val start = System.currentTimeMillis()
        val tc = createTurnContext()
        setGameProperties(TurnPhase.AFTER, true)
        characterTurnHandler.handleTurn(TurnPhase.AFTER, tc)
        combatTurnHandler.handleTurn(TurnPhase.AFTER, tc)
        worldTurnHandler.handleTurn(tc)
        val elapsed = System.currentTimeMillis() - start
        if (elapsed > 100) log.warn("TURN_LOOP: RESOLVE phase took ${elapsed}ms")
    }

    private val turnEndAction: suspend (StateFlowContext) -> Unit = { _ ->
        val start = System.currentTimeMillis()
        setGameProperties(null, false)
        incrementGameTurn()

        val game = getGame()
        ingameTimeController.onTurn(game.currentTurn)

        eventBusUtils.sendWithTracing(ADDRESS_TURN_COMPLETE, JsonObject())
        val elapsed = System.currentTimeMillis() - start
        if (elapsed > 100) log.warn("TURN_LOOP: TURN_END phase took ${elapsed}ms")
    }

    init {

        turnStateMachine.registerState("ACT_PHASE", actAction)
        turnStateMachine.registerState("EXECUTE_PHASE", executeAction)
        turnStateMachine.registerState("RESOLVE_PHASE", resolveAction)
        turnStateMachine.registerState("TURN_END", turnEndAction)

        // TODO: Have invoking class say when
        turnStateMachine.start()
    }

    /**
     * Called each frame/tick. Advances the turn state only after enough frames have elapsed.
     */
    suspend fun handleFrame() {
        frameCounter++
        if (frameCounter < sharedGameState.getTicksPerTurn()) return
        frameCounter = 0
        turnStateMachine.tryNext()
    }

    private suspend fun setGameProperties(turnPhase: TurnPhase?, isProcessing: Boolean?) {
        val game = getGame()

        val properties = JsonObject()

        if (turnPhase !== null) properties.put("turnPhase", turnPhase.name)
        if (isProcessing !== null) properties.put("turnProcessing", isProcessing)

        entityController.saveProperties(game._id, properties)
    }

    private suspend fun incrementGameTurn() {
        val game = getGame()

        val properties = JsonObject().put("currentTurn", (game.currentTurn + 1))

        try {
            entityController.saveProperties(game._id, properties)
        }
        finally {
            val gameTest = getGame()
            //log.info("TURN_LOOP: New turn ${gameTest.currentTurn}")
        }
    }

    private suspend fun getGame(): Game {
        return entityController
            .findByIds(stateStore.findAllKeysForType("Game"))
            .firstNotNullOf { it.value } as Game
    }

    companion object {
        const val ADDRESS_TURN_COMPLETE = "turn.handler.turn.complete"

        enum class TurnPhase { BEFORE, DURING, AFTER }
    }
}