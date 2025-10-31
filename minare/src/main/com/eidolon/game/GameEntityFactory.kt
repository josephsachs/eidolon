package com.eidolon.game

import eidolon.game.models.entity.agent.Clan
import eidolon.game.models.entity.Game
import eidolon.game.models.entity.mapfeature.Town
import com.minare.core.entity.factories.EntityFactory
import com.minare.core.entity.models.Entity
import com.eidolon.game.models.entity.MapZone
import com.eidolon.game.models.entity.mapfeature.MapFeature
import com.google.inject.Inject
import com.google.inject.Injector
import javax.inject.Singleton

/**
 * Game EntityFactory implementation.
 * Updated to remove dependency injection since Entity is now a pure data class.
 */
@Singleton
class GameEntityFactory @Inject constructor(
    injector: Injector
): EntityFactory(injector) {
    // Just define the map - framework does the rest!
    override val entityTypes = mapOf(
        "Game" to Game::class.java,
        "MapZone" to MapZone::class.java,
        "MapFeature" to MapFeature::class.java,
        "Town" to Town::class.java,
        "Clan" to Clan::class.java,
        "Entity" to Entity::class.java
    )
}