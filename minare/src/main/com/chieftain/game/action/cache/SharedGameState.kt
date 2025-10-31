package chieftain.game.action.cache

import chieftain.game.action.cache.services.MapDataCacheBuilder.Companion.MapCacheItem
import com.google.inject.Inject
import com.google.inject.Singleton
import com.hazelcast.core.HazelcastInstance
import com.minare.core.utils.DistributedGridMap
import com.minare.core.utils.PushVar
import io.vertx.core.impl.logging.LoggerFactory

@Singleton
class SharedGameState @Inject constructor(
    private val hazelcastInstance: HazelcastInstance,
    private val pushVar: PushVar.Factory,
    private val distributedGridMap: DistributedGridMap.Factory
) {
    private val log = LoggerFactory.getLogger(SharedGameState::class.java)

    private val _gameClockState = pushVar.create(
        address = "game.clock.state",
        initialValue = GameClockState.PAUSED,
        serializer = { it.name },  // Serialize enum to string
        deserializer = { GameClockState.valueOf(it as String) }  // Deserialize string back to enum
    )

    fun isGamePaused(): Boolean {
        return _gameClockState.get() == GameClockState.PAUSED
    }

    fun pauseGameClock() {
        _gameClockState.set(GameClockState.PAUSED)
    }

    fun resumeGameClock() {
        _gameClockState.set(GameClockState.RUNNING)
    }

    val mapDataCache = distributedGridMap.create<MapCacheItem>(hazelcastInstance)

    companion object {
        enum class GameClockState {
            RUNNING,
            PAUSED
        }
    }
}