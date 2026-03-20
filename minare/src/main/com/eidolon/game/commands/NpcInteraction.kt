package com.eidolon.game.commands

import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import eidolon.game.models.entity.agent.BrainRegistry
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

@Singleton
class NpcInteraction @Inject constructor(
    private val entityController: EntityController,
    private val brainRegistry: BrainRegistry
) : EvenniaCommand {
    private val log = LoggerFactory.getLogger(NpcInteraction::class.java)

    override suspend fun execute(message: JsonObject): JsonObject {
        val npcId = message.getString("npc_id")
            ?: return JsonObject().put("status", "error").put("error", "Missing npc_id")
        val playerId = message.getString("player_id", "")
        val playerName = message.getString("player_name", "someone")
        val interactionType = message.getString("interaction_type", "talk")
        val text = message.getString("text", "")

        val entities = entityController.findByIds(listOf(npcId))
        val npc = entities[npcId] as? EvenniaCharacter
            ?: return JsonObject().put("status", "error").put("error", "NPC not found: $npcId")

        if (!npc.isNpc) {
            return JsonObject().put("status", "error").put("error", "Character is not an NPC")
        }

        val brain = brainRegistry.get(npc.brainType)
        if (brain == null) {
            log.warn("NpcInteraction: no brain registered for type '{}'", npc.brainType)
            return JsonObject().put("status", "error").put("error", "No brain for type: ${npc.brainType}")
        }

        val interaction = JsonObject()
            .put("player_id", playerId)
            .put("player_name", playerName)
            .put("interaction_type", interactionType)
            .put("text", text)

        brain.onPlayerInteraction(npc, interaction)

        return JsonObject().put("status", "success")
    }
}
