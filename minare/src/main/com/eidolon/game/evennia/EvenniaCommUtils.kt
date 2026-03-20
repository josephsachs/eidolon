package com.eidolon.game.evennia

import com.google.inject.Inject
import com.google.inject.Singleton
import eidolon.game.controller.GameChannelController
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Minare service for sending MUD prose into the game world.
 * Builds structured agent_command messages and broadcasts them via the default channel
 * to Evennia's DownSocket, where they are dispatched to AgentCharacter instances.
 */
@Singleton
class EvenniaCommUtils @Inject constructor(
    private val channelController: GameChannelController,
    private val crossLinkRegistry: CrossLinkRegistry
) {
    private val log = LoggerFactory.getLogger(EvenniaCommUtils::class.java)

    /**
     * Broadcast a structured agent_command on the default channel.
     */
    suspend fun sendAgentCommand(command: JsonObject) {
        val channelId = channelController.getDefaultChannel()
        if (channelId == null) {
            log.warn("Cannot send agent_command: no default channel")
            return
        }
        val message = JsonObject()
            .put("type", "agent_command")
            .put("command", command)
            .put("timestamp", System.currentTimeMillis())
        channelController.broadcast(channelId, message)
    }

    // --- Convenience methods using Evennia IDs directly ---

    suspend fun say(roomEvenniaId: String, agentEvenniaId: String, text: String) {
        sendAgentCommand(JsonObject()
            .put("action", "say")
            .put("room_evennia_id", roomEvenniaId)
            .put("agent_evennia_id", agentEvenniaId)
            .put("text", text))
    }

    suspend fun emote(roomEvenniaId: String, agentEvenniaId: String, text: String) {
        sendAgentCommand(JsonObject()
            .put("action", "emote")
            .put("room_evennia_id", roomEvenniaId)
            .put("agent_evennia_id", agentEvenniaId)
            .put("text", text))
    }

    suspend fun whisper(targetEvenniaId: String, agentEvenniaId: String, text: String) {
        sendAgentCommand(JsonObject()
            .put("action", "whisper")
            .put("target_evennia_id", targetEvenniaId)
            .put("agent_evennia_id", agentEvenniaId)
            .put("text", text))
    }

    // --- Convenience wrappers that resolve from Minare IDs ---

    suspend fun sayInRoom(roomMinareId: String, agentMinareId: String, text: String) {
        val roomEvenniaId = crossLinkRegistry.getEvenniaId("Room", roomMinareId)
        val agentEvenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", agentMinareId)
        if (roomEvenniaId == null || agentEvenniaId == null) {
            log.warn("sayInRoom: missing cross-link — room={}, agent={}", roomMinareId, agentMinareId)
            return
        }
        say(roomEvenniaId, agentEvenniaId, text)
    }

    suspend fun emoteInRoom(roomMinareId: String, agentMinareId: String, text: String) {
        val roomEvenniaId = crossLinkRegistry.getEvenniaId("Room", roomMinareId)
        val agentEvenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", agentMinareId)
        if (roomEvenniaId == null || agentEvenniaId == null) {
            log.warn("emoteInRoom: missing cross-link — room={}, agent={}", roomMinareId, agentMinareId)
            return
        }
        emote(roomEvenniaId, agentEvenniaId, text)
    }

    suspend fun whisperByMinareId(targetMinareId: String, agentMinareId: String, text: String) {
        val targetEvenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", targetMinareId)
        val agentEvenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", agentMinareId)
        if (targetEvenniaId == null || agentEvenniaId == null) {
            log.warn("whisperByMinareId: missing cross-link — target={}, agent={}", targetMinareId, agentMinareId)
            return
        }
        whisper(targetEvenniaId, agentEvenniaId, text)
    }

    // --- Batch + building command helpers ---

    /**
     * Send a batch of commands to be executed sequentially by the agent.
     */
    suspend fun sendBatchCommands(commands: List<JsonObject>) {
        sendAgentCommand(JsonObject()
            .put("action", "batch")
            .put("commands", JsonArray(commands)))
    }

    /**
     * Build a dig command JsonObject (does not send it).
     * Use with sendBatchCommands for bulk room creation.
     */
    fun buildDigCommand(
        roomKey: String,
        description: String,
        scenarioId: String
    ): JsonObject {
        return JsonObject()
            .put("action", "dig")
            .put("room_key", roomKey)
            .put("description", description)
            .put("scenario_id", scenarioId)
    }

    /**
     * Build a create_exit command JsonObject (does not send it).
     */
    fun buildCreateExitCommand(
        exitName: String,
        fromRoomEvenniaId: String,
        toRoomEvenniaId: String
    ): JsonObject {
        return JsonObject()
            .put("action", "create_exit")
            .put("exit_name", exitName)
            .put("from_room_evennia_id", fromRoomEvenniaId)
            .put("to_room_evennia_id", toRoomEvenniaId)
    }

    /**
     * Build a describe command JsonObject (does not send it).
     */
    fun buildDescribeCommand(
        roomEvenniaId: String,
        description: String
    ): JsonObject {
        return JsonObject()
            .put("action", "describe")
            .put("room_evennia_id", roomEvenniaId)
            .put("text", description)
    }
}
