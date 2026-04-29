const express          = require('express');
const jwt              = require('jsonwebtoken');
const User             = require('../models/User');
const { authenticate } = require('../middleware/auth');
const { getJwtSecret } = require('../jwtSecret');

const router = express.Router();

router.post('/register', async (req, res) => {
  try {
    const { email, password, name } = req.body;
    const existingUser = await User.findOne({ email });
    if (existingUser) {
      return res.status(400).json({ success: false, error: 'Email already registered' });
    }
    const user = new User({ email, password, name });
    await user.save();
    const token = jwt.sign({ userId: user._id }, getJwtSecret(), { expiresIn: '7d' });
    res.status(201).json({
      success: true, token,
      user: { id: user._id, email: user.email, name: user.name, role: user.role }
    });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

router.post('/login', async (req, res) => {
  try {
    const { email, password } = req.body;
    const user = await User.findOne({ email });
    if (!user) return res.status(401).json({ success: false, error: 'Invalid credentials' });
    const isMatch = await user.comparePassword(password);
    if (!isMatch) return res.status(401).json({ success: false, error: 'Invalid credentials' });
    user.lastLogin = new Date();
    await user.save();
    const token = jwt.sign({ userId: user._id }, getJwtSecret(), { expiresIn: '7d' });
    res.json({
      success: true, token,
      user: { id: user._id, email: user.email, name: user.name, role: user.role }
    });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

router.get('/me', authenticate, async (req, res) => {
  res.json({
    success: true,
    user: { id: req.user._id, email: req.user.email, name: req.user.name, role: req.user.role }
  });
});

router.post('/logout', authenticate, async (req, res) => {
  res.json({ success: true, message: 'Logged out successfully' });
});

module.exports = router;
