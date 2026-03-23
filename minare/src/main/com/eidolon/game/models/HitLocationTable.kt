package com.eidolon.game.models

import kotlin.random.Random

object HitLocationTable {
    private val weights: List<Pair<HardpointName, Int>> = listOf(
        HardpointName.HEAD to 3,
        HardpointName.NECK to 2,
        HardpointName.TORSO to 25,
        HardpointName.RIGHT_ARM to 12,
        HardpointName.LEFT_ARM to 12,
        HardpointName.RIGHT_HAND to 4,
        HardpointName.LEFT_HAND to 4,
        HardpointName.RIGHT_LEG to 19,
        HardpointName.LEFT_LEG to 19
    )

    private val totalWeight = weights.sumOf { it.second }

    fun roll(): HardpointName {
        var roll = Random.nextInt(totalWeight)
        for ((name, weight) in weights) {
            roll -= weight
            if (roll < 0) return name
        }
        return HardpointName.TORSO
    }

    fun locationName(hp: HardpointName): String = when (hp) {
        HardpointName.HEAD -> "head"
        HardpointName.NECK -> "neck"
        HardpointName.TORSO -> "torso"
        HardpointName.RIGHT_ARM -> "right arm"
        HardpointName.LEFT_ARM -> "left arm"
        HardpointName.RIGHT_HAND -> "right hand"
        HardpointName.LEFT_HAND -> "left hand"
        HardpointName.RIGHT_LEG -> "right leg"
        HardpointName.LEFT_LEG -> "left leg"
    }
}
