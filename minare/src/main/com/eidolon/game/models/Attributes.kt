package com.eidolon.game.models

import java.io.Serializable

data class Attributes(
    val strength: Int = 50,
    val agility: Int = 50,
    val toughness: Int = 50,
    val intellect: Int = 50,
    val imagination: Int = 50,
    val discipline: Int = 50,
    val charisma: Int = 50,
    val empathy: Int = 50,
    val wits: Int = 50
) : Serializable
