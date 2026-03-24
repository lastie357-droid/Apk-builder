const express = require('express');
const http = require('http');
const net = require('net');
const cors = require('cors');
const path = require('path');
const mongoose = require('mongoose');
require('dotenv').config();

const app = express();
const server = http.createServer(app);

const Device = require('./models/Device');
const User = require('./models/User');
const Command = require('./models/Command');
const ActivityLog = require('./models/ActivityLog');

const authRoutes = require('./routes/auth');
const devicesRoutes = require('./routes/devices');

app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, '../frontend')));

app.use('/api/auth', authRoutes);
app.use('/api/user', devicesRoutes);

// Admin routes
app.get('/api/admin/users', async (req, res) => {
    try {
        const users = await User.find({}, '-password').sort({ createdAt: -1 });
        res.json({ success: true, users });
    } catch (e) {
        res.status(500).json({ success: false, error: e.message });
    }
});

const connections = new Map();
const commands = new Map();

mongoose.connect(process.env.MONGODB_URI || 'mongodb://localhost:27017/remoteaccess', {
    serverSelectionTimeoutMS: 5000,
    socketTimeoutMS: 45000,
}).then(() => console.log('MongoDB connected')).catch(err => {
    console.error('MongoDB connection failed:', err.message);
    console.warn('Server will continue without MongoDB. Device data will not be persisted.');
});

function broadcast(event, data) {
    for (const conn of connections.values()) {
        sendTo(conn, event, data);
    }
}

function sendTo(conn, event, data) {
    if (conn && conn.writable) {
        conn.write(JSON.stringify({ event, data }) + '\n');
    }
}

async function handleTCPMessage(conn, message) {
    try {
        const msg = JSON.parse(message);
        const { event, data } = msg;

        switch (event) {
            case 'device:register':
                const deviceId = data.deviceId || conn.id;
                let device = await Device.findOne({ deviceId });
                if (!device) {
                    device = new Device({
                        deviceId,
                        userIdString: data.userId || null,
                        deviceName: data.deviceInfo?.name || deviceId,
                        deviceInfo: data.deviceInfo || {},
                        isOnline: true
                    });
                } else {
                    device.isOnline = true;
                    device.lastSeen = new Date();
                    device.deviceInfo = { ...device.deviceInfo, ...data.deviceInfo };
                }
                await device.save();
                conn.deviceId = deviceId;
                console.log('Device registered:', deviceId);
                broadcast('device:connected', device);
                broadcast('device:list', await Device.find());
                break;

            case 'user:login':
                const user = await User.findOne({ email: data.email });
                if (user) {
                    const sessionKey = Date.now().toString();
                    broadcast('user:session', { sessionKey, userId: user._id, email: user.email });
                }
                const userDevices = await Device.find({ userId: data.userId });
                sendTo(conn, 'device:list', userDevices);
                break;

            case 'command:response':
                const { commandId, response, error } = data;
                if (commands.has(commandId)) {
                    const cmd = commands.get(commandId);
                    cmd.status = error ? 'failed' : 'success';
                    cmd.response = response;
                    cmd.error = error;
                    cmd.completedAt = new Date();
                    await cmd.save();

                    const device = await Device.findOne({ deviceId: cmd.deviceId });
                    if (device) {
                        const userDevices = await Device.find({ userId: device.userId });
                        for (const d of userDevices) {
                            const dConn = Array.from(connections.values()).find(c => c.deviceId === d.deviceId);
                            if (dConn) sendTo(dConn, 'command:result', { commandId, command: cmd.command, response, error });
                        }
                    }
                }
                break;

            case 'device:heartbeat':
                await Device.findOneAndUpdate(
                    { deviceId: data.deviceId },
                    { lastSeen: new Date() },
                    { new: true }
                );
                break;

            case 'device:get_info':
                const infoDevice = await Device.findOne({ deviceId: data.deviceId });
                if (infoDevice) sendTo(conn, 'device:info', infoDevice.deviceInfo);
                break;

            case 'device:refresh':
                const allDevices = await Device.find();
                sendTo(conn, 'device:list', allDevices);
                break;

            case 'device:disconnect':
                await Device.findOneAndUpdate(
                    { deviceId: data.deviceId },
                    { online: false, lastSeen: new Date() }
                );
                broadcast('device:list', await Device.find());
                break;
        }
    } catch (e) {
        console.error('Failed to parse message:', e.message);
    }
}

