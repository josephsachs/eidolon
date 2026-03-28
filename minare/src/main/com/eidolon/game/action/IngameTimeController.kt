package eidolon.game.action

import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.controller.OperationController
import com.minare.core.operation.models.Operation
import com.minare.core.operation.models.OperationType
import com.minare.core.storage.interfaces.StateStore
import com.eidolon.game.models.entity.Room
import com.minare.core.utils.vertx.EventBusUtils
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

@Singleton
class IngameTimeController @Inject constructor(
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val operationController: OperationController,
    private val eventBusUtils: EventBusUtils
) {
    private val log = LoggerFactory.getLogger(IngameTimeController::class.java)

    private var currentPhase: TimePhase = TimePhase.DAY

    /**
     * Called each turn. Derives time of day from game turn count
     * and updates room descriptions on transitions.
     */
    suspend fun onTurn(currentTurn: Int) {
        val newPhase = phaseForTurn(currentTurn)
        if (newPhase != currentPhase) {
            log.info("INGAME_TIME: Transition {} -> {} at turn {}", currentPhase, newPhase, currentTurn)
            currentPhase = newPhase
            updateRoomDescriptions(newPhase)
        }
    }

    private fun phaseForTurn(turn: Int): TimePhase {
        val cyclePosition = turn % DAY_CYCLE_LENGTH
        return if (cyclePosition < DAY_PHASE_LENGTH) TimePhase.DAY else TimePhase.NIGHT
    }

    private suspend fun updateRoomDescriptions(phase: TimePhase) {
        val rooms = entityController.findByIds(
            stateStore.findAllKeysForType("Room")
        )

        for ((_, entity) in rooms) {
            val room = entity as Room
            val newDesc = when (phase) {
                TimePhase.DAY -> room.dayDesc
                TimePhase.NIGHT -> room.nightDesc
            }

            if (newDesc.isEmpty()) continue

            val operation = Operation()
                .entity(room._id)
                .entityType(Room::class)
                .action(OperationType.MUTATE)
                .delta(JsonObject().put("shortDescription", newDesc))

            operationController.queue(operation)
        }
    }

    companion object {
        const val DAY_CYCLE_LENGTH = 100  // turns per full day/night cycle
        const val DAY_PHASE_LENGTH = 50   // turns of daylight

        enum class TimePhase { DAY, NIGHT }
    }
}