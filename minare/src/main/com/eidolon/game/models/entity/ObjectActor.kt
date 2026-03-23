package com.eidolon.game.models.entity

import com.eidolon.game.evennia.EvenniaCommUtils
import com.google.inject.Inject
import com.minare.controller.EntityController
import com.minare.core.entity.annotations.*
import com.minare.core.entity.models.Entity
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import kotlin.random.Random

/**
 * An object in the world with sim-driven behavior.
 * The actorType field selects which @FixedTask behaviors are active.
 */
@EntityType("ObjectActor")
class ObjectActor : Entity() {
    @Inject
    private lateinit var entityController: EntityController
    @Inject
    private lateinit var evenniaCommUtils: EvenniaCommUtils

    private val log = LoggerFactory.getLogger(ObjectActor::class.java)

    init {
        type = "ObjectActor"
    }

    @State
    @Mutable
    var evenniaId: String = ""

    @State
    @Mutable
    var roomId: String = ""

    @State
    @Mutable
    var roomEvenniaId: String = ""

    /** Selects active behavior: "exploding_hazard", etc. */
    @State
    @Mutable
    var actorType: String = ""

    // --- Exploding hazard fields ---

    @State
    @Mutable
    var explosionMessages: List<String> = emptyList()

    @State
    @Mutable
    var hardpointDamage: Int = 0

    @State
    @Mutable
    var vitalityDamage: Int = 0

    @State
    @Mutable
    var burnDuration: Long = 0L

    @State
    @Mutable
    var burnTickDamage: Int = 0

    @State
    @Mutable
    var intervalMin: Long = 8_000L

    @State
    @Mutable
    var intervalMax: Long = 20_000L

    /** Object HP. -1 means not damageable. 0 means destroyed. */
    @State
    @Mutable
    var hp: Int = -1

    @Property
    var lastAction: Long = 0L

    @Property
    var nextActionAt: Long = 0L

    // --- Behavior tasks ---

    @FixedTask
    suspend fun act() {
        try {
            when (actorType) {
                "exploding_hazard" -> actExplodingHazard()
            }
        } catch (e: Exception) {
            log.error("act failed for ObjectActor {} (type={}): {}", _id, actorType, e.message)
        }
    }

    private suspend fun actExplodingHazard() {
        if (roomEvenniaId.isEmpty()) return
        val now = System.currentTimeMillis()

        if (nextActionAt == 0L) {
            nextActionAt = now + randomInterval()
            entityController.saveProperties(_id, JsonObject()
                .put("nextActionAt", nextActionAt))
            return
        }

        if (now < nextActionAt) return

        // Send explosion message to the room
        val message = if (explosionMessages.isNotEmpty())
            explosionMessages[Random.nextInt(explosionMessages.size)]
        else "The barrel explodes!"

        evenniaCommUtils.sendAgentCommand(JsonObject()
            .put("action", "hazard_msg")
            .put("room_evennia_id", roomEvenniaId)
            .put("message", message))

        // Delegate damage to Evennia — it knows who's in the room
        evenniaCommUtils.sendAgentCommand(JsonObject()
            .put("action", "hazard_damage")
            .put("room_evennia_id", roomEvenniaId)
            .put("source_id", _id)
            .put("hardpoint_damage", hardpointDamage)
            .put("vitality_damage", vitalityDamage)
            .put("burn_duration", burnDuration)
            .put("burn_tick_damage", burnTickDamage))

        lastAction = now
        nextActionAt = now + randomInterval()
        entityController.saveProperties(_id, JsonObject()
            .put("lastAction", lastAction)
            .put("nextActionAt", nextActionAt))
    }

    private fun randomInterval(): Long {
        return intervalMin + Random.nextLong(intervalMax - intervalMin)
    }

}
