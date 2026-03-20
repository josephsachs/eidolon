"""
Minare WebSocket Client for Evennia

This module provides a WebSocket client that connects Evennia to the Minare
framework, handling bidirectional communication through UpSocket (commands)
and DownSocket (updates).
"""

import os
import json
import time
from twisted.internet import reactor, protocol
from twisted.internet.protocol import ReconnectingClientFactory
from autobahn.twisted.websocket import WebSocketClientProtocol, WebSocketClientFactory
import evennia
from evennia.utils import logger

# Configuration from environment variables
MINARE_HOST = os.environ.get('MINARE_HOST', 'localhost')
MINARE_UPSOCKET_PORT = int(os.environ.get('MINARE_UPSOCKET_PORT', '4225'))
MINARE_DOWNSOCKET_PORT = int(os.environ.get('MINARE_DOWNSOCKET_PORT', '4226'))
RECONNECT_INTERVAL = int(os.environ.get('MINARE_RECONNECT_INTERVAL', '5'))


class MinareUpSocketProtocol(WebSocketClientProtocol):
    """
    Protocol for UpSocket connection (sending commands to Minare).
    Handles connection confirmation and ACKs.
    """

    def onConnect(self, response):
        logger.log_info(f"Minare UpSocket: Connected to {response.peer}")

    def onOpen(self):
        logger.log_info("Minare UpSocket: Connection opened")
        # Store reference in factory so we can send messages later
        self.factory.active_protocol = self

    def onMessage(self, payload, isBinary):
        if isBinary:
            logger.log_warn("Minare UpSocket: Received unexpected binary message")
            return

        try:
            msg = json.loads(payload.decode('utf8'))
            msg_type = msg.get('type')

            if msg_type == 'connection_confirm':
                connection_id = msg.get('connectionId')
                self.factory.connection_id = connection_id
                logger.log_info(f"Minare UpSocket: Connection confirmed with ID {connection_id}")

                # Now connect the DownSocket
                self.factory.connect_downsocket()

            elif msg_type == 'ack':
                msg_id = msg.get('id')
                logger.log_info(f"Minare UpSocket: Received ACK for message {msg_id}")

            elif msg_type == 'sync':
                entities = msg.get('data', {}).get('entities', [])
                self.factory.pending_sync_entities.extend(entities)
                logger.log_info(f"Minare UpSocket: Sync batch received ({len(entities)} entities)")

            elif msg_type == 'initial_sync_complete':
                logger.log_info("Minare UpSocket: Initial sync complete — starting typeclass sync")
                entities = self.factory.pending_sync_entities[:]
                self.factory.pending_sync_entities.clear()
                _sync_rooms(entities, self)
                _sync_exits(entities, self)
                _ensure_account_vault()
                agent = _ensure_system_agent()
                # Notify Minare that the system agent is ready
                if agent:
                    self.factory.active_protocol.send_message({
                        "type": "system_agent_ready",
                        "agent_evennia_id": str(agent.id),
                        "agent_key": agent.key,
                    })

            elif msg_type == 'heartbeat':
                # Respond to heartbeat
                response = json.dumps({
                    'type': 'heartbeat_response',
                    'timestamp': msg.get('timestamp'),
                    'clientTimestamp': int(time.time() * 1000)
                })
                self.sendMessage(response.encode('utf8'))

            else:
                logger.log_info(f"Minare UpSocket: Received message type: {msg_type}")

            # Check for pending callbacks by request_id
            request_id = msg.get('request_id')
            if request_id and request_id in self.factory.pending_callbacks:
                callback = self.factory.pending_callbacks.pop(request_id)
                try:
                    callback(msg)
                except Exception as cb_err:
                    logger.log_err(f"Minare UpSocket: Callback error for {request_id}: {cb_err}")

        except Exception as e:
            logger.log_err(f"Minare UpSocket: Error processing message: {e}")

    def onClose(self, wasClean, code, reason):
        logger.log_warn(f"Minare UpSocket: Connection closed (clean={wasClean}, code={code}, reason={reason})")
        self.factory.connection_id = None
        self.factory.active_protocol = None

    def send_message(self, message_dict):
        """Send a message to Minare with automatic ID generation if needed."""
        if 'id' not in message_dict:
            message_dict['id'] = f"evennia-{int(time.time() * 1000)}"

        payload = json.dumps(message_dict)
        self.sendMessage(payload.encode('utf8'))
        logger.log_info(f"Minare UpSocket: Sent message {message_dict.get('id')}")


