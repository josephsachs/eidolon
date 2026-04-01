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
import eidolon.game.action.cache.TurnContext
import org.slf4j.LoggerFactory

@Singleton
class WorldTurnHandler @Inject constructor(
    private val entityController: EntityController,
    private val stateStore: StateStore
) {
    private val log = LoggerFactory.getLogger(WorldTurnHandler::class.java)

    suspend fun handleTurn(tc: TurnContext) {
        tickRooms(tc)
        tickObjectActors(tc)
        tickSpawners(tc)
        tickWorkSites(tc)
        tickExplorableExits(tc)
    }

    private suspend fun tickRooms(tc: TurnContext) {
        val entities = tc.findAllOfType("Room")
        for ((id, entity) in entities) {
            val room = entity as? Room ?: continue
            try {
                room.forgetEchoes()
            } catch (e: Exception) {
                log.error("Room tick error for {}: {}", id, e.message)
            }
        }
    }

    private suspend fun tickObjectActors(tc: TurnContext) {
        val entities = tc.findAllOfType("ObjectActor")
        for ((id, entity) in entities) {
            val actor = entity as? ObjectActor ?: continue
            try {
                actor.act()
            } catch (e: Exception) {
                log.error("ObjectActor tick error for {}: {}", id, e.message)
            }
        }
    }

    private suspend fun tickSpawners(tc: TurnContext) {
        val entities = tc.findAllOfType("Spawner")
        for ((id, entity) in entities) {
            val spawner = entity as? Spawner ?: continue
            try {
                spawner.tick()
            } catch (e: Exception) {
                log.error("Spawner tick error for {}: {}", id, e.message)
            }
        }
    }

    private suspend fun tickWorkSites(tc: TurnContext) {
        val entities = tc.findAllOfType("WorkSite")
        for ((id, entity) in entities) {
            val workSite = entity as? WorkSite ?: continue
            try {
                workSite.tick()
            } catch (e: Exception) {
                log.error("WorkSite tick error for {}: {}", id, e.message)
            }
        }
    }

    private suspend fun tickExplorableExits(tc: TurnContext) {
        val entities = tc.findAllOfType("ExplorableExit")
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
