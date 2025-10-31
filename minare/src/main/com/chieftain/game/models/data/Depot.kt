package com.chieftain.game.models.data

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.minare.core.utils.JsonSerializable
import io.vertx.core.json.JsonObject

data class Depot @JsonCreator constructor(
    @JsonProperty("contents") private val _contents: Map<String, Map<String, Int>>
) : JsonSerializable {

    val contents: Map<ResourceTypeGroup, Map<ResourceType, Int>>
        get() = _contents.mapKeys { (key, _) ->
            ResourceTypeGroup.valueOf(key)
        }.mapValues { (_, resourceMap) ->
            resourceMap.mapKeys { (resourceKey, _) ->
                ResourceType.valueOf(resourceKey)
            }
        }

    constructor() : this(
        mapOf(
            ResourceTypeGroup.FOOD.name to mapOf(
                ResourceType.CORN.name to 0,
                ResourceType.FOWL.name to 0,
                ResourceType.MEAT.name to 0,
                ResourceType.FRUIT.name to 0,
                ResourceType.HONEY.name to 0
            ),
            ResourceTypeGroup.GOODS.name to mapOf(
                ResourceType.WOOD.name to 0,
                ResourceType.PAPYRUS.name to 0,
                ResourceType.STONE.name to 0
            ),
            ResourceTypeGroup.METALS.name to mapOf(
                ResourceType.IRON.name to 0,
                ResourceType.COPPER.name to 0,
                ResourceType.TIN.name to 0,
                ResourceType.GOLD.name to 0
            ),
            ResourceTypeGroup.TREASURE.name to mapOf(
                ResourceType.BOOKS.name to 0,
                ResourceType.JEWELS.name to 0,
                ResourceType.STATUES.name to 0,
                ResourceType.COINS.name to 0
            )
        )
    )

    override fun toJson(): JsonObject {
        val json = JsonObject()
        val contentsJson = JsonObject()

        _contents.forEach { (group, resources) ->
            val resourcesJson = JsonObject()
            resources.forEach { (resource, amount) ->
                resourcesJson.put(resource, amount)
            }
            contentsJson.put(group, resourcesJson)
        }

        json.put("contents", contentsJson)
        return json
    }

    fun get(group: ResourceTypeGroup, type: ResourceType): Int {
        return _contents[group.name]?.get(type.name) ?: 0
    }

    fun set(group: ResourceTypeGroup, type: ResourceType, amount: Int): Depot {
        val newContents = _contents.toMutableMap()
        val groupMap = (newContents[group.name] ?: emptyMap()).toMutableMap()
        groupMap[type.name] = amount
        newContents[group.name] = groupMap
        return Depot(newContents)
    }

    companion object {
        enum class ResourceTypeGroup {
            FOOD,
            GOODS,
            METALS,
            TREASURE
        }

        enum class ResourceType {
            CORN,
            FOWL,
            MEAT,
            FRUIT,
            HONEY,
            WOOD,
            PAPYRUS,
            STONE,
            IRON,
            COPPER,
            TIN,
            GOLD,
            BOOKS,
            JEWELS,
            STATUES,
            COINS
        }
    }
}