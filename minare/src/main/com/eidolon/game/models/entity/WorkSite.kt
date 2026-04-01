package com.eidolon.game.models.entity

import com.eidolon.game.commands.SkillEvent
import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.service.ItemRegistry
import com.google.inject.Inject
import com.minare.controller.EntityController
import com.minare.controller.OperationController
import com.minare.core.entity.annotations.*
import com.minare.core.entity.models.Entity
import com.minare.core.operation.models.Operation
import com.minare.core.operation.models.OperationType
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import kotlin.random.Random

@EntityType("WorkSite")
class WorkSite : Entity() {
    @Inject private lateinit var evenniaCommUtils: EvenniaCommUtils
    @Inject private lateinit var crossLinkRegistry: CrossLinkRegistry
    @Inject private lateinit var itemRegistry: ItemRegistry
    @Inject private lateinit var entityController: EntityController
    @Inject private lateinit var operationController: OperationController
    @Inject private lateinit var skillEvent: SkillEvent

    private val log = LoggerFactory.getLogger(WorkSite::class.java)

    init { type = "WorkSite" }

    @State @Mutable var name: String = ""
    @State @Mutable var templateId: String = ""
    @State @Mutable var roomId: String = ""
    @State @Mutable var skillName: String = ""

    @Property var intervalMs: Long = 120_000
    @Property var lastSpawnedAt: Long = 0
    @Property var workers: List<String> = emptyList()

    suspend fun addWorker(characterId: String) {
        if (characterId !in workers) {
            workers = workers + characterId
            entityController.saveProperties(_id, JsonObject()
                .put("workers", workers))
        }
    }

    suspend fun removeWorker(characterId: String) {
        if (characterId in workers) {
            workers = workers - characterId
            entityController.saveProperties(_id, JsonObject()
                .put("workers", workers))
        }
    }

    suspend fun tick() {
        val start = System.currentTimeMillis()
        try {
            tickInner()
        } catch (e: Exception) {
            log.error("WorkSite tick failed for '{}' ({}): {}", name, _id, e.message)
        } finally {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed > 200) log.warn("SLOW WorkSite tick for '{}' ({}): {}ms", name, _id, elapsed)
        }
    }

    private suspend fun tickInner() {
        if (templateId.isEmpty() || roomId.isEmpty()) {
            log.debug("WorkSite '$name' tick skip: templateId='$templateId' roomId='$roomId'")
            return
        }
        if (workers.isEmpty()) return

        val now = System.currentTimeMillis()
        val elapsed = now - lastSpawnedAt
        if (elapsed < intervalMs) {
            log.debug("WorkSite '$name' cooldown: ${elapsed / 1000}s / ${intervalMs / 1000}s, ${workers.size} workers")
            return
        }

        // Find which workers are still in this room
        val workerEntities = entityController.findByIds(workers)
        val presentWorkers = workerEntities.values
            .filterIsInstance<EvenniaCharacter>()
            .filter { it.currentRoomId == roomId && !it.dead && !it.downed }

        if (presentWorkers.isEmpty()) {
            log.info("WorkSite '$name' ready but no present workers (${workers.size} registered, roomId=$roomId)")
            for ((id, entity) in workerEntities) {
                val char = entity as? EvenniaCharacter
                if (char != null) log.info("  worker $id: currentRoomId=${char.currentRoomId} dead=${char.dead} downed=${char.downed}")
                else log.info("  worker $id: not an EvenniaCharacter (${entity?.javaClass?.simpleName})")
            }
            return
        }

        val template = itemRegistry.get(templateId) ?: return

        lastSpawnedAt = now
        entityController.saveProperties(_id!!, JsonObject().put("lastSpawnedAt", now))

        // Grant resource to each present worker
        for (worker in presentWorkers) {
            val updatedResources = worker.resources.toMutableMap()
            updatedResources[templateId] = (updatedResources[templateId] ?: 0) + 1
            worker.resources = updatedResources
            operationController.queue(
                Operation()
                    .entity(worker._id)
                    .entityType(EvenniaCharacter::class)
                    .action(OperationType.MUTATE)
                    .delta(JsonObject().put("resources", worker.resourcesToJson()))
            )
        }

        log.info("WorkSite '$name' granted '${template.name}' to ${presentWorkers.size} workers")

        // Skill check for each present worker
        for (worker in presentWorkers) {
            val outcome = rollSkillCheck(worker)

            val result = skillEvent.execute(JsonObject()
                .put("character_id", worker._id)
                .put("skill_name", skillName)
                .put("outcome", outcome))

            // Notify the worker
            val workerEvenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", worker._id!!) ?: continue
            val levelUp = result.getBoolean("level_up", false)
            val msg = if (levelUp) {
                "|gYou have learned something new about $skillName.|n"
            } else if (outcome == "success") {
                "|gYour work pays off — you feel more skilled at $skillName.|n"
            } else {
                "|yYou struggle with the work but learn from the effort.|n"
            }

            evenniaCommUtils.sendAgentCommand(JsonObject()
                .put("action", "combat_feedback")
                .put("character_evennia_id", workerEvenniaId)
                .put("message", msg))
        }

        // Prune workers who left the room
        val presentIds = presentWorkers.map { it._id!! }.toSet()
        val staleWorkers = workers.filter { it !in presentIds }
        if (staleWorkers.isNotEmpty()) {
            workers = workers.filter { it in presentIds }
            entityController.saveProperties(_id!!, JsonObject()
                .put("workers", workers))
        }
    }

    private fun rollSkillCheck(character: EvenniaCharacter): String {
        val skill = character.skills.firstOrNull { it.name == skillName }
        val skillLevel = skill?.level ?: 0.0
        // Base 30% chance, +5% per skill level, capped at 95%
        val chance = (30.0 + skillLevel * 5.0).coerceAtMost(95.0)
        val roll = Random.nextInt(100)
        return if (roll < chance) "success" else "failure"
    }
}
