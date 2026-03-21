package eidolon.game.models.entity.agent

import com.eidolon.game.service.CombatService
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

class FeralBrain(
    private val entityController: EntityController,
    private val combatService: CombatService,
    private val stateStore: StateStore
) : Brain {
    private val log = LoggerFactory.getLogger(FeralBrain::class.java)

    override val brainType: String = "feral"

    override suspend fun onTurn(character: EvenniaCharacter, phase: String, context: JsonObject) {
        if (character.dead) return

        when (phase) {
            "BEFORE" -> onBefore(character)
            "DURING" -> onDuring(character)
        }
    }

    private suspend fun onBefore(character: EvenniaCharacter) {
        // Check if we're still actually in an active combat
        if (character.combatId.isNotEmpty()) {
            val combat = combatService.findCombatForCharacter(character._id!!)
            if (combat != null && combat.members.size > 1) return
            // Stale combatId — combat ended or we're the only member. Clear it.
            combatService.clearStaleCombatProperties(character._id!!)
        }
        if (character.currentRoomId.isEmpty()) return

        // Poll room for player characters
        val allCharKeys = stateStore.findAllKeysForType("EvenniaCharacter")
        val allChars = entityController.findByIds(allCharKeys)

        val targets = allChars.values
            .filterIsInstance<EvenniaCharacter>()
            .filter { it._id != character._id
                && !it.isNpc
                && !it.dead
                && it.currentRoomId == character.currentRoomId }

        if (targets.isEmpty()) return

        val target = targets.random()
        combatService.createCombat(character.currentRoomId, character._id!!, target._id!!)
        log.info("${character.evenniaName} attacks ${target.evenniaName}!")
    }

    private suspend fun onDuring(character: EvenniaCharacter) {
        if (character.combatId.isEmpty()) return

        // Ensure we're in attack mode with a valid target
        if (character.combatMode != "attack" || character.targetId.isEmpty()) {
            // Find a target in the combat
            val combat = combatService.findCombatForCharacter(character._id!!) ?: return
            val allChars = entityController.findByIds(combat.members)
            val target = allChars.values
                .filterIsInstance<EvenniaCharacter>()
                .firstOrNull { it._id != character._id && !it.dead }

            if (target != null) {
                combatService.setAttackMode(character._id!!, target._id!!)
            }
        }
    }

    override suspend fun onPlayerInteraction(character: EvenniaCharacter, interaction: JsonObject) {
        // Pigs don't talk
    }
}
