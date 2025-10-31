package com.chieftain.game.models.entity

import chieftain.game.models.entity.Polity
import com.minare.core.entity.annotations.EntityType
import com.minare.core.entity.annotations.Mutable
import com.minare.core.entity.annotations.State

@EntityType("religion")
class Religion {
    @State
    var name: String = ""

    @State
    @Mutable
    var gods: List<String> = listOf()

    @State
    @Mutable
    var followers: List<Polity> = listOf()

    companion object {
        enum class ReligionStructure {
            HENOTHEISM,
            POLYTHEISM,
            SYNCRETISM,
            MONOTHEISM
        }
    }
}