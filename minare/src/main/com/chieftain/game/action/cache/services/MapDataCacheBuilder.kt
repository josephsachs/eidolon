package chieftain.game.action.cache.services

import chieftain.game.action.cache.SharedGameState
import chieftain.game.models.entity.MapZoneResources
import com.chieftain.game.models.entity.MapZone
import com.chieftain.game.models.entity.MapZone.Companion.TerrainType
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
import com.minare.core.utils.vertx.EventBusUtils
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import java.io.Serializable

@Singleton
class MapDataCacheBuilder @Inject constructor(
    private val sharedGameState: SharedGameState,
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val eventBusUtils: EventBusUtils
) {
    private val log = LoggerFactory.getLogger(MapDataCacheBuilder::class.java)
    /**
     * Rebuilds map data cache from current source of truth
     */
    suspend fun rebuild() {
        val allMapZones = entityController.findByIds(
            stateStore.findKeysByType("MapZone")
        )

        for(item in allMapZones) {
            val mapZone = item.value as MapZone
            val x = mapZone.location.x
            val y = mapZone.location.y

            val movementCost = 0
            val isPassable =
                mapZone.terrainType !in listOf(
                    TerrainType.OCEAN,
                    TerrainType.UNASSIGNED,
                    TerrainType.ROCKLAND
                )

            sharedGameState.mapDataCache.put(
                mapZone.location.x,
                mapZone.location.y,
                MapCacheItem(
                    mapZone.location.x,
                    mapZone.location.y,
                    isPassable,
                    movementCost,
                    mapZone.resources
                )
            )
        }

        eventBusUtils.publishWithTracing(ADDRESS_MAP_CACHE_BUILT,
            JsonObject()
                .put("updatedTime", System.currentTimeMillis())
        )
    }

    companion object {
        const val ADDRESS_MAP_CACHE_BUILT = "chieftain.game.map.cache.built"

        data class MapCacheItem(
            val x: Int,
            val y: Int,
            val isPassable: Boolean,
            val movementCost: Int,
            val resources: MapZoneResources
        ) : Serializable {}
    }
}