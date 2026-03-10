package com.eidolon.game.scenario

import eidolon.game.controller.GameChannelController
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.entity.factories.EntityFactory
import com.minare.core.entity.models.Entity
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.Vertx
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await

@Singleton
class AgentInitializer @Inject constructor(
    private val gameChannelController: GameChannelController,
    private val entityFactory: EntityFactory,
    private val entityController: EntityController,
    private val vertx: Vertx
) {
    private val log = LoggerFactory.getLogger(AgentInitializer::class.java)

    suspend fun initialize() {
        val entities = mutableListOf<Entity>()
        val defaultChannelId = gameChannelController.getDefaultChannel() ?: throw Exception("Default channel not configured- game state issue?")

        readAgentsData().forEach { jsonObject ->
            val character = entityFactory.createEntity(EvenniaCharacter::class.java) as EvenniaCharacter
            //character.setConnection(something)

            entityController.create(character) as EvenniaCharacter
            entities.add(character)
        }

        gameChannelController.addEntitiesToChannel(entities.toList(), defaultChannelId!!)
    }

    suspend fun readAgentsData(): List<JsonObject> {
        return try {
            val buffer = vertx.fileSystem().readFile("scenario/agents.json").await()

            JsonArray(buffer.toString()).map { it as JsonObject }
        } catch (e: Exception) {
            log.error("Failed to read agents.json: $e")
            emptyList()
        }
    }
}