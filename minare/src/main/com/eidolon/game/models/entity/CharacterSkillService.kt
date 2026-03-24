package com.eidolon.game.models.entity

import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.OperationController
import com.minare.core.operation.models.Operation
import com.minare.core.operation.models.OperationType
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

@Singleton
class CharacterSkillService @Inject constructor (
    private val operationController: OperationController
) {
    companion object {
        const val STATUS_DECAY_RATE = 0.001
        const val LEVEL_GAIN_RATE = 0.001
    }

    suspend fun doSkillTurnCalcBefore(character: EvenniaCharacter) {
        skillDecay(character)
        // Concentration regen
        // Health regen
    }

    private suspend fun skillDecay(character: EvenniaCharacter) {
        if (character.skills.isEmpty()) return

        val skillsArray = JsonArray()
        var changed = false

        for (skill in character.skills) {
            if (skill.status > 0.0) {
                changed = true
                val newStatus = (skill.status - STATUS_DECAY_RATE).coerceAtLeast(0.0)
                val newLevel = skill.level + LEVEL_GAIN_RATE
                skillsArray.add(JsonObject()
                    .put("name", skill.name)
                    .put("level", newLevel)
                    .put("status", newStatus)
                    .put("lastUsed", skill.lastUsed)
                )
            } else {
                skillsArray.add(JsonObject()
                    .put("name", skill.name)
                    .put("level", skill.level)
                    .put("status", skill.status)
                    .put("lastUsed", skill.lastUsed)
                )
            }
        }

        if (!changed) return

        val operation = Operation()
            .entity(character._id)
            .entityType(EvenniaCharacter::class)
            .action(OperationType.MUTATE)
            .delta(JsonObject().put("skills", skillsArray))

        operationController.queue(operation)
    }

    suspend fun doSkillTurnCalcDuring(character: EvenniaCharacter) {
        // pass
    }

    suspend fun doSkillTurnCalcAfter(character: EvenniaCharacter) {
        // Status checks
    }
}