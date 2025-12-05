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

            elif msg_type == 'message_response':
                # Handle message responses - broadcast to all players
                msg_id = msg.get('id')
                original = msg.get('original_message', '')
                timestamp = msg.get('timestamp')

                logger.log_info(f"Minare DownSocket: Received response for message {msg_id}")

                # Broadcast to all connected players
                broadcast_msg = f"[Minare] Response: {original}"
                evennia.SESSION_HANDLER.announce_all(broadcast_msg)

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

    def clientConnectionFailed(self, connector, reason):
        logger.log_warn(f"Minare UpSocket: Connection failed - {reason.getErrorMessage()}")
        ReconnectingClientFactory.clientConnectionFailed(self, connector, reason)

    def clientConnectionLost(self, connector, reason):
        logger.log_warn(f"Minare UpSocket: Connection lost - {reason.getErrorMessage()}")
        ReconnectingClientFactory.clientConnectionLost(self, connector, reason)

    def connect_downsocket(self):
        """Initiate DownSocket connection after UpSocket is established."""
        if self.downsocket_factory:
            downsocket_url = f"ws://{MINARE_HOST}:{MINARE_DOWNSOCKET_PORT}"
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
        upsocket_url = f"ws://{MINARE_HOST}:{MINARE_UPSOCKET_PORT}"
        downsocket_url = f"ws://{MINARE_HOST}:{MINARE_DOWNSOCKET_PORT}"

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