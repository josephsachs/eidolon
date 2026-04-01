package eidolon.game.action.cache

import com.google.inject.Inject
import com.google.inject.Singleton
import com.hazelcast.core.HazelcastInstance
import com.minare.core.utils.types.PushVar

@Singleton
class SharedGameState @Inject constructor(
    private val pushVar: PushVar.Factory
) {
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

    private val _ticksPerTurn = pushVar.create(
        address = "game.ticks.per.turn",
        initialValue = DEFAULT_TICKS_PER_TURN,
        serializer = { it.toString() },
        deserializer = { (it as String).toInt() }
    )

    fun getTicksPerTurn(): Int = _ticksPerTurn.get()

    fun setTicksPerTurn(ticks: Int) {
        _ticksPerTurn.set(ticks.coerceAtLeast(1))
    }

    companion object {
        const val DEFAULT_TICKS_PER_TURN = 5
        enum class GameClockState {
            RUNNING,
            PAUSED
        }
    }
}