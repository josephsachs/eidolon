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
import com.eidolon.clients.ModelAPI
import com.google.inject.AbstractModule
import com.google.inject.Provides
import com.minare.controller.EntityController
import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.service.CombatService
import com.eidolon.game.service.ItemRegistry
import eidolon.game.models.entity.agent.BrainRegistry
import eidolon.game.models.entity.agent.FeralBrain
import eidolon.game.models.entity.agent.IdleBrain
import eidolon.game.models.entity.agent.KibitzBrain
import eidolon.game.models.entity.agent.VendorBrain
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

        bind(ModelAPI::class.java)
            .`in`(Singleton::class.java)

        log.info("GameModule configured with custom EntityFactory and controllers")
    }

    @Provides
    @Singleton
    fun provideItemRegistry(): ItemRegistry {
        val registry = ItemRegistry()
        registry.load()
        return registry
    }

    @Provides
    @Singleton
    fun provideBrainRegistry(
        entityController: EntityController,
        modelAPI: ModelAPI,
        combatService: CombatService,
        itemRegistry: ItemRegistry,
        evenniaCommUtils: EvenniaCommUtils,
        crossLinkRegistry: CrossLinkRegistry
    ): BrainRegistry {
        val registry = BrainRegistry()
        registry.register(IdleBrain())
        registry.register(KibitzBrain(entityController, modelAPI))
        registry.register(FeralBrain(entityController, combatService, evenniaCommUtils, crossLinkRegistry))
        registry.register(VendorBrain(entityController, itemRegistry))
        return registry
    }
}