class MinareDownSocketProtocol(WebSocketClientProtocol):
    """
    Protocol for DownSocket connection (receiving updates from Minare).
    Handles responses and broadcasts them to connected players.
    """

    def onConnect(self, response):
        logger.log_info(f"Minare DownSocket: Connected to {response.peer}")

    def onOpen(self):
        logger.log_info("Minare DownSocket: Connection opened")

        # Send connection ID to associate with UpSocket
        if self.factory.connection_id:
            msg = json.dumps({
                'type': 'connection_id',
                'connectionId': self.factory.connection_id
            })
            self.sendMessage(msg.encode('utf8'))
            logger.log_info(f"Minare DownSocket: Sent connection ID {self.factory.connection_id}")
        else:
            logger.log_warn("Minare DownSocket: No connection ID available yet")

    def onMessage(self, payload, isBinary):
        if isBinary:
            logger.log_warn("Minare DownSocket: Received unexpected binary message")
            return

        try:
            msg = json.loads(payload.decode('utf8'))
            msg_type = msg.get('type')

            if msg_type == 'down_socket_confirm':
                logger.log_info("Minare DownSocket: Subscription confirmed")

            elif msg_type == 'update':
                logger.log_info(f"Minare DownSocket: Received entity update: {msg}")
                _handle_entity_updates(msg.get('updates', {}))

            elif msg_type == 'sync':
                # Initial sync data
                logger.log_info("Minare DownSocket: Received sync data")

            elif msg_type == 'error':
                # ADD THIS BLOCK
                error_code = msg.get('code', 'UNKNOWN')
                error_msg = msg.get('message', 'No message')
                logger.log_err(f"Minare DownSocket: Error - code={error_code}, message={error_msg}")
                logger.log_err(f"Minare DownSocket: Full error payload: {msg}")

            elif msg_type == 'message_response':
                # Handle message responses - broadcast to all players
                msg_id = msg.get('id')
                original = msg.get('original_message', '')
                timestamp = msg.get('timestamp')

                logger.log_info(f"Minare DownSocket: Received response for message {msg_id}")

                # Broadcast to all connected players
                broadcast_msg = f"[Minare] Response: {original}"
                evennia.SESSION_HANDLER.announce_all(broadcast_msg)

            elif msg_type == 'set_domain_link':
                _handle_set_domain_link(msg)

            elif msg_type == 'agent_command':
                command = msg.get('command', {})
                logger.log_info(f"Minare DownSocket: Received agent_command: {command.get('action')}")
                _dispatch_agent_command(command)

            elif msg_type == 'heartbeat':
                # Respond to heartbeat
                response = json.dumps({
                    'type': 'heartbeat_response',
                    'timestamp': msg.get('timestamp'),
                    'clientTimestamp': int(time.time() * 1000)
                })
                self.sendMessage(response.encode('utf8'))

            else:
                logger.log_info(f"Minare DownSocket: Received message type: {msg_type}")

        except Exception as e:
            logger.log_err(f"Minare DownSocket: Error processing message: {e}")

    def onClose(self, wasClean, code, reason):
        logger.log_warn(f"Minare DownSocket: Connection closed (clean={wasClean}, code={code}, reason={reason})")


class MinareUpSocketFactory(ReconnectingClientFactory, WebSocketClientFactory):
    """
    Factory for UpSocket connection with automatic reconnection.
    """
    protocol = MinareUpSocketProtocol
    maxDelay = RECONNECT_INTERVAL
    maxRetries = None  # Unlimited retries

    def __init__(self, *args, **kwargs):
        WebSocketClientFactory.__init__(self, *args, **kwargs)
        self.connection_id = None
        self.downsocket_factory = None
        self.active_protocol = None  # Track the active protocol instance
        self.pending_sync_entities = []  # Accumulated during initial sync
        self.pending_callbacks = {}  # request_id -> callback for async responses

    def clientConnectionFailed(self, connector, reason):
        logger.log_warn(f"Minare UpSocket: Connection failed - {reason.getErrorMessage()}")
        ReconnectingClientFactory.clientConnectionFailed(self, connector, reason)

    def clientConnectionLost(self, connector, reason):
        logger.log_warn(f"Minare UpSocket: Connection lost - {reason.getErrorMessage()}")
        ReconnectingClientFactory.clientConnectionLost(self, connector, reason)

    def connect_downsocket(self):
        """Initiate DownSocket connection after UpSocket is established."""
        if self.downsocket_factory:
            downsocket_url = f"ws://{MINARE_HOST}:{MINARE_DOWNSOCKET_PORT}/update"
            self.downsocket_factory.connection_id = self.connection_id
            reactor.connectTCP(MINARE_HOST, MINARE_DOWNSOCKET_PORT, self.downsocket_factory)
            logger.log_info(f"Minare: Connecting DownSocket to {downsocket_url}")


