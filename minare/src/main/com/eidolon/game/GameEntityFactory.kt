package com.eidolon.game

import com.eidolon.game.models.entity.Account
import com.eidolon.game.models.entity.EvenniaObject
import com.eidolon.game.models.entity.Exit
import com.eidolon.game.models.entity.ExplorableExit
import com.eidolon.game.models.entity.Room
import eidolon.game.models.entity.Game
import com.minare.core.entity.factories.EntityFactory
import eidolon.game.models.entity.agent.EvenniaCharacter
import javax.inject.Singleton

@Singleton
class GameEntityFactory : EntityFactory() {
    override val entityTypes = mapOf(
        "Game" to Game::class.java,
        "Room" to Room::class.java,
        "Exit" to Exit::class.java,
        "ExplorableExit" to ExplorableExit::class.java,
        "EvenniaCharacter" to EvenniaCharacter::class.java,
        "EvenniaObject" to EvenniaObject::class.java,
        "Account" to Account::class.java,
    )
}
