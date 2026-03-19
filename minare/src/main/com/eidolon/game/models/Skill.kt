package com.eidolon.game.models

import java.io.Serializable

data class Skill(
    val current: Double = 0.0,
    val potential: Double = 0.0
) : Serializable
