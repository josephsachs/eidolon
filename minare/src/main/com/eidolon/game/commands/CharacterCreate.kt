package com.eidolon.game.commands

import com.eidolon.game.GameEntityFactory
import com.eidolon.game.models.entity.Account
import com.eidolon.game.service.ItemRegistry
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import eidolon.game.controller.GameChannelController
import eidolon.game.models.entity.agent.EvenniaCharacter
import com.eidolon.game.models.Attributes
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

@Singleton
class CharacterCreate @Inject constructor(
    private val entityController: EntityController,
    private val entityFactory: GameEntityFactory,
    private val channelController: GameChannelController,
    private val itemRegistry: ItemRegistry
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
        // Attributes: base 50, +20 per boost, -20 for dump, +10 per dump bonus
        val attrBoosts = characterData.getJsonArray("attr_boosts")?.map { it as String } ?: emptyList()
        val attrDump = characterData.getString("attr_dump", null)
        val attrDumpBoosts = characterData.getJsonArray("attr_dump_boosts")?.map { it as String } ?: emptyList()

        val base = 50
        val boostAmount = 20
        val dumpPenalty = 20
        val dumpBonus = 10

        fun resolve(attr: String): Int {
            var v = base
            if (attr in attrBoosts) v += boostAmount
            if (attr == attrDump) v -= dumpPenalty
            if (attr in attrDumpBoosts) v += dumpBonus
            return v
        }

        character.attributes = Attributes(
            strength = resolve("strength"),
            agility = resolve("agility"),
            toughness = resolve("toughness"),
            intellect = resolve("intellect"),
            imagination = resolve("imagination"),
            discipline = resolve("discipline"),
            charisma = resolve("charisma"),
            empathy = resolve("empathy"),
            wits = resolve("wits")
        )

        // Starting equipment
        val startingEquipment = characterData.getJsonObject("starting_equipment")
        if (startingEquipment != null) {
            val templateId = startingEquipment.getString("template_id", "")
            val template = itemRegistry.get(templateId)
            if (template != null && template.slot.isNotEmpty()) {
                character.equipment = mapOf(template.slot to templateId)
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
