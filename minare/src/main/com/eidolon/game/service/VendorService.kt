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

        val entities = entityController.findByIds(listOf(characterId))
        val character = entities[characterId] as? EvenniaCharacter
            ?: return JsonObject().put("success", false).put("reason", "character not found")

        val characterEvenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", characterId)
            ?: return JsonObject().put("success", false).put("reason", "character not found")

        val currencyTemplate = itemRegistry.get(config.currency)
        val currencyName = currencyTemplate?.name ?: config.currency
        val price = template.price.amount
        val currentCurrency = character.resources[config.currency] ?: 0

        if (currentCurrency < price)
            return JsonObject().put("success", false)
                .put("reason", "You don't have enough $currencyName (need $price, have $currentCurrency).")

        // Deduct currency
        val updatedResources = character.resources.toMutableMap()
        val remaining = currentCurrency - price
        if (remaining <= 0) updatedResources.remove(config.currency)
        else updatedResources[config.currency] = remaining
        character.resources = updatedResources
        entityController.saveState(characterId, JsonObject()
            .put("resources", character.resourcesToJson()))

        if (template.type == "resource" || template.type == "currency") {
            // Add resource to character
            updatedResources[templateId] = (updatedResources[templateId] ?: 0) + 1
            character.resources = updatedResources
            entityController.saveState(characterId, JsonObject()
                .put("resources", character.resourcesToJson()))

            evenniaCommUtils.sendAgentCommand(JsonObject()
                .put("action", "combat_feedback")
                .put("character_evennia_id", characterEvenniaId)
                .put("message", "You receive ${template.name}."))
        } else {
            // Create the real item in Evennia
            evenniaCommUtils.sendAgentCommand(JsonObject()
                .put("action", "vendor_buy")
                .put("character_evennia_id", characterEvenniaId)
                .put("template_id", templateId)
                .put("item_name", template.name)
                .put("item_description", template.description))
        }

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

        val entities = entityController.findByIds(listOf(characterId))
        val character = entities[characterId] as? EvenniaCharacter
            ?: return JsonObject().put("success", false).put("reason", "character not found")

        val characterEvenniaId = crossLinkRegistry.getEvenniaId("EvenniaCharacter", characterId)
            ?: return JsonObject().put("success", false).put("reason", "character not found")

        val currencyTemplate = itemRegistry.get(config.currency)
        val currencyName = currencyTemplate?.name ?: config.currency
        val payout = template.price.amount

        // Add currency to resources
        val updatedResources = character.resources.toMutableMap()
        updatedResources[config.currency] = (updatedResources[config.currency] ?: 0) + payout

        if (template.type == "resource" || template.type == "currency") {
            // Remove resource from character
            val current = updatedResources[templateId] ?: 0
            if (current <= 0)
                return JsonObject().put("success", false)
                    .put("reason", "You don't have any ${template.name} to sell.")
            if (current <= 1) updatedResources.remove(templateId)
            else updatedResources[templateId] = current - 1

            character.resources = updatedResources
            entityController.saveState(characterId, JsonObject()
                .put("resources", character.resourcesToJson()))

            evenniaCommUtils.sendAgentCommand(JsonObject()
                .put("action", "combat_feedback")
                .put("character_evennia_id", characterEvenniaId)
                .put("message", "You sell ${template.name} for $payout $currencyName."))
        } else {
            // Remove the real item from Evennia
            character.resources = updatedResources
            entityController.saveState(characterId, JsonObject()
                .put("resources", character.resourcesToJson()))

            evenniaCommUtils.sendAgentCommand(JsonObject()
                .put("action", "vendor_sell")
                .put("character_evennia_id", characterEvenniaId)
                .put("template_id", templateId)
                .put("item_name", template.name)
                .put("payout", payout)
                .put("currency_name", currencyName))
        }

        return JsonObject().put("success", true)
            .put("item_name", template.name)
            .put("payout", payout)
            .put("currency", currencyName)
    }
}
