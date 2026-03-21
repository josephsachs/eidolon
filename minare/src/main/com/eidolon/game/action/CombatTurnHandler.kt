package eidolon.game.action

import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.models.CombatEquilibrium
import com.eidolon.game.models.CombatScores
import com.eidolon.game.models.HealthData
import com.eidolon.game.service.CombatService
import com.eidolon.game.service.DamageService
import com.eidolon.game.models.entity.Combat
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import kotlin.random.Random

@Singleton
class CombatTurnHandler @Inject constructor(
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val combatService: CombatService,
    private val damageService: DamageService,
    private val evenniaCommUtils: EvenniaCommUtils,
    private val crossLinkRegistry: CrossLinkRegistry
) {
    private val log = LoggerFactory.getLogger(CombatTurnHandler::class.java)

    // Transient per-turn derived scores
    private val turnScores = mutableMapOf<String, CombatScores>()

    suspend fun handleTurn(turnPhase: GameTurnHandler.Companion.TurnPhase) {
        val combatKeys = stateStore.findAllKeysForType("Combat")
        if (combatKeys.isEmpty()) return

        val combats = entityController.findByIds(combatKeys)
        for ((_, entity) in combats) {
            val combat = entity as? Combat ?: continue
            if (combat.members.isEmpty()) continue

            when (turnPhase) {
                GameTurnHandler.Companion.TurnPhase.BEFORE -> handleBefore(combat)
                GameTurnHandler.Companion.TurnPhase.DURING -> handleDuring(combat)
                GameTurnHandler.Companion.TurnPhase.AFTER -> handleAfter(combat)
            }
        }
    }

    private suspend fun handleBefore(combat: Combat) {
        val members = loadMembers(combat.members)

        for ((id, character) in members) {
            if (character.dead) continue
            val scores = calculateScores(character)
            turnScores[id] = scores
        }
    }

    private suspend fun handleDuring(combat: Combat) {
        val members = loadMembers(combat.members)

        for ((id, character) in members) {
            if (character.dead) continue

            when (character.combatMode) {
                "attack" -> resolveAttack(combat, id, character, members)
                "defend" -> resolveDefend(combat, id, character)
                "avoid" -> resolveAvoid(combat, id, character)
            }
        }
    }

    private suspend fun handleAfter(combat: Combat) {
        val members = loadMembers(combat.members)

        // Drift equilibrium toward center
        for ((id, character) in members) {
            if (character.dead) continue

            val eq = character.combatEquilibrium
            val newBalance = driftToward(eq.balance, 50.0, 2.0)
            val newPosition = driftToward(eq.position, 50.0, 2.0)
            val newTempo = driftToward(eq.tempo, 50.0, 2.0)

            val updated = CombatEquilibrium(newBalance, newPosition, newTempo)
            if (updated != eq) {
                saveEquilibrium(id, updated)
            }
        }

        // Remove dead members
        for ((id, character) in members) {
            if (character.dead && id in combat.members) {
                combatService.removeMember(combat._id!!, id)
            }
        }

        turnScores.clear()
    }

    // --- Combat resolution ---

    private suspend fun resolveAttack(
        combat: Combat,
        attackerId: String,
        attacker: EvenniaCharacter,
        members: Map<String, EvenniaCharacter>
    ) {
        val targetId = attacker.targetId
        if (targetId.isEmpty()) return

        val target = members[targetId]
        if (target == null || target.dead) {
            entityController.saveProperties(attackerId, JsonObject().put("targetId", ""))
            return
        }

        val attackScores = turnScores[attackerId] ?: return
        val defenseScores = turnScores[targetId] ?: return
        val attackerEq = attacker.combatEquilibrium
        val targetEq = target.combatEquilibrium

        // Position affects to-hit
        val toHitBonus = (targetEq.position - 50.0) * -0.3 // low target position = easier to hit

        // Free block/dodge from defender's position
        val freeDefense = when (target.combatMode) {
            "defend" -> (targetEq.position - 50.0) * 0.5
            "avoid" -> (targetEq.position - 50.0) * 0.4
            else -> 0.0
        }

        // Mode defense bonuses
        val modeDefenseBonus = when (target.combatMode) {
            "defend" -> 25.0
            "avoid" -> 15.0
            else -> 0.0
        }

        val attackRoll = Random.nextInt(100) + attackScores.attack + toHitBonus
        val defenseRoll = defenseScores.defense + modeDefenseBonus + freeDefense

        if (attackRoll > defenseRoll) {
            // Hit — balance affects damage output
            val balanceMod = attackerEq.balance / 50.0 // 0.0 to 2.0 multiplier
            val baseDamage = (attacker.attributes.strength / 10.0 * balanceMod).toInt().coerceAtLeast(1)
            val hardpointDamage = baseDamage
            val vitalityDamage = baseDamage / 2

            var updatedHealth = damageService.applyBurn(target.health, hardpointDamage, vitalityDamage)
            entityController.saveState(targetId, JsonObject().put("health", healthToJson(updatedHealth)))

            // Death check
            if (updatedHealth.vitality <= 0) {
                val deathResult = damageService.rollDeathSave(target)
                if (!deathResult.passed) {
                    entityController.saveState(targetId, JsonObject()
                        .put("dead", true)
                        .put("health", healthToJson(updatedHealth)))
                    damageService.flagDead(target)
                }
            }

            // Tempo drives position gain on attack
            val positionGain = (attackerEq.tempo - 50.0) * 0.1
            shiftEquilibrium(attackerId, attacker,
                tempoShift = 5.0,
                positionShift = positionGain)

            // Target loses balance (staggered) — low balance risks falling
            shiftEquilibrium(targetId, target,
                balanceShift = -5.0,
                tempoShift = -2.0)

            // Clash message
            sendCombatMessage(combat.roomId,
                "|R${attacker.evenniaName} lands a blow on ${target.evenniaName}!|n")

            // Feedback to attacker
            sendPersonalFeedback(combat.roomId, attackerId,
                buildFeedback(attacker, target, updatedHealth, "hit"))

            // Feedback to target
            sendPersonalFeedback(combat.roomId, targetId,
                buildFeedback(target, attacker, attacker.health, "was_hit"))

        } else {
            // Miss — attacker loses balance from overcommitting
            shiftEquilibrium(attackerId, attacker, balanceShift = -3.0)

            sendCombatMessage(combat.roomId,
                "|y${attacker.evenniaName} swings at ${target.evenniaName} but misses.|n")

            sendPersonalFeedback(combat.roomId, attackerId,
                buildFeedback(attacker, target, target.health, "miss"))
        }
    }

    private suspend fun resolveDefend(
        combat: Combat,
        defenderId: String,
        defender: EvenniaCharacter
    ) {
        val eq = defender.combatEquilibrium

        // Defending builds position (tactical advantage)
        // Balance affects position gain on defense
        val balanceMod = eq.balance / 50.0
        val positionGain = 3.0 * balanceMod

        shiftEquilibrium(defenderId, defender,
            positionShift = positionGain,
            balanceShift = 2.0) // defending also steadies balance
    }

    private suspend fun resolveAvoid(
        combat: Combat,
        avoiderId: String,
        avoider: EvenniaCharacter
    ) {
        // Position affects escape odds — tracked for the escape skill check
        // Avoiding builds position and steadies tempo
        shiftEquilibrium(avoiderId, avoider,
            positionShift = 3.0,
            tempoShift = 2.0,
            balanceShift = 1.0)
    }

    // --- Score calculation ---

    /**
     * Equilibrium effects:
     * - Balance: damage output, position gain on defense, save vs falling
     * - Position: to-hit, free block/dodge, escape odds when avoiding
     * - Tempo: position gain on attack, save vs disordered
     */
    private fun calculateScores(character: EvenniaCharacter): CombatScores {
        val handToHand = character.skills.firstOrNull { it.name == "Hand-to-Hand" }
        val weaponSkill = handToHand?.level ?: 0.0
        val eq = character.combatEquilibrium

        val attack = weaponSkill + character.attributes.strength * 0.5 + eq.balance * 0.2
        val defense = weaponSkill + character.attributes.agility * 0.5 + eq.position * 0.2
        val control = weaponSkill + character.attributes.wits * 0.5 + eq.tempo * 0.2

        return CombatScores(attack, defense, control)
    }

    // --- Combat feedback messaging ---

    private fun buildFeedback(
        self: EvenniaCharacter,
        adversary: EvenniaCharacter,
        adversaryHealth: HealthData,
        outcome: String
    ): String {
        val lines = mutableListOf<String>()

        // Your equilibrium state
        val eq = self.combatEquilibrium
        lines.add(describeEquilibrium(eq))

        // Adversary health read
        lines.add("${adversary.evenniaName} ${describeVitality(adversaryHealth.vitality)}.")

        return lines.joinToString(" ")
    }

    private fun describeEquilibrium(eq: CombatEquilibrium): String {
        val parts = mutableListOf<String>()

        parts.add(when {
            eq.balance >= 70 -> "Your footing is solid."
            eq.balance >= 40 -> "Your balance is steady."
            eq.balance >= 20 -> "Your balance is shaky."
            else -> "You're staggering."
        })

        parts.add(when {
            eq.position >= 70 -> "You have the advantage."
            eq.position >= 40 -> "Your position is neutral."
            eq.position >= 20 -> "You're on the back foot."
            else -> "You're cornered."
        })

        parts.add(when {
            eq.tempo >= 70 -> "You have momentum."
            eq.tempo >= 40 -> "The pace is even."
            eq.tempo >= 20 -> "You're losing the tempo."
            else -> "You're completely disordered."
        })

        return parts.joinToString(" ")
    }

    private fun describeVitality(vitality: Int): String {
        return when {
            vitality >= 90 -> "looks fresh"
            vitality >= 70 -> "is scratched up"
            vitality >= 50 -> "looks battered"
            vitality >= 30 -> "is badly wounded"
            vitality >= 10 -> "is on the edge of death"
            else -> "is barely standing"
        }
    }

    // --- Equilibrium helpers ---

    private suspend fun shiftEquilibrium(
        characterId: String,
        character: EvenniaCharacter,
        balanceShift: Double = 0.0,
        positionShift: Double = 0.0,
        tempoShift: Double = 0.0
    ) {
        val eq = character.combatEquilibrium
        val updated = CombatEquilibrium(
            balance = (eq.balance + balanceShift).coerceIn(0.0, 100.0),
            position = (eq.position + positionShift).coerceIn(0.0, 100.0),
            tempo = (eq.tempo + tempoShift).coerceIn(0.0, 100.0)
        )
        saveEquilibrium(characterId, updated)
    }

    private suspend fun saveEquilibrium(characterId: String, eq: CombatEquilibrium) {
        entityController.saveProperties(characterId, JsonObject()
            .put("combatEquilibrium", JsonObject()
                .put("balance", eq.balance)
                .put("position", eq.position)
                .put("tempo", eq.tempo)))
    }

    private fun driftToward(current: Double, target: Double, rate: Double): Double {
        return when {
            current < target -> (current + rate).coerceAtMost(target)
            current > target -> (current - rate).coerceAtLeast(target)
            else -> current
        }
    }

    // --- Messaging ---

    private suspend fun sendCombatMessage(roomId: String, message: String) {
        val roomEvenniaId = crossLinkRegistry.getEvenniaId("Room", roomId) ?: return
        evenniaCommUtils.sendAgentCommand(JsonObject()
            .put("action", "combat_msg")
            .put("room_evennia_id", roomEvenniaId)
            .put("message", message))
    }

    private suspend fun sendPersonalFeedback(roomId: String, characterId: String, message: String) {
        val evenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", characterId) ?: return
        val roomEvenniaId = crossLinkRegistry.getEvenniaId("Room", roomId) ?: return
        evenniaCommUtils.sendAgentCommand(JsonObject()
            .put("action", "combat_feedback")
            .put("room_evennia_id", roomEvenniaId)
            .put("character_evennia_id", evenniaId)
            .put("message", message))
    }

    // --- Serialization ---

    private fun healthToJson(health: HealthData): JsonObject {
        val hardpoints = JsonArray()
        health.hardpoints.forEach { hp ->
            hardpoints.add(JsonObject()
                .put("name", hp.name.name)
                .put("hp", hp.hp)
                .put("status", hp.status.name))
        }
        return JsonObject()
            .put("hardpoints", hardpoints)
            .put("vitality", health.vitality)
            .put("concentration", health.concentration)
            .put("stamina", health.stamina)
            .put("luck", health.luck)
    }

    private suspend fun loadMembers(memberIds: List<String>): Map<String, EvenniaCharacter> {
        val entities = entityController.findByIds(memberIds)
        return entities.mapNotNull { (id, entity) ->
            (entity as? EvenniaCharacter)?.let { id to it }
        }.toMap()
    }
}
