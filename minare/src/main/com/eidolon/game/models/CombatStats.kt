package com.eidolon.game.models

import java.io.Serializable

data class CombatScores(
    val attack: Double = 0.0,
    val defense: Double = 0.0,
    val control: Double = 0.0
) : Serializable

data class CombatEquilibrium(
    val balance: Double = 50.0,
    val position: Double = 50.0,
    val tempo: Double = 50.0
) : Serializable
