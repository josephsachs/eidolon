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

    data class VendorConfig(
        val buyMenu: List<String>,   // template IDs the vendor sells to players
        val sellMenu: List<String>,  // template IDs the vendor buys from players
        val currency: String         // currency template ID
    )

    /**
     * Persist vendor config as properties on the EvenniaCharacter entity.
     */
    suspend fun registerVendor(vendorId: String, config: VendorConfig) {
        entityController.saveProperties(vendorId, JsonObject()
            .put("vendorBuyMenu", JsonArray(config.buyMenu))
            .put("vendorSellMenu", JsonArray(config.sellMenu))
            .put("vendorCurrency", config.currency))
        log.info("Registered vendor {} with buy={}, sell={}", vendorId, config.buyMenu, config.sellMenu)
    }

    private suspend fun getConfig(vendorId: String): VendorConfig? {
        val json = stateStore.findOneJson(vendorId) ?: return null
        val props = json.getJsonObject("properties", JsonObject())
        val buyMenu = props.getJsonArray("vendorBuyMenu")
        if (buyMenu == null || buyMenu.isEmpty) return null
        return VendorConfig(
            buyMenu = buyMenu.map { it as String },
            sellMenu = (props.getJsonArray("vendorSellMenu") ?: JsonArray()).map { it as String },
            currency = props.getString("vendorCurrency", "dollar")
        )
    }

    suspend fun getBuyMenu(vendorId: String): JsonObject {
        val config = getConfig(vendorId)
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
        val config = getConfig(vendorId)
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

    suspend fun buyItem(
        vendorId: String,
        characterId: String,
        templateId: String
    ): JsonObject {
        val config = getConfig(vendorId)
            ?: return JsonObject().put("success", false).put("reason", "not a vendor")

        if (templateId !in config.buyMenu)
            return JsonObject().put("success", false).put("reason", "not for sale")

        val template = itemRegistry.get(templateId)
            ?: return JsonObject().put("success", false).put("reason", "unknown item")

        val characterEvenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", characterId)
            ?: return JsonObject().put("success", false).put("reason", "character not found")

        val currencyTemplate = itemRegistry.get(config.currency)

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

    suspend fun sellItem(
        vendorId: String,
        characterId: String,
        templateId: String
    ): JsonObject {
        val config = getConfig(vendorId)
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
            .put("currency", currencyTemplate?.name ?: config.currency)
    }
}
