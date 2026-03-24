package eidolon.game.models.entity.agent

import com.eidolon.game.service.ItemRegistry
import com.minare.controller.EntityController
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

class VendorBrain(
    private val entityController: EntityController,
    private val itemRegistry: ItemRegistry
) : Brain {
    private val log = LoggerFactory.getLogger(VendorBrain::class.java)

    override val brainType: String = "vendor"

    override suspend fun onTurn(character: EvenniaCharacter, phase: String, context: JsonObject) {
        // Vendors don't act on turns
    }

    override suspend fun onPlayerInteraction(character: EvenniaCharacter, interaction: JsonObject) {
        // Handled via buy/sell commands routed through GameMessageController
    }
}
