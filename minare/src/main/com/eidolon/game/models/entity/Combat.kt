package com.eidolon.game.models.entity

import com.google.inject.Inject
import com.minare.controller.EntityController
import com.minare.core.entity.annotations.*
import com.minare.core.entity.models.Entity
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

@EntityType("Combat")
class Combat : Entity() {
    @Inject
    private lateinit var entityController: EntityController

    private val log = LoggerFactory.getLogger(Combat::class.java)

    init {
        type = "Combat"
    }

    @State
    @Mutable
    var members: List<String> = emptyList()

    @State
    @Mutable
    var roomId: String = ""

    @Property
    var createdAt: Long = 0L

    @Property
    var lastActivity: Long = 0L

    @Property
    var emptyAt: Long = 0L

    @Property
    var currentRound: Int = 0

    @Property
    var combatLog: List<JsonObject> = emptyList()

    companion object {
        const val EXPIRY_DELAY_MS = 5_000L
    }

    @FixedTask
    suspend fun checkExpiry() {
        if (members.isNotEmpty()) {
            if (emptyAt != 0L) {
                emptyAt = 0L
                entityController.saveProperties(_id!!, JsonObject().put("emptyAt", 0L))
            }
            return
        }

        val now = System.currentTimeMillis()

        if (emptyAt == 0L) {
            emptyAt = now
            entityController.saveProperties(_id!!, JsonObject().put("emptyAt", now))
            return
        }

        if (now - emptyAt >= EXPIRY_DELAY_MS) {
            log.info("Combat $_id expired (empty for ${EXPIRY_DELAY_MS}ms), deleting")
            entityController.delete(_id!!)
        }
    }
}