class MinareDownSocketFactory(ReconnectingClientFactory, WebSocketClientFactory):
    """
    Factory for DownSocket connection with automatic reconnection.
    """
    protocol = MinareDownSocketProtocol
    maxDelay = RECONNECT_INTERVAL
    maxRetries = None  # Unlimited retries

    def __init__(self, *args, **kwargs):
        WebSocketClientFactory.__init__(self, *args, **kwargs)
        self.connection_id = None

    def clientConnectionFailed(self, connector, reason):
        logger.log_warn(f"Minare DownSocket: Connection failed - {reason.getErrorMessage()}")
        ReconnectingClientFactory.clientConnectionFailed(self, connector, reason)

    def clientConnectionLost(self, connector, reason):
        logger.log_warn(f"Minare DownSocket: Connection lost - {reason.getErrorMessage()}")
        ReconnectingClientFactory.clientConnectionLost(self, connector, reason)


# Entity types that should sync state to their Evennia counterpart
_SYNC_TYPE_MAP = {
    "Room": ["typeclasses.rooms.Room"],
    "EvenniaCharacter": [
        "typeclasses.characters.PlayerCharacter",
        "typeclasses.characters.NonplayerCharacter",
    ],
}


def _handle_entity_updates(updates):
    """
    Handle entity update messages from Minare DownSocket.
    For entity types in _SYNC_TYPE_MAP, looks up the Evennia object
    by minare_id and writes the delta into db.sim_state.
    """
    for entity_id, update in updates.items():
        entity_type = update.get('type')
        logger.log_info(f"Minare sync: entity_id={entity_id}, type={entity_type}")
        if entity_type not in _SYNC_TYPE_MAP:
            logger.log_info(f"Minare sync: type '{entity_type}' not in _SYNC_TYPE_MAP, skipping")
            continue

        delta = update.get('delta', {})
        if not delta:
            logger.log_info(f"Minare sync: empty delta for {entity_id}, skipping")
            continue

        module_paths = _SYNC_TYPE_MAP[entity_type]
        evennia_obj = None
        for module_path in module_paths:
            evennia_obj = find_evennia_object_by_minare_id_cached(module_path, entity_id)
            if evennia_obj:
                break
        if not evennia_obj:
            logger.log_info(f"Minare sync: no Evennia object found for minare_id={entity_id}")
            continue

        sim_state = evennia_obj.db.sim_state or {}
        sim_state.update(delta)
        evennia_obj.db.sim_state = sim_state
        logger.log_info(f"Minare sync: updated sim_state for {evennia_obj.key}: {sim_state}")


def _handle_set_domain_link(msg):
    """
    Store a domain entity's Minare ID on the corresponding Evennia object.
    This lets _handle_entity_updates match incoming domain entity updates
    to the correct Evennia object.
    """
    evennia_id = msg.get('evennia_id')
    domain_entity_id = msg.get('domain_entity_id')
    domain_entity_type = msg.get('domain_entity_type')
    if not evennia_id or not domain_entity_id:
        return
    try:
        from evennia.objects.models import ObjectDB
        obj = ObjectDB.objects.get(id=int(evennia_id))
        obj.db.minare_domain_id = domain_entity_id
        obj.db.minare_domain_type = domain_entity_type
        logger.log_info(
            f"Minare sync: Set domain link on '{obj.key}' "
            f"(evennia_id={evennia_id}): {domain_entity_type}={domain_entity_id}"
        )
    except Exception as e:
        logger.log_err(f"Minare sync: Failed to set domain link: {e}")


