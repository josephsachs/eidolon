package eidolon.game.action.cache.services

import com.eidolon.game.models.entity.Room
import eidolon.game.action.cache.SharedGameState
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
import com.minare.core.utils.vertx.EventBusUtils
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonObject
import java.io.Serializable

@Singleton
class RoomDataCacheBuilder @Inject constructor(
    private val sharedGameState: SharedGameState,
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val eventBusUtils: EventBusUtils
) {
    private val log = LoggerFactory.getLogger(RoomDataCacheBuilder::class.java)
    /**
     * Rebuilds map data cache from current source of truth
     */
    suspend fun rebuild() {
        val allRooms = entityController.findByIds(
            stateStore.findAllKeysForType("Room")
        )

        //for(item in allRooms) {
            // val room = item.value as Room

            /// room.evenniaId = ...
        //}

        eventBusUtils.publishWithTracing(ADDRESS_ROOM_CACHE_BUILT,
            JsonObject()
                .put("updatedTime", System.currentTimeMillis())
        )
    }

    companion object {
        const val ADDRESS_ROOM_CACHE_BUILT = "eidolon.game.room.cache.built"

        data class RoomCacheItem(
            val evennidId: String
        ) : Serializable {}
    }
}