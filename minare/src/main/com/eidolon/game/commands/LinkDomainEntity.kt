package com.eidolon.game.commands

import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.models.entity.EvenniaObject
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
import eidolon.game.controller.GameChannelController
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Links an EvenniaObject stub to its corresponding domain entity
 * (Room, EvenniaCharacter, etc.). Sets domainEntityId/domainEntityType
 * on the EvenniaObject so EntityQuery can resolve through it.
 *
 * This is the single point of contact for establishing a domain entity link.
 * It handles: EvenniaObject back-link, domain entity evenniaId, cross-link
 * registration, and broadcasting set_domain_link to Evennia.
 */
@Singleton
class LinkDomainEntity @Inject constructor(
    private val entityController: EntityController,
    private val crossLinkRegistry: CrossLinkRegistry,
    private val stateStore: StateStore,
    private val channelController: GameChannelController
) {
    private val log = LoggerFactory.getLogger(LinkDomainEntity::class.java)

    /**
     * Establish a full bidirectional link between an Evennia object and a Minare domain entity.
     * Resolves the EvenniaObject stub internally, registers cross-links, and notifies Evennia.
     */
    suspend fun link(evenniaId: String, domainEntityId: String, domainEntityType: String): JsonObject {
        // Resolve the EvenniaObject stub
        val eoMinareId = crossLinkRegistry.getMinareId("EvenniaObject", evenniaId)
        if (eoMinareId == null) {
            log.warn("LinkDomainEntity: No EvenniaObject stub for evennia_id={}", evenniaId)
            return error("No EvenniaObject stub for evennia_id=$evenniaId")
        }

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

        // Broadcast to Evennia so it can match incoming domain entity updates
        val defaultChannelId = channelController.getDefaultChannel()
        if (defaultChannelId != null) {
            channelController.broadcast(defaultChannelId, JsonObject()
                .put("type", "set_domain_link")
                .put("evennia_id", evenniaId)
                .put("domain_entity_id", domainEntityId)
                .put("domain_entity_type", domainEntityType))
        }

        log.info("Linked {} {} <-> evennia {} (eo={})",
            domainEntityType, domainEntityId, evenniaId, eoMinareId)

        return JsonObject().put("status", "success")
    }

    /**
     * Legacy execute method for message-based invocation (e.g., from GameMessageController).
     */
    suspend fun execute(message: JsonObject): JsonObject {
        val evenniaId = message.getString("evennia_id")
            ?: return error("Missing evennia_id")
        val domainEntityId = message.getString("domain_entity_id")
            ?: return error("Missing domain_entity_id")
        val domainEntityType = message.getString("domain_entity_type")
            ?: return error("Missing domain_entity_type")

        return link(evenniaId, domainEntityId, domainEntityType)
    }

    private fun error(msg: String): JsonObject {
        log.warn("LinkDomainEntity: {}", msg)
        return JsonObject().put("status", "error").put("error", msg)
    }
}
