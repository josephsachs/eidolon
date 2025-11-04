package eidolon.game.controller

import com.eidolon.game.controller.GameChannelController
import com.google.inject.Inject
import com.google.inject.Singleton
import io.vertx.core.json.JsonObject

@Singleton
class SystemMessageController @Inject constructor(
    private val channelController: GameChannelController
) {
    suspend fun broadcast(message: String) {
        val systemChannelId = channelController.getSystemMessagesChannel()

        if (systemChannelId.isNullOrBlank()) {
            return
        }

        channelController.broadcast(
            systemChannelId,
            JsonObject().put("minare_system", message)
        )
    }
}