const tcpServer = net.createServer((conn) => {
    conn.id = Date.now().toString();
    connections.set(conn.id, conn);
    console.log('New TCP connection:', conn.id);

    let buffer = '';

    conn.on('data', (data) => {
        buffer += data.toString();
        let newlineIndex;
        while ((newlineIndex = buffer.indexOf('\n')) !== -1) {
            const message = buffer.slice(0, newlineIndex);
            buffer = buffer.slice(newlineIndex + 1);
            if (message.trim()) {
                handleTCPMessage(conn, message);
            }
        }
    });

    conn.on('close', async () => {
        console.log('Connection closed:', conn.id);
        connections.delete(conn.id);

        if (conn.deviceId) {
            await Device.findOneAndUpdate(
                { deviceId: conn.deviceId },
                { online: false, lastSeen: new Date() }
            );
            broadcast('device:disconnected', { deviceId: conn.deviceId });
            broadcast('device:list', await Device.find());
        }
    });

    conn.on('error', (err) => {
        console.error('Connection error:', err.message);
    });
});

tcpServer.listen(8080, '0.0.0.0', () => {
    console.log('TCP server listening on 0.0.0.0:8080');
});

app.get('/api/devices', async (req, res) => {
    try {
        const devices = await Device.find();
        res.json(devices);
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

app.get('/api/devices/:deviceId', async (req, res) => {
    try {
        const device = await Device.findOne({ deviceId: req.params.deviceId });
        if (device) res.json(device);
        else res.status(404).json({ error: 'Device not found' });
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

app.post('/api/commands', async (req, res) => {
    try {
        const { deviceId, command, data } = req.body;
        const device = await Device.findOne({ deviceId });

        if (device && device.online) {
            const commandId = Date.now().toString();
            const cmd = new Command({
                id: commandId,
                deviceId,
                command,
                data,
                status: 'pending'
            });
            await cmd.save();

            const conn = Array.from(connections.values()).find(c => c.deviceId === deviceId);
            if (conn) sendTo(conn, 'command:execute', cmd);

            res.json({ success: true, commandId });
        } else {
            res.status(404).json({ error: 'Device offline or not found' });
        }
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

app.get('/api/commands/:commandId', (req, res) => {
    const cmd = commands.get(req.params.commandId);
    if (cmd) res.json(cmd);
    else res.status(404).json({ error: 'Command not found' });
});

app.get('/api/health', async (req, res) => {
    let mongoStatus = 'disconnected';
    try {
        if (mongoose.connection.readyState === 1) mongoStatus = 'connected';
    } catch (e) {
        mongoStatus = 'error';
    }
    res.json({
        status: 'ok',
        mongodb: mongoStatus,
        tcpConnections: connections.size,
        commands: commands.size,
        uptime: process.uptime()
    });
});

const fs = require('fs');

app.get('*', (req, res) => {
    const filePath = path.join(__dirname, '../frontend', req.path);
    if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
        res.sendFile(filePath);
    } else {
        res.sendFile(path.join(__dirname, '../frontend/index.html'));
    }
});

const HTTP_PORT = process.env.PORT || 5000;
server.listen(HTTP_PORT, () => {
    console.log(`HTTP Server running on port ${HTTP_PORT}`);
    console.log(`TCP device server: 127.0.0.1:8080`);
    console.log(`Admin panel: http://localhost:${HTTP_PORT}/admin-login.html`);
});

setInterval(async () => {
    const thirtySecondsAgo = new Date(Date.now() - 30 * 1000);
    await Device.updateMany(
        { lastSeen: { $lt: thirtySecondsAgo }, online: true },
        { online: false }
    );
}, 30 * 1000);

process.on('SIGINT', async () => {
    await mongoose.connection.close();
    process.exit(0);
});