package com.eidolon.game.models.entity

import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.OperationController
import eidolon.game.models.entity.agent.EvenniaCharacter

@Singleton
class CharacterSkillService @Inject constructor (
    private val operationController: OperationController
) {
    fun doSkillTurnCalcBefore(character: EvenniaCharacter) {
        skillDecay(character)
        // Concentration regen
        // Health regen
    }

    private fun skillDecay(character: EvenniaCharacter): EvenniaCharacter {
        // Skill decay in which the status of the skill will degrade 0.001
        // and the level will increase 0.001 or `multiplied by
        // character.???something`

        return character
    }

    fun doSkillTurnCalcDuring(character: EvenniaCharacter) {
        // pass
    }

    fun doSkillTurnCalcAfter(character: EvenniaCharacter) {
        // Status checks
    }
}