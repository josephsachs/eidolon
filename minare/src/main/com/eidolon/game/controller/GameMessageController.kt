package com.eidolon.game.controller

import com.eidolon.game.evennia.EvenniaCommandHandler
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.MessageController
import com.minare.core.transport.models.Connection
import com.minare.core.transport.models.message.HeartbeatResponse
import com.minare.core.transport.models.message.OperationCommand
import com.minare.core.transport.models.message.SyncCommand
import com.minare.core.transport.models.message.SyncCommandType
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

@Singleton
class GameMessageController @Inject constructor(
    private val evenniaCommandHandler: EvenniaCommandHandler,
    private val channelController: GameChannelController
) : MessageController() {
    private val log = LoggerFactory.getLogger(GameMessageController::class.java)

    override suspend fun handle(connection: Connection, message: JsonObject) {
        log.info("MESSAGE_CONTROLLER: ${message}")

        when {
            message.getString("type") == "heartbeat_response" -> {
                val heartbeatCommand = HeartbeatResponse(
                    connection,
                    message.getLong("timestamp"),
                    message.getLong("clientTimestamp")
                )

                dispatch(heartbeatCommand)
            }

            message.getString("type") == "sync" -> {
                val channels = message.getString("channels").split(",").toSet()

                val syncCommand = SyncCommand(
                    connection,
                    SyncCommandType.CHANNEL,
                    channels    // null = sync all
                )

                dispatch(syncCommand)
            }

            message.getString("type") == "command" -> {
                val response = JsonObject()
                val requestId = message.getString("request_id")

                response.put("status", "success")
                response.put("request_id", requestId)
                response.put("message",
                    evenniaCommandHandler.dispatch(message)
                )

                sendToUpSocket(connection, response)
            }

            // Handle Evennia-style system messages for tracer bullet
            message.getString("type") == "message" -> {
                handleEvenniaMessage(connection, message)
            }

            else -> {
                val operationCommand = OperationCommand(message)

                dispatch(operationCommand)
            }
        }
    }

    /**
     * Handle Evennia-style messages: {"id": "xyz", "type": "message", "message": "..."}
     *
     * Flow:
     * 1. Send ACK immediately to unblock Evennia
     * 2. Send response to DownSocket with correlation ID
     */
    private suspend fun handleEvenniaMessage(connection: Connection, message: JsonObject) {
        val messageId = message.getString("id")
        val messageText = message.getString("message", "")

        log.info("Received Evennia message from {}: id={}, message={}",
            connection._id, messageId, messageText)

        // Send ACK immediately to unblock Evennia
        if (messageId != null) {
            val ack = JsonObject()
                .put("type", "ack")
                .put("id", messageId)

            sendToUpSocket(connection, ack)
            log.debug("Sent ACK for message {} to connection {}", messageId, connection._id)
        }

        // Send response through DownSocket via channel broadcast
        // Connection is already subscribed to default channel via onClientFullyConnected
        val defaultChannelId = channelController.getDefaultChannel()

        if (defaultChannelId != null && messageId != null) {
            val response = JsonObject()
                .put("id", messageId)
                .put("type", "message_response")
                .put("original_message", messageText)
                .put("timestamp", System.currentTimeMillis())

            channelController.broadcast(defaultChannelId, response)
            log.debug("Broadcast response for message {} to channel {}", messageId, defaultChannelId)
        } else {
            log.warn("Cannot send response: defaultChannelId={}, messageId={}", defaultChannelId, messageId)
        }
    }
}