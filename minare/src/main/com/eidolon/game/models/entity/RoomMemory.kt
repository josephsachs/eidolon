package com.eidolon.game.models.entity

import com.google.inject.Inject
import com.minare.controller.EntityController
import com.minare.core.entity.annotations.*
import com.minare.core.entity.models.Entity
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.Serializable
import kotlin.math.log

@EntityType("RoomMemory")
class RoomMemory : Entity(), Serializable {
    @Inject
    lateinit var entityController: EntityController

    private val log = LoggerFactory.getLogger(RoomMemory::class.java)

    init {
        type = "RoomMemory"
    }

    /**
     * The Room this memory belongs to.
     */
    @Child
    @State
    @Mutable
    var roomId: String = ""

    /**
     * Accumulated room echoes: say, pose, and other observable events.
     * Each entry: {character, characterName, message, type, timestamp}
     */
    @State
    @Mutable
    var echoes: JsonArray = JsonArray()

    @FixedTask
    suspend fun forgetEchoes() {
        log.info("forgetEchoes")
        val max = 20
        if (echoes.size() <= max) return
        val trimmed = JsonArray()
        for (i in (echoes.size() - max) until echoes.size()) {
            trimmed.add(echoes.getValue(i))
        }
        log.info(trimmed.toString())
        val changes = JsonObject().put("echoes", trimmed)
        log.info(changes.toString())
        entityController.saveState(_id!!, changes)
    }
}
