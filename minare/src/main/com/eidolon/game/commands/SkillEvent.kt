package com.eidolon.game.commands

import com.eidolon.game.models.Skill
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import kotlin.math.floor

@Singleton
class SkillEvent @Inject constructor(
    private val entityController: EntityController
) {
    private val log = LoggerFactory.getLogger(SkillEvent::class.java)

    companion object {
        const val DEFAULT_COOLDOWN_MS = 2000L
    }

    suspend fun execute(message: JsonObject): JsonObject {
        val characterId = message.getString("character_id")
            ?: return JsonObject().put("status", "error").put("error", "Missing character_id")
        val skillName = message.getString("skill_name")
            ?: return JsonObject().put("status", "error").put("error", "Missing skill_name")
        val outcome = message.getString("outcome")
            ?: return JsonObject().put("status", "error").put("error", "Missing outcome")

        val entities = entityController.findByIds(listOf(characterId))
        val character = entities[characterId] as? EvenniaCharacter
            ?: return JsonObject().put("status", "error").put("error", "Character not found: $characterId")

        val now = System.currentTimeMillis()
        val updatedSkills = character.skills.toMutableList()
        val skillIndex = updatedSkills.indexOfFirst { it.name == skillName }
        val oldSkill = if (skillIndex >= 0) updatedSkills[skillIndex] else Skill(name = skillName)

        // Cooldown check
        if (now - oldSkill.lastUsed < DEFAULT_COOLDOWN_MS) {
            return JsonObject().put("status", "cooldown").put("skill_name", skillName)
        }

        val oldLevel = oldSkill.level
        var newLevel = oldLevel
        var newStatus = oldSkill.status

        when (outcome) {
            "success" -> {
                // Moderate status gain
                newStatus = (newStatus + 5.0).coerceAtMost(100.0)
                // Small level gain, modulated by status
                val baseGain = 0.1
                val multiplier = if (newStatus < 50.0) {
                    1.0 + newStatus * 0.01   // up to 1.5x at status 50
                } else {
                    (1.0 - (newStatus - 50.0) * 0.04).coerceAtLeast(0.01)  // throttle hard above 50
                }
                newLevel += baseGain * multiplier
            }
            "failure" -> {
                // No level gain, higher status gain (status decays into level over time)
                newStatus = (newStatus + 7.0).coerceAtMost(100.0)
            }
        }

        val newSkill = Skill(
            name = skillName,
            level = newLevel,
            status = newStatus,
            lastUsed = now
        )

        if (skillIndex >= 0) {
            updatedSkills[skillIndex] = newSkill
        } else {
            updatedSkills.add(newSkill)
        }

        // Persist updated skills
        val skillsArray = JsonArray()
        for (skill in updatedSkills) {
            skillsArray.add(JsonObject()
                .put("name", skill.name)
                .put("level", skill.level)
                .put("status", skill.status)
                .put("lastUsed", skill.lastUsed)
            )
        }
        entityController.saveState(characterId, JsonObject().put("skills", skillsArray))

        val leveledUp = floor(newLevel) > floor(oldLevel)
        if (leveledUp) {
            log.info("Character {} leveled up in {}: {} -> {}", characterId, skillName, oldLevel, newLevel)
        }

        val response = JsonObject()
            .put("status", "success")
            .put("skill_name", skillName)
            .put("new_level", newLevel)
            .put("new_status", newStatus)
        if (leveledUp) {
            response.put("level_up", true)
        }
        return response
    }
}
