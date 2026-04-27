const crypto = require('crypto');
const svgCaptcha = require('svg-captcha');

const CAPTCHA_TTL_MS = 5 * 60 * 1000;
const CAPTCHA_MAX_STORE = 5000;

const store = new Map();

function sweep() {
  const now = Date.now();
  for (const [id, entry] of store) {
    if (entry.expiresAt <= now) store.delete(id);
  }
  if (store.size > CAPTCHA_MAX_STORE) {
    const overflow = store.size - CAPTCHA_MAX_STORE;
    let removed = 0;
    for (const id of store.keys()) {
      store.delete(id);
      if (++removed >= overflow) break;
    }
  }
}

function createCaptcha() {
  sweep();
  const c = svgCaptcha.create({
    size: 5,
    noise: 3,
    color: true,
    background: '#0f172a',
    width: 180,
    height: 60,
    ignoreChars: '0o1iIlO',
    fontSize: 56,
  });
  const captchaId = crypto.randomBytes(16).toString('hex');
  store.set(captchaId, {
    text: c.text.toLowerCase(),
    expiresAt: Date.now() + CAPTCHA_TTL_MS,
    attempts: 0,
  });
  return { captchaId, svg: c.data };
}

function verifyCaptcha(captchaId, answer) {
  if (!captchaId || !answer) return false;
  const entry = store.get(captchaId);
  if (!entry) return false;
  if (entry.expiresAt <= Date.now()) {
    store.delete(captchaId);
    return false;
  }
  entry.attempts += 1;
  if (entry.attempts > 5) {
    store.delete(captchaId);
    return false;
  }
  const ok = String(answer).trim().toLowerCase() === entry.text;
  if (ok) store.delete(captchaId);
  return ok;
}

module.exports = { createCaptcha, verifyCaptcha };
