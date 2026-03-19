package com.eidolon.game.models.entity

import com.minare.core.entity.annotations.Child
import com.minare.core.entity.annotations.EntityType
import com.minare.core.entity.annotations.Mutable
import com.minare.core.entity.annotations.State
import com.minare.core.entity.models.Entity
import io.vertx.core.json.JsonArray
import java.io.Serializable

@EntityType("RoomMemory")
class RoomMemory : Entity(), Serializable {
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
}
