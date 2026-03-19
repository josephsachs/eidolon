package eidolon.game.models.entity.agent

interface Agent {
    val agentMinareId: String

    suspend fun say(roomMinareId: String, text: String)
    suspend fun emote(roomMinareId: String, text: String)
}
