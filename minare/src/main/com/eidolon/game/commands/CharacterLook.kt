package com.eidolon.game.commands

import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.json.JsonObject

@Singleton
class CharacterLook @Inject constructor(
    var operationController: EntityController,
    private var entityController: EntityController
): EvenniaCommand {

    override suspend fun execute(message: JsonObject): JsonObject {
        // Start an EventStateFlow for skills like detect hidden, spirit sense, etc.
        val entityId = message.getString("minare_id")
        val entity = entityController.findByIds(listOf(entityId))[entityId] as EvenniaCharacter

        // val response = skillOutput(
        //     message,
        //     entity
        // )

        // Send response to downsocket addressed to invoker

        // Unblock with generic ack
        return JsonObject()
    }

    //private fun skillOutput() {
    //     return listOf(
    //        (if (entity.skills.detectHidden > 0) rollDetectHidden(entity)),
    //        (if (entity.skills.detectMagic > 0) rollDetectMagic(entity))
    //     ).toString()
    // }
}