def find_evennia_object_by_minare_id_cached(typeclass_path, minare_id):
    """Find an Evennia object by minare_id, importing the typeclass by path."""
    from django.utils.module_loading import import_string
    try:
        typeclass = import_string(typeclass_path)
    except ImportError:
        logger.log_err(f"Minare sync: Could not import {typeclass_path}")
        return None
    return find_evennia_object_by_minare_id(typeclass, minare_id)


def _sync_rooms(minare_entities, protocol):
    """
    Reconcile Evennia Room objects with Minare Room entities.
    Evennia is authoritative for room descriptions and object graph.
    Minare Room entities are lightweight references — sync only establishes
    cross-links, does NOT overwrite Evennia descriptions.
    """
    from typeclasses.rooms import Room as EvenniaRoom

    minare_rooms = {
        e['_id']: e
        for e in minare_entities
        if e.get('type') == 'Room'
    }

    if not minare_rooms:
        logger.log_info("Minare sync: No Room entities in sync data (rooms created via agent commands)")
        return

    # Build lookup: minare_id attribute -> Evennia Room
    evennia_rooms_by_minare_id = {}
    for room in EvenniaRoom.objects.all():
        mid = room.db.minare_eo_id
        if mid:
            evennia_rooms_by_minare_id[mid] = room

    # Log cross-link status — do NOT create or modify Evennia rooms
    for minare_id, entity in minare_rooms.items():
        if minare_id in evennia_rooms_by_minare_id:
            room = evennia_rooms_by_minare_id[minare_id]
            logger.log_info(f"Minare sync: Room '{room.key}' cross-linked to minare_id={minare_id}")
        else:
            logger.log_info(f"Minare sync: Room minare_id={minare_id} has no Evennia counterpart yet")

    # Register cross-links for all synced rooms
    client = get_minare_client()
    # Rebuild lookup to include newly created rooms
    for room in EvenniaRoom.objects.all():
        mid = room.db.minare_eo_id
        if mid and mid in minare_rooms:
            client.send_message({
                "type": "register_cross_link",
                "entity_type": "Room",
                "minare_id": mid,
                "evennia_id": str(room.id),
            })


def _sync_exits(minare_entities, protocol):
    """
    Reconcile Evennia Exit objects with Minare Exit entities.
    Creates Evennia Exits linking source rooms to destination rooms
    based on the Room.exits state and Exit entity data from Minare.
    """
    from typeclasses.exits import Exit as EvenniaExit
    from typeclasses.rooms import Room as EvenniaRoom
    from evennia import create_object

    # Build lookups
    minare_exits = {
        e['_id']: e
        for e in minare_entities
        if e.get('type') == 'Exit'
    }
    minare_rooms = {
        e['_id']: e
        for e in minare_entities
        if e.get('type') == 'Room'
    }

    # Map minare_id -> Evennia Room
    evennia_rooms_by_minare_id = {}
    for room in EvenniaRoom.objects.all():
        mid = room.db.minare_eo_id
        if mid:
            evennia_rooms_by_minare_id[mid] = room

    # Map minare_id -> Evennia Exit (existing)
    evennia_exits_by_minare_id = {}
    for exit_obj in EvenniaExit.objects.all():
        mid = exit_obj.db.minare_eo_id
        if mid:
            evennia_exits_by_minare_id[mid] = exit_obj

    # For each Room, look at its exits state to find source->exit mappings
    for room_minare_id, room_entity in minare_rooms.items():
        source_evennia_room = evennia_rooms_by_minare_id.get(room_minare_id)
        if not source_evennia_room:
            continue

        exits_map = room_entity.get('state', {}).get('exits', {})
        for direction, exit_minare_id in exits_map.items():
            exit_entity = minare_exits.get(exit_minare_id)
            if not exit_entity:
                continue

            exit_state = exit_entity.get('state', {})
            dest_room_minare_id = exit_state.get('destination', '')
            dest_evennia_room = evennia_rooms_by_minare_id.get(dest_room_minare_id)
            if not dest_evennia_room:
                logger.log_warn(
                    f"Minare sync: Exit '{direction}' destination room {dest_room_minare_id} "
                    f"not found in Evennia, skipping"
                )
                continue

            if exit_minare_id not in evennia_exits_by_minare_id:
                # Create new Evennia Exit
                new_exit = create_object(
                    EvenniaExit,
                    key=direction,
                    location=source_evennia_room,
                    destination=dest_evennia_room,
                )
                new_exit.db.minare_eo_id = exit_minare_id
                new_exit.db.desc = exit_state.get('description', '')
                logger.log_info(
                    f"Minare sync: Created Exit '{direction}' in '{source_evennia_room.key}' "
                    f"-> '{dest_evennia_room.key}'"
                )
            else:
                # Update existing exit if needed
                existing_exit = evennia_exits_by_minare_id[exit_minare_id]
                desc = exit_state.get('description', '')
                if existing_exit.db.desc != desc:
                    existing_exit.db.desc = desc
                    logger.log_info(f"Minare sync: Updated Exit '{direction}' description")

    # Archive exits whose Minare entity no longer exists
    for minare_id, exit_obj in evennia_exits_by_minare_id.items():
        if minare_id not in minare_exits:
            logger.log_info(
                f"Minare sync: Archiving Exit '{exit_obj.key}' "
                f"(minare_id={minare_id} not found in Minare)"
            )
            exit_obj.db.minare_eo_id = None
            exit_obj.db.minare_archived = True

    # Register cross-links for all synced exits
    client = get_minare_client()
    for exit_obj in EvenniaExit.objects.all():
        mid = exit_obj.db.minare_eo_id
        if mid and mid in minare_exits:
            client.send_message({
                "type": "register_cross_link",
                "entity_type": "Exit",
                "minare_id": mid,
                "evennia_id": str(exit_obj.id),
            })


