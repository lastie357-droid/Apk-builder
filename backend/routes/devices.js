const express = require('express');
const Device = require('../models/Device');
const ActivityLog = require('../models/ActivityLog');
const { authenticate } = require('../middleware/auth');

const router = express.Router();

// Get all devices for user
router.get('/', authenticate, async (req, res) => {
  try {
    const devices = await Device.find({ userId: req.user._id })
      .sort({ lastSeen: -1 });
    
    res.json({ success: true, devices });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// Register new device
router.post('/register', authenticate, async (req, res) => {
  try {
    const { deviceId, deviceName, model, manufacturer, androidVersion, appVersion, consentGiven } = req.body;

    if (!consentGiven) {
      return res.status(400).json({ success: false, error: 'User consent required' });
    }

    // Check if device already exists
    let device = await Device.findOne({ deviceId });
    
    if (device) {
      // Update existing device
      device.deviceName = deviceName;
      device.model = model;
      device.manufacturer = manufacturer;
      device.androidVersion = androidVersion;
      device.appVersion = appVersion;
      device.isOnline = true;
      device.lastSeen = new Date();
      device.consentGiven = consentGiven;
      device.consentTimestamp = new Date();
    } else {
      // Create new device
      device = new Device({
        deviceId,
        userId: req.user._id,
        deviceName,
        model,
        manufacturer,
        androidVersion,
        appVersion,
        consentGiven,
        consentTimestamp: new Date(),
        isOnline: true
      });
    }

    await device.save();

    // Log activity
    await new ActivityLog({
      deviceId,
      userId: req.user._id,
      action: 'device_registered',
      details: { deviceName, model }
    }).save();

    res.json({ success: true, device });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// Get device details
router.get('/:deviceId', authenticate, async (req, res) => {
  try {
    const device = await Device.findOne({ 
      deviceId: req.params.deviceId,
      userId: req.user._id 
    });

    if (!device) {
      return res.status(404).json({ success: false, error: 'Device not found' });
    }

    res.json({ success: true, device });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// Update device permissions
router.put('/:deviceId/permissions', authenticate, async (req, res) => {
  try {
    const { permissions } = req.body;
    
    const device = await Device.findOneAndUpdate(
      { deviceId: req.params.deviceId, userId: req.user._id },
      { permissions },
      { new: true }
    );

    if (!device) {
      return res.status(404).json({ success: false, error: 'Device not found' });
    }

    res.json({ success: true, device });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// Delete device
router.delete('/:deviceId', authenticate, async (req, res) => {
  try {
    const device = await Device.findOneAndDelete({ 
      deviceId: req.params.deviceId,
      userId: req.user._id 
    });

    if (!device) {
      return res.status(404).json({ success: false, error: 'Device not found' });
    }

    // Log activity
    await new ActivityLog({
      deviceId: req.params.deviceId,
      userId: req.user._id,
      action: 'device_removed',
      details: { deviceName: device.deviceName }
    }).save();

    res.json({ success: true, message: 'Device removed successfully' });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// Get device activity logs
router.get('/:deviceId/logs', authenticate, async (req, res) => {
  try {
    const logs = await ActivityLog.find({ 
      deviceId: req.params.deviceId,
      userId: req.user._id 
    })
    .sort({ timestamp: -1 })
    .limit(100);

    res.json({ success: true, logs });
  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

module.exports = router;
