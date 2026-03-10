package eidolon.game

import eidolon.game.action.GameTurnHandler
import eidolon.game.action.cache.SharedGameState
import com.google.inject.Injector
import com.minare.core.frames.coordinator.FrameCoordinatorVerticle.Companion.ADDRESS_NEXT_FRAME
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.launch
import javax.inject.Inject

class GameStateVerticle @Inject constructor(
    private var sharedGameState: SharedGameState,
    private var gameTurnHandler: GameTurnHandler
) : CoroutineVerticle() {
    override suspend fun start() {
        sharedGameState.resumeGameClock()

        vertx.eventBus().consumer<JsonObject>(ADDRESS_NEXT_FRAME, {
            launch {
                if (sharedGameState.isGamePaused()) return@launch

                gameTurnHandler.handleFrame()
            }
        })
    }
}