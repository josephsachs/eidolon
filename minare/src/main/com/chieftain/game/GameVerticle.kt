package chieftain.game

import chieftain.game.action.GameTurnHandler
import chieftain.game.action.cache.SharedGameState
import com.google.inject.Injector
import com.minare.core.frames.coordinator.FrameCoordinatorVerticle.Companion.ADDRESS_NEXT_FRAME
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.launch
import javax.inject.Inject

class GameStateVerticle @Inject constructor(
    private var injector: Injector,
    private var sharedGameState: SharedGameState
) : CoroutineVerticle() {
    lateinit var gameTurnHandler: GameTurnHandler

    override suspend fun start() {
        gameTurnHandler = injector.getInstance(GameTurnHandler::class.java)
        sharedGameState.resumeGameClock()

        vertx.eventBus().consumer<JsonObject>(ADDRESS_NEXT_FRAME, {
            launch {
                if (sharedGameState.isGamePaused()) return@launch

                gameTurnHandler.handleFrame()
            }
        })
    }
}