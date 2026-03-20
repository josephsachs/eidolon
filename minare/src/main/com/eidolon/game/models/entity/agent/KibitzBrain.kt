package eidolon.game.models.entity.agent

import com.eidolon.game.models.entity.Room
import com.eidolon.game.models.entity.RoomMemory
import com.minare.controller.EntityController
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

class KibitzBrain(
    private val entityController: EntityController
) : Brain {
    private val log = LoggerFactory.getLogger(KibitzBrain::class.java)

    override val brainType: String = "kibitz"

    override suspend fun onTurn(character: EvenniaCharacter, phase: String, context: JsonObject) {
        if (phase != "AFTER") return

        val roomId = character.currentRoomId
        if (roomId.isEmpty()) return

        val roomEntities = entityController.findByIds(listOf(roomId))
        val room = roomEntities[roomId] as? Room ?: return

        val memoryId = room.roomMemoryId
        if (memoryId.isEmpty()) return

        val memoryEntities = entityController.findByIds(listOf(memoryId))
        val memory = memoryEntities[memoryId] as? RoomMemory ?: return

        val echoCount = memory.echoes.size()
        if (echoCount > 3) {
            character.say(roomId, "Argh! My bloody hearing!")
            log.info("KibitzBrain: {} complained about {} echoes", character.evenniaName, echoCount)
        }
    }

    override suspend fun onPlayerInteraction(character: EvenniaCharacter, interaction: JsonObject) {
        val roomId = character.currentRoomId
        if (roomId.isEmpty()) return

        character.say(roomId, "Can't you see I'm trying to listen?!")
    }
}
