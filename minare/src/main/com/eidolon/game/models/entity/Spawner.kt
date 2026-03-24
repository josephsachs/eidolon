package com.eidolon.game.models.entity

import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.service.ItemRegistry
import com.google.inject.Inject
import com.minare.controller.EntityController
import com.minare.core.entity.annotations.*
import com.minare.core.entity.models.Entity
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

@EntityType("Spawner")
class Spawner : Entity() {
    @Inject private lateinit var evenniaCommUtils: EvenniaCommUtils
    @Inject private lateinit var crossLinkRegistry: CrossLinkRegistry
    @Inject private lateinit var itemRegistry: ItemRegistry
    @Inject private lateinit var entityController: EntityController

    private val log = LoggerFactory.getLogger(Spawner::class.java)

    init { type = "Spawner" }

    @State @Mutable var templateId: String = ""
    @State @Mutable var roomId: String = ""

    /** Spawn interval in milliseconds. */
    @Property var intervalMs: Long = 120_000

    @Property var lastSpawnedAt: Long = 0

    suspend fun tick() {
        val start = System.currentTimeMillis()
        try {
            if (templateId.isEmpty() || roomId.isEmpty()) return

            val now = System.currentTimeMillis()
            if (now - lastSpawnedAt < intervalMs) return

            val template = itemRegistry.get(templateId) ?: return
            val roomEvenniaId = crossLinkRegistry.getEvenniaId("Room", roomId) ?: return

            lastSpawnedAt = now
            entityController.saveProperties(_id, JsonObject().put("lastSpawnedAt", now))

            evenniaCommUtils.sendAgentCommand(JsonObject()
                .put("action", "create_item")
                .put("room_evennia_id", roomEvenniaId)
                .put("template_id", templateId)
                .put("item_name", template.name)
                .put("item_description", template.description))

            log.info("Spawner $_id spawned '${template.name}' in room $roomId")
        } catch (e: Exception) {
            log.error("Spawner tick failed for {}: {}", _id, e.message)
        } finally {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed > 200) log.warn("SLOW Spawner tick for {}: {}ms", _id, elapsed)
        }
    }
}
