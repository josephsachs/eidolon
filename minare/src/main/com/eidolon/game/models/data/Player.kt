package eidolon.game.models.data

import eidolon.game.models.entity.agent.Agent

class Player {
    var connectionId: String = ""

    var owns: List<Agent> = listOf()
}