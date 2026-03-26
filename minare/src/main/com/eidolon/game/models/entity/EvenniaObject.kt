package com.eidolon.game.models.entity

import com.eidolon.game.evennia.Viewable
import com.minare.core.entity.annotations.EntityType
import com.minare.core.entity.annotations.Mutable
import com.minare.core.entity.annotations.State
import com.minare.core.entity.models.Entity
import io.vertx.core.json.JsonObject
import java.io.Serializable

/**
 * Generic Minare entity representing any Evennia object.
 * Stores Evennia-domain metadata (typeclass, key, location).
 * Domain entities (PlayerCharacter, Room, etc.) are separate —
 * this decouples Evennia representation from game logic.
 */
@EntityType("EvenniaObject")
class EvenniaObject : Entity(), Serializable, Viewable {
    init {
        type = "EvenniaObject"
    }

    @State
    @Mutable
    var evenniaId: String = ""

    @State
    @Mutable
    var typeclassPath: String = ""

    @State
    @Mutable
    var key: String = ""

    @State
    @Mutable
    var locationEvenniaId: String = ""

    @State
    @Mutable
    var domainEntityId: String = ""

    @State
    @Mutable
    var domainEntityType: String = ""

    @State
    @Mutable
    var description: String = ""

    @State
    @Mutable
    var shortDescription: String = ""

    override fun project(viewName: String): JsonObject? = when (viewName) {
        "default" -> JsonObject()
            .put("evenniaId", evenniaId)
            .put("key", key)
            .put("typeclassPath", typeclassPath)
            .put("description", description)
            .put("shortDescription", shortDescription)
        "sync" -> JsonObject()
            .put("evenniaId", evenniaId)
            .put("description", description)
            .put("shortDescription", shortDescription)
        else -> null
    }
}
