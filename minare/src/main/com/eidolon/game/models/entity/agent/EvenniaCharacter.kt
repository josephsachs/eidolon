package eidolon.game.models.entity.agent

import com.eidolon.game.models.Attributes
import com.eidolon.game.models.CombatEquilibrium
import com.eidolon.game.models.HardpointStatus
import com.eidolon.game.models.HealthData
import com.eidolon.game.models.Skill
import com.eidolon.game.models.StatusEffect
import com.eidolon.game.service.DamageService
import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.evennia.EvenniaShadow
import com.eidolon.game.evennia.Viewable
import com.google.inject.Inject
import com.minare.controller.EntityController
import com.minare.core.entity.annotations.*
import com.minare.core.entity.models.Entity
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory

@EntityType("EvenniaCharacter")
class EvenniaCharacter: Entity(), Agent, EvenniaShadow, Viewable {
    @Inject
    private lateinit var coroutineScope: CoroutineScope
    @Inject
    private lateinit var entityController: EntityController
    @Inject
    private lateinit var evenniaCommUtils: EvenniaCommUtils
    @Inject
    private lateinit var damageService: DamageService

    private val log = LoggerFactory.getLogger(EvenniaCharacter::class.java)

    init {
        type = "EvenniaCharacter"
    }

    @State
    @Mutable
    var evenniaId: String = ""

    @State
    @Mutable
    var evenniaName: String = ""

    @State
    @Mutable
    var description: String = ""

    @State
    @Mutable
    var shortDescription: String = ""

    @State
    @Mutable
    var isNpc: Boolean = false

    @State
    @Mutable
    var brainType: String = ""

    @Property
    var lastActed: Long = 0L

    @Property
    var lastThought: Long = 0L

    @State
    @Mutable
    var skills: List<Skill> = emptyList()

    @State
    @Mutable
    var health: HealthData = HealthData()

    @Property
    var statusEffects: List<StatusEffect> = emptyList()

    @State
    @Mutable
    var attributes: Attributes = Attributes()

    @State
    @Mutable
    var dead: Boolean = false

    @State
    @Mutable
    var downed: Boolean = false

    /**
     * The Room entity _id the character is currently in.
     */
    @State
    @Mutable
    var currentRoomId: String = ""

    @Property
    var connectionId: String = ""

    @Property
    var lastActivity: Long = 0L

    @Property
    var combatMode: String = ""

    @Property
    var targetId: String = ""

    @State
    @Mutable
    var combatId: String = ""

    @Property
    var stance: String = ""

    @Property
    var tactic: String = ""

    @Property
    var combatEquilibrium: CombatEquilibrium = CombatEquilibrium()

    /**
     * Equipment slots: slot name -> item template ID.
     * Slots: HEAD, NECK, TORSO, RIGHT_ARM, RIGHT_HAND, LEFT_ARM, LEFT_HAND, RIGHT_LEG, LEFT_LEG (armor)
     *        MAIN_HAND, OFF_HAND (held items/weapons)
     */
    @State
    @Mutable
    var equipment: Map<String, String> = emptyMap()

    // --- Regeneration ---

