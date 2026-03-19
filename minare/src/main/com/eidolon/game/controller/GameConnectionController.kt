package com.eidolon.game.controller

import com.minare.controller.ConnectionController
import com.minare.core.transport.models.Connection
import com.minare.core.transport.upsocket.handlers.SyncCommandHandler
import eidolon.game.controller.GameChannelController
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Game-specific extension of ConnectionController that handles
 * channel subscriptions and graph synchronization on client connection.
 */
@Singleton
class GameConnectionController @Inject constructor(
    private val channelController: GameChannelController,
    private val syncCommandHandler: SyncCommandHandler,
) : ConnectionController() {
    private val log = LoggerFactory.getLogger(GameConnectionController::class.java)

    /**
     * Called when a client becomes fully connected.
     * Subscribes the client to the default channel and initiates sync.
     */
    override suspend fun onConnected(connection: Connection) {
        log.info("Test client {} is now fully connected", connection.id)

        val defaultChannelId = channelController.getDefaultChannel()

        if (defaultChannelId == null) {
            log.warn("No default channel found for client {}, skipping auto-subscription", connection.id)
            return
        }

        if (channelController.addClient(connection.id, defaultChannelId)) {
            syncCommandHandler.syncChannelToConnection(defaultChannelId, connection.id)

            sendToUpSocket(connection.id, JsonObject()
                .put("type", "initial_sync_complete")
                .put("timestamp", System.currentTimeMillis())
            )
        }
    }

    companion object {
        const val ADDRESS_EVENNIA_READY = "eidolon.evennia.ready"
    }
}