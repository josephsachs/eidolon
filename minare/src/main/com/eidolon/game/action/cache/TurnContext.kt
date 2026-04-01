package eidolon.game.action.cache

import com.minare.controller.EntityController
import com.minare.core.entity.models.Entity
import com.minare.core.storage.interfaces.StateStore

/**
 * Per-phase cache for entity lookups during the turn loop.
 * Avoids redundant Redis reads when multiple handlers need the same entities.
 * Create a new instance per turn phase — do not reuse across phases.
 */
class TurnContext(
    private val entityController: EntityController,
    private val stateStore: StateStore
) {
    private val keysByType = mutableMapOf<String, List<String>>()
    private val entitiesById = mutableMapOf<String, Entity>()
    private val dirtyIds = mutableSetOf<String>()

    /**
     * Get all entity keys for a type, caching the result.
     */
    suspend fun findAllKeysForType(type: String): List<String> {
        return keysByType.getOrPut(type) {
            stateStore.findAllKeysForType(type)
        }
    }

    /**
     * Load entities by IDs, returning cached results where available
     * and fetching only the uncached ones from Redis.
     */
    suspend fun findByIds(ids: List<String>): Map<String, Entity> {
        if (ids.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, Entity>()
        val uncached = mutableListOf<String>()

        for (id in ids) {
            val cached = entitiesById[id]
            if (cached != null && id !in dirtyIds) {
                result[id] = cached
            } else {
                uncached.add(id)
            }
        }

        if (uncached.isNotEmpty()) {
            val fetched = entityController.findByIds(uncached)
            for ((id, entity) in fetched) {
                entitiesById[id] = entity
                dirtyIds.remove(id)
                result[id] = entity
            }
        }

        return result
    }

    /**
     * Load all entities of a given type, using cached keys and entities.
     */
    suspend fun findAllOfType(type: String): Map<String, Entity> {
        val keys = findAllKeysForType(type)
        if (keys.isEmpty()) return emptyMap()
        return findByIds(keys)
    }

    /**
     * Mark an entity as dirty so the next findByIds call re-fetches it.
     * Use when an entity was saved but the in-memory object doesn't
     * reflect the new state (e.g. saves made by code you don't control).
     */
    fun markDirty(entityId: String) {
        dirtyIds.add(entityId)
    }

    /**
     * Update the cached entity after a mutation. Use this when the
     * in-memory object already reflects the saved state — avoids
     * both a stale cache hit and an unnecessary re-fetch.
     */
    fun put(entity: Entity) {
        val id = entity._id ?: return
        entitiesById[id] = entity
        dirtyIds.remove(id)
    }
}