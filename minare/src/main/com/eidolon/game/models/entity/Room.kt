package com.eidolon.game.models.entity

import com.minare.core.entity.annotations.EntityType
import com.minare.core.entity.annotations.Mutable
import com.minare.core.entity.annotations.State
import com.minare.core.entity.models.Entity
import java.io.Serializable

@EntityType("Room")
class Room: Entity(), Serializable {
    init {
        type = "Room"
    }

    @State
    @Mutable
    var evenniaId: String = ""
}