const express = require('express');
const jwt = require('jsonwebtoken');
const crypto = require('crypto');
const User = require('../models/User');
const { getJwtSecret } = require('../jwtSecret');
const { sendVerificationEmail } = require('../utils/emailService');

const router = express.Router();

function generateVerificationCode() {
  return Math.floor(100000 + Math.random() * 900000).toString();
}

router.post('/register', async (req, res) => {
  try {
    const { email, password, name, licenseAccepted } = req.body;

    if (!email || !password || !name) {
      return res.status(400).json({ success: false, error: 'Email, password, and name are required.' });
    }
    if (password.length < 6) {
      return res.status(400).json({ success: false, error: 'Password must be at least 6 characters.' });
    }
    if (!licenseAccepted) {
      return res.status(400).json({ success: false, error: 'You must accept the Terms & Conditions.' });
    }

    const existing = await User.findOne({ email });
    if (existing) {
      return res.status(400).json({ success: false, error: 'An account with this email already exists.' });
    }

    const code = generateVerificationCode();
    const expiry = new Date(Date.now() + 15 * 60 * 1000);

    const user = new User({
      email,
      password,
      name,
      licenseAccepted: true,
      licenseAcceptedAt: new Date(),
      verificationCode: code,
      verificationCodeExpiry: expiry,
      emailVerified: false,
      role: 'user'
    });

    await user.save();
    await sendVerificationEmail(email, name, code);

    res.status(201).json({
      success: true,
      message: 'Account created. Check your email for a verification code.',
      email
    });
  } catch (error) {
    console.error('[USER-AUTH] Register error:', error.message);
    res.status(500).json({ success: false, error: 'Registration failed. Please try again.' });
  }
});

router.post('/verify-email', async (req, res) => {
  try {
    const { email, code } = req.body;

    if (!email || !code) {
      return res.status(400).json({ success: false, error: 'Email and code are required.' });
    }

    const user = await User.findOne({ email });
    if (!user) {
      return res.status(404).json({ success: false, error: 'Account not found.' });
    }
    if (user.emailVerified) {
      return res.status(400).json({ success: false, error: 'Email already verified. Please login.' });
    }
    if (!user.verificationCode || user.verificationCode !== code) {
      return res.status(400).json({ success: false, error: 'Invalid verification code.' });
    }
    if (new Date() > user.verificationCodeExpiry) {
      return res.status(400).json({ success: false, error: 'Verification code has expired. Please request a new one.' });
    }

    user.emailVerified = true;
    user.verificationCode = undefined;
    user.verificationCodeExpiry = undefined;
    await user.save();

    const token = jwt.sign(
      { userId: user._id, role: 'user' },
      getJwtSecret(),
      { expiresIn: '7d' }
    );

    res.json({
      success: true,
      message: 'Email verified successfully!',
      token,
      user: {
        id: user._id,
        accessId: user.accessId,
        email: user.email,
        name: user.name,
        role: user.role,
        tier: user.tier,
        trialStartDate: user.trialStartDate,
        trialEndDate: user.trialEndDate,
        trialDaysLeft: user.trialDaysLeft(),
        isTrialActive: user.isTrialActive()
      }
    });
  } catch (error) {
    console.error('[USER-AUTH] Verify error:', error.message);
    res.status(500).json({ success: false, error: 'Verification failed. Please try again.' });
  }
});

router.post('/resend-code', async (req, res) => {
  try {
    const { email } = req.body;
    if (!email) {
      return res.status(400).json({ success: false, error: 'Email is required.' });
    }

    const user = await User.findOne({ email });
    if (!user) {
      return res.status(404).json({ success: false, error: 'Account not found.' });
    }
    if (user.emailVerified) {
      return res.status(400).json({ success: false, error: 'Email already verified.' });
    }

    const code = generateVerificationCode();
    const expiry = new Date(Date.now() + 15 * 60 * 1000);
    user.verificationCode = code;
    user.verificationCodeExpiry = expiry;
    await user.save();

    await sendVerificationEmail(email, user.name, code);

    res.json({ success: true, message: 'A new verification code has been sent to your email.' });
  } catch (error) {
    console.error('[USER-AUTH] Resend error:', error.message);
    res.status(500).json({ success: false, error: 'Failed to resend code. Please try again.' });
  }
});

router.post('/login', async (req, res) => {
  try {
    const { email, password } = req.body;

    if (!email || !password) {
      return res.status(400).json({ success: false, error: 'Email and password are required.' });
    }

    const user = await User.findOne({ email, role: 'user' });
    if (!user) {
      return res.status(401).json({ success: false, error: 'Invalid credentials.' });
    }

    const isMatch = await user.comparePassword(password);
    if (!isMatch) {
      return res.status(401).json({ success: false, error: 'Invalid credentials.' });
    }

    if (!user.emailVerified) {
      return res.status(403).json({
        success: false,
        error: 'Email not verified.',
        needsVerification: true,
        email: user.email
      });
    }

    if (!user.isActive) {
      return res.status(403).json({ success: false, error: 'Account suspended. Contact support.' });
    }

    user.lastLogin = new Date();
    await user.save();

    const token = jwt.sign(
      { userId: user._id, role: 'user' },
      getJwtSecret(),
      { expiresIn: '7d' }
    );

    res.json({
      success: true,
      token,
      user: {
        id: user._id,
        accessId: user.accessId,
        email: user.email,
        name: user.name,
        role: user.role,
        tier: user.tier,
        trialStartDate: user.trialStartDate,
        trialEndDate: user.trialEndDate,
        trialDaysLeft: user.trialDaysLeft(),
        isTrialActive: user.isTrialActive()
      }
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
        id: user._id,
        accessId: user.accessId,
        email: user.email,
        name: user.name,
        role: user.role,
        tier: user.tier,
        trialStartDate: user.trialStartDate,
        trialEndDate: user.trialEndDate,
        trialDaysLeft: user.trialDaysLeft(),
        isTrialActive: user.isTrialActive(),
        lastLogin: user.lastLogin,
        createdAt: user.createdAt
      }
    });
  } catch (error) {
    res.status(401).json({ success: false, error: 'Invalid or expired token.' });
  }
});

module.exports = router;
