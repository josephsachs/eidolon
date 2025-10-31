package com.chieftain.game

import chieftain.game.GameStateVerticle
import com.minare.core.MinareApplication
import com.chieftain.game.config.GameModule
import com.chieftain.game.controller.GameChannelController
import com.chieftain.game.scenario.GameInitializer
import chieftain.game.action.cache.SharedGameState
import io.vertx.core.DeploymentOptions
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpHeaders
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.kotlin.coroutines.await
import javax.inject.Inject

/**
 * Game application that demonstrates the framework capabilities.
 * Creates a complex node graph with parent/child relationships using JGraphT.
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
            log.info("CHIEFTAIN: Created default channel: $defaultChannelId")

            channelController.setDefaultChannel(defaultChannelId)

            try {
                getGameState()
                getGameInitializer().initialize()
            } finally {
                createVerticle(
                    GameStateVerticle::class.java,
                    DeploymentOptions()
                        .setInstances(1)
                        .setConfig(JsonObject().put("role", "coordinator"))
                )
            }

            log.info("CHIEFTAIN: Game application started with default channel: $defaultChannelId")
        } catch (e: Exception) {
            log.error("Failed to start Game application", e)
            throw e
        }
    }

    override suspend fun onWorkerStart() {
        // Initialize the singletons
        getGameState()
        getGameInitializer()
    }

    private fun getGameInitializer(): GameInitializer {
        // IMPORTANT: Gotta get these from tne injector, because GameState depends on the CP subsystem
        return injector.getInstance(GameInitializer::class.java)
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

        // Register specific routes FIRST
        router.get("/client").handler { ctx ->
            val resource = Thread.currentThread().contextClassLoader.getResourceAsStream("webroot/index.html")

            if (resource != null) {
                val content = resource.readAllBytes()
                ctx.response()
                    .putHeader(HttpHeaders.CONTENT_TYPE, "text/html")
                    .end(Buffer.buffer(content))
            } else {
                ctx.response()
                    .setStatusCode(404)
                    .end("Couldn't find index.html in resources")
            }
        }

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
        /**
         * Returns the Guice module for this application
         */
        @JvmStatic
        fun getModule() = GameModule()
    }
}