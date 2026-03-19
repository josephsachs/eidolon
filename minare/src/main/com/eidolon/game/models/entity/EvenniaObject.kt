package com.eidolon.game.models.entity

import com.minare.core.entity.annotations.EntityType
import com.minare.core.entity.annotations.Mutable
import com.minare.core.entity.annotations.State
import com.minare.core.entity.models.Entity
import java.io.Serializable

/**
 * Generic Minare entity representing any Evennia object.
 * Stores Evennia-domain metadata (typeclass, key, location).
 * Domain entities (PlayerCharacter, Room, etc.) are separate —
 * this decouples Evennia representation from game logic.
 */
@EntityType("EvenniaObject")
class EvenniaObject : Entity(), Serializable {
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
}
