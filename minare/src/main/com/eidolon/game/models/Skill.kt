package com.eidolon.game.models

import java.io.Serializable

data class Skill(
    val name: String = "",
    val level: Double = 0.0,
    val status: Double = 0.0
) : Serializable
