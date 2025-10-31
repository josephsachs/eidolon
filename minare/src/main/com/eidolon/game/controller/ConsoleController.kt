package eidolon.game.controller

import com.eidolon.game.controller.GameChannelController
import com.google.inject.Inject
import com.google.inject.Singleton
import io.vertx.core.json.JsonObject

@Singleton
class ConsoleController @Inject constructor(
    private val channelController: GameChannelController
) {
    suspend fun broadcast(message: String) {
        val defaultChannelId = channelController.getDefaultChannel()

        if (defaultChannelId.isNullOrBlank()) {
            return
        }

        channelController.broadcast(
            defaultChannelId,
            JsonObject().put("console", message)
        )
    }
}