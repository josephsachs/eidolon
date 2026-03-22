package com.eidolon.game.service

import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.models.entity.Combat
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.entity.factories.EntityFactory
import com.minare.core.storage.interfaces.StateStore
import eidolon.game.controller.GameChannelController
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

@Singleton
class CombatService @Inject constructor(
    private val entityController: EntityController,
    private val entityFactory: EntityFactory,
    private val stateStore: StateStore,
    private val evenniaCommUtils: EvenniaCommUtils,
    private val crossLinkRegistry: CrossLinkRegistry,
    private val gameChannelController: GameChannelController
) {
    private val log = LoggerFactory.getLogger(CombatService::class.java)

    suspend fun createCombat(roomId: String, attackerId: String, targetId: String): Combat {
        val combat = entityFactory.createEntity(Combat::class.java) as Combat
        entityController.create(combat)

        val now = System.currentTimeMillis()
        entityController.saveState(combat._id!!, JsonObject()
            .put("members", listOf(attackerId, targetId))
            .put("roomId", roomId))
        entityController.saveProperties(combat._id!!, JsonObject()
            .put("createdAt", now)
            .put("lastActivity", now))

        // Add to default channel
        val channelId = gameChannelController.getDefaultChannel()
        if (channelId != null) {
            gameChannelController.addEntitiesToChannel(listOf(combat), channelId)
        }

        // Set combatId on both characters
        setCombatProperties(attackerId, combat._id!!, "attack", targetId)
        setCombatProperties(targetId, combat._id!!, "", "")

        // Lock movement for both
        sendCombatLock(attackerId)
        sendCombatLock(targetId)

        // Room message
        val attackerName = getCharacterName(attackerId)
        val targetName = getCharacterName(targetId)
        sendCombatMessage(roomId, "|R** Combat breaks out! $attackerName attacks $targetName! **|n")

        log.info("Combat created: ${combat._id} in room $roomId, members: [$attackerId, $targetId]")
        return combat
    }

    suspend fun joinCombat(combatId: String, characterId: String) {
        val combat = getCombat(combatId) ?: return

        if (characterId in combat.members) return

        val updatedMembers = combat.members + characterId
        entityController.saveState(combatId, JsonObject().put("members", updatedMembers))
        entityController.saveProperties(combatId, JsonObject()
            .put("lastActivity", System.currentTimeMillis()))

        setCombatProperties(characterId, combatId, "", "")
        sendCombatLock(characterId)

        val name = getCharacterName(characterId)
        sendCombatMessage(combat.roomId, "|y$name enters the fray!|n")

        log.info("$characterId joined combat $combatId")
    }

    suspend fun removeMember(combatId: String, characterId: String) {
        val combat = getCombat(combatId) ?: return

        if (characterId !in combat.members) return

        val updatedMembers = combat.members - characterId
        entityController.saveState(combatId, JsonObject().put("members", updatedMembers))
        entityController.saveProperties(combatId, JsonObject()
            .put("lastActivity", System.currentTimeMillis()))

        clearCombatProperties(characterId)
        sendCombatUnlock(characterId)

        val name = getCharacterName(characterId)
        sendCombatMessage(combat.roomId, "|w$name disengages from combat.|n")

        // Clear targets pointing at the removed member
        for (memberId in updatedMembers) {
            val member = getCharacter(memberId) ?: continue
            if (member.targetId == characterId) {
                entityController.saveProperties(memberId, JsonObject()
                    .put("targetId", ""))
            }
        }

        if (updatedMembers.size <= 1) {
            // End combat — no fight with one or zero members
            for (lastMemberId in updatedMembers) {
                clearCombatProperties(lastMemberId)
                sendCombatUnlock(lastMemberId)
            }
            entityController.saveState(combatId, JsonObject().put("members", emptyList<String>()))
            sendCombatMessage(combat.roomId, "|wThe fighting subsides.|n")
        }

        log.info("$characterId removed from combat $combatId, ${updatedMembers.size} members remaining")
    }

    suspend fun findCombatInRoom(roomId: String): Combat? {
        val combatKeys = stateStore.findAllKeysForType("Combat")
        if (combatKeys.isEmpty()) return null
        val combats = entityController.findByIds(combatKeys)
        return combats.values
            .filterIsInstance<Combat>()
            .firstOrNull { it.roomId == roomId && it.members.isNotEmpty() }
    }

    suspend fun findCombatForCharacter(characterId: String): Combat? {
        val character = getCharacter(characterId) ?: return null
        if (character.combatId.isEmpty()) return null
        return getCombat(character.combatId)
    }

    suspend fun cleanup(combatId: String) {
        val combat = getCombat(combatId) ?: return
        for (memberId in combat.members) {
            clearCombatProperties(memberId)
            sendCombatUnlock(memberId)
        }
        entityController.delete(combatId)
        log.info("Combat $combatId cleaned up")
    }

    suspend fun clearStaleCombatProperties(characterId: String) {
        clearCombatProperties(characterId)
    }

    suspend fun setAttackMode(characterId: String, targetId: String) {
        entityController.saveProperties(characterId, JsonObject()
            .put("combatMode", "attack")
            .put("targetId", targetId))
    }

    suspend fun setCombatMode(characterId: String, mode: String) {
        val props = JsonObject().put("combatMode", mode)
        if (mode != "attack") {
            props.put("targetId", "")
        }
        entityController.saveProperties(characterId, props)
    }

    suspend fun attemptEscape(characterId: String): JsonObject {
        val character = getCharacter(characterId)
            ?: return JsonObject().put("success", false).put("status", "error")

        if (character.combatId.isEmpty()) {
            return JsonObject().put("success", true).put("status", "not_in_combat")
        }

        // Skill check: Escape skill + Agility + position bonus
        val escapeSkill = character.skills.firstOrNull { it.name == "Escape" }
        val skillLevel = escapeSkill?.level ?: 0.0
        val agility = character.attributes.agility
        val positionBonus = (character.combatEquilibrium.position - 50.0) * 0.5
        val threshold = (skillLevel + agility) / 3.0 + positionBonus
        val roll = kotlin.random.Random.nextInt(100)

        return if (roll < threshold) {
            removeMember(character.combatId, characterId)
            val name = character.evenniaName
            val combat = getCombat(character.combatId)
            if (combat != null) {
                sendCombatMessage(combat.roomId, "|G$name breaks free and escapes!|n")
            }
            JsonObject().put("success", true).put("status", "escaped")
        } else {
            JsonObject().put("success", false).put("status", "failed")
        }
    }

    suspend fun onCharacterMoved(characterId: String, newRoomId: String) {
        val character = getCharacter(characterId) ?: return

        // Always update currentRoomId
        entityController.saveState(characterId, JsonObject().put("currentRoomId", newRoomId))

        if (character.combatId.isEmpty()) return

        // Remove from combat if in one
        removeMember(character.combatId, characterId)
        log.info("$characterId moved rooms, removed from combat ${character.combatId}")
    }

    // --- Internal helpers ---

    private suspend fun getCombat(combatId: String): Combat? {
        val entities = entityController.findByIds(listOf(combatId))
        return entities[combatId] as? Combat
    }

    private suspend fun getCharacter(characterId: String): EvenniaCharacter? {
        val entities = entityController.findByIds(listOf(characterId))
        return entities[characterId] as? EvenniaCharacter
    }

    private suspend fun getCharacterName(characterId: String): String {
        return getCharacter(characterId)?.evenniaName ?: "Someone"
    }

    private suspend fun setCombatProperties(characterId: String, combatId: String, mode: String, targetId: String) {
        entityController.saveProperties(characterId, JsonObject()
            .put("combatId", combatId)
            .put("combatMode", mode)
            .put("targetId", targetId))
    }

    private suspend fun clearCombatProperties(characterId: String) {
        entityController.saveProperties(characterId, JsonObject()
            .put("combatId", "")
            .put("combatMode", "")
            .put("targetId", "")
            .put("stance", "")
            .put("tactic", ""))
    }

    private suspend fun sendCombatLock(characterId: String) {
        val evenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", characterId) ?: return
        evenniaCommUtils.sendAgentCommand(JsonObject()
            .put("action", "combat_lock")
            .put("character_evennia_id", evenniaId))
    }

    private suspend fun sendCombatUnlock(characterId: String) {
        val evenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", characterId) ?: return
        evenniaCommUtils.sendAgentCommand(JsonObject()
            .put("action", "combat_unlock")
            .put("character_evennia_id", evenniaId))
    }

    private suspend fun sendCombatMessage(roomId: String, message: String) {
        val roomEvenniaId = crossLinkRegistry.getEvenniaId("Room", roomId) ?: return
        evenniaCommUtils.sendAgentCommand(JsonObject()
            .put("action", "combat_msg")
            .put("room_evennia_id", roomEvenniaId)
            .put("message", message))
    }
}
