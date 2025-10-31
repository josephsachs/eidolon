package eidolon.game.action

import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.core.utils.vertx.EventBusUtils
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonObject

@Singleton
class GameTaskHandler @Inject constructor(
    private val eventBusUtils: EventBusUtils
) {
    suspend fun handle() {
        try {
        } finally {
            eventBusUtils.publishWithTracing(
                ADDRESS_GAME_TASKS_COMPLETE,
                JsonObject()
            )
        }
    }

    companion object {
        const val ADDRESS_GAME_TASKS_COMPLETE = "turn.handler.game.tasks.complete"
    }
}