package eidolon.game.models.entity

import eidolon.game.action.GameTaskHandler
import eidolon.game.action.GameTurnHandler.Companion.TurnPhase
import eidolon.game.action.cache.SharedGameState
import com.google.inject.Inject
import com.minare.core.entity.annotations.*
import com.minare.core.entity.models.Entity
import org.slf4j.LoggerFactory

@EntityType("Game")
class Game: Entity() {
    @Inject private lateinit var sharedGameState: SharedGameState
    @Inject private lateinit var gameTaskHandler: GameTaskHandler

    private val log = LoggerFactory.getLogger(Game::class.java)

    init {
        type = "Game"
    }

    var name: String = "MyGame"

    @Property
    var currentTurn: Int = 0

    @Property
    var turnPhase: TurnPhase = TurnPhase.ACT

    @Property
    var turnProcessing: Boolean = false

    @Task
    suspend fun task() {
        if (sharedGameState.isGamePaused()) return

        gameTaskHandler.handle()
    }
}