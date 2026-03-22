package com.eidolon.game.service

import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.models.ItemTemplate
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

@Singleton
class VendorService @Inject constructor(
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val itemRegistry: ItemRegistry,
    private val evenniaCommUtils: EvenniaCommUtils,
    private val crossLinkRegistry: CrossLinkRegistry
) {
    private val log = LoggerFactory.getLogger(VendorService::class.java)

    // Vendor configs keyed by vendor character Minare ID
    private val vendorConfigs = mutableMapOf<String, VendorConfig>()

    data class VendorConfig(
        val buyMenu: List<String>,   // template IDs the vendor sells to players
        val sellMenu: List<String>,  // template IDs the vendor buys from players
        val currency: String         // currency template ID
    )

    fun registerVendor(vendorId: String, config: VendorConfig) {
        vendorConfigs[vendorId] = config
        log.info("Registered vendor {} with buy={}, sell={}", vendorId, config.buyMenu, config.sellMenu)
    }

    suspend fun getBuyMenu(vendorId: String): JsonObject {
        val config = vendorConfigs[vendorId]
            ?: return JsonObject().put("success", false).put("reason", "not a vendor")

        val items = JsonArray()
        for (templateId in config.buyMenu) {
            val template = itemRegistry.get(templateId) ?: continue
            items.add(JsonObject()
                .put("id", template.id)
                .put("name", template.name)
                .put("description", template.description)
                .put("price", template.price.amount)
                .put("currency", config.currency))
        }
        return JsonObject().put("success", true).put("items", items)
    }

    suspend fun getSellMenu(vendorId: String): JsonObject {
        val config = vendorConfigs[vendorId]
            ?: return JsonObject().put("success", false).put("reason", "not a vendor")

        val items = JsonArray()
        for (templateId in config.sellMenu) {
            val template = itemRegistry.get(templateId) ?: continue
            items.add(JsonObject()
                .put("id", template.id)
                .put("name", template.name)
                .put("price", template.price.amount)
                .put("currency", config.currency))
        }
        return JsonObject().put("success", true).put("items", items)
    }

    /**
     * Player buys an item from the vendor.
     * Evennia validates inventory (currency count) and sends the result.
     * We tell the Agent to destroy currency items and create the purchased item.
     */
    suspend fun buyItem(
        vendorId: String,
        characterId: String,
        templateId: String
    ): JsonObject {
        val config = vendorConfigs[vendorId]
            ?: return JsonObject().put("success", false).put("reason", "not a vendor")

        if (templateId !in config.buyMenu)
            return JsonObject().put("success", false).put("reason", "not for sale")

        val template = itemRegistry.get(templateId)
            ?: return JsonObject().put("success", false).put("reason", "unknown item")

        val characterEvenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", characterId)
            ?: return JsonObject().put("success", false).put("reason", "character not found")

        val currencyTemplate = itemRegistry.get(config.currency)

        // Tell the Agent to check currency, destroy it, and create the item
        evenniaCommUtils.sendAgentCommand(JsonObject()
            .put("action", "vendor_buy")
            .put("character_evennia_id", characterEvenniaId)
            .put("template_id", templateId)
            .put("item_name", template.name)
            .put("item_description", template.description)
            .put("currency_template_id", config.currency)
            .put("currency_name", currencyTemplate?.name ?: config.currency)
            .put("price", template.price.amount))

        return JsonObject().put("success", true).put("item_name", template.name)
    }

    /**
     * Player sells an item to the vendor.
     * Agent destroys the item and creates currency.
     */
    suspend fun sellItem(
        vendorId: String,
        characterId: String,
        templateId: String
    ): JsonObject {
        val config = vendorConfigs[vendorId]
            ?: return JsonObject().put("success", false).put("reason", "not a vendor")

        if (templateId !in config.sellMenu)
            return JsonObject().put("success", false).put("reason", "vendor doesn't want that")

        val template = itemRegistry.get(templateId)
            ?: return JsonObject().put("success", false).put("reason", "unknown item")

        val characterEvenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", characterId)
            ?: return JsonObject().put("success", false).put("reason", "character not found")

        val currencyTemplate = itemRegistry.get(config.currency)

        evenniaCommUtils.sendAgentCommand(JsonObject()
            .put("action", "vendor_sell")
            .put("character_evennia_id", characterEvenniaId)
            .put("template_id", templateId)
            .put("item_name", template.name)
            .put("currency_template_id", config.currency)
            .put("currency_name", currencyTemplate?.name ?: config.currency)
            .put("payout", template.price.amount))

        return JsonObject().put("success", true)
            .put("item_name", template.name)
            .put("payout", template.price.amount)
    }
}
