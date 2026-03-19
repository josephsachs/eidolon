package com.eidolon.game.models.entity

import com.minare.core.entity.annotations.EntityType
import com.minare.core.entity.annotations.Mutable
import com.minare.core.entity.annotations.State
import com.minare.core.entity.models.Entity
import java.io.Serializable

@EntityType("Item")
class Item : Entity(), Serializable {
    init {
        type = "Item"
    }

    @State
    @Mutable
    var name: String = ""

    @State
    @Mutable
    var description: String = ""

    @State
    @Mutable
    var shortDescription: String = ""

    /**
     * Room entity _id where item is located. Empty if carried by a character.
     */
    @State
    @Mutable
    var locationRoomId: String = ""

    /**
     * Character entity _id holding this item. Empty if in a room.
     */
    @State
    @Mutable
    var heldByCharacterId: String = ""
}
