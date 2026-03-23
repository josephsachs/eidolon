package com.eidolon.game.models

import java.io.Serializable

data class ItemPrice(
    val currency: String = "",
    val amount: Int = 0
) : Serializable

data class ItemTemplate(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val type: String = "",       // weapon, armor, resource, currency
    val slot: String = "",       // equipment slot (MAIN_HAND, OFF_HAND, HEAD, TORSO, etc.)
    val damage: Int = 0,         // weapon base damage
    val absorption: Int = 0,     // armor damage absorption
    val skill: String = "",      // weapon skill name (e.g. "Blades", "Firearms")
    val price: ItemPrice = ItemPrice(),
    val balanceCost: Int = 5,
    val critModifier: Double = 0.0,
    val weaponType: String = ""
) : Serializable