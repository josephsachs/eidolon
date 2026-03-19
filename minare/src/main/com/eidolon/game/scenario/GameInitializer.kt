package com.eidolon.game.scenario

import eidolon.game.action.GameTurnHandler.Companion.TurnPhase
import eidolon.game.controller.GameChannelController
import eidolon.game.models.entity.Game
import com.eidolon.game.GameEntityFactory
import com.eidolon.game.evennia.EntityViewRegistry
import com.eidolon.game.models.entity.Room
import com.eidolon.game.models.entity.agent.EvenniaCharacter
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.utils.vertx.VerticleLogger
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject

@Singleton
class GameInitializer @Inject constructor(
    private val mapInitializer: RoomInitializer,
    private val entityController: EntityController,
    private val entityFactory: GameEntityFactory,
    private val channelController: GameChannelController,
    private val viewRegistry: EntityViewRegistry,
    private val vertx: Vertx,
    private val verticleLogger: VerticleLogger
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

        // Create the default channel before initializing rooms
        val defaultChannelId = channelController.createChannel()
        channelController.setDefaultChannel(defaultChannelId)
        verticleLogger.logInfo("Created default channel: $defaultChannelId")

        var startupOptions = JsonObject()
            .put("turnPhase", TurnPhase.ACT)
            .put("turnProcessing", false)

        entityController.saveProperties(gameEntity._id!!,startupOptions)
        verticleLogger.logInfo("Chieftain: Initializing entities")
        verticleLogger.logInfo("Initial settings: $startupOptions")

        registerEntityViews()

        mapInitializer.initialize()

        vertx.eventBus().publish(ADDRESS_INITIALIZE_GAME_COMPLETE, JsonObject())

        verticleLogger.logInfo("Chieftain: Game initialized")
    }

    private fun registerEntityViews() {
        viewRegistry.register("Room", "default") { entity ->
            val room = entity as Room
            JsonObject()
                .put("description", room.description)
                .put("shortDescription", room.shortDescription)
        }

        viewRegistry.register("EvenniaCharacter", "default") { entity ->
            val char = entity as EvenniaCharacter
            JsonObject()
                .put("evenniaName", char.evenniaName)
                .put("currentRoomId", char.currentRoomId)
        }

        verticleLogger.logInfo("Registered entity views")
    }

    companion object {
        const val ADDRESS_INITIALIZE_GAME_COMPLETE = "eidolon.initialize.game.complete"

        const val GAME_TITLE = "Eidolon; Minare 0.4.0"
    }
}