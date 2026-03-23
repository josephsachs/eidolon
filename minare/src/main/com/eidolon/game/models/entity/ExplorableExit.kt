package com.eidolon.game.models.entity

import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import com.google.inject.Inject
import com.minare.controller.EntityController
import com.minare.core.entity.annotations.*
import com.minare.core.entity.models.Entity
import com.minare.core.storage.interfaces.StateStore
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.Serializable

@EntityType("ExplorableExit")
class ExplorableExit : Entity(), Serializable {
    @Inject
    lateinit var entityController: EntityController
    @Inject
    lateinit var evenniaCommUtils: EvenniaCommUtils
    @Inject
    lateinit var crossLinkRegistry: CrossLinkRegistry
    @Inject
    lateinit var stateStore: StateStore

    private val log = LoggerFactory.getLogger(ExplorableExit::class.java)

    init {
        type = "ExplorableExit"
    }

    @State
    @Mutable
    var direction: String = ""

    @State
    @Mutable
    var destination: String = ""

    @State
    @Mutable
    var description: String = ""

    @State
    @Mutable
    var sourceRoomId: String = ""

    @State
    @Mutable
    var blockMessage: String = ""

    @State
    @Mutable
    var threshold: Int = 10

    @State
    @Mutable
    var unlocked: Boolean = false

    @State
    @Mutable
    var explorers: Set<String> = emptySet()

    /**
     * List of {characterId, timestamp} entries recording each contribution.
     */
    @State
    @Mutable
    var contributions: JsonArray = JsonArray()

    @FixedTask
    suspend fun progressExploration() {
        val start = System.currentTimeMillis()
        try {
            if (unlocked || explorers.isEmpty()) return

            // Prune explorers who left the source room
            val explorerIds = explorers.toList()
            val characters = entityController.findByIds(explorerIds)
            val validExplorers = mutableSetOf<String>()
            for (id in explorerIds) {
                val char = characters[id] as? EvenniaCharacter ?: continue
                if (char.currentRoomId == sourceRoomId) {
                    validExplorers.add(id)
                }
            }

            if (validExplorers.isEmpty()) {
                if (validExplorers.size != explorers.size) {
                    entityController.saveState(_id, JsonObject()
                        .put("explorers", validExplorers.toList()))
                }
                return
            }

            // Append contributions for each active explorer
            val updatedContributions = contributions.copy()
            val now = System.currentTimeMillis()
            for (explorerId in validExplorers) {
                updatedContributions.add(JsonObject()
                    .put("characterId", explorerId)
                    .put("timestamp", now))
            }

            val changes = JsonObject()
                .put("explorers", validExplorers.toList())
                .put("contributions", updatedContributions)

            if (updatedContributions.size() >= threshold) {
                changes.put("unlocked", true)
                changes.put("explorers", emptyList<String>())

                val exitEvenniaId = resolveExitEvenniaId()
                if (exitEvenniaId != null) {
                    evenniaCommUtils.sendAgentCommand(JsonObject()
                        .put("action", "unlock_exit")
                        .put("exit_evennia_id", exitEvenniaId))
                    log.info("ExplorableExit: {} unlocked (direction={})", _id, direction)
                } else {
                    log.warn("ExplorableExit: {} unlocked but could not resolve Evennia exit ID", _id)
                }
            }

            entityController.saveState(_id, changes)
        } catch (e: Exception) {
            log.error("progressExploration failed for exit {}: {}", _id, e.message)
        } finally {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed > 200) log.warn("SLOW progressExploration for exit {}: {}ms", _id, elapsed)
        }
    }

    /**
     * Resolve the Evennia exit ID by finding EvenniaObject stubs whose key matches
     * this exit's direction and whose location matches the source room's Evennia ID.
     */
    private suspend fun resolveExitEvenniaId(): String? {
        val roomEvenniaId = crossLinkRegistry.getEvenniaId("Room", sourceRoomId) ?: return null
        val eoKeys = stateStore.findAllKeysForType("EvenniaObject")
        val eos = entityController.findByIds(eoKeys)
        for ((_, entity) in eos) {
            val eo = entity as? EvenniaObject ?: continue
            if (eo.key == direction && eo.locationEvenniaId == roomEvenniaId) {
                return eo.evenniaId
            }
        }
        return null
    }
}
