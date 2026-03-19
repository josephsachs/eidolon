package com.eidolon.game.commands

import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.Viewable
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Generic entity state query handler.
 * Takes a minare_id (or evennia_id + entity_type) and a view name,
 * returns a projection of the entity's state.
 */
@Singleton
class EntityQuery @Inject constructor(
    private val entityController: EntityController,
    private val crossLinkRegistry: CrossLinkRegistry,
) : EvenniaCommand {
    private val log = LoggerFactory.getLogger(EntityQuery::class.java)

    override suspend fun execute(message: JsonObject): JsonObject {
        val minareId = message.getString("minare_id")
            ?: resolveFromEvenniaId(message)
            ?: return error("No minare_id or valid evennia_id+entity_type provided")

        val viewName = message.getString("view", "default")

        val entities = entityController.findByIds(listOf(minareId))
        val entity = entities[minareId]
            ?: return error("Entity not found: $minareId")

        val viewable = entity as? Viewable
            ?: return error("Entity type ${entity.type} does not support views")

        val view = viewable.project(viewName)
            ?: return error("Unknown view '$viewName' for entity type ${entity.type}")

        return JsonObject()
            .put("status", "success")
            .put("minare_id", minareId)
            .put("view", viewName)
            .put("data", view)
    }

    private suspend fun resolveFromEvenniaId(message: JsonObject): String? {
        val evenniaId = message.getString("evennia_id") ?: return null
        val entityType = message.getString("entity_type") ?: return null
        return crossLinkRegistry.getMinareId(entityType, evenniaId)
    }

    private fun error(msg: String): JsonObject {
        log.warn("EntityQuery: {}", msg)
        return JsonObject()
            .put("status", "error")
            .put("error", msg)
    }
}
