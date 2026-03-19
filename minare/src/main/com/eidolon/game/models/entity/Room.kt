package com.eidolon.game.models.entity

import com.eidolon.game.evennia.Viewable
import com.minare.core.entity.annotations.EntityType
import com.minare.core.entity.annotations.Mutable
import com.minare.core.entity.annotations.Parent
import com.minare.core.entity.annotations.State
import com.minare.core.entity.models.Entity
import io.vertx.core.json.JsonObject
import java.io.Serializable

@EntityType("Room")
class Room : Entity(), Serializable, Viewable {
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
     * RoomMemory entity _id — child that stores echoes and room history.
     */
    @Parent
    @State
    @Mutable
    var roomMemoryId: String = ""

    override fun project(viewName: String): JsonObject? = when (viewName) {
        "default" -> JsonObject()
            .put("description", description)
            .put("shortDescription", shortDescription)
        else -> null
    }
}
