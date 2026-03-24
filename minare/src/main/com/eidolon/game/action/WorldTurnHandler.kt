package eidolon.game.action

import com.eidolon.game.models.entity.ExplorableExit
import com.eidolon.game.models.entity.ObjectActor
import com.eidolon.game.models.entity.Room
import com.eidolon.game.models.entity.Spawner
import com.eidolon.game.models.entity.WorkSite
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
import org.slf4j.LoggerFactory

@Singleton
class WorldTurnHandler @Inject constructor(
    private val entityController: EntityController,
    private val stateStore: StateStore
) {
    private val log = LoggerFactory.getLogger(WorldTurnHandler::class.java)

    suspend fun handleTurn(turnPhase: GameTurnHandler.Companion.TurnPhase) {
        tickRooms()
        tickObjectActors()
        tickSpawners()
        tickWorkSites()
        tickExplorableExits()
    }

    private suspend fun tickRooms() {
        val keys = stateStore.findAllKeysForType("Room")
        if (keys.isEmpty()) return
        val entities = entityController.findByIds(keys)
        for ((id, entity) in entities) {
            val room = entity as? Room ?: continue
            try {
                room.forgetEchoes()
            } catch (e: Exception) {
                log.error("Room tick error for {}: {}", id, e.message)
            }
        }
    }

    private suspend fun tickObjectActors() {
        val keys = stateStore.findAllKeysForType("ObjectActor")
        if (keys.isEmpty()) return
        val entities = entityController.findByIds(keys)
        for ((id, entity) in entities) {
            val actor = entity as? ObjectActor ?: continue
            try {
                actor.act()
            } catch (e: Exception) {
                log.error("ObjectActor tick error for {}: {}", id, e.message)
            }
        }
    }

    private suspend fun tickSpawners() {
        val keys = stateStore.findAllKeysForType("Spawner")
        if (keys.isEmpty()) return
        val entities = entityController.findByIds(keys)
        for ((id, entity) in entities) {
            val spawner = entity as? Spawner ?: continue
            try {
                spawner.tick()
            } catch (e: Exception) {
                log.error("Spawner tick error for {}: {}", id, e.message)
            }
        }
    }

    private suspend fun tickWorkSites() {
        val keys = stateStore.findAllKeysForType("WorkSite")
        if (keys.isEmpty()) return
        val entities = entityController.findByIds(keys)
        for ((id, entity) in entities) {
            val workSite = entity as? WorkSite ?: continue
            try {
                workSite.tick()
            } catch (e: Exception) {
                log.error("WorkSite tick error for {}: {}", id, e.message)
            }
        }
    }

    private suspend fun tickExplorableExits() {
        val keys = stateStore.findAllKeysForType("ExplorableExit")
        if (keys.isEmpty()) return
        val entities = entityController.findByIds(keys)
        for ((id, entity) in entities) {
            val exit = entity as? ExplorableExit ?: continue
            try {
                exit.progressExploration()
            } catch (e: Exception) {
                log.error("ExplorableExit tick error for {}: {}", id, e.message)
            }
        }
    }
}
