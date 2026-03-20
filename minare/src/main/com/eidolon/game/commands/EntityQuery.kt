package com.eidolon.game.commands

import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.Viewable
import com.eidolon.game.models.entity.EvenniaObject
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
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
    private val stateStore: StateStore,
    private val crossLinkRegistry: CrossLinkRegistry,
) : EvenniaCommand {
    private val log = LoggerFactory.getLogger(EntityQuery::class.java)

    override suspend fun execute(message: JsonObject): JsonObject {
        val minareId = message.getString("minare_id")
            ?: resolveFromEvenniaId(message)
            ?: return error("No minare_id or valid evennia_id+entity_type provided")

        val viewName = message.getString("view", "default")

        var resolvedId = minareId
        val entities = entityController.findByIds(listOf(minareId))
        var entity = entities[minareId]
            ?: return error("Entity not found: $minareId")

        // If this is an EvenniaObject with a domain entity back-link, resolve to the domain entity
        if (entity is EvenniaObject && entity.domainEntityId.isNotEmpty()) {
            val domainEntities = entityController.findByIds(listOf(entity.domainEntityId))
            val domainEntity = domainEntities[entity.domainEntityId]
            if (domainEntity != null) {
                log.info("EntityQuery: resolved EvenniaObject {} -> {} {}", minareId, entity.domainEntityType, entity.domainEntityId)
                resolvedId = entity.domainEntityId
                entity = domainEntity
            }
        }

        // Re-hydrate inline (workaround: framework hydrator doesn't await)
        val entityJsons = stateStore.findJson(listOf(resolvedId))
        val entityJson = entityJsons[resolvedId]
        if (entityJson != null) {
            stateStore.setEntityState(entity, entity.type, entityJson.getJsonObject("state", JsonObject()))
            stateStore.setEntityProperties(entity, entity.type, entityJson.getJsonObject("properties", JsonObject()))
        }

        val viewable = entity as? Viewable
            ?: return error("Entity type ${entity.type} does not support views")

        val view = viewable.project(viewName)
            ?: return error("Unknown view '$viewName' for entity type ${entity.type}")

        log.info("EntityQuery: entity={} view={} result={}", minareId, viewName, view)

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