    suspend fun regenerate() {
        val start = System.currentTimeMillis()
        try {
            if (dead) return
            if (Random.nextInt(4) != 0) return

            val max = 100
            val regenRate = 1
            val staminaRate = regenRate + (attributes.toughness / 50.0).toInt()
            val concentrationRate = regenRate + (attributes.discipline / 50.0).toInt()

            var updated = health

            if (updated.vitality >= max
                && updated.stamina >= max
                && updated.concentration >= max
                && updated.hardpoints.all { it.hp >= max }) return

            // Vitality regens first, then hardpoint hp
            if (updated.vitality < max) {
                updated = updated.copy(vitality = (updated.vitality + regenRate).coerceAtMost(max))
            } else {
                // Hardpoint hp regens but status never improves — cap hp at current status ceiling
                val updatedHardpoints = updated.hardpoints.map { hp ->
                    val statusCeiling = statusHpCeiling(hp.status)
                    if (hp.hp < statusCeiling) hp.copy(hp = (hp.hp + regenRate).coerceAtMost(statusCeiling))
                    else hp
                }
                updated = updated.copy(hardpoints = updatedHardpoints)
            }

            if (updated.stamina < max) {
                updated = updated.copy(stamina = (updated.stamina + staminaRate).coerceAtMost(max))
            }
            if (updated.concentration < max) {
                updated = updated.copy(concentration = (updated.concentration + concentrationRate).coerceAtMost(max))
            }

            if (updated != health) {
                health = updated
                val recovering = downed && updated.vitality > 0

                val changes = JsonObject().put("health", healthToJson())
                if (recovering) {
                    downed = false
                    changes.put("downed", false)
                }
                entityController.saveState(_id, changes)

                if (recovering) {
                    damageService.flagUndowned(this)
                }
            }
        } catch (e: Exception) {
            log.error("regenerate failed for {} ({}): {}", evenniaName, _id, e.message)
        } finally {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed > 200) log.warn("SLOW regenerate for {} ({}): {}ms", evenniaName, _id, elapsed)
        }
    }

    /**
     * Max hp a hardpoint can regen to without crossing into a better status tier.
     * Matches the thresholds in DamageService.statusForHp().
     */
    private fun statusHpCeiling(status: HardpointStatus): Int = when (status) {
        HardpointStatus.HEALTHY   -> 100
        HardpointStatus.SCRATCHED -> 89
        HardpointStatus.BRUISED   -> 74
        HardpointStatus.WOUNDED   -> 59
        HardpointStatus.INJURED   -> 39
        HardpointStatus.BROKEN    -> 24
        HardpointStatus.CRITICAL  -> 9
        HardpointStatus.DESTROYED -> 0
    }

    // --- Status processing ---

    suspend fun processStatuses() {
        val start = System.currentTimeMillis()
        try {
            if (dead || statusEffects.isEmpty()) return

            val now = System.currentTimeMillis()
            var updatedHealth = health

            // Apply damage from active effects
            for (effect in statusEffects) {
                if (effect.damage > 0) {
                    updatedHealth = when (effect.type) {
                        "burn" -> damageService.applyBurn(updatedHealth, effect.damage, effect.damage / 2)
                        else -> damageService.applyVitalityDamage(updatedHealth, effect.damage)
                    }
                }
            }

            // Prune expired effects
            val remaining = damageService.pruneExpired(statusEffects, now)

            // Downed/death check
            if (updatedHealth.vitality <= 0) {
                if (downed) {
                    val result = damageService.rollDeathSave(this)
                    if (!result.passed) {
                        dead = true
                        health = updatedHealth
                        damageService.flagDead(this)
                        entityController.saveState(_id, JsonObject()
                            .put("dead", true)
                            .put("health", healthToJson()))
                        entityController.saveProperties(_id, JsonObject()
                            .put("statusEffects", listOf<StatusEffect>()))
                        return
                    }
                } else {
                    downed = true
                    health = updatedHealth
                    damageService.flagDowned(this)
                    entityController.saveState(_id!!, JsonObject()
                        .put("downed", true)
                        .put("health", healthToJson()))
                }
            }

            // Persist remaining changes in one pass
            val stateChanges = JsonObject()
            val propChanges = JsonObject()

            if (updatedHealth != health) {
                health = updatedHealth
                stateChanges.put("health", healthToJson())
            }
            if (remaining != statusEffects) {
                propChanges.put("statusEffects", remaining)
            }

            if (!stateChanges.isEmpty) entityController.saveState(_id, stateChanges)
            if (!propChanges.isEmpty) entityController.saveProperties(_id, propChanges)
        } catch (e: Exception) {
            log.error("processStatuses failed for {} ({}): {}", evenniaName, _id, e.message)
        } finally {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed > 200) log.warn("SLOW processStatuses for {} ({}): {}ms", evenniaName, _id, elapsed)
        }
    }

    // --- Viewable interface ---

    override fun project(viewName: String): JsonObject? = when (viewName) {
        "default" -> JsonObject()
            .put("evenniaName", evenniaName)
            .put("currentRoomId", currentRoomId)
            .put("skills", skillsToJson())
            .put("health", healthToJson())
            .put("attributes", attributesToJson())
            .put("equipment", equipmentToJson())
        "skills" -> skillsToJson()
        "health" -> healthToJson()
        "attributes" -> attributesToJson()
        "equipment" -> equipmentToJson()
        else -> null
    }

    private fun skillsToJson(): JsonObject {
        val obj = JsonObject()
        skills.forEach { skill ->
            obj.put(skill.name, JsonObject()
                .put("level", skill.level)
                .put("status", skill.status)
                .put("lastUsed", skill.lastUsed))
        }
        return obj
    }

    private fun healthToJson(): JsonObject {
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

    private fun attributesToJson(): JsonObject {
        return JsonObject()
            .put("strength", attributes.strength)
            .put("agility", attributes.agility)
            .put("toughness", attributes.toughness)
            .put("intellect", attributes.intellect)
            .put("imagination", attributes.imagination)
            .put("discipline", attributes.discipline)
            .put("charisma", attributes.charisma)
            .put("empathy", attributes.empathy)
            .put("wits", attributes.wits)
    }

    private fun equipmentToJson(): JsonObject {
        val obj = JsonObject()
        equipment.forEach { (slot, templateId) ->
            obj.put(slot, templateId)
        }
        return obj
    }

    // --- EvenniaShadow interface ---

    override fun shadowEvenniaId(): String = evenniaId

    override fun updateView(): JsonObject {
        return JsonObject()
            .put("evenniaName", evenniaName)
            .put("currentRoomId", currentRoomId)
    }

    // --- Agent interface ---

    override val agentMinareId: String
        get() = _id ?: ""

    override suspend fun say(roomMinareId: String, text: String) {
        if (isNpc) {
            evenniaCommUtils.npcSayInRoom(roomMinareId, _id, text)
        } else {
            evenniaCommUtils.sayInRoom(roomMinareId, _id, text)
        }
        appendEcho(roomMinareId, "say", text)
    }

    override suspend fun emote(roomMinareId: String, text: String) {
        if (isNpc) {
            evenniaCommUtils.npcEmoteInRoom(roomMinareId, _id, text)
        } else {
            evenniaCommUtils.emoteInRoom(roomMinareId, _id, text)
        }
        appendEcho(roomMinareId, "pose", text)
    }

    private suspend fun appendEcho(roomMinareId: String, type: String, text: String) {
        val rooms = entityController.findByIds(listOf(roomMinareId))
        val room = rooms[roomMinareId] as? com.eidolon.game.models.entity.Room ?: return
        val echo = JsonObject()
            .put("character", _id)
            .put("characterName", evenniaName)
            .put("message", text)
            .put("type", type)
            .put("timestamp", System.currentTimeMillis())
        val updatedEchoes = room.echoes.copy().add(echo)
        entityController.saveProperties(roomMinareId, JsonObject().put("echoes", updatedEchoes))
    }
}
