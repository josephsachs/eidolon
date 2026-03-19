package eidolon.game.models.entity.agent

import com.google.inject.Inject
import com.minare.controller.EntityController
import com.minare.core.entity.annotations.*
import com.minare.core.entity.models.Entity
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CoroutineScope

@EntityType("EvenniaCharacter")
class EvenniaCharacter: Entity(), Agent {
    @Inject
    private lateinit var coroutineScope: CoroutineScope
    @Inject
    private lateinit var entityController: EntityController

    init {
        type = "EvenniaCharacter"
    }

    @State
    @Mutable
    var evenniaId: String = ""

    @State
    @Mutable
    var evenniaName: String = ""

    @State
    @Mutable
    var description: String = ""

    @State
    @Mutable
    var shortDescription: String = ""

    /**
     * The Room entity _id the character is currently in.
     */
    @State
    @Mutable
    var currentRoomId: String = ""

    @Property
    var connectionId: String = ""

    @Property
    var lastActivity: Long = 0L

}