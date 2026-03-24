package eidolon.game.action

import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.models.CombatEquilibrium
import com.eidolon.game.models.CombatScores
import com.eidolon.game.models.HardpointName
import com.eidolon.game.models.HealthData
import com.eidolon.game.models.HitLocationTable
import com.eidolon.game.models.ItemTemplate
import com.eidolon.game.commands.SkillEvent
import com.eidolon.game.service.CombatMessageService
import com.eidolon.game.service.CombatService
import com.eidolon.game.service.DamageService
import com.eidolon.game.service.ItemRegistry
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
    private val crossLinkRegistry: CrossLinkRegistry,
    private val itemRegistry: ItemRegistry,
    private val skillEvent: SkillEvent,
    private val combatMessageService: CombatMessageService
) {
    private val log = LoggerFactory.getLogger(CombatTurnHandler::class.java)

    // Transient per-turn derived scores
    private val turnScores = mutableMapOf<String, CombatScores>()
    private val downedThisRound = mutableSetOf<String>()
    private val pendingEquilibrium = mutableMapOf<String, CombatEquilibrium>()

    suspend fun handleTurn(turnPhase: GameTurnHandler.Companion.TurnPhase) {
        if (turnPhase == GameTurnHandler.Companion.TurnPhase.DURING) {
            downedThisRound.clear()
        }

        val combatKeys = stateStore.findAllKeysForType("Combat")
        if (combatKeys.isEmpty()) return

        val combats = entityController.findByIds(combatKeys)
        for ((id, entity) in combats) {
            val combat = entity as? Combat ?: continue
            if (combat.members.isEmpty()) continue

            if (turnPhase == GameTurnHandler.Companion.TurnPhase.DURING) {
                log.info("Combat $id DURING phase: ${combat.members.size} members in room ${combat.roomId}")
            }

            when (turnPhase) {
                GameTurnHandler.Companion.TurnPhase.BEFORE -> handleBefore(combat)
                GameTurnHandler.Companion.TurnPhase.DURING -> handleDuring(combat)
                GameTurnHandler.Companion.TurnPhase.AFTER -> handleAfter(combat)
            }
        }

        if (turnPhase == GameTurnHandler.Companion.TurnPhase.AFTER) {
            downedThisRound.clear()
        }
    }

    private suspend fun handleBefore(combat: Combat) {
        val members = loadMembers(combat.members)

        // Increment round
        val newRound = combat.currentRound + 1
        entityController.saveProperties(combat._id, JsonObject().put("currentRound", newRound))

        for ((id, character) in members) {
            if (character.dead || character.downed) continue
            val scores = calculateScores(character)
            turnScores[id] = scores
        }
    }

    private suspend fun handleDuring(combat: Combat) {
        val members = loadMembers(combat.members)

        for ((id, character) in members) {
            log.info("Combat member ${character.evenniaName} ($id): mode=${character.combatMode} target=${character.targetId} dead=${character.dead} downed=${character.downed}")
            if (character.dead || character.downed || id in downedThisRound) continue

            when (character.combatMode) {
                "attack" -> resolveAttack(combat, id, character, members)
                "defend" -> resolveDefend(combat, id, character)
                "avoid" -> resolveAvoid(combat, id, character)
                else -> log.info("Combat member ${character.evenniaName} has no actionable mode: '${character.combatMode}'")
            }
        }

        flushEquilibrium()
    }

    private suspend fun handleAfter(combat: Combat) {
        val members = loadMembers(combat.members)

        // Drift equilibrium toward center (accumulate, don't save yet)
        for ((id, character) in members) {
            if (character.dead || character.downed) continue

            val eq = character.combatEquilibrium
            val newBalance = driftToward(eq.balance, 50.0, 2.0)
            val newPosition = driftToward(eq.position, 50.0, 2.0)
            val newTempo = driftToward(eq.tempo, 50.0, 2.0)

            val updated = CombatEquilibrium(newBalance, newPosition, newTempo)
            if (updated != eq) {
                pendingEquilibrium[id] = updated
            }
        }

        // Second-strikes: defenders/avoiders with high position may counter
        for ((id, character) in members) {
            if (character.dead || character.downed || id in downedThisRound) continue
            if (character.combatMode !in listOf("defend", "avoid")) continue

            val eq = pendingEquilibrium[id] ?: character.combatEquilibrium
            if (eq.position < 65.0) continue

            val attackerTargetingMe = members.entries
                .firstOrNull { it.value.targetId == id && it.key != id && !it.value.dead && !it.value.downed && it.key !in downedThisRound }
            val targetEntry = attackerTargetingMe ?: continue
            val targetId = targetEntry.key
            val target = targetEntry.value

            val scores = turnScores[id] ?: continue
            val defScores = turnScores[targetId] ?: continue

            val counterRoll = Random.nextInt(100) + scores.attack * 0.6
            val dodgeRoll = defScores.defense * 0.5

            if (counterRoll > dodgeRoll) {
                val weapon = character.equipment["MAIN_HAND"]?.let { itemRegistry.get(it) }
                val weaponType = weapon?.weaponType?.ifEmpty { null } ?: "hand-to-hand"
                applyDamage(combat, id, character, targetId, target, weapon, 0.6, "second_strike")
            }

            // Spend position for the counter
            shiftEquilibrium(id, character, positionShift = -15.0)
        }

        flushEquilibrium()

        // Send status feedback once per member
        for ((id, character) in members) {
            if (character.dead || character.downed || id in downedThisRound) continue
            val adversaryId = character.targetId
            if (adversaryId.isEmpty()) continue
            val adversary = members[adversaryId] ?: continue
            sendPersonalFeedback(combat.roomId, id,
                buildFeedback(character, adversary, adversary.health, "status"))
        }

        // Remove dead and downed members (including those downed this round)
        for ((id, character) in members) {
            if ((character.dead || character.downed || id in downedThisRound) && id in combat.members) {
                combatService.removeMember(combat._id, id)
            }
        }

        // Remove members with no target and no one targeting them
        val refreshedCombat = entityController.findByIds(listOf(combat._id!!))
            .values.firstOrNull() as? Combat
        if (refreshedCombat != null && refreshedCombat.members.size > 1) {
            val refreshedMembers = loadMembers(refreshedCombat.members)
            val targeted = refreshedMembers.values.map { it.targetId }.toSet()

            for ((id, character) in refreshedMembers) {
                val hasTarget = character.targetId.isNotEmpty() && character.targetId in refreshedMembers
                val isTargeted = id in targeted
                if (!hasTarget && !isTargeted) {
                    combatService.removeMember(combat._id, id)
                }
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
        if (targetId.isEmpty()) {
            log.info("resolveAttack: ${attacker.evenniaName} has empty targetId")
            return
        }

        val target = members[targetId]
        if (target == null || target.dead || target.downed || targetId in downedThisRound) {
            log.info("resolveAttack: ${attacker.evenniaName} target ${targetId} is null=${target == null} dead=${target?.dead} downed=${target?.downed} downedThisRound=${targetId in downedThisRound}")
            entityController.saveProperties(attackerId, JsonObject().put("targetId", ""))
            return
        }

        val attackScores = turnScores[attackerId]
        if (attackScores == null) {
            log.info("resolveAttack: ${attacker.evenniaName} has no turnScores (keys: ${turnScores.keys})")
            return
        }
        val defenseScores = turnScores[targetId]
        if (defenseScores == null) {
            log.info("resolveAttack: target ${target.evenniaName} has no turnScores (keys: ${turnScores.keys})")
            return
        }
        val attackerEq = attacker.combatEquilibrium
        val targetEq = target.combatEquilibrium

        val weapon = attacker.equipment["MAIN_HAND"]?.let { itemRegistry.get(it) }
        val weaponType = weapon?.weaponType?.ifEmpty { null } ?: "hand-to-hand"
        val weaponSkillName = weapon?.skill?.ifEmpty { null } ?: "Hand-to-Hand"
        val weaponSkill = attacker.skills.firstOrNull { it.name == weaponSkillName }?.level ?: 0.0

        // Attack costs balance (weapon-specific)
        val balanceCost = weapon?.balanceCost ?: 3
        shiftEquilibrium(attackerId, attacker, balanceShift = -balanceCost.toDouble())
        // Compute effective attacker equilibrium after balance cost
        val effectiveBalance = (attackerEq.balance - balanceCost).coerceIn(0.0, 100.0)

        // Position affects to-hit
        val toHitBonus = (targetEq.position - 50.0) * -0.3

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

        log.info("resolveAttack: ${attacker.evenniaName} vs ${target.evenniaName} — roll ${attackRoll} vs defense ${defenseRoll} (hit=${attackRoll > defenseRoll})")

        if (attackRoll > defenseRoll) {
            // Hit — apply damage with crit check
            val balanceMod = effectiveBalance / 50.0

            val critChance = calculateCritChance(weaponSkill, weapon, attackerEq)
            val isCrit = Random.nextInt(100) < critChance

            // Unarmed untrained penalty: 0.5x without crit
            var damageMod = 1.0
            if (isCrit) {
                damageMod = 1.5
            } else if (weapon == null && weaponSkill < 2.0) {
                damageMod = 0.5
            }

            val weaponDamage = weapon?.damage ?: 0
            val baseDamage = ((attacker.attributes.strength / 8.0 + weaponDamage + 2) * balanceMod * damageMod).toInt().coerceAtLeast(1)

            val hitLocation = HitLocationTable.roll()

            // Armor absorption on the hit location
            val armor = target.equipment[hitLocation.name]?.let { itemRegistry.get(it) }
            val absorption = armor?.absorption ?: 0
            val hardpointDamage = (baseDamage - absorption).coerceAtLeast(1)
            val vitalityDamage = hardpointDamage * 2

            var updatedHealth = damageService.applyHardpointDamage(target.health, hitLocation, hardpointDamage)
            updatedHealth = damageService.applyVitalityDamage(updatedHealth, vitalityDamage)

            // Stamina cost for absorbing a blow; torso hits cost extra
            val staminaCost = hardpointDamage + if (hitLocation == HardpointName.TORSO) hardpointDamage / 2 else 0
            updatedHealth = updatedHealth.copy(stamina = (updatedHealth.stamina - staminaCost).coerceAtLeast(0))

            // Head blows cost concentration
            if (hitLocation == HardpointName.HEAD) {
                updatedHealth = updatedHealth.copy(concentration = (updatedHealth.concentration - hardpointDamage).coerceAtLeast(0))
            }

            entityController.saveState(targetId, JsonObject().put("health", healthToJson(updatedHealth)))

            // Downed check (vitality <= 0)
            if (updatedHealth.vitality <= 0 && !target.downed && targetId !in downedThisRound) {
                downedThisRound.add(targetId)
                entityController.saveState(targetId, JsonObject()
                    .put("downed", true)
                    .put("health", healthToJson(updatedHealth)))
                damageService.flagDowned(target)

                appendCombatLog(combat, JsonObject()
                    .put("round", combat.currentRound)
                    .put("phase", "DURING")
                    .put("type", "downed")
                    .put("targetId", targetId)
                    .put("targetName", target.evenniaName)
                    .put("timestamp", System.currentTimeMillis()))
            }

            // Equilibrium shifts on hit
            val tacticsSkill = attacker.skills.firstOrNull { it.name == "Tactics" }?.level ?: 0.0
            val targetTactics = target.skills.firstOrNull { it.name == "Tactics" }?.level ?: 0.0
            val rhythmSkill = attacker.skills.firstOrNull { it.name == "Rhythm" }?.level ?: 0.0
            val targetRhythm = target.skills.firstOrNull { it.name == "Rhythm" }?.level ?: 0.0

            val positionGain = (attackerEq.tempo - 50.0) * 0.1 + (tacticsSkill - targetTactics) * 0.3
            val tempoGain = 5.0 + (rhythmSkill - targetRhythm) * 0.3

            shiftEquilibrium(attackerId, attacker,
                tempoShift = tempoGain,
                positionShift = positionGain)

            shiftEquilibrium(targetId, target,
                balanceShift = -5.0,
                tempoShift = -2.0)

            // Combat message
            val msg = if (isCrit) {
                combatMessageService.critHitMessage(weaponType, attacker.evenniaName, target.evenniaName, hitLocation)
            } else {
                combatMessageService.hitMessage(weaponType, attacker.evenniaName, target.evenniaName, hitLocation)
            }
            sendCombatMessage(combat.roomId, "|R$msg|n")

            // Combat log
            appendCombatLog(combat, JsonObject()
                .put("round", combat.currentRound)
                .put("phase", "DURING")
                .put("type", if (isCrit) "crit_hit" else "attack_hit")
                .put("attackerId", attackerId)
                .put("targetId", targetId)
                .put("attackerName", attacker.evenniaName)
                .put("targetName", target.evenniaName)
                .put("hitLocation", hitLocation.name)
                .put("hardpointDamage", hardpointDamage)
                .put("vitalityDamage", vitalityDamage)
                .put("weaponType", weaponType)
                .put("crit", isCrit)
                .put("timestamp", System.currentTimeMillis()))

        } else {
            // Miss
            val isCritMiss = attackRoll < (defenseRoll - 30)

            if (isCritMiss) {
                // Critical miss: defender gets position/tempo boost, attacker loses extra balance
                if (target.combatMode in listOf("defend", "avoid")) {
                    shiftEquilibrium(targetId, target, positionShift = 8.0, tempoShift = 5.0)
                }
                shiftEquilibrium(attackerId, attacker, balanceShift = -8.0)

                val msg = combatMessageService.critMissMessage(attacker.evenniaName, target.evenniaName)
                sendCombatMessage(combat.roomId, "|y$msg|n")
            } else {
                val msg = combatMessageService.missMessage(weaponType, attacker.evenniaName, target.evenniaName)
                sendCombatMessage(combat.roomId, "|y$msg|n")
            }

            // Combat log
            appendCombatLog(combat, JsonObject()
                .put("round", combat.currentRound)
                .put("phase", "DURING")
                .put("type", if (isCritMiss) "crit_miss" else "attack_miss")
                .put("attackerId", attackerId)
                .put("targetId", targetId)
                .put("attackerName", attacker.evenniaName)
                .put("targetName", target.evenniaName)
                .put("weaponType", weaponType)
                .put("crit", isCritMiss)
                .put("timestamp", System.currentTimeMillis()))
        }

        // Weapon skill gain for attacker (hit = success, miss = failure)
        if (!attacker.isNpc) {
            val outcome = if (attackRoll > defenseRoll) "success" else "failure"
            skillEvent.execute(JsonObject()
                .put("character_id", attackerId)
                .put("skill_name", weaponSkillName)
                .put("outcome", outcome))
        }
    }

    /**
     * Apply damage from a hit (used by both resolveAttack and second-strikes).
     * damageMult scales the damage (1.0 for normal, 0.6 for counter-attacks).
     */
    private suspend fun applyDamage(
        combat: Combat,
        attackerId: String,
        attacker: EvenniaCharacter,
        targetId: String,
        target: EvenniaCharacter,
        weapon: ItemTemplate?,
        damageMult: Double,
        logType: String
    ) {
        val attackerEq = attacker.combatEquilibrium
        val balanceMod = attackerEq.balance / 50.0
        val weaponType = weapon?.weaponType?.ifEmpty { null } ?: "hand-to-hand"

        val weaponDamage = weapon?.damage ?: 0
        val baseDamage = ((attacker.attributes.strength / 8.0 + weaponDamage + 2) * balanceMod * damageMult).toInt().coerceAtLeast(1)

        val hitLocation = HitLocationTable.roll()

        val armor = target.equipment[hitLocation.name]?.let { itemRegistry.get(it) }
        val absorption = armor?.absorption ?: 0
        val hardpointDamage = (baseDamage - absorption).coerceAtLeast(1)
        val vitalityDamage = hardpointDamage * 2

        var updatedHealth = damageService.applyHardpointDamage(target.health, hitLocation, hardpointDamage)
        updatedHealth = damageService.applyVitalityDamage(updatedHealth, vitalityDamage)

        val staminaCost = hardpointDamage + if (hitLocation == HardpointName.TORSO) hardpointDamage / 2 else 0
        updatedHealth = updatedHealth.copy(stamina = (updatedHealth.stamina - staminaCost).coerceAtLeast(0))

        if (hitLocation == HardpointName.HEAD) {
            updatedHealth = updatedHealth.copy(concentration = (updatedHealth.concentration - hardpointDamage).coerceAtLeast(0))
        }

        entityController.saveState(targetId, JsonObject().put("health", healthToJson(updatedHealth)))

        if (updatedHealth.vitality <= 0 && !target.downed && targetId !in downedThisRound) {
            downedThisRound.add(targetId)
            entityController.saveState(targetId, JsonObject()
                .put("downed", true)
                .put("health", healthToJson(updatedHealth)))
            damageService.flagDowned(target)
        }

        val msg = combatMessageService.hitMessage(weaponType, attacker.evenniaName, target.evenniaName, hitLocation)
        sendCombatMessage(combat.roomId, "|M$msg|n")

        appendCombatLog(combat, JsonObject()
            .put("round", combat.currentRound)
            .put("phase", "AFTER")
            .put("type", logType)
            .put("attackerId", attackerId)
            .put("targetId", targetId)
            .put("attackerName", attacker.evenniaName)
            .put("targetName", target.evenniaName)
            .put("hitLocation", hitLocation.name)
            .put("hardpointDamage", hardpointDamage)
            .put("vitalityDamage", vitalityDamage)
            .put("weaponType", weaponType)
            .put("timestamp", System.currentTimeMillis()))
    }

    private suspend fun resolveDefend(
        combat: Combat,
        defenderId: String,
        defender: EvenniaCharacter
    ) {
        val eq = defender.combatEquilibrium
        val balanceMod = eq.balance / 50.0
        val positionGain = 3.0 * balanceMod

        shiftEquilibrium(defenderId, defender,
            positionShift = positionGain,
            balanceShift = 2.0)
    }

    private suspend fun resolveAvoid(
        combat: Combat,
        avoiderId: String,
        avoider: EvenniaCharacter
    ) {
        shiftEquilibrium(avoiderId, avoider,
            positionShift = 3.0,
            tempoShift = 2.0,
            balanceShift = 1.0)
    }

    // --- Score calculation ---

    private fun calculateScores(character: EvenniaCharacter): CombatScores {
        val weapon = character.equipment["MAIN_HAND"]?.let { itemRegistry.get(it) }
        val skillName = weapon?.skill?.ifEmpty { null } ?: "Hand-to-Hand"
        val skill = character.skills.firstOrNull { it.name == skillName }
        val weaponSkill = skill?.level ?: 0.0
        val eq = character.combatEquilibrium

        val tacticsSkill = character.skills.firstOrNull { it.name == "Tactics" }?.level ?: 0.0

        val attack = weaponSkill + character.attributes.strength * 0.5 + eq.balance * 0.2
        val defense = weaponSkill + character.attributes.agility * 0.5 + eq.position * 0.2
        val mobility = weaponSkill * 0.3 + character.attributes.agility * 0.3 +
                character.attributes.wits * 0.2 + eq.tempo * 0.2 + tacticsSkill * 0.2

        return CombatScores(attack, defense, mobility)
    }

    private fun calculateCritChance(
        weaponSkill: Double,
        weapon: ItemTemplate?,
        attackerEq: CombatEquilibrium
    ): Double {
        val base = 5.0
        val skillBonus = weaponSkill * 0.5
        val weaponBonus = weapon?.critModifier ?: 0.0
        val tempoBonus = (attackerEq.tempo - 50.0) * 0.1
        return (base + skillBonus + weaponBonus + tempoBonus).coerceIn(1.0, 40.0)
    }

    // --- Combat feedback messaging ---

    private fun buildFeedback(
        self: EvenniaCharacter,
        adversary: EvenniaCharacter,
        adversaryHealth: HealthData,
        outcome: String
    ): String {
        val selfStatus = describeEquilibrium(self.combatEquilibrium)
        val adversaryStatus = describeEquilibrium(adversary.combatEquilibrium)
        val adversaryVit = describeVitality(adversaryHealth.vitality)
        val adversaryName = adversary.evenniaName.replaceFirstChar { it.uppercase() }

        return "You: $selfStatus\n$adversaryName: $adversaryStatus [$adversaryVit]"
    }

    private fun describeEquilibrium(eq: CombatEquilibrium): String {
        val balance = when {
            eq.balance >= 70 -> "solid"
            eq.balance >= 40 -> "balanced"
            eq.balance >= 20 -> "shaky"
            else -> "staggering"
        }

        val position = when {
            eq.position >= 70 -> "advantage"
            eq.position >= 40 -> "even"
            eq.position >= 20 -> "back foot"
            else -> "cornered"
        }

        val tempo = when {
            eq.tempo >= 70 -> "momentum"
            eq.tempo >= 40 -> "even"
            eq.tempo >= 20 -> "losing tempo"
            else -> "disordered"
        }

        return "[$balance, $position, $tempo]"
    }

    private fun describeVitality(vitality: Int): String {
        return when {
            vitality >= 90 -> "fresh"
            vitality >= 70 -> "scratched up"
            vitality >= 50 -> "battered"
            vitality >= 30 -> "badly wounded"
            vitality >= 10 -> "on the edge"
            vitality > 0 -> "barely standing"
            else -> "down"
        }
    }

    // --- Combat log ---

    private suspend fun appendCombatLog(combat: Combat, entry: JsonObject) {
        val updatedLog = combat.combatLog + entry
        entityController.saveProperties(combat._id, JsonObject()
            .put("combatLog", JsonArray(updatedLog)))
    }

    // --- Equilibrium helpers ---

    private fun shiftEquilibrium(
        characterId: String,
        character: EvenniaCharacter,
        balanceShift: Double = 0.0,
        positionShift: Double = 0.0,
        tempoShift: Double = 0.0
    ) {
        val eq = pendingEquilibrium[characterId] ?: character.combatEquilibrium
        pendingEquilibrium[characterId] = CombatEquilibrium(
            balance = (eq.balance + balanceShift).coerceIn(0.0, 100.0),
            position = (eq.position + positionShift).coerceIn(0.0, 100.0),
            tempo = (eq.tempo + tempoShift).coerceIn(0.0, 100.0)
        )
    }

    private suspend fun flushEquilibrium() {
        for ((characterId, eq) in pendingEquilibrium) {
            entityController.saveProperties(characterId, JsonObject()
                .put("combatEquilibrium", JsonObject()
                    .put("balance", eq.balance)
                    .put("position", eq.position)
                    .put("tempo", eq.tempo)))
        }
        pendingEquilibrium.clear()
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
