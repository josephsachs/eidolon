package com.eidolon.game.controller

import com.eidolon.game.commands.AccountRegister
import com.eidolon.game.commands.CharacterCreate
import com.eidolon.game.commands.EntityQuery
import com.eidolon.game.commands.ExploreCommand
import com.eidolon.game.commands.LinkDomainEntity
import com.eidolon.game.commands.NpcInteraction
import com.eidolon.game.commands.PlayerDisconnect
import com.eidolon.game.commands.RegisterEvenniaObject
import com.eidolon.game.commands.RoomPose
import com.eidolon.game.commands.RoomSay
import com.eidolon.game.commands.SkillEvent
import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommandHandler
import com.eidolon.game.service.CombatService
import com.eidolon.game.service.DamageService
import com.eidolon.game.service.ItemRegistry
import com.eidolon.game.service.VendorService
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import eidolon.game.models.entity.agent.BrainRegistry
import eidolon.game.models.entity.agent.EvenniaCharacter
import com.minare.controller.MessageController
import com.minare.core.transport.models.Connection
import com.minare.core.transport.models.message.HeartbeatResponse
import com.minare.core.transport.models.message.OperationCommand
import com.minare.core.transport.models.message.SyncCommand
import com.minare.core.transport.models.message.SyncCommandType
import com.minare.core.transport.upsocket.UpSocketVerticle
import eidolon.game.controller.GameChannelController
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory

