'use strict';

const crypto = require('crypto');
const fs     = require('fs');
const path   = require('path');

const SECRET_FILE = path.join(__dirname, '.jwt_secret');
const DATE_FILE   = path.join(__dirname, '.jwt_secret_date');

function todayString() {
    return new Date().toISOString().slice(0, 10);
}

function generateSecret() {
    return crypto.randomBytes(64).toString('hex');
}

function loadOrCreate() {
    const today = todayString();

    try {
        const storedDate = fs.existsSync(DATE_FILE)
            ? fs.readFileSync(DATE_FILE, 'utf8').trim()
            : null;

        if (storedDate === today && fs.existsSync(SECRET_FILE)) {
            const existing = fs.readFileSync(SECRET_FILE, 'utf8').trim();
            if (existing && existing.length >= 64) {
                return existing;
            }
        }
    } catch (_) {}

    const secret = generateSecret();
    try {
        fs.writeFileSync(SECRET_FILE, secret, { mode: 0o600 });
        fs.writeFileSync(DATE_FILE, today, { mode: 0o600 });
    } catch (e) {
        console.warn('[JWT] Could not persist secret to disk:', e.message);
    }
    console.log('[JWT] New secret generated for', today);
    return secret;
}

let _current = loadOrCreate();

function rotate() {
    _current = generateSecret();
    const today = todayString();
    try {
        fs.writeFileSync(SECRET_FILE, _current, { mode: 0o600 });
        fs.writeFileSync(DATE_FILE, today, { mode: 0o600 });
    } catch (e) {
        console.warn('[JWT] Could not persist rotated secret:', e.message);
    }
    console.log('[JWT] Secret rotated for', today);
}

function scheduleDailyRotation() {
    const now   = new Date();
    const next  = new Date(now);
    next.setUTCHours(0, 0, 0, 0);
    next.setUTCDate(next.getUTCDate() + 1);
    const msUntilMidnight = next.getTime() - now.getTime();

    setTimeout(() => {
        rotate();
        setInterval(rotate, 24 * 60 * 60 * 1000);
    }, msUntilMidnight);

    console.log(`[JWT] Next rotation in ${Math.round(msUntilMidnight / 3600000 * 10) / 10}h (UTC midnight)`);
}

scheduleDailyRotation();

function getJwtSecret() {
    return _current;
}

module.exports = { getJwtSecret };
