package com.eidolon.game.controller

import com.eidolon.game.commands.AccountRegister
import com.eidolon.game.commands.CharacterCreate
import com.eidolon.game.commands.EntityQuery
import com.eidolon.game.commands.PlayerDisconnect
import com.eidolon.game.commands.RegisterEvenniaObject
import com.eidolon.game.commands.RoomPose
import com.eidolon.game.commands.RoomSay
import com.eidolon.game.commands.SkillEvent
import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommandHandler
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.MessageController
import com.minare.core.transport.models.Connection
import com.minare.core.transport.models.message.HeartbeatResponse
import com.minare.core.transport.models.message.OperationCommand
import com.minare.core.transport.models.message.SyncCommand
import com.minare.core.transport.models.message.SyncCommandType
import com.minare.core.transport.upsocket.UpSocketVerticle
import eidolon.game.controller.GameChannelController
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

@Singleton
class GameMessageController @Inject constructor(
    private val vertx: Vertx,
    private val evenniaCommandHandler: EvenniaCommandHandler,
    private val channelController: GameChannelController,
    private val crossLinkRegistry: CrossLinkRegistry,
    private val entityQuery: EntityQuery,
    private val accountRegister: AccountRegister,
    private val characterCreate: CharacterCreate,
    private val roomSay: RoomSay,
    private val roomPose: RoomPose,
    private val playerDisconnect: PlayerDisconnect,
    private val registerEvenniaObject: RegisterEvenniaObject,
    private val skillEvent: SkillEvent,
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

            message.getString("type") == "register_account" -> {
                val requestId = message.getString("request_id")
                val result = accountRegister.execute(message)
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "create_character" -> {
                val requestId = message.getString("request_id")
                val result = characterCreate.execute(message)
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "room_say" -> {
                roomSay.execute(message)
            }

            message.getString("type") == "room_pose" -> {
                roomPose.execute(message)
            }

            message.getString("type") == "player_disconnect" -> {
                playerDisconnect.execute(message)
            }

            message.getString("type") == "skill_event" -> {
                val requestId = message.getString("request_id")
                val result = skillEvent.execute(message)
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "entity_query" -> {
                val requestId = message.getString("request_id")
                val result = entityQuery.execute(message)
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "register_cross_link" -> {
                val entityType = message.getString("entity_type", "")
                val minareId = message.getString("minare_id", "")
                val evenniaId = message.getString("evennia_id", "")
                if (entityType.isNotEmpty() && minareId.isNotEmpty() && evenniaId.isNotEmpty()) {
                    crossLinkRegistry.link(entityType, minareId, evenniaId)
                } else {
                    log.warn("register_cross_link: missing fields — entity_type={}, minare_id={}, evennia_id={}",
                        entityType, minareId, evenniaId)
                }
            }

            message.getString("type") == "register_evennia_object" -> {
                val requestId = message.getString("request_id")
                val result = registerEvenniaObject.execute(message)
                result.put("request_id", requestId)
                result.put("key", message.getString("key", ""))
                sendToClient(connection, result)

                // Publish to event bus so RoomInitializer (and others) can react
                vertx.eventBus().publish("eidolon.evennia_object.registered", result)
            }

            message.getString("type") == "system_agent_ready" -> {
                val agentEvenniaId = message.getString("agent_evennia_id", "")
                val agentKey = message.getString("agent_key", "")
                log.info("System agent ready in Evennia: key={}, evenniaId={}", agentKey, agentEvenniaId)
                vertx.eventBus().publish(
                    GameConnectionController.ADDRESS_EVENNIA_READY,
                    JsonObject()
                        .put("agent_evennia_id", agentEvenniaId)
                        .put("agent_key", agentKey)
                )
            }

            message.getString("type") == "command" -> {
                val response = JsonObject()
                val requestId = message.getString("request_id")

                response.put("status", "success")
                response.put("request_id", requestId)
                response.put("message",
                    evenniaCommandHandler.dispatch(message)
                )

                sendToClient(connection, response)
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
            connection.id, messageId, messageText)

        // Send ACK immediately to unblock Evennia
        if (messageId != null) {
            val ack = JsonObject()
                .put("type", "ack")
                .put("id", messageId)

            sendToClient(connection, ack)
            log.debug("Sent ACK for message {} to connection {}", messageId, connection.id)
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

    private fun sendToClient(connection: Connection, message: JsonObject) {
        val deploymentId = connection.upSocketInstanceId ?: run {
            log.warn("Cannot send to connection {}: no upSocketInstanceId", connection.id)
            return
        }
        vertx.eventBus().send(
            "${UpSocketVerticle.ADDRESS_SEND_TO_CONNECTION}.${deploymentId}",
            JsonObject()
                .put("connectionId", connection.id)
                .put("message", message)
        )
    }
}