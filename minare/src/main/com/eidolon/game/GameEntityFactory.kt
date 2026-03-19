package com.eidolon.game

import com.eidolon.game.models.entity.Account
import com.eidolon.game.models.entity.Exit
import com.eidolon.game.models.entity.Room
import com.eidolon.game.models.entity.RoomMemory
import eidolon.game.models.entity.Game
import com.minare.core.entity.factories.EntityFactory
import eidolon.game.models.entity.agent.EvenniaCharacter
import javax.inject.Singleton

@Singleton
class GameEntityFactory : EntityFactory() {
    override val entityTypes = mapOf(
        "Game" to Game::class.java,
        "Room" to Room::class.java,
        "RoomMemory" to RoomMemory::class.java,
        "Exit" to Exit::class.java,
        "EvenniaCharacter" to EvenniaCharacter::class.java,
        "Account" to Account::class.java,
    )
}