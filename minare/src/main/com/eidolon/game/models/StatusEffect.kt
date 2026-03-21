package com.eidolon.game.models

import java.io.Serializable

data class StatusEffect(
    val type: String = "",
    val damage: Int = 0,
    val duration: Long = 0L,
    val appliedAt: Long = 0L,
    val sourceId: String = ""
) : Serializable
