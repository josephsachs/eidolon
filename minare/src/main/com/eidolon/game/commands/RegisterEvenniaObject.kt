package com.eidolon.game.commands

import com.eidolon.game.GameEntityFactory
import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.models.entity.EvenniaObject
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import eidolon.game.controller.GameChannelController
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

@Singleton
class RegisterEvenniaObject @Inject constructor(
    private val entityController: EntityController,
    private val entityFactory: GameEntityFactory,
    private val crossLinkRegistry: CrossLinkRegistry,
    private val channelController: GameChannelController
) {
    private val log = LoggerFactory.getLogger(RegisterEvenniaObject::class.java)

    suspend fun execute(message: JsonObject): JsonObject {
        val evenniaId = message.getString("evennia_id")
            ?: return JsonObject().put("status", "error").put("error", "Missing evennia_id")
        val typeclassPath = message.getString("typeclass_path", "")
        val key = message.getString("key", "")
        val locationEvenniaId = message.getString("location_evennia_id", "")
        val description = message.getString("description", "")
        val shortDescription = message.getString("short_description", "")

        // Check if already registered
        val existingId = crossLinkRegistry.getMinareId("EvenniaObject", evenniaId)
        if (existingId != null) {
            val entities = entityController.findByIds(listOf(existingId))
            val existing = entities[existingId]
            if (existing != null && existing is EvenniaObject) {
                // Update fields if changed
                val updates = JsonObject()
                if (existing.typeclassPath != typeclassPath) updates.put("typeclassPath", typeclassPath)
                if (existing.key != key) updates.put("key", key)
                if (existing.locationEvenniaId != locationEvenniaId) updates.put("locationEvenniaId", locationEvenniaId)
                if (description.isNotEmpty() && existing.description != description) updates.put("description", description)
                if (shortDescription.isNotEmpty() && existing.shortDescription != shortDescription) updates.put("shortDescription", shortDescription)
                if (!updates.isEmpty) {
                    entityController.saveState(existingId, updates)
                }
                log.info("Found existing EvenniaObject for evennia_id={}: {}", evenniaId, existingId)
                return JsonObject()
                    .put("status", "success")
                    .put("minare_id", existingId)
                    .put("evennia_id", evenniaId)
            }
        }

        // Create new EvenniaObject
        val eo = entityFactory.createEntity(EvenniaObject::class.java) as EvenniaObject
        eo.evenniaId = evenniaId
        eo.typeclassPath = typeclassPath
        eo.key = key
        eo.locationEvenniaId = locationEvenniaId
        eo.description = description
        eo.shortDescription = shortDescription
        entityController.create(eo)

        // Add to default channel
        val defaultChannelId = channelController.getDefaultChannel()
        if (defaultChannelId != null) {
            channelController.addEntitiesToChannel(listOf(eo), defaultChannelId)
        }

        // Register cross-link
        crossLinkRegistry.link("EvenniaObject", eo._id, evenniaId)

        log.info("Created EvenniaObject for evennia_id={}: {} (typeclass={})", evenniaId, eo._id, typeclassPath)
        return JsonObject()
            .put("status", "success")
            .put("minare_id", eo._id)
            .put("evennia_id", evenniaId)
    }
}
