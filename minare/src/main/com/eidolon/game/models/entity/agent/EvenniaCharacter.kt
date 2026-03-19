package eidolon.game.models.entity.agent

import com.google.inject.Inject
import com.minare.controller.EntityController
import com.minare.core.entity.annotations.*
import com.minare.core.entity.models.Entity
import io.vertx.core.json.JsonArray
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
     * Item entity IDs held by this character.
     */
    @State
    @Mutable
    var inventory: JsonArray = JsonArray()

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

    /**
     * Assert: can this character pick up the target item?
     * Stub — always returns true until item entities exist.
     */
    @Assert
    fun canGet(stepContext: JsonObject?): Boolean {
        return true
    }
}