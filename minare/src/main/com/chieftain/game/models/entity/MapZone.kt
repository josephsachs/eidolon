package com.chieftain.game.models.entity

import chieftain.game.models.entity.MapZoneResources
import com.chieftain.game.models.data.Depot
import com.minare.core.entity.annotations.EntityType
import com.minare.core.entity.annotations.Mutable
import com.minare.core.entity.annotations.State
import com.minare.core.entity.models.Entity
import com.minare.core.entity.models.serializable.Vector2
import java.io.Serializable

@EntityType("MapZone")
class MapZone: Entity(), Serializable {
    init {
        type = "MapZone"
    }

    @State
    var location: Vector2 = Vector2(0, 0)

    @State
    var terrainType: TerrainType = TerrainType.UNASSIGNED

    @State
    @Mutable
    var depot: Depot = Depot()

    @State
    var resources: MapZoneResources = MapZoneResources()

    companion object {
        enum class TerrainType(val value: String) {
            UNASSIGNED("unassigned"),
            OCEAN("ocean"),
            GRASSLAND("grassland"),
            MEADOW("meadow"),
            SCRUB("scrub"),
            DRYLAND("dryland"),
            WOODLAND("woodland"),
            ROCKLAND("rockland"),
            DESERT("desert"),
            MARSH("marsh");

            companion object {
                fun fromString(value: String): TerrainType {
                    return values().find { it.value == value }
                        ?: throw IllegalArgumentException("Unknown terrain type: $value")
                }
            }

            override fun toString(): String = value
        }
    }
}