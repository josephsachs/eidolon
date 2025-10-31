package com.eidolon.game.scenario

import eidolon.game.models.entity.MapZoneResources
import eidolon.game.models.entity.mapfeature.Town
import com.eidolon.game.models.entity.mapfeature.MapFeature
import com.eidolon.game.controller.GameChannelController
import com.eidolon.game.models.entity.Culture.Companion.CultureGroup
import com.eidolon.game.models.entity.MapZone
import com.eidolon.game.models.entity.MapZone.Companion.TerrainType
import com.eidolon.game.models.entity.mapfeature.MapFeature.Companion.MapFeatureType
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.entity.factories.EntityFactory
import com.minare.core.entity.models.Entity
import com.minare.core.entity.models.serializable.Vector2
import io.vertx.core.Vertx
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await

@Singleton
class MapInitializer @Inject constructor(
    private val gameChannelController: GameChannelController,
    private val entityFactory: EntityFactory,
    private val entityController: EntityController,
    private val vertx: Vertx
) {
    private val log = LoggerFactory.getLogger(MapInitializer::class.java)

    suspend fun initialize() {
        val entities = mutableListOf<Entity>()
        val defaultChannelId = gameChannelController.getDefaultChannel()

        log.info("Set default channel: $defaultChannelId")

        readMapData().forEach { jsonObject ->
            val mapZone = entityFactory.createEntity(MapZone::class.java) as MapZone
            mapZone.location = Vector2(
                jsonObject.getInteger("x"),
                jsonObject.getInteger("y")
            )
            mapZone.terrainType = TerrainType.fromString(jsonObject.getString("terrainType"))

            when (mapZone.terrainType) {
                TerrainType.MEADOW -> {
                    mapZone.resources = mapZone.resources.set(MapZoneResources.RawResourceType.SOIL, 3)
                    mapZone.resources = mapZone.resources.set(MapZoneResources.RawResourceType.FOWL, 2)
                }
                else -> {
                    // Pass
                }
            }

            entityController.create(mapZone) as MapZone
            entities.add(mapZone)
        }

        readFeatureData().forEach { jsonObject ->
            val mapFeature = entityFactory.createEntity(MapFeature::class.java) as MapFeature
            mapFeature.location = Pair(
                jsonObject.getInteger("x"),
                jsonObject.getInteger("y")
            )

            val featureType = MapFeatureType.fromString(jsonObject.getString("featureType"))

            mapFeature.featureType = featureType
            mapFeature.name = jsonObject.getString("name")

            val mapFeatureId = entityController.create(mapFeature)._id

            when (featureType) {
                MapFeatureType.TOWN -> {
                    val town = entityFactory.createEntity(Town::class.java) as Town
                    town.culture = CultureGroup.fromString(jsonObject.getString("culture"))
                    town.mapFeatureRef = mapFeatureId!!

                    mapFeature.childRef = entityController.create(town)._id!!

                    entityController.saveState(
                        mapFeature._id!!,
                        JsonObject().put("childRef", mapFeature.childRef)
                    )

                    entities.add(town)
                }
                else -> {
                    log.warn("Not yet implemented")
                }
            }

            entities.add(mapFeature)
        }

        gameChannelController.addEntitiesToChannel(entities.toList(), defaultChannelId!!)
    }

    private suspend fun readMapData(): List<JsonObject> {
        return try {
            val buffer = vertx.fileSystem().readFile("scenario/mapzones.json").await()

            val mapZonesArray = JsonArray(buffer.toString())
            mapZonesArray.map { it as JsonObject }
        } catch (e: Exception) {
            log.error("Failed to read mapzones.json: $e")
            emptyList()
        }
    }

    private suspend fun readFeatureData(): List<JsonObject> {
        return try {
            val buffer = vertx.fileSystem().readFile("scenario/features.json").await()

            val featuresArray = JsonArray(buffer.toString())
            featuresArray.map { it as JsonObject }
        } catch (e: Exception) {
            log.error("Failed to read features.json: $e")
            emptyList()
        }
    }
}