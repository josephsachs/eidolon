package chieftain.game.models.data

import chieftain.game.models.entity.agent.Agent

class Player {
    var connectionId: String = ""

    var owns: List<Agent> = listOf()
}