def find_evennia_object_by_minare_id(typeclass, minare_id):
    """Find an Evennia object by minare_domain_id (domain entity) or minare_eo_id (EvenniaObject)."""
    for obj in typeclass.objects.all():
        if obj.db.minare_domain_id == minare_id or obj.db.minare_eo_id == minare_id:
            return obj
    return None


def _dispatch_agent_command(command):
    """Route an agent_command to the appropriate AgentCharacter or NonplayerCharacter.
    If npc_evennia_id is present, routes to that NPC.
    Otherwise routes to AgentCharacter by agent_evennia_id or the default system agent."""
    from typeclasses.characters import AgentCharacter, NonplayerCharacter

    npc_evennia_id = command.get('npc_evennia_id')
    if npc_evennia_id:
        try:
            npc = NonplayerCharacter.objects.get(id=int(npc_evennia_id))
            npc.handle_agent_command(command)
            return
        except (NonplayerCharacter.DoesNotExist, ValueError):
            logger.log_err(f"NonplayerCharacter not found: evennia_id={npc_evennia_id}")
            return

    agent_evennia_id = command.get('agent_evennia_id')
    if agent_evennia_id:
        try:
            agent = AgentCharacter.objects.get(id=int(agent_evennia_id))
        except (AgentCharacter.DoesNotExist, ValueError):
            logger.log_err(f"AgentCharacter not found: evennia_id={agent_evennia_id}")
            return
    else:
        # Default to system agent
        agents = AgentCharacter.objects.all()
        if agents.count() == 0:
            logger.log_err("agent_command: no AgentCharacter available")
            return
        agent = agents[0]

    agent.handle_agent_command(command)


def _ensure_account_vault():
    """Ensure the Account Vault room exists, connected to Limbo."""
    from typeclasses.rooms import Room
    from typeclasses.exits import Exit as EvenniaExit
    from evennia import create_object
    from evennia.objects.models import ObjectDB

    # Check if vault already exists
    for room in Room.objects.all():
        if room.db.account_vault:
            logger.log_info(f"Minare sync: Found existing Account Vault '{room.key}' (id={room.id})")
            return room

    limbo = ObjectDB.objects.get(id=2)
    vault = create_object(Room, key="Account Vault")
    vault.db.desc = "Account objects are stored here."
    vault.db.account_vault = True

    # Limbo -> Vault exit
    create_object(EvenniaExit, key="account vault", location=limbo, destination=vault)
    # Vault -> Limbo exit
    create_object(EvenniaExit, key="dim gray door", location=vault, destination=limbo)

    logger.log_info(f"Minare sync: Created Account Vault (id={vault.id}) connected to Limbo")
    return vault


