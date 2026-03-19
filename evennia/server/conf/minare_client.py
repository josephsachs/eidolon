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
                _sync_items(entities, self)

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

            elif msg_type == 'update':
                # Entity update broadcast from channel
                _handle_entity_updates(msg)

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


def _sync_rooms(minare_entities, protocol):
    """
    Reconcile Evennia Room objects with Minare Room entities.
    Minare is the source of truth: create Evennia Rooms for new Minare rooms,
    update descriptions for changed rooms, and archive Evennia Rooms whose
    Minare entity no longer exists.
    """
    from typeclasses.rooms import Room as EvenniaRoom
    from evennia import create_object

    minare_rooms = {
        e['_id']: e
        for e in minare_entities
        if e.get('type') == 'Room'
    }

    # Build lookup: minare_id attribute -> Evennia Room
    evennia_rooms_by_minare_id = {}
    for room in EvenniaRoom.objects.all():
        mid = room.db.minare_id
        if mid:
            evennia_rooms_by_minare_id[mid] = room

    # Create Evennia Rooms for Minare rooms we don't have yet
    for minare_id, entity in minare_rooms.items():
        if minare_id not in evennia_rooms_by_minare_id:
            state = entity.get('state', {})
            new_room = create_object(
                EvenniaRoom,
                key=state.get('shortDescription', f"Room {minare_id[:8]}"),
            )
            new_room.db.minare_id = minare_id
            new_room.db.desc = state.get('description', '')
            logger.log_info(f"Minare sync: Created Evennia Room '{new_room.key}' (minare_id={minare_id})")
        else:
            # Room exists — update description if it has changed
            room = evennia_rooms_by_minare_id[minare_id]
            state = entity.get('state', {})
            if room.db.desc != state.get('description', ''):
                room.db.desc = state.get('description', '')
                logger.log_info(f"Minare sync: Updated Room '{room.key}' description")

    # Archive Evennia Rooms whose Minare entity no longer exists
    for minare_id, room in evennia_rooms_by_minare_id.items():
        if minare_id not in minare_rooms:
            logger.log_info(
                f"Minare sync: Archiving Room '{room.key}' (minare_id={minare_id} not found in Minare)"
            )
            room.db.minare_id = None
            room.db.minare_archived = True


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
        mid = room.db.minare_id
        if mid:
            evennia_rooms_by_minare_id[mid] = room

    # Map minare_id -> Evennia Exit (existing)
    evennia_exits_by_minare_id = {}
    for exit_obj in EvenniaExit.objects.all():
        mid = exit_obj.db.minare_id
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
                new_exit.db.minare_id = exit_minare_id
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
            exit_obj.db.minare_id = None
            exit_obj.db.minare_archived = True


# Pending item creates: name -> Evennia room object.
# Populated by @mcreate, consumed by _handle_item_update when the
# DownSocket broadcast arrives with the newly created Item entity.
_pending_item_creates = {}


def register_pending_create(item_name, location):
    """Register a pending item create so the DownSocket handler knows where to place it."""
    _pending_item_creates[item_name] = location
    logger.log_info(f"Minare sync: Registered pending create for '{item_name}'")


