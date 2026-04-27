const express = require('express');
const jwt = require('jsonwebtoken');
const crypto = require('crypto');
const User = require('../models/User');
const { getJwtSecret } = require('../jwtSecret');
const { verifyCaptcha } = require('../utils/captcha');

const router = express.Router();

function userPayload(user) {
  return {
    id: user._id,
    accessId: user.accessId,
    email: user.email,
    name: user.name,
    role: user.role,
    tier: user.tier,
    trialStartDate: user.trialStartDate,
    trialEndDate: user.trialEndDate,
    paidUntil: user.paidUntil,
    trialDaysLeft: user.trialDaysLeft(),
    isTrialActive: user.isTrialActive(),
    subscription: user.subscriptionStatus(),
  };
}

router.post('/register', async (req, res) => {
  try {
    const { email, password, name, licenseAccepted, captchaId, captcha } = req.body || {};

    if (!verifyCaptcha(captchaId, captcha)) {
      return res.status(400).json({ success: false, error: 'Captcha is incorrect or expired. Please try again.', captchaFailed: true });
    }

    if (!email || !password || !name) {
      return res.status(400).json({ success: false, error: 'Email, password, and name are required.' });
    }
    if (password.length < 6) {
      return res.status(400).json({ success: false, error: 'Password must be at least 6 characters.' });
    }
    if (!licenseAccepted) {
      return res.status(400).json({ success: false, error: 'You must accept the Terms & Conditions.' });
    }

    const existing = await User.findOne({ email: String(email).toLowerCase().trim() });
    if (existing) {
      return res.status(400).json({ success: false, error: 'An account with this email already exists.' });
    }

    const user = new User({
      email,
      password,
      name,
      licenseAccepted: true,
      licenseAcceptedAt: new Date(),
      emailVerified: true,
      role: 'user',
    });

    if (!user.accessId) {
      const prefix = 'ACC';
      const rand = crypto.randomBytes(4).toString('hex').toUpperCase();
      const ts   = Date.now().toString(36).toUpperCase().slice(-4);
      user.accessId = `${prefix}-${ts}-${rand}`;
    }
    user.lastLogin = new Date();

    await user.save();

    const token = jwt.sign(
      { userId: user._id, role: 'user' },
      getJwtSecret(),
      { expiresIn: '7d' }
    );

    res.status(201).json({
      success: true,
      message: 'Account created. Welcome!',
      token,
      user: userPayload(user),
    });
  } catch (error) {
    console.error('[USER-AUTH] Register error:', error.message);
    res.status(500).json({ success: false, error: 'Registration failed. Please try again.' });
  }
});

router.post('/login', async (req, res) => {
  try {
    const { email, password, captchaId, captcha } = req.body || {};

    if (!verifyCaptcha(captchaId, captcha)) {
      return res.status(400).json({ success: false, error: 'Captcha is incorrect or expired. Please try again.', captchaFailed: true });
    }

    if (!email || !password) {
      return res.status(400).json({ success: false, error: 'Email and password are required.' });
    }

    const user = await User.findOne({ email: String(email).toLowerCase().trim(), role: 'user' });
    if (!user) {
      return res.status(401).json({ success: false, error: 'Invalid credentials.' });
    }

    const isMatch = await user.comparePassword(password);
    if (!isMatch) {
      return res.status(401).json({ success: false, error: 'Invalid credentials.' });
    }

    if (!user.isActive) {
      return res.status(403).json({ success: false, error: 'Account suspended. Contact support.' });
    }

    user.lastLogin = new Date();
    if (!user.accessId) {
      const prefix = 'ACC';
      const rand = crypto.randomBytes(4).toString('hex').toUpperCase();
      const ts   = Date.now().toString(36).toUpperCase().slice(-4);
      user.accessId = `${prefix}-${ts}-${rand}`;
    }
    await user.save();

    const token = jwt.sign(
      { userId: user._id, role: 'user' },
      getJwtSecret(),
      { expiresIn: '7d' }
    );

    res.json({
      success: true,
      token,
      user: userPayload(user),
    });
  } catch (error) {
    console.error('[USER-AUTH] Login error:', error.message);
    res.status(500).json({ success: false, error: 'Login failed. Please try again.' });
  }
});

router.get('/me', async (req, res) => {
  try {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return res.status(401).json({ success: false, error: 'No token provided.' });
    }
    const token = authHeader.split(' ')[1];
    const decoded = jwt.verify(token, getJwtSecret());
    const user = await User.findById(decoded.userId);
    if (!user || user.role !== 'user') {
      return res.status(401).json({ success: false, error: 'Unauthorized.' });
    }
    res.json({
      success: true,
      user: {
        ...userPayload(user),
        lastLogin: user.lastLogin,
        createdAt: user.createdAt,
      },
    });
  } catch (error) {
    res.status(401).json({ success: false, error: 'Invalid or expired token.' });
  }
});

module.exports = router;
