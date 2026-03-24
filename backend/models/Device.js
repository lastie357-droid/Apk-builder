const mongoose = require('mongoose');

const deviceSchema = new mongoose.Schema({
  deviceId: {
    type: String,
    required: true,
    unique: true
  },
  userId: {
    type: mongoose.Schema.Types.ObjectId,
    ref: 'User',
    required: true
  },
  deviceName: {
    type: String,
    required: true
  },
  model: String,
  manufacturer: String,
  androidVersion: String,
  appVersion: String,
  permissions: [{
    name: String,
    granted: Boolean
  }],
  isOnline: {
    type: Boolean,
    default: false
  },
  lastSeen: {
    type: Date,
    default: Date.now
  },
  registeredAt: {
    type: Date,
    default: Date.now
  },
  ipAddress: String,
  location: {
    latitude: Number,
    longitude: Number,
    address: String
  },
  consentGiven: {
    type: Boolean,
    required: true,
    default: false
  },
  consentTimestamp: Date
});

module.exports = mongoose.model('Device', deviceSchema);