@Singleton
class GameMessageController @Inject constructor(
    private val vertx: Vertx,
    private val evenniaCommandHandler: EvenniaCommandHandler,
    private val channelController: GameChannelController,
    private val crossLinkRegistry: CrossLinkRegistry,
    private val entityQuery: EntityQuery,
    private val accountRegister: AccountRegister,
    private val characterCreate: CharacterCreate,
    private val roomSay: RoomSay,
    private val roomPose: RoomPose,
    private val playerDisconnect: PlayerDisconnect,
    private val registerEvenniaObject: RegisterEvenniaObject,
    private val skillEvent: SkillEvent,
    private val linkDomainEntity: LinkDomainEntity,
    private val npcInteraction: NpcInteraction,
    private val exploreCommand: ExploreCommand,
    private val damageService: DamageService,
    private val combatService: CombatService,
    private val entityController: EntityController,
    private val brainRegistry: BrainRegistry,
    private val itemRegistry: ItemRegistry,
    private val vendorService: VendorService,
) : MessageController() {
    private val log = LoggerFactory.getLogger(GameMessageController::class.java)

    override suspend fun handle(connection: Connection, message: JsonObject) {
        log.info("MESSAGE_CONTROLLER: ${message}")

        when {
            message.getString("type") == "heartbeat_response" -> {
                val heartbeatCommand = HeartbeatResponse(
                    connection,
                    message.getLong("timestamp"),
                    message.getLong("clientTimestamp")
                )

                dispatch(heartbeatCommand)
            }

            message.getString("type") == "sync" -> {
                val channels = message.getString("channels").split(",").toSet()

                val syncCommand = SyncCommand(
                    connection,
                    SyncCommandType.CHANNEL,
                    channels    // null = sync all
                )

                dispatch(syncCommand)
            }

            message.getString("type") == "register_account" -> {
                val requestId = message.getString("request_id")
                val result = accountRegister.execute(message)
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "create_character" -> {
                val requestId = message.getString("request_id")
                val result = characterCreate.execute(message)
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "room_say" -> {
                roomSay.execute(message)
            }

            message.getString("type") == "room_pose" -> {
                roomPose.execute(message)
            }

            message.getString("type") == "player_disconnect" -> {
                playerDisconnect.execute(message)
            }

            message.getString("type") == "skill_event" -> {
                val requestId = message.getString("request_id")
                val result = skillEvent.execute(message)
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "entity_query" -> {
                val requestId = message.getString("request_id")
                val result = entityQuery.execute(message)
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "npc_interact" -> {
                val requestId = message.getString("request_id")
                val result = npcInteraction.execute(message)
                if (result.getString("status") == "success") {
                    val playerId = message.getString("player_id", "")
                    if (playerId.isNotEmpty()) {
                        skillEvent.execute(JsonObject()
                            .put("character_id", playerId)
                            .put("skill_name", "Smalltalk")
                            .put("outcome", "success"))
                    }
                }
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "explore" -> {
                val requestId = message.getString("request_id")
                val result = exploreCommand.execute(message)
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "combat_attack" -> {
                val characterId = message.getString("character_id", "")
                val targetId = message.getString("target_id", "")
                val roomId = message.getString("room_id", "")
                val existing = combatService.findCombatInRoom(roomId)
                if (existing != null) {
                    combatService.joinCombat(existing._id!!, characterId)
                    // Set attacker mode
                    combatService.setAttackMode(characterId, targetId)
                } else {
                    combatService.createCombat(roomId, characterId, targetId)
                }
            }

            message.getString("type") == "combat_mode" -> {
                val characterId = message.getString("character_id", "")
                val mode = message.getString("mode", "")
                combatService.setCombatMode(characterId, mode)
            }

            message.getString("type") == "combat_escape" -> {
                val requestId = message.getString("request_id")
                val characterId = message.getString("character_id", "")
                val result = combatService.attemptEscape(characterId)
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "character_moved" -> {
                val characterId = message.getString("character_id", "")
                val newRoomId = message.getString("new_room_id", "")
                combatService.onCharacterMoved(characterId, newRoomId)
            }

            message.getString("type") == "presence" -> {
                handlePresence(message)
            }

            message.getString("type") == "equip_item" -> {
                val requestId = message.getString("request_id")
                val result = handleEquip(message)
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "unequip_item" -> {
                val requestId = message.getString("request_id")
                val result = handleUnequip(message)
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "vendor_menu" -> {
                val requestId = message.getString("request_id")
                val vendorId = message.getString("vendor_id", "")
                val menuType = message.getString("menu_type", "buy")
                val result = if (menuType == "sell") {
                    vendorService.getSellMenu(vendorId)
                } else {
                    vendorService.getBuyMenu(vendorId)
                }
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "vendor_buy" -> {
                val requestId = message.getString("request_id")
                val vendorId = message.getString("vendor_id", "")
                val characterId = message.getString("character_id", "")
                val itemName = message.getString("item_name", "")
                // Resolve item name to template ID
                val template = itemRegistry.all().values.firstOrNull {
                    it.name.equals(itemName, ignoreCase = true)
                }
                val result = if (template != null) {
                    val buyResult = vendorService.buyItem(vendorId, characterId, template.id)
                    if (buyResult.getBoolean("success", false)) {
                        skillEvent.execute(JsonObject()
                            .put("character_id", characterId)
                            .put("skill_name", "Haggling")
                            .put("outcome", "success"))
                    }
                    buyResult
                } else {
                    io.vertx.core.json.JsonObject().put("success", false).put("reason", "Unknown item.")
                }
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "vendor_sell" -> {
                val requestId = message.getString("request_id")
                val vendorId = message.getString("vendor_id", "")
                val characterId = message.getString("character_id", "")
                val templateId = message.getString("template_id", "")
                val result = vendorService.sellItem(vendorId, characterId, templateId)
                if (result.getBoolean("success", false)) {
                    skillEvent.execute(JsonObject()
                        .put("character_id", characterId)
                        .put("skill_name", "Haggling")
                        .put("outcome", "success"))
                }
                result.put("request_id", requestId)
                sendToClient(connection, result)
            }

            message.getString("type") == "apply_damage" -> {
                damageService.applyDamageFromEvennia(message)
            }

            message.getString("type") == "link_domain_entity" -> {
                linkDomainEntity.execute(message)
            }

            message.getString("type") == "register_cross_link" -> {
                val entityType = message.getString("entity_type", "")
                val minareId = message.getString("minare_id", "")
                val evenniaId = message.getString("evennia_id", "")
                if (entityType.isNotEmpty() && minareId.isNotEmpty() && evenniaId.isNotEmpty()) {
                    crossLinkRegistry.link(entityType, minareId, evenniaId)
                } else {
                    log.warn("register_cross_link: missing fields — entity_type={}, minare_id={}, evennia_id={}",
                        entityType, minareId, evenniaId)
                }
            }

            message.getString("type") == "register_evennia_object" -> {
                val requestId = message.getString("request_id")
                val result = registerEvenniaObject.execute(message)
                result.put("request_id", requestId)
                result.put("key", message.getString("key", ""))
                result.put("typeclass_path", message.getString("typeclass_path", ""))
                sendToClient(connection, result)

                // Publish to event bus so RoomInitializer (and others) can react
                vertx.eventBus().publish("eidolon.evennia_object.registered", result)
            }

            message.getString("type") == "system_agent_ready" -> {
                val agentEvenniaId = message.getString("agent_evennia_id", "")
                val agentKey = message.getString("agent_key", "")
                log.info("System agent ready in Evennia: key={}, evenniaId={}", agentKey, agentEvenniaId)
                vertx.eventBus().publish(
                    GameConnectionController.ADDRESS_EVENNIA_READY,
                    JsonObject()
                        .put("agent_evennia_id", agentEvenniaId)
                        .put("agent_key", agentKey)
                )
            }

            message.getString("type") == "command" -> {
                val response = JsonObject()
                val requestId = message.getString("request_id")

                response.put("status", "success")
                response.put("request_id", requestId)
                response.put("message",
                    evenniaCommandHandler.dispatch(message)
                )

                sendToClient(connection, response)
            }

            // Handle Evennia-style system messages for tracer bullet
            message.getString("type") == "message" -> {
                handleEvenniaMessage(connection, message)
            }

            else -> {
                val operationCommand = OperationCommand(message)

                dispatch(operationCommand)
            }
        }
    }

    /**
     * Handle Evennia-style messages: {"id": "xyz", "type": "message", "message": "..."}
     *
     * Flow:
     * 1. Send ACK immediately to unblock Evennia
     * 2. Send response to DownSocket with correlation ID
     */
    private suspend fun handleEvenniaMessage(connection: Connection, message: JsonObject) {
        val messageId = message.getString("id")
        val messageText = message.getString("message", "")

        log.info("Received Evennia message from {}: id={}, message={}",
            connection.id, messageId, messageText)

        // Send ACK immediately to unblock Evennia
        if (messageId != null) {
            val ack = JsonObject()
                .put("type", "ack")
                .put("id", messageId)

            sendToClient(connection, ack)
            log.debug("Sent ACK for message {} to connection {}", messageId, connection.id)
        }

        // Send response through DownSocket via channel broadcast
        // Connection is already subscribed to default channel via onClientFullyConnected
        val defaultChannelId = channelController.getDefaultChannel()

        if (defaultChannelId != null && messageId != null) {
            val response = JsonObject()
                .put("id", messageId)
                .put("type", "message_response")
                .put("original_message", messageText)
                .put("timestamp", System.currentTimeMillis())

            channelController.broadcast(defaultChannelId, response)
            log.debug("Broadcast response for message {} to channel {}", messageId, defaultChannelId)
        } else {
            log.warn("Cannot send response: defaultChannelId={}, messageId={}", defaultChannelId, messageId)
        }
    }

    private suspend fun handleEquip(message: JsonObject): JsonObject {
        val characterId = message.getString("character_id", "")
        val templateId = message.getString("template_id", "")

        val template = itemRegistry.get(templateId)
            ?: return JsonObject().put("success", false).put("reason", "unknown item")

        if (template.slot.isEmpty())
            return JsonObject().put("success", false).put("reason", "not equippable")

        val entities = entityController.findByIds(listOf(characterId))
        val character = entities[characterId] as? EvenniaCharacter
            ?: return JsonObject().put("success", false).put("reason", "character not found")

        val updatedEquipment = character.equipment.toMutableMap()
        updatedEquipment[template.slot] = templateId

        entityController.saveState(characterId, JsonObject()
            .put("equipment", JsonObject(updatedEquipment as Map<String, Any>)))

        return JsonObject()
            .put("success", true)
            .put("slot", template.slot)
            .put("item_name", template.name)
    }

    private suspend fun handleUnequip(message: JsonObject): JsonObject {
        val characterId = message.getString("character_id", "")
        val slotOrItem = message.getString("slot_or_item", "").uppercase()

        val entities = entityController.findByIds(listOf(characterId))
        val character = entities[characterId] as? EvenniaCharacter
            ?: return JsonObject().put("success", false).put("reason", "character not found")

        val updatedEquipment = character.equipment.toMutableMap()

        // Try as slot name first
        val slot = if (updatedEquipment.containsKey(slotOrItem)) {
            slotOrItem
        } else {
            // Try to find by template name (exact or partial match)
            updatedEquipment.entries.firstOrNull { (_, templateId) ->
                if (templateId.isEmpty()) return@firstOrNull false
                val template = itemRegistry.get(templateId) ?: return@firstOrNull false
                template.name.equals(slotOrItem, ignoreCase = true)
                    || template.name.contains(slotOrItem, ignoreCase = true)
            }?.key
        }

        if (slot == null || updatedEquipment[slot].isNullOrEmpty())
            return JsonObject().put("success", false).put("reason", "Nothing equipped there.")

        val templateId = updatedEquipment[slot]!!
        val template = itemRegistry.get(templateId)
        updatedEquipment[slot] = ""

        entityController.saveState(characterId, JsonObject()
            .put("equipment", JsonObject(updatedEquipment as Map<String, Any>)))

        return JsonObject()
            .put("success", true)
            .put("slot", slot)
            .put("item_name", template?.name ?: templateId)
    }

    private suspend fun handlePresence(message: JsonObject) {
        val occupants = message.getJsonArray("occupants") ?: return
        if (occupants.isEmpty) return

        // Collect all NPC IDs from the occupants list
        val npcIds = (0 until occupants.size())
            .map { occupants.getJsonObject(it) }
            .filter { it.getBoolean("is_npc", false) }
            .map { it.getString("id") }

        if (npcIds.isEmpty()) return

        val entities = entityController.findByIds(npcIds)
        for ((id, entity) in entities) {
            val character = entity as? EvenniaCharacter ?: continue
            if (character.brainType.isEmpty()) continue
            val brain = brainRegistry.get(character.brainType) ?: continue
            try {
                brain.onPresence(character, message)
            } catch (e: Exception) {
                log.error("Brain '${character.brainType}' onPresence error for ${character.evenniaName}: ${e.message}")
            }
        }
    }

    private fun sendToClient(connection: Connection, message: JsonObject) {
        val deploymentId = connection.upSocketInstanceId ?: run {
            log.warn("Cannot send to connection {}: no upSocketInstanceId", connection.id)
            return
        }
        vertx.eventBus().send(
            "${UpSocketVerticle.ADDRESS_SEND_TO_CONNECTION}.${deploymentId}",
            JsonObject()
                .put("connectionId", connection.id)
                .put("message", message)
        )
    }
}