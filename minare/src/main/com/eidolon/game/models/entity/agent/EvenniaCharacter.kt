package eidolon.game.models.entity.agent

import com.google.inject.Inject
import com.minare.controller.EntityController
import com.minare.core.entity.annotations.*
import com.minare.core.entity.models.Entity
import eidolon.game.action.GameTaskHandler
import eidolon.game.action.cache.SharedGameState
import eidolon.game.models.data.PlayerControllable
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@EntityType("EvenniaCharacter")
class EvenniaCharacter: Entity(), Agent, PlayerControllable {
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

    @Property
    var connectionId: String = ""

    override fun getConnection(): String {
        return connectionId
    }

    override fun setConnection(id: String) {
        connectionId = id

        coroutineScope.launch {
            entityController.saveProperties(
                _id!!,
                JsonObject().put("connectionId", id)
            )
        }
    }
}