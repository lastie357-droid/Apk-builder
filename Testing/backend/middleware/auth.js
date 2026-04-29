const jwt            = require('jsonwebtoken');
const User           = require('../models/User');
const { getJwtSecret } = require('../jwtSecret');

const authenticate = async (req, res, next) => {
  try {
    const token = req.header('Authorization')?.replace('Bearer ', '');
    if (!token) {
      return res.status(401).json({ success: false, error: 'Authentication required' });
    }
    const decoded = jwt.verify(token, getJwtSecret());
    const user = await User.findById(decoded.userId);
    if (!user || !user.isActive) {
      return res.status(401).json({ success: false, error: 'Invalid authentication' });
    }
    req.user  = user;
    req.token = token;
    next();
  } catch (error) {
    res.status(401).json({ success: false, error: 'Invalid authentication' });
  }
};

const authenticateSocket = async (socket, next) => {
  try {
    const token = socket.handshake.auth.token;
    if (!token) return next(new Error('Authentication required'));
    const decoded = jwt.verify(token, getJwtSecret());
    const user = await User.findById(decoded.userId);
    if (!user || !user.isActive) return next(new Error('Invalid authentication'));
    socket.user = user;
    next();
  } catch (error) {
    next(new Error('Invalid authentication'));
  }
};

const requireAdmin = (req, res, next) => {
  if (req.user.role !== 'admin') {
    return res.status(403).json({ success: false, error: 'Admin access required' });
  }
  next();
};

module.exports = { authenticate, authenticateSocket, requireAdmin };
