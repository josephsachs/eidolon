package com.eidolon.game.config

import com.google.inject.Singleton
import com.google.inject.name.Names
import com.minare.controller.ChannelController
import com.minare.controller.ConnectionController
import com.minare.controller.MessageController
import com.minare.controller.OperationController
import com.minare.core.entity.factories.EntityFactory
import com.eidolon.game.GameEntityFactory
import eidolon.game.controller.GameChannelController
import com.eidolon.game.controller.GameConnectionController
import com.eidolon.game.controller.GameMessageController
import com.eidolon.game.controller.GameOperationController
import com.google.inject.AbstractModule
import org.slf4j.LoggerFactory

/**
 * Application-specific Guice module for the Game app.
 * This provides bindings specific to our Game application.
 *
 * When combined with the framework through a child injector,
 * bindings defined here will override the framework's default bindings.
 */
class GameModule : AbstractModule() {
    private val log = LoggerFactory.getLogger(GameModule::class.java)

    override fun configure() {
        bind(EntityFactory::class.java)
            .annotatedWith(Names.named("user"))
            .to(GameEntityFactory::class.java)

        bind(ChannelController::class.java)
            .to(GameChannelController::class.java)
            .`in`(Singleton::class.java)

        bind(ConnectionController::class.java)
            .to(GameConnectionController::class.java)
            .`in`(Singleton::class.java)

        bind(OperationController::class.java)
            .to(GameOperationController::class.java)
            .`in`(Singleton::class.java)

        bind(MessageController::class.java)
            .to(GameMessageController::class.java)
            .`in`(Singleton::class.java)

        log.info("GameModule configured with custom EntityFactory and controllers")
    }
}