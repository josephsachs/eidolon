package com.eidolon.game.scenario

import eidolon.game.action.GameTurnHandler.Companion.TurnPhase
import eidolon.game.action.cache.services.MapDataCacheBuilder
import eidolon.game.models.entity.Game
import com.eidolon.game.GameEntityFactory
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.utils.vertx.VerticleLogger
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

@Singleton
class GameInitializer @Inject constructor(
    private val mapInitializer: com.eidolon.game.scenario.MapInitializer,
    private val agentInitializer: com.eidolon.game.scenario.AgentInitializer,
    private val entityController: EntityController,
    private val entityFactory: GameEntityFactory,
    private val vertx: Vertx,
    private val verticleLogger: VerticleLogger,
    private val mapDataCacheBuilder: MapDataCacheBuilder
) {
    suspend fun initialize() {
        verticleLogger.logInfo("Chieftain: Initializing game")

        var gameEntity = entityController.create(
            entityFactory.createEntity(Game::class.java)
        ) as Game

        entityController.saveState(gameEntity._id!!,
            JsonObject()
                .put("name", com.eidolon.game.scenario.GameInitializer.Companion.GAME_TITLE)
        )

        verticleLogger.logInfo("Initializing game with title ${com.eidolon.game.scenario.GameInitializer.Companion.GAME_TITLE}")

        var startupOptions = JsonObject()
            .put("turnPhase", TurnPhase.ACT)
            .put("turnProcessing", false)

        entityController.saveProperties(gameEntity._id!!,startupOptions)
        verticleLogger.logInfo("Chieftain: Initializing entities")
        verticleLogger.logInfo("Initial settings: $startupOptions")

        mapInitializer.initialize()
        mapDataCacheBuilder.rebuild()
        agentInitializer.initialize()

        vertx.eventBus().publish(com.eidolon.game.scenario.GameInitializer.Companion.ADDRESS_INITIALIZE_GAME_COMPLETE, JsonObject())

        verticleLogger.logInfo("Chieftain: Game initialized")
    }

    companion object {
        const val ADDRESS_INITIALIZE_GAME_COMPLETE = "eidolon.initialize.game.complete"

        const val GAME_TITLE = "Chieftain 1.0"
    }
}