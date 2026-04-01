package com.eidolon.game.models

import java.io.Serializable

data class CharacterKnowledge(
    val name: String = "",
    val description: String = "",
    val longDescription: String = "",
    val weight: Double = 0.0,
    val tags: List<String> = emptyList()
) : Serializable