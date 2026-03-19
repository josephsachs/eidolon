package com.eidolon.game.controller

import com.eidolon.game.models.entity.Room
import com.minare.controller.OperationController
import com.minare.core.entity.factories.EntityFactory
import com.minare.core.operation.interfaces.MessageQueue
import com.minare.core.operation.models.Assert
import com.minare.core.operation.models.FailurePolicy
import com.minare.core.operation.models.Operation
import com.minare.core.operation.models.OperationSet
import com.minare.core.operation.models.OperationType
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-specific implementation of OperationController.
 * Handles conversion of client commands to Operations for Kafka.
 */
@Singleton
class GameOperationController @Inject constructor(
    private val entityFactory: EntityFactory,
)
    : OperationController() {

    private val log = LoggerFactory.getLogger(GameOperationController::class.java)

    /**
     * Convert incoming client commands to Operations before queueing to Kafka.
     *
     * @param message The raw message from the client
     * @return Operation, OperationSet, or null to skip processing
     */
    override suspend fun preQueue(message: JsonObject): Any? {
        val command = message.getString("command")
        val connectionId = message.getString("connectionId")

        val messageType = message.getString("type")

        // Handle player commands routed through the operation pipeline
        if (messageType in listOf("command_get", "command_drop", "command_give")) {
            return buildItemOperationSet(message, messageType!!)
        }

        return when (command) {
            "create" -> {
                val entityId = message.getString("_id")
                val entityObject = message.getJsonObject("entity")

                if (entityObject == null) {
                    log.warn("Invalid create command: missing entity object")
                    return null
                }

                if (entityObject.getString("type") == null) {
                    log.warn("Invalid create command: missing entity type")
                    return null
                }

                val entityType = entityFactory.getNew(entityObject.getString("type"))

                val operation = Operation()
                    .entityType(entityType::class.java)
                    .action(OperationType.CREATE)
                    .delta(entityObject.getJsonObject("state") ?: JsonObject())
                    .meta(JsonObject().put("connectionId", connectionId).encode())

                log.debug("Created CREATE operation for new {} from connection {}", entityType, connectionId)
                operation
            }

            "mutate" -> {
                val entityId = message.getString("_id")
                val entityObject = message.getJsonObject("entity")

                if (entityObject == null) {
                    log.warn("Invalid mutate command: missing entity object")
                    return null
                }

                if (entityId == null) {
                    log.warn("Invalid mutate command: missing entity ID")
                    return null
                }

                val operation = Operation()
                    .entity(entityId)
                    .action(OperationType.MUTATE)
                    .delta(entityObject.getJsonObject("state") ?: JsonObject())

                entityObject.getLong("version")?.let {
                    operation.version(it)
                }

                operation.value("connectionId", connectionId)
                operation.value("entityType", entityObject.getString("type"))

                log.debug("Created MUTATE operation for entity {} from connection {}", entityId, connectionId)
            }

            "delete" -> {
                val entityId = message.getString("_id")
                val entityObject = message.getJsonObject("entity")

                if (entityObject == null) {
                    log.warn("Invalid create command: missing entity object")
                    return null
                }

                if (entityObject.getString("type") == null) {
                    log.warn("Invalid create command: missing entity type")
                    return null
                }

                val entityType = entityFactory.getNew(entityObject.getString("type"))

                val operation = Operation()
                    .entityType(entityType::class.java)
                    .action(OperationType.DELETE)
                    .delta(JsonObject())  // Empty delta for delete
                    .meta(JsonObject().put("connectionId", connectionId).encode())

                log.debug("Created DELETE operation for entity {} from connection {}", entityId, connectionId)
                operation
            }

            else -> {
                log.warn("Unknown command received: {} from connection: {}", command, connectionId)
                null
            }
        }
    }

    /**
     * Build an OperationSet for item commands (get/drop/give).
     *
     * Pattern:
     * 1. Assert on EvenniaCharacter — validate the action is allowed
     * 2. Mutate EvenniaCharacter.inventory — add/remove item
     * 3. Mutate Room.contents — remove/add item
     *
     * Currently a stub: Assert always passes, mutations are placeholders
     * until Item entities exist.
     */
    private fun buildItemOperationSet(message: JsonObject, type: String): OperationSet {
        val characterId = message.getString("character_id", "")
        val roomId = message.getString("room_id", "")
        val target = message.getString("target", "")

        log.info("Building OperationSet for {}: character={} room={} target='{}'",
            type, characterId, roomId, target)

        val set = OperationSet().failurePolicy(FailurePolicy.ABORT)

        // Step 1: Assert the action is valid
        set.add(
            Assert()
                .entity(characterId)
                .entityType(EvenniaCharacter::class)
                .function("canGet")
                .args(JsonObject()
                    .put("target", target)
                    .put("roomId", roomId)
                    .put("commandType", type))
        )

        // Step 2: Mutate character inventory (stub delta — no items yet)
        set.add(
            Operation()
                .entity(characterId)
                .entityType(EvenniaCharacter::class.java)
                .action(OperationType.MUTATE)
                .delta(JsonObject())  // Empty delta until items exist
        )

        // Step 3: Mutate room contents (stub delta — no items yet)
        if (roomId.isNotEmpty()) {
            set.add(
                Operation()
                    .entity(roomId)
                    .entityType(Room::class.java)
                    .action(OperationType.MUTATE)
                    .delta(JsonObject())  // Empty delta until items exist
            )
        }

        return set
    }
}