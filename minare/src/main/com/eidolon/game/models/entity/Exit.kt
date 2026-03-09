package com.eidolon.game.models.entity

import com.minare.core.entity.annotations.EntityType
import com.minare.core.entity.annotations.Mutable
import com.minare.core.entity.annotations.State
import com.minare.core.entity.models.Entity
import java.io.Serializable

@EntityType("Exit")
class Exit : Entity(), Serializable {
    init {
        type = "Exit"
    }

    /**
     * Minare Room entity _id of the destination room.
     */
    @State
    @Mutable
    var destination: String = ""
}
