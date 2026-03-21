package com.eidolon.game.models

import java.io.Serializable

enum class HardpointStatus {
    HEALTHY,
    SCRATCHED,
    BRUISED,
    WOUNDED,
    INJURED,
    BROKEN,
    CRITICAL,
    DESTROYED
}

enum class HardpointName {
    HEAD,
    NECK,
    TORSO,
    RIGHT_ARM,
    RIGHT_HAND,
    LEFT_ARM,
    LEFT_HAND,
    RIGHT_LEG,
    LEFT_LEG
}

data class Hardpoint(
    val name: HardpointName = HardpointName.TORSO,
    val hp: Int = 100,
    val status: HardpointStatus = HardpointStatus.HEALTHY
) : Serializable

data class HealthData(
    val hardpoints: List<Hardpoint> = HardpointName.entries.map { Hardpoint(name = it) },
    val vitality: Int = 100,
    val concentration: Int = 100,
    val stamina: Int = 100,
    val luck: Int = 100
) : Serializable
