package com.eidolon.game.scenario

import eidolon.game.action.GameTurnHandler.Companion.TurnPhase
import eidolon.game.models.entity.Game
import com.eidolon.game.GameEntityFactory
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.utils.vertx.VerticleLogger
import eidolon.game.action.cache.services.RoomDataCacheBuilder
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

@Singleton
class GameInitializer @Inject constructor(
    private val mapInitializer: RoomInitializer,
    private val agentInitializer: AgentInitializer,
    private val entityController: EntityController,
    private val entityFactory: GameEntityFactory,
    private val vertx: Vertx,
    private val verticleLogger: VerticleLogger,
    private val roomDataCacheBuilder: RoomDataCacheBuilder
) {
    suspend fun initialize() {
        verticleLogger.logInfo("Eidolon: Initializing game")

        var gameEntity = entityController.create(
            entityFactory.createEntity(Game::class.java)
        ) as Game

        entityController.saveState(gameEntity._id!!,
            JsonObject()
                .put("name", GAME_TITLE)
        )

        verticleLogger.logInfo("Initializing game with title ${GAME_TITLE}")

        var startupOptions = JsonObject()
            .put("turnPhase", TurnPhase.ACT)
            .put("turnProcessing", false)

        entityController.saveProperties(gameEntity._id!!,startupOptions)
        verticleLogger.logInfo("Chieftain: Initializing entities")
        verticleLogger.logInfo("Initial settings: $startupOptions")

        mapInitializer.initialize()
        roomDataCacheBuilder.rebuild()
        agentInitializer.initialize()

        vertx.eventBus().publish(ADDRESS_INITIALIZE_GAME_COMPLETE, JsonObject())

        verticleLogger.logInfo("Chieftain: Game initialized")
    }

    companion object {
        const val ADDRESS_INITIALIZE_GAME_COMPLETE = "eidolon.initialize.game.complete"

        const val GAME_TITLE = "Eidolon; Minare 0.4.0"
    }
}