package com.eidolon.game.models.entity

import com.eidolon.game.evennia.Viewable
import com.google.inject.Inject
import com.minare.controller.EntityController
import com.minare.core.entity.annotations.*
import com.minare.core.entity.models.Entity
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.io.Serializable

@EntityType("Room")
class Room : Entity(), Serializable, Viewable {
    @Inject
    lateinit var entityController: EntityController

    init {
        type = "Room"
    }

    @State
    @Mutable
    var description: String = ""

    @State
    @Mutable
    var shortDescription: String = ""

    /**
     * Map of direction -> Exit entity _id, e.g. {"north": "exit-abc-123"}
     */
    @State
    @Mutable
    var exits: JsonObject = JsonObject()

    /**
     * Accumulated room echoes: say, pose, and other observable events.
     * Each entry: {character, characterName, message, type, timestamp}
     * Property rather than State — not synced to Evennia.
     */
    @Property
    var echoes: JsonArray = JsonArray()

    @State
    @Mutable
    var dayDesc: String = ""

    @State
    @Mutable
    var nightDesc: String = ""

    @State
    @Mutable
    var concealment: Double = 0.0

    companion object {
        const val ECHO_TTL_MS: Long = 300_000L
    }

    @FixedTask
    suspend fun forgetEchoes() {
        if (echoes.isEmpty) return
        val cutoff = System.currentTimeMillis() - ECHO_TTL_MS
        val filtered = JsonArray()
        for (i in 0 until echoes.size()) {
            val echo = echoes.getJsonObject(i)
            if (echo.getLong("timestamp", 0L) >= cutoff) {
                filtered.add(echo)
            }
        }
        if (filtered.size() < echoes.size()) {
            entityController.saveProperties(_id!!, JsonObject().put("echoes", filtered))
        }
    }

    override fun project(viewName: String): JsonObject? = when (viewName) {
        "default" -> JsonObject()
            .put("description", description)
            .put("shortDescription", shortDescription)
        "sync" -> JsonObject()
            .put("description", description)
            .put("concealment", concealment)
        else -> null
    }
}
