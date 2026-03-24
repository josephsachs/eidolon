package com.eidolon.game.scenario

import com.eidolon.game.commands.LinkDomainEntity
import com.eidolon.game.evennia.CrossLinkRegistry
import com.eidolon.game.evennia.EvenniaCommUtils
import com.eidolon.game.service.VendorService
import com.google.inject.Inject
import com.google.inject.Singleton
import com.minare.controller.EntityController
import com.minare.core.entity.factories.EntityFactory
import com.minare.core.storage.interfaces.StateStore
import eidolon.game.controller.GameChannelController
import eidolon.game.models.entity.agent.BrainRegistry
import eidolon.game.models.entity.agent.EvenniaCharacter
import eidolon.game.models.entity.agent.StateConfig
import eidolon.game.models.entity.agent.StateMachineBrain
import eidolon.game.models.entity.agent.StateMachineConfig
import io.vertx.core.Vertx
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Singleton
class NpcInitializer @Inject constructor(
    private val gameChannelController: GameChannelController,
    private val entityFactory: EntityFactory,
    private val entityController: EntityController,
    private val stateStore: StateStore,
    private val evenniaCommUtils: EvenniaCommUtils,
    private val crossLinkRegistry: CrossLinkRegistry,
    private val linkDomainEntity: LinkDomainEntity,
    private val coroutineScope: CoroutineScope,
    private val vertx: Vertx,
    private val vendorService: VendorService,
    private val brainRegistry: BrainRegistry
) {
    private val log = LoggerFactory.getLogger(NpcInitializer::class.java)
    private val initialized = AtomicBoolean(false)

    fun initialize() {
        log.info("NpcInitializer: Registering deferred initialization (waiting for rooms)")
        vertx.eventBus().consumer<JsonObject>(RoomInitializer.ADDRESS_ROOMS_INITIALIZED) { msg ->
            if (initialized.compareAndSet(false, true)) {
                log.info("NpcInitializer: Rooms initialized, starting NPC initialization")
                coroutineScope.launch {
                    try {
                        initializeNpcs()
                    } catch (e: Exception) {
                        log.error("NpcInitializer: Failed to initialize NPCs: $e")
                    }
                }
            }
        }
    }

    private suspend fun initializeNpcs() {
        val defaultChannelId = gameChannelController.getDefaultChannel()
            ?: throw IllegalStateException("Default channel must exist before NpcInitializer runs")

        val npcData = readNpcData()
        if (npcData.isEmpty()) {
            log.warn("NpcInitializer: No NPC data found in npcs.json")
            return
        }

        // Resolve room scenario IDs to Evennia IDs via the room shortDescription
        val roomData = readRoomData()
        val scenarioIdToRoomKey = roomData.associate {
            it.getString("id") to it.getString("shortDescription", "")
        }

        // Build roomKey -> evenniaId lookup from CrossLinkRegistry + EntityController
        val roomKeyToEvenniaId = buildRoomKeyToEvenniaId(scenarioIdToRoomKey)

        // Phase 1: Create NPCs in Evennia via agent commands
        val npcNameToEvenniaId = createEvenniaNpcs(npcData, scenarioIdToRoomKey, roomKeyToEvenniaId)

        // Phase 2: Create EvenniaCharacter entities in Minare
        createMinareEntities(npcData, npcNameToEvenniaId, scenarioIdToRoomKey, roomKeyToEvenniaId, defaultChannelId)

        log.info("NpcInitializer: initialized ${npcNameToEvenniaId.size} NPCs")
    }

    private suspend fun buildRoomKeyToEvenniaId(
        scenarioIdToRoomKey: Map<String, String>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // Find all Room entities in Minare
        val roomKeys = stateStore.findAllKeysForType("Room")
        val rooms = entityController.findByIds(roomKeys)

        for ((id, entity) in rooms) {
            val room = entity as? com.eidolon.game.models.entity.Room ?: continue
            val evenniaId = crossLinkRegistry.getEvenniaId("Room", id)
            if (evenniaId != null && room.shortDescription.isNotEmpty()) {
                result[room.shortDescription] = evenniaId
            }
        }

        return result
    }

    private suspend fun createEvenniaNpcs(
        npcData: List<JsonObject>,
        scenarioIdToRoomKey: Map<String, String>,
        roomKeyToEvenniaId: Map<String, String>
    ): Map<String, String> {
        val npcNameToEvenniaId = mutableMapOf<String, String>()
        val expectedNames = npcData.map { it.getString("name") }.toSet()
        val pendingCount = AtomicInteger(npcData.size)
        val latch = CompletableDeferred<Unit>()

        // Listen for register_evennia_object events for NPC typeclasses
        val consumer = vertx.eventBus().consumer<JsonObject>("eidolon.evennia_object.registered") { msg ->
            val body = msg.body()
            val key = body.getString("key", "")
            val evenniaId = body.getString("evennia_id", "")
            val typeclass = body.getString("typeclass_path", "")
            if (key in expectedNames && evenniaId.isNotEmpty()
                && typeclass.contains("NonplayerCharacter")) {
                npcNameToEvenniaId[key] = evenniaId
                log.info("NPC registered: key=$key, evenniaId=$evenniaId")
                if (pendingCount.decrementAndGet() == 0) {
                    latch.complete(Unit)
                }
            }
        }

        // Build create commands
        val createCommands = mutableListOf<JsonObject>()
        for (npc in npcData) {
            val name = npc.getString("name")
            val roomScenarioId = npc.getString("room", "")
            val roomKey = scenarioIdToRoomKey[roomScenarioId]
            val roomEvenniaId = if (roomKey != null) roomKeyToEvenniaId[roomKey] else null

            if (roomEvenniaId == null) {
                log.warn("NpcInitializer: Room '${roomScenarioId}' not found for NPC '${name}', skipping")
                pendingCount.decrementAndGet()
                continue
            }

            val cmd = JsonObject()
                .put("action", "create_npc")
                .put("npc_name", name)
                .put("room_evennia_id", roomEvenniaId)
            createCommands.add(cmd)
        }

        if (createCommands.isNotEmpty()) {
            log.info("NpcInitializer: Sending batch create for ${createCommands.size} NPCs")
            evenniaCommUtils.sendBatchCommands(createCommands)
        }

        // Wait for all registrations
        try {
            withTimeout(30_000) { latch.await() }
        } catch (e: Exception) {
            log.error("NpcInitializer: Timed out waiting for NPC registrations. " +
                "Received ${npcNameToEvenniaId.size}/${npcData.size}")
        } finally {
            consumer.unregister()
        }

        return npcNameToEvenniaId
    }

    private suspend fun createMinareEntities(
        npcData: List<JsonObject>,
        npcNameToEvenniaId: Map<String, String>,
        scenarioIdToRoomKey: Map<String, String>,
        roomKeyToEvenniaId: Map<String, String>,
        defaultChannelId: String
    ) {
        val characters = mutableListOf<EvenniaCharacter>()

        for (npc in npcData) {
            val name = npc.getString("name")
            val evenniaId = npcNameToEvenniaId[name] ?: continue
            val brainType = npc.getString("brainType", "idle")
            val roomScenarioId = npc.getString("room", "")
            val roomKey = scenarioIdToRoomKey[roomScenarioId] ?: ""
            val roomEvenniaId = roomKeyToEvenniaId.getOrDefault(roomKey, "")

            // Find the Room entity's Minare ID so we can set currentRoomId
            val roomMinareId = if (roomEvenniaId.isNotEmpty()) {
                crossLinkRegistry.getMinareId("Room", roomEvenniaId) ?: ""
            } else ""

            // Create EvenniaCharacter entity
            val character = entityFactory.createEntity(EvenniaCharacter::class.java) as EvenniaCharacter
            entityController.create(character)

            val state = JsonObject()
                .put("evenniaId", evenniaId)
                .put("evenniaName", name)
                .put("description", npc.getString("description", ""))
                .put("shortDescription", npc.getString("shortDescription", ""))
                .put("isNpc", true)
                .put("brainType", brainType)
                .put("currentRoomId", roomMinareId)

            val attrJson = npc.getJsonObject("attributes")
            if (attrJson != null) {
                state.put("attributes", attrJson)
            }

            val equipmentJson = npc.getJsonObject("equipment")
            if (equipmentJson != null) {
                state.put("equipment", equipmentJson)
            }

            entityController.saveState(character._id, state)

            // Link EvenniaObject stub <-> EvenniaCharacter domain entity
            linkDomainEntity.link(evenniaId, character._id!!, "EvenniaCharacter")

            // Register vendor config if applicable
            val vendorJson = npc.getJsonObject("vendor")
            if (vendorJson != null && brainType == "vendor") {
                val buyMenu = vendorJson.getJsonArray("buyMenu")?.map { it as String } ?: emptyList()
                val sellMenu = vendorJson.getJsonArray("sellMenu")?.map { it as String } ?: emptyList()
                val currency = vendorJson.getString("currency", "dollar")
                vendorService.registerVendor(character._id, VendorService.VendorConfig(buyMenu, sellMenu, currency))
            }

            // Register state machine config if applicable
            val smJson = npc.getJsonObject("stateMachine")
            if (smJson != null && brainType == "state_machine") {
                val smBrain = brainRegistry.get("state_machine") as? StateMachineBrain
                if (smBrain != null) {
                    val config = parseStateMachineConfig(smJson)
                    smBrain.registerConfig(character._id, config)
                }
            }

            characters.add(character)
            log.info("Created Minare EvenniaCharacter '${name}' (id=${character._id}, brain=${brainType}, room=${roomMinareId})",)
        }

        if (characters.isNotEmpty()) {
            gameChannelController.addEntitiesToChannel(characters, defaultChannelId)
        }
    }

    private suspend fun readNpcData(): List<JsonObject> {
        return try {
            val buffer = vertx.fileSystem().readFile("scenario/npcs.json").await()
            val array = JsonArray(buffer.toString())
            array.map { it as JsonObject }
        } catch (e: Exception) {
            log.error("Failed to read npcs.json: $e")
            emptyList()
        }
    }

    private suspend fun readRoomData(): List<JsonObject> {
        return try {
            val buffer = vertx.fileSystem().readFile("scenario/rooms.json").await()
            val array = JsonArray(buffer.toString())
            array.map { it as JsonObject }
        } catch (e: Exception) {
            log.error("Failed to read rooms.json: $e")
            emptyList()
        }
    }

    private fun parseStateMachineConfig(json: JsonObject): StateMachineConfig {
        val initial = json.getString("initial")
        val statesJson = json.getJsonObject("states")
        val states = mutableMapOf<String, StateConfig>()

        for (key in statesJson.fieldNames()) {
            val stateJson = statesJson.getJsonObject(key)
            states[key] = StateConfig(
                type = stateJson.getString("type"),
                args = stateJson.getString("args", ""),
                delay = stateJson.getInteger("delay", 1),
                next = stateJson.getString("next", null)
            )
        }

        return StateMachineConfig(initial = initial, states = states)
    }
}