def _sync_items(minare_entities, protocol):
    """
    Reconcile Evennia Item objects with Minare Item entities.
    During initial sync, Items don't carry location — Evennia determines
    placement from Room.contents or pending creates.
    """
    from typeclasses.objects import Item as EvenniaItem
    from evennia import create_object

    minare_items = {
        e['_id']: e
        for e in minare_entities
        if e.get('type') == 'Item'
    }

    if not minare_items:
        logger.log_info("Minare sync: No Item entities to sync")
        return

    # Build lookup: minare_id -> Evennia Item
    evennia_items_by_minare_id = {}
    for item in EvenniaItem.objects.all():
        mid = item.db.minare_id
        if mid:
            evennia_items_by_minare_id[mid] = item

    # Determine item locations from Room.contents in the sync data
    item_to_room = _build_item_room_map(minare_entities)

    # Resolve Evennia rooms by minare_id
    from typeclasses.rooms import Room as EvenniaRoom
    evennia_rooms_by_minare_id = {}
    for room in EvenniaRoom.objects.all():
        mid = room.db.minare_id
        if mid:
            evennia_rooms_by_minare_id[mid] = room

    for minare_id, entity in minare_items.items():
        state = entity.get('state', {})
        room_minare_id = item_to_room.get(minare_id)
        location = evennia_rooms_by_minare_id.get(room_minare_id) if room_minare_id else None

        if minare_id not in evennia_items_by_minare_id:
            item_name = state.get('name', f"Item {minare_id[:8]}")
            new_item = create_object(
                EvenniaItem,
                key=item_name,
                location=location,
            )
            new_item.db.minare_id = minare_id
            new_item.db.desc = state.get('description', '')
            logger.log_info(f"Minare sync: Created Item '{item_name}' (minare_id={minare_id})")
        else:
            item = evennia_items_by_minare_id[minare_id]
            item.key = state.get('name', item.key)
            item.db.desc = state.get('description', '')
            logger.log_info(f"Minare sync: Updated Item '{item.key}'")

    # Archive items whose Minare entity no longer exists
    for minare_id, item in evennia_items_by_minare_id.items():
        if minare_id not in minare_items:
            logger.log_info(
                f"Minare sync: Archiving Item '{item.key}' "
                f"(minare_id={minare_id} not found in Minare)"
            )
            item.db.minare_id = None
            item.db.minare_archived = True


def _build_item_room_map(minare_entities):
    """Build a map of item_id -> room_id from Room.contents arrays."""
    item_to_room = {}
    for entity in minare_entities:
        if entity.get('type') == 'Room':
            room_id = entity['_id']
            contents = entity.get('state', {}).get('contents', [])
            for item_id in contents:
                item_to_room[item_id] = room_id
    return item_to_room


def _handle_entity_updates(msg):
    """
    Handle entity update broadcasts from Minare channels.
    When a new entity appears that Evennia doesn't know about,
    create the corresponding typeclass object.

    Message format:
    {
        "type": "update",
        "timestamp": 123456,
        "updates": {
            "<entity_id>": {
                "type": "Item",
                "_id": "<entity_id>",
                "version": 1,
                "operation": "update",
                "changedAt": 123456,
                "delta": { "name": "...", ... }
            }
        }
    }
    """
    updates = msg.get('updates', {})

    for entity_id, entity_update in updates.items():
        entity_type = entity_update.get('type')
        operation = entity_update.get('operation')
        delta = entity_update.get('delta', {})

        logger.log_info(
            f"Minare DownSocket: Entity update: type={entity_type} "
            f"id={entity_id} op={operation}"
        )

        if entity_type == 'Item':
            _handle_item_update(entity_id, delta, operation)
        # Other entity types can be added here


def _handle_item_update(entity_id, delta, operation):
    """Handle a single Item entity update from Minare."""
    from typeclasses.objects import Item as EvenniaItem
    from evennia import create_object

    # Check if we already have this item
    existing = None
    for item in EvenniaItem.objects.all():
        if item.db.minare_id == entity_id:
            existing = item
            break

    if delta.get('_deleted'):
        if existing:
            logger.log_info(f"Minare sync: Deleting Item '{existing.key}'")
            existing.delete()
        return

    if existing:
        # Update existing item
        name = delta.get('name')
        if name:
            existing.key = name
        desc = delta.get('description')
        if desc is not None:
            existing.db.desc = desc
        logger.log_info(f"Minare sync: Updated Item '{existing.key}'")
    else:
        # New item — check pending creates for location
        item_name = delta.get('name', f"Item {entity_id[:8]}")
        location = _pending_item_creates.pop(item_name, None)

        new_item = create_object(
            EvenniaItem,
            key=item_name,
            location=location,
        )
        new_item.db.minare_id = entity_id
        new_item.db.desc = delta.get('description', '')
        logger.log_info(
            f"Minare sync: Created Item '{item_name}' in "
            f"'{location.key if location else 'nowhere'}' from channel update"
        )


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