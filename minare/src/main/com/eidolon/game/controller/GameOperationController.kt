package com.eidolon.game.controller

import com.eidolon.game.models.entity.Item
import com.eidolon.game.models.entity.Room
import com.minare.controller.EntityController
import com.minare.controller.OperationController
import com.minare.core.entity.factories.EntityFactory
import com.minare.core.entity.models.Entity
import com.minare.core.operation.models.Assert
import com.minare.core.operation.models.FailurePolicy
import com.minare.core.operation.models.Operation
import com.minare.core.operation.models.OperationSet
import com.minare.core.operation.models.OperationType
import eidolon.game.controller.GameChannelController
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.json.JsonArray
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
    private val channelController: GameChannelController,
    private val entityController: EntityController,
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

        if (messageType == "command_create_item") {
            return buildCreateItemOperation(message)
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
     * After a CREATE operation completes, add the new entity to the default channel
     * so it broadcasts to subscribed clients (including Evennia).
     */
    override suspend fun afterCreateOperation(operation: JsonObject, entity: Entity) {
        val defaultChannelId = channelController.getDefaultChannel()
        if (defaultChannelId != null) {
            channelController.addEntity(entity, defaultChannelId)
            log.info("Added entity {} ({}) to default channel {}", entity._id, entity.type, defaultChannelId)
        } else {
            log.warn("Cannot add entity {} to channel: no default channel set", entity._id)
        }

        // For Items, add to the room's contents array
        if (entity is Item) {
            val meta = try { JsonObject(operation.getString("meta", "{}")) } catch (e: Exception) { JsonObject() }
            val roomId = meta.getString("roomId", "")
            if (roomId.isNotEmpty()) {
                val rooms = entityController.findByIds(listOf(roomId))
                val room = rooms[roomId] as? Room
                if (room != null) {
                    val newContents = JsonArray(room.contents.list.toMutableList())
                    if (!newContents.contains(entity._id)) {
                        newContents.add(entity._id)
                    }
                    entityController.saveState(roomId, JsonObject().put("contents", newContents))
                    log.info("Added item {} to room {} contents", entity._id, roomId)
                }
            }
        }
    }

    /**
     * Build a CREATE operation for a new Item entity.
     */
    private fun buildCreateItemOperation(message: JsonObject): Operation {
        val name = message.getString("name", "")
        val description = message.getString("description", "")
        val roomId = message.getString("room_id", "")

        log.info("Building CREATE operation for Item: name='{}' room={}", name, roomId)

        val entityType = entityFactory.getNew("Item")

        return Operation()
            .entityType(entityType::class.java)
            .action(OperationType.CREATE)
            .delta(JsonObject()
                .put("name", name)
                .put("description", description)
                .put("shortDescription", name))
            .meta(JsonObject().put("roomId", roomId).encode())
    }

    /**
     * Build an OperationSet for item commands (get/drop/give).
     *
     * Reads current entity state to compute inventory/contents deltas.
     * For get: remove item from room.contents, add to character.inventory
     * For drop: remove item from character.inventory, add to room.contents
     * For give: remove item from giver.inventory, add to recipient.inventory
     */
    private suspend fun buildItemOperationSet(message: JsonObject, type: String): OperationSet? {
        val characterId = message.getString("character_id", "")
        val roomId = message.getString("room_id", "")
        val itemId = message.getString("item_id", "")

        log.info("Building OperationSet for {}: character={} room={} item={}",
            type, characterId, roomId, itemId)

        if (itemId.isEmpty()) {
            log.warn("No item_id provided for {}", type)
            return null
        }

        val set = OperationSet().failurePolicy(FailurePolicy.ABORT)

        // Step 1: Assert the action is valid
        set.add(
            Assert()
                .entity(characterId)
                .entityType(EvenniaCharacter::class)
                .function("canGet")
        )

        when (type) {
            "command_get" -> {
                // Read current state
                val entities = entityController.findByIds(listOf(characterId, roomId))
                val character = entities[characterId] as? EvenniaCharacter
                val room = entities[roomId] as? Room

                if (character == null || room == null) {
                    log.warn("Cannot resolve character {} or room {} for get", characterId, roomId)
                    return null
                }

                // Build new inventory: add item
                val newInventory = JsonArray(character.inventory.list.toMutableList())
                if (!newInventory.contains(itemId)) {
                    newInventory.add(itemId)
                }

                // Build new contents: remove item
                val newContents = JsonArray(room.contents.list.toMutableList())
                newContents.remove(itemId)

                set.add(
                    Operation()
                        .entity(characterId)
                        .entityType(EvenniaCharacter::class.java)
                        .action(OperationType.MUTATE)
                        .delta(JsonObject().put("inventory", newInventory))
                )

                if (roomId.isNotEmpty()) {
                    set.add(
                        Operation()
                            .entity(roomId)
                            .entityType(Room::class.java)
                            .action(OperationType.MUTATE)
                            .delta(JsonObject().put("contents", newContents))
                    )
                }
            }

            "command_drop" -> {
                val entities = entityController.findByIds(listOf(characterId, roomId))
                val character = entities[characterId] as? EvenniaCharacter
                val room = entities[roomId] as? Room

                if (character == null || room == null) {
                    log.warn("Cannot resolve character {} or room {} for drop", characterId, roomId)
                    return null
                }

                // Build new inventory: remove item
                val newInventory = JsonArray(character.inventory.list.toMutableList())
                newInventory.remove(itemId)

                // Build new contents: add item
                val newContents = JsonArray(room.contents.list.toMutableList())
                if (!newContents.contains(itemId)) {
                    newContents.add(itemId)
                }

                set.add(
                    Operation()
                        .entity(characterId)
                        .entityType(EvenniaCharacter::class.java)
                        .action(OperationType.MUTATE)
                        .delta(JsonObject().put("inventory", newInventory))
                )

                if (roomId.isNotEmpty()) {
                    set.add(
                        Operation()
                            .entity(roomId)
                            .entityType(Room::class.java)
                            .action(OperationType.MUTATE)
                            .delta(JsonObject().put("contents", newContents))
                    )
                }
            }

            "command_give" -> {
                val recipientId = message.getString("recipient_id", "")
                if (recipientId.isEmpty()) {
                    log.warn("No recipient_id provided for give")
                    return null
                }

                val entities = entityController.findByIds(listOf(characterId, recipientId))
                val giver = entities[characterId] as? EvenniaCharacter
                val recipient = entities[recipientId] as? EvenniaCharacter

                if (giver == null || recipient == null) {
                    log.warn("Cannot resolve giver {} or recipient {} for give", characterId, recipientId)
                    return null
                }

                // Remove from giver
                val giverInventory = JsonArray(giver.inventory.list.toMutableList())
                giverInventory.remove(itemId)

                // Add to recipient
                val recipientInventory = JsonArray(recipient.inventory.list.toMutableList())
                if (!recipientInventory.contains(itemId)) {
                    recipientInventory.add(itemId)
                }

                set.add(
                    Operation()
                        .entity(characterId)
                        .entityType(EvenniaCharacter::class.java)
                        .action(OperationType.MUTATE)
                        .delta(JsonObject().put("inventory", giverInventory))
                )

                set.add(
                    Operation()
                        .entity(recipientId)
                        .entityType(EvenniaCharacter::class.java)
                        .action(OperationType.MUTATE)
                        .delta(JsonObject().put("inventory", recipientInventory))
                )
            }
        }

        return set
    }
}