package eidolon.game.models.entity.agent

import com.google.inject.Inject
import com.minare.controller.EntityController
import com.minare.core.entity.annotations.*
import com.minare.core.entity.models.Entity
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
    var evenniaName: Int = 0

    @State
    @Mutable
    var description: Int = 0

    @State
    @Mutable
    var shortDescription: Int = 0

    @Property
    var connectionId: String = ""

    @Property
    var lastActivity: Long = 0L
}