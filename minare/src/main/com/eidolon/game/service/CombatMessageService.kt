package com.eidolon.game.service

import com.eidolon.game.models.HardpointName
import com.eidolon.game.models.HitLocationTable
import com.google.inject.Singleton
import kotlin.random.Random

@Singleton
class CombatMessageService {

    private val hitTemplates = mapOf(
        "blade" to listOf(
            "{attacker} slashes {target} across the {location}!",
            "{attacker} drives the blade into {target}'s {location}!",
            "{attacker} cuts {target} on the {location}!",
            "{attacker} carves a line across {target}'s {location}!"
        ),
        "firearm" to listOf(
            "{attacker} shoots {target} in the {location}!",
            "A round from {attacker} tears through {target}'s {location}!",
            "{attacker} puts a bullet in {target}'s {location}!",
            "{attacker} tags {target} in the {location}!"
        ),
        "blunt" to listOf(
            "{attacker} smashes {target}'s {location}!",
            "{attacker} cracks {target} across the {location}!",
            "{attacker} slams into {target}'s {location}!",
            "{attacker} bludgeons {target}'s {location}!"
        ),
        "hand-to-hand" to listOf(
            "{attacker} punches {target} in the {location}!",
            "{attacker} lands a fist on {target}'s {location}!",
            "{attacker} strikes {target}'s {location}!",
            "{attacker} connects with {target}'s {location}!"
        )
    )

    private val missTemplates = mapOf(
        "blade" to listOf(
            "{attacker} swings at {target} but the blade finds only air.",
            "{attacker} slashes wide of {target}.",
            "{attacker}'s blade whistles past {target}."
        ),
        "firearm" to listOf(
            "{attacker} fires at {target} but misses.",
            "A shot from {attacker} goes wide of {target}.",
            "{attacker} squeezes off a round but {target} isn't there."
        ),
        "blunt" to listOf(
            "{attacker} swings at {target} but whiffs.",
            "{attacker}'s blow sails past {target}.",
            "{attacker} overreaches and misses {target}."
        ),
        "hand-to-hand" to listOf(
            "{attacker} swings at {target} but misses.",
            "{attacker} throws a punch at {target} and catches nothing.",
            "{attacker} lunges at {target} but comes up empty."
        )
    )

    private val critHitTemplates = mapOf(
        "blade" to listOf(
            "{attacker}'s blade goes clean through the fleshy part of {target}'s {location}!",
            "{attacker} finds a gap and buries the blade deep in {target}'s {location}!",
            "A vicious cut from {attacker} opens {target}'s {location} wide!"
        ),
        "firearm" to listOf(
            "{attacker} lands a dead-center shot on {target}'s {location}!",
            "A round from {attacker} punches clean through {target}'s {location}!",
            "{attacker} nails {target} square in the {location}!"
        ),
        "blunt" to listOf(
            "{attacker} brings it down with devastating force on {target}'s {location}!",
            "A sickening crack as {attacker} connects squarely with {target}'s {location}!",
            "{attacker} crushes {target}'s {location} with a brutal swing!"
        ),
        "hand-to-hand" to listOf(
            "{attacker} catches {target} flush on the {location}!",
            "{attacker} lands a perfect shot on {target}'s {location}!",
            "A haymaker from {attacker} connects solidly with {target}'s {location}!"
        )
    )

    private val critMissTemplates = listOf(
        "{attacker} badly overcommits against {target}!",
        "{attacker} stumbles, completely off balance!",
        "{attacker} whiffs spectacularly, leaving an opening!"
    )

    fun hitMessage(weaponType: String, attackerName: String, targetName: String, location: HardpointName): String {
        val templates = hitTemplates[weaponType] ?: hitTemplates["hand-to-hand"]!!
        return fill(templates.random(), attackerName, targetName, location)
    }

    fun missMessage(weaponType: String, attackerName: String, targetName: String): String {
        val templates = missTemplates[weaponType] ?: missTemplates["hand-to-hand"]!!
        return fill(templates.random(), attackerName, targetName, null)
    }

    fun critHitMessage(weaponType: String, attackerName: String, targetName: String, location: HardpointName): String {
        val templates = critHitTemplates[weaponType] ?: critHitTemplates["hand-to-hand"]!!
        return fill(templates.random(), attackerName, targetName, location)
    }

    fun critMissMessage(attackerName: String, targetName: String): String {
        return fill(critMissTemplates.random(), attackerName, targetName, null)
    }

    private fun fill(template: String, attacker: String, target: String, location: HardpointName?): String {
        var result = template
            .replace("{attacker}", attacker)
            .replace("{target}", target)
        if (location != null) {
            result = result.replace("{location}", HitLocationTable.locationName(location))
        }
        return result
    }
}
