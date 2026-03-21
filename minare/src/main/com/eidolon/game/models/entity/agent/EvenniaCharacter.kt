package eidolon.game.models.entity.agent

import com.eidolon.game.models.Attributes
import com.eidolon.game.models.CombatEquilibrium
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
import kotlinx.coroutines.CoroutineScope

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

    @Property
    var combatId: String = ""

    @Property
    var stance: String = ""

    @Property
    var tactic: String = ""

    @Property
    var combatEquilibrium: CombatEquilibrium = CombatEquilibrium()

    // --- Status processing ---

    @FixedTask
    suspend fun processStatuses() {
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

        // Death save check
        if (updatedHealth.vitality <= 0) {
            val result = damageService.rollDeathSave(this)
            if (!result.passed) {
                dead = true
                health = updatedHealth
                damageService.flagDead(this)
                entityController.saveState(_id!!, JsonObject()
                    .put("dead", true)
                    .put("health", healthToJson()))
                entityController.saveProperties(_id!!, JsonObject()
                    .put("statusEffects", listOf<StatusEffect>()))
                return
            }
        }

        // Persist changes
        val healthChanged = updatedHealth != health
        val effectsChanged = remaining != statusEffects

        if (healthChanged) {
            health = updatedHealth
            entityController.saveState(_id!!, JsonObject().put("health", healthToJson()))
        }
        if (effectsChanged) {
            entityController.saveProperties(_id!!, JsonObject()
                .put("statusEffects", remaining))
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
        "skills" -> skillsToJson()
        "health" -> healthToJson()
        "attributes" -> attributesToJson()
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
            evenniaCommUtils.npcSayInRoom(roomMinareId, _id!!, text)
        } else {
            evenniaCommUtils.sayInRoom(roomMinareId, _id!!, text)
        }
        appendEcho(roomMinareId, "say", text)
    }

    override suspend fun emote(roomMinareId: String, text: String) {
        if (isNpc) {
            evenniaCommUtils.npcEmoteInRoom(roomMinareId, _id!!, text)
        } else {
            evenniaCommUtils.emoteInRoom(roomMinareId, _id!!, text)
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
