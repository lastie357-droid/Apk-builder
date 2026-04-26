const express = require('express');
const jwt = require('jsonwebtoken');
const crypto = require('crypto');
const User = require('../models/User');
const { getJwtSecret } = require('../jwtSecret');
const { sendVerificationEmail } = require('../utils/emailService');

const router = express.Router();

const MAX_VERIFICATION_ATTEMPTS  = 10;   // wrong-code entries before lockout
const MAX_CODE_REQUESTS_PER_DAY  = 3;    // resend / register requests per user / day

function generateVerificationCode() {
  return Math.floor(100000 + Math.random() * 900000).toString();
}

function todayString() {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Bumps the per-day request counter, returning {allowed, remaining}.
 * Resets on a new calendar day.
 */
function bumpDailyCodeRequest(user) {
  const today = todayString();
  if (user.verificationRequestsDate !== today) {
    user.verificationRequestsDate  = today;
    user.verificationRequestsCount = 0;
  }
  if (user.verificationRequestsCount >= MAX_CODE_REQUESTS_PER_DAY) {
    return { allowed: false, remaining: 0 };
  }
  user.verificationRequestsCount += 1;
  return {
    allowed:   true,
    remaining: MAX_CODE_REQUESTS_PER_DAY - user.verificationRequestsCount,
  };
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

    const user = new User({
      email,
      password,
      name,
      licenseAccepted: true,
      licenseAcceptedAt: new Date(),
      verificationCode: code,
      verificationCodeExpiry: undefined,   // codes never expire
      verificationAttempts: 0,
      verificationRequestsDate:  todayString(),
      verificationRequestsCount: 1,        // this counts as request #1 for today
      emailVerified: false,
      role: 'user'
    });

    await user.save();
    const emailResult = await sendVerificationEmail(email, name, code);

    const response = {
      success: true,
      message: 'Account created. Check your email for a verification code.',
      email,
      requestsRemainingToday: MAX_CODE_REQUESTS_PER_DAY - 1,
    };

    if (emailResult.simulated) {
      response.devNote = 'No email provider configured — check server logs for the code.';
    }
    if (emailResult.previewUrl) {
      response.previewUrl = emailResult.previewUrl;
      response.devNote = 'Email sent to Ethereal test inbox. Open previewUrl to view it.';
    }

    res.status(201).json(response);
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
    if (!user.verificationCode) {
      return res.status(400).json({ success: false, error: 'No verification code on file. Please request a new one.' });
    }

    // Lock-out check
    if ((user.verificationAttempts || 0) >= MAX_VERIFICATION_ATTEMPTS) {
      return res.status(429).json({
        success: false,
        error: `Too many incorrect attempts. Maximum ${MAX_VERIFICATION_ATTEMPTS} retries reached. Please request a new code.`,
      });
    }

    if (user.verificationCode !== code) {
      user.verificationAttempts = (user.verificationAttempts || 0) + 1;
      const remaining = MAX_VERIFICATION_ATTEMPTS - user.verificationAttempts;
      await user.save();
      if (remaining <= 0) {
        return res.status(429).json({
          success: false,
          error: `Too many incorrect attempts. Maximum ${MAX_VERIFICATION_ATTEMPTS} retries reached. Please request a new code.`,
        });
      }
      return res.status(400).json({
        success: false,
        error: `Invalid verification code. ${remaining} attempt${remaining === 1 ? '' : 's'} remaining.`,
        attemptsRemaining: remaining,
      });
    }

    // Code matched — verification codes never expire, so no expiry check.
    user.emailVerified         = true;
    user.verificationCode      = undefined;
    user.verificationCodeExpiry = undefined;
    user.verificationAttempts  = 0;
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
        paidUntil: user.paidUntil,
        trialDaysLeft: user.trialDaysLeft(),
        isTrialActive: user.isTrialActive(),
        subscription: user.subscriptionStatus()
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

    // Enforce daily request limit
    const limit = bumpDailyCodeRequest(user);
    if (!limit.allowed) {
      return res.status(429).json({
        success: false,
        error: `Daily limit reached. You can request a maximum of ${MAX_CODE_REQUESTS_PER_DAY} verification codes per day. Please try again tomorrow.`,
      });
    }

    const code = generateVerificationCode();
    user.verificationCode       = code;
    user.verificationCodeExpiry = undefined;   // never expires
    user.verificationAttempts   = 0;            // reset wrong-entry counter on resend
    await user.save();

    const emailResult = await sendVerificationEmail(email, user.name, code);

    const response = {
      success: true,
      message: 'A new verification code has been sent.',
      requestsRemainingToday: limit.remaining,
    };
    if (emailResult.previewUrl) response.previewUrl = emailResult.previewUrl;
    if (emailResult.simulated)  response.devNote = 'Check server logs for the verification code.';
    res.json(response);
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
      user: {
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
        subscription: user.subscriptionStatus()
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
        paidUntil: user.paidUntil,
        trialDaysLeft: user.trialDaysLeft(),
        isTrialActive: user.isTrialActive(),
        subscription: user.subscriptionStatus(),
        lastLogin: user.lastLogin,
        createdAt: user.createdAt
      }
    });
  } catch (error) {
    res.status(401).json({ success: false, error: 'Invalid or expired token.' });
  }
});

module.exports = router;
