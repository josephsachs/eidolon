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
import com.eidolon.game.models.entity.WorkSite
import com.eidolon.game.service.VendorService
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.storage.interfaces.StateStore
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

/**
 * Message handler type: receives the connection, the full message, and
 * a function for sending responses back to the client.
 */
typealias MessageHandler = suspend (
    connection: Connection,
    message: JsonObject,
    reply: suspend (JsonObject) -> Unit
) -> Unit

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
    private val stateStore: StateStore,
) : MessageController() {
    private val log = LoggerFactory.getLogger(GameMessageController::class.java)

    /**
     * Registry of message type -> handler. Populated at construction.
     * New features register here instead of adding branches to a when block.
     */
    private val handlers = mutableMapOf<String, MessageHandler>()

    init {
        // --- Request-response commands (EvenniaCommand pattern) ---
        registerRequestResponse("register_account") { msg -> accountRegister.execute(msg) }
        registerRequestResponse("create_character") { msg -> characterCreate.execute(msg) }
        registerRequestResponse("skill_event") { msg -> skillEvent.execute(msg) }
        registerRequestResponse("entity_query") { msg -> entityQuery.execute(msg) }
        registerRequestResponse("explore") { msg -> exploreCommand.execute(msg) }
        registerRequestResponse("combat_escape") { msg -> combatService.attemptEscape(msg.getString("character_id", "")) }

        registerRequestResponse("skill_cooldown_check") { msg ->
            skillEvent.checkCooldown(
                msg.getString("character_id", ""),
                msg.getString("skill_name", "")
            )
        }

        registerRequestResponse("npc_interact") { msg ->
            val result = npcInteraction.execute(msg)
            if (result.getString("status") == "success") {
                val playerId = msg.getString("player_id", "")
                if (playerId.isNotEmpty()) {
                    skillEvent.execute(JsonObject()
                        .put("character_id", playerId)
                        .put("skill_name", "Smalltalk")
                        .put("outcome", "success"))
                }
            }
            result
        }

        registerRequestResponse("equip_item") { msg -> handleEquip(msg) }
        registerRequestResponse("unequip_item") { msg -> handleUnequip(msg) }
        registerRequestResponse("work_site_join") { msg -> handleWorkSiteJoin(msg) }
        registerRequestResponse("work_site_leave") { msg -> handleWorkSiteLeave(msg) }

        registerRequestResponse("vendor_menu") { msg ->
            val vendorId = msg.getString("vendor_id", "")
            val menuType = msg.getString("menu_type", "buy")
            if (menuType == "sell") vendorService.getSellMenu(vendorId)
            else vendorService.getBuyMenu(vendorId)
        }

        registerRequestResponse("vendor_buy") { msg ->
            val vendorId = msg.getString("vendor_id", "")
            val characterId = msg.getString("character_id", "")
            val itemName = msg.getString("item_name", "")
            val template = itemRegistry.all().values.firstOrNull {
                it.name.equals(itemName, ignoreCase = true)
            }
            if (template != null) {
                val buyResult = vendorService.buyItem(vendorId, characterId, template.id)
                if (buyResult.getBoolean("success", false)) {
                    skillEvent.execute(JsonObject()
                        .put("character_id", characterId)
                        .put("skill_name", "Haggling")
                        .put("outcome", "success"))
                }
                buyResult
            } else {
                JsonObject().put("success", false).put("reason", "Unknown item.")
            }
        }

        registerRequestResponse("vendor_sell") { msg ->
            val vendorId = msg.getString("vendor_id", "")
            val characterId = msg.getString("character_id", "")
            val templateId = msg.getString("template_id", "")
            val result = vendorService.sellItem(vendorId, characterId, templateId)
            if (result.getBoolean("success", false)) {
                skillEvent.execute(JsonObject()
                    .put("character_id", characterId)
                    .put("skill_name", "Haggling")
                    .put("outcome", "success"))
            }
            result
        }

        // --- Fire-and-forget commands ---
        register("room_say") { _, msg, _ -> roomSay.execute(msg) }
        register("room_pose") { _, msg, _ -> roomPose.execute(msg) }
        register("player_disconnect") { _, msg, _ -> playerDisconnect.execute(msg) }
        register("apply_damage") { _, msg, _ -> damageService.applyDamageFromEvennia(msg) }
        register("link_domain_entity") { _, msg, _ -> linkDomainEntity.execute(msg) }
        register("presence") { _, msg, _ -> handlePresence(msg) }

        register("character_moved") { _, msg, _ ->
            combatService.onCharacterMoved(
                msg.getString("character_id", ""),
                msg.getString("new_room_id", "")
            )
        }

        register("combat_attack") { _, msg, _ ->
            val characterId = msg.getString("character_id", "")
            val targetId = msg.getString("target_id", "")
            val roomId = msg.getString("room_id", "")
            val target = entityController.findByIds(listOf(targetId))
                .values.firstOrNull() as? EvenniaCharacter
            if (target != null && (target.dead || target.downed)) {
                combatService.rejectAttack(characterId, targetId, roomId)
            } else {
                val existing = combatService.findCombatInRoom(roomId)
                if (existing != null) {
                    combatService.joinCombat(existing._id!!, characterId)
                    combatService.setAttackMode(characterId, targetId)
                } else {
                    combatService.createCombat(roomId, characterId, targetId)
                }
            }
        }

        register("combat_mode") { _, msg, _ ->
            combatService.setCombatMode(
                msg.getString("character_id", ""),
                msg.getString("mode", "")
            )
        }

        register("register_cross_link") { _, msg, _ ->
            val entityType = msg.getString("entity_type", "")
            val minareId = msg.getString("minare_id", "")
            val evenniaId = msg.getString("evennia_id", "")
            if (entityType.isNotEmpty() && minareId.isNotEmpty() && evenniaId.isNotEmpty()) {
                crossLinkRegistry.link(entityType, minareId, evenniaId)
            } else {
                log.warn("register_cross_link: missing fields — entity_type={}, minare_id={}, evennia_id={}",
                    entityType, minareId, evenniaId)
            }
        }

        // --- Commands with special response handling ---
        register("register_evennia_object") { conn, msg, reply ->
            val requestId = msg.getString("request_id")
            val result = registerEvenniaObject.execute(msg)
            result.put("request_id", requestId)
            result.put("key", msg.getString("key", ""))
            result.put("typeclass_path", msg.getString("typeclass_path", ""))
            reply(result)
            vertx.eventBus().publish("eidolon.evennia_object.registered", result)
        }

        register("system_agent_ready") { _, msg, _ ->
            val agentEvenniaId = msg.getString("agent_evennia_id", "")
            val agentKey = msg.getString("agent_key", "")
            log.info("System agent ready in Evennia: key={}, evenniaId={}", agentKey, agentEvenniaId)
            vertx.eventBus().publish(
                GameConnectionController.ADDRESS_EVENNIA_READY,
                JsonObject()
                    .put("agent_evennia_id", agentEvenniaId)
                    .put("agent_key", agentKey)
            )
        }

        register("command") { conn, msg, reply ->
            val response = JsonObject()
            val requestId = msg.getString("request_id")
            response.put("status", "success")
            response.put("request_id", requestId)
            response.put("message", evenniaCommandHandler.dispatch(msg))
            reply(response)
        }

        register("message") { conn, msg, _ ->
            handleEvenniaMessage(conn, msg)
        }

        // --- Framework-level handlers ---
        register("heartbeat_response") { conn, msg, _ ->
            dispatch(HeartbeatResponse(
                conn,
                msg.getLong("timestamp"),
                msg.getLong("clientTimestamp")
            ))
        }

        register("sync") { conn, msg, _ ->
            val channels = msg.getString("channels").split(",").toSet()
            dispatch(SyncCommand(conn, SyncCommandType.CHANNEL, channels))
        }
    }

    // --- Registration helpers ---

    /**
     * Register a handler for a message type.
     */
    fun register(type: String, handler: MessageHandler) {
        handlers[type] = handler
    }

    /**
     * Register a simple request-response handler. The lambda receives the
     * message and returns a result JsonObject. The request_id is automatically
     * propagated to the response.
     */
    fun registerRequestResponse(type: String, handler: suspend (JsonObject) -> JsonObject) {
        handlers[type] = { conn, msg, reply ->
            val requestId = msg.getString("request_id")
            val result = handler(msg)
            result.put("request_id", requestId)
            reply(result)
        }
    }

    // --- Main dispatch ---

    override suspend fun handle(connection: Connection, message: JsonObject) {
        val type = message.getString("type")
        log.info("MESSAGE_CONTROLLER: {}", message)

        val handler = handlers[type]
        if (handler != null) {
            handler(connection, message) { response ->
                sendToClient(connection, response)
            }
        } else {
            dispatch(OperationCommand(message))
        }
    }

    // --- Private handlers for complex logic ---

    private suspend fun handleEvenniaMessage(connection: Connection, message: JsonObject) {
        val messageId = message.getString("id")
        val messageText = message.getString("message", "")

        log.info("Received Evennia message from {}: id={}, message={}",
            connection.id, messageId, messageText)

        if (messageId != null) {
            val ack = JsonObject()
                .put("type", "ack")
                .put("id", messageId)
            sendToClient(connection, ack)
        }

        val defaultChannelId = channelController.getDefaultChannel()
        if (defaultChannelId != null && messageId != null) {
            val response = JsonObject()
                .put("id", messageId)
                .put("type", "message_response")
                .put("original_message", messageText)
                .put("timestamp", System.currentTimeMillis())
            channelController.broadcast(defaultChannelId, response)
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

        val slot = if (updatedEquipment.containsKey(slotOrItem)) {
            slotOrItem
        } else {
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

    private suspend fun handleWorkSiteJoin(message: JsonObject): JsonObject {
        val characterId = message.getString("character_id", "")
        val roomId = message.getString("room_id", "")

        if (characterId.isEmpty() || roomId.isEmpty()) {
            return JsonObject().put("status", "error").put("error", "Missing character_id or room_id")
        }

        val workSiteKeys = stateStore.findAllKeysForType("WorkSite")
        val workSites = entityController.findByIds(workSiteKeys)
        val workSite = workSites.values
            .filterIsInstance<WorkSite>()
            .firstOrNull { it.roomId == roomId }
            ?: return JsonObject().put("status", "error").put("error", "No work site here")

        workSite.addWorker(characterId)
        log.info("Character {} joined work site '{}'", characterId, workSite.name)

        return JsonObject()
            .put("status", "success")
            .put("work_site_name", workSite.name)
            .put("skill_name", workSite.skillName)
    }

    private suspend fun handleWorkSiteLeave(message: JsonObject): JsonObject {
        val characterId = message.getString("character_id", "")
        val roomId = message.getString("room_id", "")

        if (characterId.isEmpty() || roomId.isEmpty()) {
            return JsonObject().put("status", "error").put("error", "Missing character_id or room_id")
        }

        val workSiteKeys = stateStore.findAllKeysForType("WorkSite")
        val workSites = entityController.findByIds(workSiteKeys)
        val workSite = workSites.values
            .filterIsInstance<WorkSite>()
            .firstOrNull { it.roomId == roomId && characterId in it.workers }
            ?: return JsonObject().put("status", "error").put("error", "You aren't working here")

        workSite.removeWorker(characterId)
        log.info("Character {} left work site '{}'", characterId, workSite.name)

        return JsonObject()
            .put("status", "success")
            .put("work_site_name", workSite.name)
    }

    private suspend fun handlePresence(message: JsonObject) {
        val event = message.getString("event", "")
        val characterId = message.getString("character_id", "")
        val isNpc = message.getBoolean("is_npc", false)
        val roomId = message.getString("room_id", "")

        if (event == "departed" && !isNpc && characterId.isNotEmpty() && roomId.isNotEmpty()) {
            removeWorkerFromSites(characterId, roomId)
        }

        val occupants = message.getJsonArray("occupants") ?: return
        if (occupants.isEmpty) return

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

    private suspend fun removeWorkerFromSites(characterId: String, roomId: String) {
        val workSiteKeys = stateStore.findAllKeysForType("WorkSite")
        if (workSiteKeys.isEmpty()) return

        val workSites = entityController.findByIds(workSiteKeys)
        for ((_, entity) in workSites) {
            val site = entity as? WorkSite ?: continue
            if (site.roomId == roomId && characterId in site.workers) {
                site.removeWorker(characterId)
                log.info("Removed departed player {} from work site '{}'", characterId, site.name)
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
