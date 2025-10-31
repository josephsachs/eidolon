package com.eidolon.game.models.entity.mapfeature

import com.minare.core.entity.annotations.Child
import com.minare.core.entity.annotations.EntityType
import com.minare.core.entity.annotations.Mutable
import com.minare.core.entity.annotations.State
import com.minare.core.entity.models.Entity

@EntityType("MapFeature")
class MapFeature: Entity() {
    init {
        type = "MapFeature"
    }

    @State
    var location: Pair<Int, Int> = Pair(0, 0)

    @State
    @Mutable
    var name: String = ""

    @State
    var featureType: MapFeatureType = MapFeatureType.TOWN

    @State
    @Child
    var childRef: String = ""

    companion object {
        enum class MapFeatureType(val value: String) {
            HARBOR("harbor"),
            RIVER("river"),
            OASIS("oasis"),
            TOWN("town");

            companion object {
                fun fromString(value: String): MapFeatureType {
                    return MapFeatureType.values().find { it.value == value }
                        ?: throw IllegalArgumentException("Unknown map feature type: $value")
                }
            }

            override fun toString(): String = value
        }
    }
}