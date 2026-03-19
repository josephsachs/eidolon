package com.eidolon.game.evennia

import com.google.inject.Singleton
import com.minare.core.entity.models.Entity
import io.vertx.core.json.JsonObject

/**
 * Registry mapping (entity type, view name) to projection functions.
 * Views define the display-ready subset of entity state that Evennia receives.
 */
@Singleton
class EntityViewRegistry {
    private val views = mutableMapOf<String, MutableMap<String, (Entity) -> JsonObject>>()

    fun register(entityType: String, viewName: String, projector: (Entity) -> JsonObject) {
        views.getOrPut(entityType) { mutableMapOf() }[viewName] = projector
    }

    fun project(entity: Entity, viewName: String): JsonObject? {
        val projector = views[entity.type]?.get(viewName) ?: return null
        return projector(entity)
    }
}
