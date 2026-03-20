package eidolon.game.models.entity.agent

import com.eidolon.game.models.Skill
import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.evennia.EvenniaShadow
import com.eidolon.game.evennia.Viewable
import com.google.inject.Inject
import com.minare.controller.EntityController
import com.minare.core.entity.annotations.*
import com.minare.core.entity.models.Entity
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CoroutineScope

@EntityType("EvenniaCharacter")
class EvenniaCharacter: Entity(), Agent, EvenniaShadow, Viewable {
    @Inject
    private lateinit var coroutineScope: CoroutineScope
    @Inject
    private lateinit var entityController: EntityController
    @Inject
    private lateinit var evenniaCommUtils: EvenniaCommUtils

    init {
        type = "EvenniaCharacter"
    }

    @State
    @Mutable
    var evenniaId: String = ""

    @State
    @Mutable
    var evenniaName: String = ""

    @State
    @Mutable
    var description: String = ""

    @State
    @Mutable
    var shortDescription: String = ""

    @State
    @Mutable
    var isNpc: Boolean = false

    @State
    @Mutable
    var brainType: String = ""

    @State
    @Mutable
    var skills: List<Skill> = emptyList()

    /**
     * The Room entity _id the character is currently in.
     */
    @State
    @Mutable
    var currentRoomId: String = ""

    @Property
    var connectionId: String = ""

    @Property
    var lastActivity: Long = 0L

    // --- Viewable interface ---

    override fun project(viewName: String): JsonObject? = when (viewName) {
        "default" -> JsonObject()
            .put("evenniaName", evenniaName)
            .put("currentRoomId", currentRoomId)
            .put("skills", skillsToJson())
        "skills" -> skillsToJson()
        else -> null
    }

    private fun skillsToJson(): JsonObject {
        val obj = JsonObject()
        skills.forEach { skill ->
            obj.put(skill.name, JsonObject()
                .put("level", skill.level)
                .put("status", skill.status)
                .put("lastUsed", skill.lastUsed))
        }
        return obj
    }

    // --- EvenniaShadow interface ---

    override fun shadowEvenniaId(): String = evenniaId

    override fun updateView(): JsonObject {
        return JsonObject()
            .put("evenniaName", evenniaName)
            .put("currentRoomId", currentRoomId)
    }

    // --- Agent interface ---

    override val agentMinareId: String
        get() = _id ?: ""

    override suspend fun say(roomMinareId: String, text: String) {
        if (isNpc) {
            evenniaCommUtils.npcSayInRoom(roomMinareId, _id!!, text)
        } else {
            evenniaCommUtils.sayInRoom(roomMinareId, _id!!, text)
        }
    }

    override suspend fun emote(roomMinareId: String, text: String) {
        if (isNpc) {
            evenniaCommUtils.npcEmoteInRoom(roomMinareId, _id!!, text)
        } else {
            evenniaCommUtils.emoteInRoom(roomMinareId, _id!!, text)
        }
    }
}
