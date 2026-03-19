const WebSocket = require('ws');

console.log('=== Evennia Message Round-trip Test ===\n');

// Connect to command socket (UpSocket)
console.log('Connecting to UpSocket (localhost:4225)...');
const upSocket = new WebSocket('ws://localhost:4225/command');

let connectionId = null;
let downSocket = null;
let testMessageId = `test-${Date.now()}`;

upSocket.on('open', () => {
  console.log('✓ UpSocket connected\n');
});

upSocket.on('message', (data) => {
  try {
    const message = JSON.parse(data.toString());

    if (message.type === 'connection_confirm') {
      connectionId = message.connectionId;
      console.log(`✓ Connection confirmed: ${connectionId}\n`);

      // Connect to DownSocket
      connectDownSocket();

      // Send test message after DownSocket connects
      setTimeout(() => {
        sendTestMessage();
      }, 1000);
    }
    else if (message.type === 'ack') {
      console.log('✓ Received ACK from UpSocket:');
      console.log('  ', JSON.stringify(message, null, 2));
      console.log('');
    }
    else if (message.type === 'initial_sync_complete') {
      console.log('✓ Initial sync complete (auto-subscribed to default channel)\n');
    }
    else {
      console.log('UpSocket message:', JSON.stringify(message, null, 2), '\n');
    }
  } catch (err) {
    console.error('Error parsing UpSocket message:', err);
  }
});

upSocket.on('error', (err) => {
  console.error('UpSocket error:', err);
});

upSocket.on('close', (code, reason) => {
  console.log(`UpSocket closed: ${code} ${reason}`);
});

function connectDownSocket() {
  console.log('Connecting to DownSocket (localhost:4226)...');
  downSocket = new WebSocket('ws://localhost:4226/update');

  downSocket.on('open', () => {
    console.log('✓ DownSocket connected');
    console.log('Sending connection ID to DownSocket...');

    // Associate with connection
    downSocket.send(JSON.stringify({
      connectionId: connectionId
    }));
  });

  downSocket.on('message', (data) => {
    try {
      const message = JSON.parse(data.toString());

      if (message.type === 'down_socket_confirm') {
        console.log('✓ DownSocket confirmed\n');
      }
      else if (message.id === testMessageId) {
        console.log('✓ Received response from DownSocket:');
        console.log('  ', JSON.stringify(message, null, 2));
        console.log('\n=== Round-trip complete! ===\n');

        // Clean shutdown
        setTimeout(() => {
          console.log('Test complete, closing connections...');
          upSocket.close();
          downSocket.close();
          process.exit(0);
        }, 500);
      }
      else {
        console.log('DownSocket message:', JSON.stringify(message, null, 2), '\n');
      }
    } catch (err) {
      console.error('Error parsing DownSocket message:', err);
    }
  });

  downSocket.on('error', (err) => {
    console.error('DownSocket error:', err);
  });

  downSocket.on('close', (code, reason) => {
    console.log(`DownSocket closed: ${code} ${reason}`);
  });
}

function sendTestMessage() {
  console.log('Sending test message:');
  const testMessage = {
    id: testMessageId,
    type: 'message',
    message: 'Hello from Evennia test!'
  };
  console.log('  ', JSON.stringify(testMessage, null, 2));
  console.log('');

  upSocket.send(JSON.stringify(testMessage));
}

// Handle process termination
process.on('SIGINT', () => {
  console.log('\nClosing WebSocket connections...');
  if (upSocket.readyState === WebSocket.OPEN) {
    upSocket.close();
  }
  if (downSocket && downSocket.readyState === WebSocket.OPEN) {
    downSocket.close();
  }
  process.exit(0);
});