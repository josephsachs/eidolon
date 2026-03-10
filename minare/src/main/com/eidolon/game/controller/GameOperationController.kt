package com.eidolon.game.controller

import com.minare.controller.OperationController
import com.minare.core.entity.factories.EntityFactory
import com.minare.core.operation.interfaces.MessageQueue
import com.minare.core.operation.models.Operation
import com.minare.core.operation.models.OperationType
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
}