def _ensure_system_agent():
    """Ensure at least one AgentCharacter exists for Minare to control."""
    from typeclasses.characters import AgentCharacter
    from evennia import create_object
    from evennia.objects.models import ObjectDB

    agents = AgentCharacter.objects.all()
    if agents.count() > 0:
        agent = agents[0]
        # Ensure builder permissions on existing agents
        if "Builder" not in agent.permissions.all():
            agent.permissions.add("Builder")
            logger.log_info(f"Minare sync: Granted Builder permission to '{agent.key}'")
        logger.log_info(f"Minare sync: Found existing AgentCharacter '{agent.key}' (id={agent.id})")
        return agent

    limbo = ObjectDB.objects.get(id=2)
    agent = create_object(AgentCharacter, key="SystemMinareAgent", location=limbo)
    logger.log_info(f"Minare sync: Created AgentCharacter '{agent.key}' (id={agent.id}) in Limbo")
    return agent


class MinareClient:
    """
    Main client manager that coordinates both UpSocket and DownSocket connections.
    """

    def __init__(self):
        self.upsocket_factory = None
        self.downsocket_factory = None
        self.upsocket_connector = None

    def start(self):
        """Start the Minare client connections."""
        logger.log_info("Minare Client: Starting...")

        # Create factories
        upsocket_url = f"ws://{MINARE_HOST}:{MINARE_UPSOCKET_PORT}/command"
        downsocket_url = f"ws://{MINARE_HOST}:{MINARE_DOWNSOCKET_PORT}/update"

        self.upsocket_factory = MinareUpSocketFactory(upsocket_url)
        self.downsocket_factory = MinareDownSocketFactory(downsocket_url)

        # Link them together
        self.upsocket_factory.downsocket_factory = self.downsocket_factory

        # Start UpSocket connection (DownSocket will connect after confirmation)
        self.upsocket_connector = reactor.connectTCP(
            MINARE_HOST,
            MINARE_UPSOCKET_PORT,
            self.upsocket_factory
        )

        logger.log_info(f"Minare Client: Connecting to UpSocket at {upsocket_url}")
        logger.log_info(f"Minare Client: DownSocket will connect to {downsocket_url} after confirmation")

    def stop(self):
        """Stop the Minare client connections."""
        logger.log_info("Minare Client: Stopping...")

        if self.upsocket_connector:
            self.upsocket_connector.disconnect()

        if self.upsocket_factory:
            self.upsocket_factory.stopTrying()

        if self.downsocket_factory:
            self.downsocket_factory.stopTrying()

        logger.log_info("Minare Client: Stopped")

    def send_message(self, message_dict):
        """
        Send a message to Minare through the UpSocket.

        Args:
            message_dict: Dictionary containing the message to send
        """
        if self.upsocket_factory and self.upsocket_factory.active_protocol:
            self.upsocket_factory.active_protocol.send_message(message_dict)
        else:
            logger.log_warn("Minare Client: Not connected, cannot send message")

    def query_entity(self, minare_id, view="default", callback=None):
        """
        Query a Minare entity's state via the entity_query mechanism.

        Args:
            minare_id: The Minare entity ID to query.
            view: Named view projection (default: "default").
            callback: Optional callback for the response.
        """
        msg = {"type": "entity_query", "minare_id": minare_id, "view": view}
        if callback:
            self.send_with_callback(msg, callback)
        else:
            self.send_message(msg)

    def send_with_callback(self, message_dict, callback):
        """
        Send a message to Minare and register a callback for the response.
        The callback will be called with the response message dict when
        a response with the matching request_id arrives.

        Args:
            message_dict: Dictionary containing the message to send.
                          A request_id will be generated if not present.
            callback: Function to call with the response message dict.
        """
        if 'request_id' not in message_dict:
            message_dict['request_id'] = f"evennia-{int(time.time() * 1000)}"
        request_id = message_dict['request_id']
        if self.upsocket_factory:
            self.upsocket_factory.pending_callbacks[request_id] = callback
        self.send_message(message_dict)


# Global client instance
_minare_client = None


def get_minare_client():
    """Get the global Minare client instance."""
    global _minare_client
    if _minare_client is None:
        _minare_client = MinareClient()
    return _minare_client


def start_minare_client():
    """Start the Minare client. Called from at_server_start()."""
    client = get_minare_client()
    client.start()


def stop_minare_client():
    """Stop the Minare client. Called from at_server_stop()."""
    client = get_minare_client()
    client.stop()