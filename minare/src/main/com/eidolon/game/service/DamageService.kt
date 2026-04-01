package com.eidolon.game.service

import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.models.*
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import kotlin.random.Random

data class DeathSaveResult(
    val passed: Boolean,
    val roll: Int,
    val threshold: Int,
    val modifiers: JsonObject = JsonObject()
)

@Singleton
class DamageService @Inject constructor(
    private val entityController: EntityController,
    private val evenniaCommUtils: EvenniaCommUtils
) {
    private val log = LoggerFactory.getLogger(DamageService::class.java)

    /**
     * Apply damage to a specific hardpoint on the character.
     * Returns the updated HealthData.
     */
    fun applyHardpointDamage(health: HealthData, target: HardpointName, amount: Int): HealthData {
        val updatedHardpoints = health.hardpoints.map { hp ->
            if (hp.name == target) {
                val newHp = (hp.hp - amount).coerceAtLeast(0)
                hp.copy(hp = newHp, status = statusForHp(newHp))
            } else hp
        }
        return health.copy(hardpoints = updatedHardpoints)
    }

    /**
     * Apply damage to vitality (global health).
     * Returns the new vitality value (can go negative).
     */
    fun applyVitalityDamage(health: HealthData, amount: Int): HealthData {
        return health.copy(vitality = health.vitality - amount)
    }

    /**
     * Apply a burn effect: damage a random hardpoint and reduce vitality.
     */
    fun applyBurn(health: HealthData, hardpointDamage: Int, vitalityDamage: Int): HealthData {
        val target = HardpointName.values().let { it[Random.nextInt(it.size)] }
        val afterHardpoint = applyHardpointDamage(health, target, hardpointDamage)
        return applyVitalityDamage(afterHardpoint, vitalityDamage)
    }

    /**
     * Add a status effect to the character's list.
     */
    fun addStatus(existing: List<StatusEffect>, effect: StatusEffect): List<StatusEffect> {
        return existing + effect
    }

    /**
     * Remove expired status effects.
     */
    fun pruneExpired(effects: List<StatusEffect>, now: Long): List<StatusEffect> {
        return effects.filter { (now - it.appliedAt) < it.duration }
    }

    /**
     * The death save embroidery loop.
     *
     * Currently: base threshold is 50, adjusted by how deep into
     * negative vitality the character is. Luck provides a small bonus.
     * Roll d100 against the threshold — meet or beat to survive.
     *
     * Stitch in: armor, blessings, racial traits, nearby allies, etc.
     */
    fun rollDeathSave(character: EvenniaCharacter): DeathSaveResult {
        val vitality = character.health.vitality
        val luck = character.health.luck

        // Base threshold 50, gets harder as vitality goes more negative
        val depthPenalty = (-vitality / 5).coerceAtMost(40)
        val luckBonus = (luck - 50) / 10  // luck 100 = +5, luck 0 = -5
        val threshold = (50 + depthPenalty - luckBonus).coerceIn(5, 95)

        val roll = Random.nextInt(100)
        val passed = roll >= threshold

        val modifiers = JsonObject()
            .put("depthPenalty", depthPenalty)
            .put("luckBonus", luckBonus)
            .put("vitality", vitality)

        return DeathSaveResult(
            passed = passed,
            roll = roll,
            threshold = threshold,
            modifiers = modifiers
        )
    }

    /**
     * Log that a character has died.
     *
     * Evennia is notified via the @State delta broadcast for `dead = true`,
     * which triggers the sync hook on the Evennia side. No agent command needed.
     */
    fun flagDead(character: EvenniaCharacter) {
        log.info("Character {} ({}) has died", character.evenniaName, character._id)
    }

    /**
     * Log that a character has been downed.
     *
     * Evennia is notified via the @State delta broadcast for `downed = true`.
     */
    fun flagDowned(character: EvenniaCharacter) {
        log.info("Character {} ({}) has been downed", character.evenniaName, character._id)
    }

    /**
     * Log that a character has recovered from downed.
     *
     * Evennia is notified via the @State delta broadcast for `downed = false`.
     */
    fun flagUndowned(character: EvenniaCharacter) {
        log.info("Character {} ({}) has recovered from downed", character.evenniaName, character._id)
    }

    /**
     * Handle an apply_damage message from Evennia.
     * Evennia has already resolved room occupancy — we just apply the damage.
     */
    suspend fun applyDamageFromEvennia(message: JsonObject) {
        val characterId = message.getString("character_id") ?: return
        val sourceId = message.getString("source_id", "")
        val hardpointDamage = message.getInteger("hardpoint_damage", 0)
        val vitalityDamage = message.getInteger("vitality_damage", 0)
        val burnDuration = message.getLong("burn_duration", 0L)
        val burnTickDamage = message.getInteger("burn_tick_damage", 0)

        val entities = entityController.findByIds(listOf(characterId))
        val character = entities[characterId] as? EvenniaCharacter
        if (character == null) {
            log.warn("applyDamageFromEvennia: character not found: {}", characterId)
            return
        }
        if (character.dead) return

        // Apply immediate burn damage
        val updatedHealth = applyBurn(character.health, hardpointDamage, vitalityDamage)
        entityController.saveState(characterId, JsonObject()
            .put("health", healthToJson(updatedHealth)))

        // Apply burn status effect
        if (burnDuration > 0 && burnTickDamage > 0) {
            val burnEffect = StatusEffect(
                type = "burn",
                damage = burnTickDamage,
                duration = burnDuration,
                appliedAt = System.currentTimeMillis(),
                sourceId = sourceId
            )
            val updatedEffects = addStatus(character.statusEffects, burnEffect)
            entityController.saveProperties(characterId, JsonObject()
                .put("statusEffects", updatedEffects))
        }

        if (updatedHealth.vitality <= 0) {
            if (character.downed) {
                // Already downed, taking more damage — death save
                val result = rollDeathSave(character)
                if (!result.passed) {
                    character.dead = true
                    flagDead(character)
                    entityController.saveState(characterId, JsonObject()
                        .put("dead", true))
                    entityController.saveProperties(characterId, JsonObject()
                        .put("statusEffects", listOf<StatusEffect>()))
                }
            } else {
                // First time at 0 — downed, not dead
                character.downed = true
                flagDowned(character)
                entityController.saveState(characterId, JsonObject()
                    .put("downed", true))
            }
        }

        log.info("Applied damage to '{}' (id={}): hp={}, vit={}, vitality now={}",
            character.evenniaName, characterId, hardpointDamage, vitalityDamage, updatedHealth.vitality)
    }

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

    private fun statusForHp(hp: Int): HardpointStatus = when {
        hp >= 90 -> HardpointStatus.HEALTHY
        hp >= 75 -> HardpointStatus.SCRATCHED
        hp >= 60 -> HardpointStatus.BRUISED
        hp >= 40 -> HardpointStatus.WOUNDED
        hp >= 25 -> HardpointStatus.INJURED
        hp >= 10 -> HardpointStatus.BROKEN
        hp > 0   -> HardpointStatus.CRITICAL
        else     -> HardpointStatus.DESTROYED
    }
}
