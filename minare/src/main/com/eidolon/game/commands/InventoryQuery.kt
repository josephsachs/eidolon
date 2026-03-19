package com.eidolon.game.commands

import com.eidolon.game.models.entity.Item
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Handles inventory_query messages from Evennia.
 * Reads EvenniaCharacter.inventory directly from Redis (no frame loop)
 * and returns the item list via UpSocket response.
 */
@Singleton
class InventoryQuery @Inject constructor(
    private val entityController: EntityController
) : EvenniaCommand {
    private val log = LoggerFactory.getLogger(InventoryQuery::class.java)

    override suspend fun execute(message: JsonObject): JsonObject {
        val characterId = message.getString("character_id", "")

        if (characterId.isEmpty()) {
            return JsonObject().put("status", "error").put("error", "Missing character_id")
        }

        val entities = entityController.findByIds(listOf(characterId))
        val character = entities[characterId] as? EvenniaCharacter

        if (character == null) {
            log.warn("InventoryQuery: character {} not found", characterId)
            return JsonObject().put("status", "error").put("error", "Character not found")
        }

        val itemIds = character.inventory
        val items = JsonArray()

        if (itemIds.size() > 0) {
            // Load item entities to get names/descriptions
            val ids = (0 until itemIds.size()).map { itemIds.getString(it) }
            val itemEntities = entityController.findByIds(ids)
            for (id in ids) {
                val item = itemEntities[id]
                if (item != null) {
                    val itemName = if (item is Item) item.name else item.type
                    items.add(JsonObject()
                        .put("_id", item._id)
                        .put("name", itemName))
                }
            }
        }

        log.info("InventoryQuery: character={} items={}", characterId, items.size())
        return JsonObject()
            .put("status", "success")
            .put("items", items)
    }
}
