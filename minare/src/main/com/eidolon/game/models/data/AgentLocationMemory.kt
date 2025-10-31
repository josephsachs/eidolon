package eidolon.game.models.data

import com.eidolon.game.models.data.Depot.Companion.ResourceType
import com.eidolon.game.models.data.Depot.Companion.ResourceTypeGroup
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.minare.core.entity.models.serializable.Vector2
import com.minare.core.utils.JsonSerializable
import io.vertx.core.json.JsonObject

data class AgentLocationMemory @JsonCreator constructor(
    @JsonProperty("memories") private val _memories: Map<String, Map<String, Map<String, Int>>>
) : JsonSerializable {

    // Type-safe accessor
    val memories: Map<Vector2, Map<AgentLocationMemoryType, Map<String, Int>>>
        get() = _memories.mapKeys { (key, _) ->
            // Parse "x,y" back to Vector2
            val parts = key.split(",")
            Vector2(parts[0].toInt(), parts[1].toInt())
        }.mapValues { (_, typeMap) ->
            typeMap.mapKeys { (typeKey, _) ->
                AgentLocationMemoryType.valueOf(typeKey)
            }
        }

    constructor() : this(emptyMap())

    // Helper to get memory for a location
    fun getMemory(location: Vector2, type: AgentLocationMemoryType): Map<String, Int>? {
        return _memories["${location.x},${location.y}"]?.get(type.name)
    }

    // Helper to set memory (returns new instance)
    fun setMemory(
        location: Vector2,
        type: AgentLocationMemoryType,
        reasons: Map<String, Int>
    ): AgentLocationMemory {
        val newMemories = _memories.toMutableMap()
        val locationKey = "${location.x},${location.y}"
        val locationMemories = (newMemories[locationKey] ?: emptyMap()).toMutableMap()
        locationMemories[type.name] = reasons
        newMemories[locationKey] = locationMemories
        return AgentLocationMemory(newMemories)
    }

    override fun toJson(): JsonObject {
        val json = JsonObject()
        val memoriesJson = JsonObject()

        _memories.forEach { (location, typeMap) ->
            val typeJson = JsonObject()
            typeMap.forEach { (type, reasonMap) ->
                val reasonJson = JsonObject()
                reasonMap.forEach { (reason, amount) ->
                    reasonJson.put(reason, amount)
                }
                typeJson.put(type, reasonJson)
            }
            memoriesJson.put(location, typeJson)
        }

        json.put("memories", memoriesJson)
        return json
    }

    enum class AgentLocationMemoryType {
        HAS_FOOD,
        HAS_GOODS,
        HAS_METALS,
        VISITED,
        DANGER,
        MARKET
    }
}