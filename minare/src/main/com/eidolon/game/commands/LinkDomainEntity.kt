package com.eidolon.game.commands

import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.models.entity.EvenniaObject
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Links an EvenniaObject stub to its corresponding domain entity
 * (Room, EvenniaCharacter, etc.). Sets domainEntityId/domainEntityType
 * on the EvenniaObject so EntityQuery can resolve through it.
 */
@Singleton
class LinkDomainEntity @Inject constructor(
    private val entityController: EntityController,
    private val crossLinkRegistry: CrossLinkRegistry,
    private val stateStore: StateStore
) {
    private val log = LoggerFactory.getLogger(LinkDomainEntity::class.java)

    suspend fun execute(message: JsonObject): JsonObject {
        val evenniaId = message.getString("evennia_id")
            ?: return error("Missing evennia_id")
        val eoMinareId = message.getString("eo_minare_id")
            ?: return error("Missing eo_minare_id")
        val domainEntityId = message.getString("domain_entity_id")
            ?: return error("Missing domain_entity_id")
        val domainEntityType = message.getString("domain_entity_type")
            ?: return error("Missing domain_entity_type")

        // Set back-link on the EvenniaObject
        val entities = entityController.findByIds(listOf(eoMinareId))
        val eo = entities[eoMinareId] as? EvenniaObject
        if (eo == null) {
            log.warn("LinkDomainEntity: EvenniaObject not found: {}", eoMinareId)
            return error("EvenniaObject not found: $eoMinareId")
        }

        entityController.saveState(eoMinareId, JsonObject()
            .put("domainEntityId", domainEntityId)
            .put("domainEntityType", domainEntityType))

        // Set evenniaId on the domain entity if it has one
        val domainJson = stateStore.findOneJson(domainEntityId)
        if (domainJson != null) {
            val domainState = domainJson.getJsonObject("state", JsonObject())
            if (domainState.containsKey("evenniaId")) {
                entityController.saveState(domainEntityId, JsonObject()
                    .put("evenniaId", evenniaId))
            }
        }

        // Register cross-link for the domain entity type
        crossLinkRegistry.link(domainEntityType, domainEntityId, evenniaId)

        log.info("Linked EvenniaObject {} -> {} {} (evennia={})",
            eoMinareId, domainEntityType, domainEntityId, evenniaId)

        return JsonObject().put("status", "success")
    }

    private fun error(msg: String): JsonObject {
        log.warn("LinkDomainEntity: {}", msg)
        return JsonObject().put("status", "error").put("error", msg)
    }
}