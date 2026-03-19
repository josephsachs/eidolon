package com.eidolon.game.models.entity

import com.minare.core.entity.annotations.EntityType
import com.minare.core.entity.annotations.Mutable
import com.minare.core.entity.annotations.State
import com.minare.core.entity.models.Entity
import io.vertx.core.json.JsonArray
import java.io.Serializable

@EntityType("Account")
class Account : Entity(), Serializable {
    init {
        type = "Account"
    }

    @State
    @Mutable
    var evenniaAccountId: String = ""

    @State
    @Mutable
    var characterIds: JsonArray = JsonArray()
}
