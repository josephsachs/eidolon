package com.eidolon.game.service

import com.eidolon.game.models.ItemPrice
import com.eidolon.game.models.ItemTemplate
import com.google.inject.Singleton
import io.vertx.core.json.JsonArray
import org.slf4j.LoggerFactory

@Singleton
class ItemRegistry {
    private val log = LoggerFactory.getLogger(ItemRegistry::class.java)
    private val templates = mutableMapOf<String, ItemTemplate>()

    fun load() {
        val stream = javaClass.classLoader.getResourceAsStream("scenario/items.json")
            ?: throw IllegalStateException("items.json not found")
        val json = JsonArray(stream.bufferedReader().readText())

        for (i in 0 until json.size()) {
            val obj = json.getJsonObject(i)
            val priceObj = obj.getJsonObject("price")
            val price = if (priceObj != null) {
                ItemPrice(
                    currency = priceObj.getString("currency", ""),
                    amount = priceObj.getInteger("amount", 0)
                )
            } else ItemPrice()

            val template = ItemTemplate(
                id = obj.getString("id", ""),
                name = obj.getString("name", ""),
                description = obj.getString("description", ""),
                type = obj.getString("type", ""),
                slot = obj.getString("slot", ""),
                damage = obj.getInteger("damage", 0),
                absorption = obj.getInteger("absorption", 0),
                skill = obj.getString("skill", ""),
                price = price
            )
            templates[template.id] = template
        }
        log.info("Loaded {} item templates", templates.size)
    }

    fun get(id: String): ItemTemplate? = templates[id]

    fun all(): Map<String, ItemTemplate> = templates.toMap()

    fun byType(type: String): List<ItemTemplate> = templates.values.filter { it.type == type }
}