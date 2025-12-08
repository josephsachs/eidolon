package com.eidolon.game

import eidolon.game.GameStateVerticle
import com.minare.core.MinareApplication
import com.eidolon.game.config.GameModule
import com.eidolon.game.controller.GameChannelController
import eidolon.game.action.cache.SharedGameState
import io.vertx.core.DeploymentOptions
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.kotlin.coroutines.await
import javax.inject.Inject

/**
 * Eidolon controls a thin implementation of Evennia over WebSockets
 */
class GameApplication : MinareApplication() {
    private val log = LoggerFactory.getLogger(GameApplication::class.java)

    @Inject
    lateinit var channelController: GameChannelController

    /**
     * Application-specific initialization logic that runs after the server starts.
     */
    override suspend fun onCoordinatorStart() {
        try {
            val defaultChannelId = channelController.createChannel()
            log.info("EIDOLON: Created default channel: $defaultChannelId")
            channelController.setDefaultChannel(defaultChannelId)

            val systemChannelId = channelController.createChannel()
            channelController.setSystemMessagesChannel(systemChannelId)

            getGameState()
            getGameInitializer().initialize()

            log.info("EIDOLON: Game application started with default channel: $defaultChannelId")
        } catch (e: Exception) {
            log.error("Failed to start Game application", e)
            throw e
        }
    }

    override suspend fun afterCoordinatorStart() {
        createVerticle(
            GameStateVerticle::class.java,
            DeploymentOptions()
                .setInstances(1)
                .setConfig(JsonObject().put("role", "coordinator"))
        )
    }

    override suspend fun onWorkerStart() {
        // Initialize the singletons
        getGameState()
        getGameInitializer()
    }

    private fun getGameInitializer(): com.eidolon.game.scenario.GameInitializer {
        // IMPORTANT: Gotta get these from tne injector, because GameState depends on the CP subsystem

        return injector.getInstance(com.eidolon.game.scenario.GameInitializer::class.java)
    }

    private fun getGameState(): SharedGameState {
        return injector.getInstance(SharedGameState::class.java)
    }

    override suspend fun setupApplicationRoutes() {
        val router = Router.router(vertx)
        router.route().handler(BodyHandler.create())

        val serverPort = 8080
        httpServer = vertx.createHttpServer()
            .requestHandler(router)
            .listen(serverPort)
            .await()

        log.info("Main application HTTP server started on port $serverPort")

        router.get("/debug").handler { ctx ->
            val classLoader = Thread.currentThread().contextClassLoader
            val resourceUrl = classLoader.getResource("webroot/index.html")
            val resourceStream = classLoader.getResourceAsStream("webroot/index.html")

            val response = StringBuilder()
            response.append("Resource URL: ${resourceUrl}\n")
            response.append("Resource Stream null? ${resourceStream == null}\n")

            if (resourceStream != null) {
                val content = String(resourceStream.readAllBytes())
                response.append("Content length: ${content.length}\n")
                response.append("First 100 chars: ${content.take(100)}\n")
            }

            ctx.response()
                .putHeader("content-type", "text/plain")
                .end(response.toString())
        }

        // Register catch-all static handler LAST
        val staticHandler = StaticHandler.create()
            .setCachingEnabled(false)
            .setDefaultContentEncoding("UTF-8")
            .setFilesReadOnly(true)

        router.route("/*").handler(staticHandler)
    }

    companion object {
        // Game
        const val EVENNIA_TELNET_PORT = 4000
        const val EVENNIA_SOCKET_PORT = 4001
        const val EVENNIA_WEBCLIENT_PORT = 4002

        // Portal
        const val EVENNIA_WEBSERVER_PORT = 4005
        const val EVENNIA_AMP_PORT = 4006

        /**
         * Returns the Guice module for this application
         */
        @JvmStatic
        fun getModule() = GameModule()
    }
}