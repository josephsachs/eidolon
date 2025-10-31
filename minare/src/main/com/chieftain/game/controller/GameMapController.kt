package chieftain.game.controller

import chieftain.game.action.cache.SharedGameState
import com.google.inject.Inject
import com.google.inject.Singleton

@Singleton
class GameMapController @Inject constructor(
    private val sharedGameState: SharedGameState
) {
    /**
     * data class MapCacheItem(
     * val x: Int,
     * val y: Int,
     * val isPassable: Boolean,
     * val movementCost: Int
     * ) : Serializable {}
     */

    fun valid(from: Pair<Int, Int>): Boolean {
       val item = sharedGameState.mapDataCache.get(from.first, from.second)

       if (item?.isPassable == true) {
           return true
       }

        return false
    }

    fun findPath(from: Pair<Int, Int>, to: Pair<Int, Int>): List<Pair<Int, Int>> {

        /**
         * data class Vector2(
         *     val x: Int,
         *     val y: Int,
         * )
         */
        // sharedGameState.mapDataCache.get(pos: Vector2)
        // sharedGameState.mapDataCache.put(pos: Vector2, item: MapCacheItem)

        return emptyList()

    }
}