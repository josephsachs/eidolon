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
    private val evenniaCommandHandler: EvenniaCommandHandler
) : MessageController() {
    private val log = LoggerFactory.getLogger(GameMessageController::class.java)

    override suspend fun handle(connection: Connection, message: JsonObject) {
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

            else -> {
                val operationCommand = OperationCommand(message)

                dispatch(operationCommand)
            }
        }
    }
}