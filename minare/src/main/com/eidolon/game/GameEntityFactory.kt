package com.eidolon.game

import com.eidolon.game.models.entity.Exit
import com.eidolon.game.models.entity.Room
import eidolon.game.models.entity.Game
import com.minare.core.entity.factories.EntityFactory
import com.google.inject.Inject
import com.google.inject.Injector
import eidolon.game.models.entity.agent.EvenniaCharacter
import javax.inject.Singleton

/**
 * Game EntityFactory implementation.
 * Updated to remove dependency injection since Entity is now a pure data class.
 */
@Singleton
class GameEntityFactory : EntityFactory() {
    override val entityTypes = mapOf(
        "Game" to Game::class.java,
        "Room" to Room::class.java,
        "Exit" to Exit::class.java,
        "EvenniaCharacter" to EvenniaCharacter::class.java,
    )
}