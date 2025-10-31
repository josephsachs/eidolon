package chieftain.game.models.entity

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.minare.core.utils.JsonSerializable
import io.vertx.core.json.JsonObject
import java.io.Serializable

data class MapZoneResources @JsonCreator constructor(
    @JsonProperty("resources") private val _resources: Map<String, Int>
) : Serializable, JsonSerializable {

    // Type-safe accessor
    val resources: Map<RawResourceType, Int>
        get() = _resources.mapKeys { (key, _) -> RawResourceType.valueOf(key) }

    constructor() : this(
        mapOf(
            RawResourceType.SOIL.name to 0,
            RawResourceType.CATTLE.name to 0,
            RawResourceType.FOWL.name to 0,
            RawResourceType.FISH.name to 0,
            RawResourceType.REEDS.name to 0,
            RawResourceType.BEES.name to 0,
            RawResourceType.CEDAR.name to 0,
            RawResourceType.GRANITE.name to 0,
            RawResourceType.IRON.name to 0,
            RawResourceType.TIN.name to 0,
            RawResourceType.COPPER.name to 0,
            RawResourceType.GOLD.name to 0,
            RawResourceType.GEMS.name to 0
        )
    )

    fun get(type: RawResourceType): Int {
        return _resources[type.name] ?: 0
    }

    fun set(type: RawResourceType, amount: Int): MapZoneResources {
        val newResources = _resources.toMutableMap()
        newResources[type.name] = amount
        return MapZoneResources(newResources)
    }

    override fun toJson(): JsonObject {
        val json = JsonObject()
        json.put("resources", JsonObject(_resources))
        return json
    }

    enum class RawResourceType {
        SOIL,
        CATTLE,
        FOWL,
        FISH,
        REEDS,
        BEES,
        CEDAR,
        GRANITE,
        IRON,
        TIN,
        COPPER,
        GOLD,
        GEMS
    }
}