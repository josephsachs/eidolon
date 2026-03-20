package com.eidolon.game.commands

import com.eidolon.game.GameEntityFactory
import com.eidolon.game.models.entity.Account
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import eidolon.game.controller.GameChannelController
import eidolon.game.models.entity.agent.EvenniaCharacter
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

@Singleton
class CharacterCreate @Inject constructor(
    private val entityController: EntityController,
    private val entityFactory: GameEntityFactory,
    private val channelController: GameChannelController
) {
    private val log = LoggerFactory.getLogger(CharacterCreate::class.java)

    suspend fun execute(message: JsonObject): JsonObject {
        val accountId = message.getString("account_id")
            ?: return JsonObject().put("status", "error").put("error", "Missing account_id")
        val characterData = message.getJsonObject("character_data")
            ?: return JsonObject().put("status", "error").put("error", "Missing character_data")

        // Load account
        val entities = entityController.findByIds(listOf(accountId))
        val account = entities[accountId] as? Account
            ?: return JsonObject().put("status", "error").put("error", "Account not found: $accountId")

        val defaultChannelId = channelController.getDefaultChannel()
            ?: return JsonObject().put("status", "error").put("error", "No default channel")

        // If account already has characters, delete them (overwrite behavior)
        for (i in 0 until account.characterIds.size()) {
            val oldId = account.characterIds.getString(i)
            log.info("Deleting old character {} for account {}", oldId, accountId)
            entityController.delete(oldId)
        }

        val startingSkills: List<String> = listOf("Swimming", "Climbing", "Pathfinding", "Haggling",
            "Gossip", "Menace", "Investigation", "First Aid", "Dancing", "Meditating", "Hand-to--Hand",
            "Dodge", "Block", "Escape")

        // Create new character
        val character = entityFactory.createEntity(EvenniaCharacter::class.java) as EvenniaCharacter
        character.evenniaName = characterData.getString("name", "")
        character.description = characterData.getString("description", "")
        character.shortDescription = characterData.getString("name", "")
        val skillsJson = characterData.getJsonObject("skills")

        if (skillsJson != null) {
            character.skills = skillsJson.map { (name, value) ->
                var arr = value as? io.vertx.core.json.JsonArray
                com.eidolon.game.models.Skill(
                    name = name,
                    level = arr?.getDouble(0) ?: 0.0,
                    status = arr?.getDouble(1) ?: 0.0
                )
            }

        }
        entityController.create(character)

        // Update account
        val newCharacterIds = JsonArray().add(character._id)
        entityController.saveState(accountId, JsonObject().put("characterIds", newCharacterIds))

        // Add character to default channel so it syncs to Evennia
        channelController.addEntitiesToChannel(listOf(character), defaultChannelId)

        log.info("Created character '{}' (id={}) for account {}", character.evenniaName, character._id, accountId)
        return JsonObject()
            .put("status", "success")
            .put("character", JsonObject()
                .put("_id", character._id)
                .put("evenniaName", character.evenniaName)
                .put("description", character.description)
            )
    }
}
