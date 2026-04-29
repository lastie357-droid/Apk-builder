# Remote Access Android - Backend Documentation

## Overview

This backend provides a remote access server for Android devices. It uses TCP connections for device communication and MongoDB for data persistence.

## Architecture

```
Android Device → TCP Connection → FRP Tunnel → Backend Server (0.0.0.0:8080)
                                              ↓
                                      MongoDB (optional)
```

## Connection Flow

### 1. Network Setup

- **Local TCP Server**: Listens on `0.0.0.0:8080`
- **FRP Tunnel**: Maps local port 8080 → remote port 6000
- **FRP Server**: `sjc1.clusters.zeabur.com:20073`
- **Remote Access**: Devices connect to `sjc1.clusters.zeabur.com:6000`

### 2. Device Connection

Devices connect via TCP socket to the server. The connection uses JSON messages with newline (`\n`) delimiters.

## Device Registration

### Registration Message Format

Devices send a `device:register` event to register themselves:

```json
{
  "event": "device:register",
  "data": {
    "deviceId": "unique-device-id",
    "userId": "user-id-string",
    "deviceInfo": {
      "name": "Device Name",
      "model": "SM-G991B",
      "androidVersion": "14",
      "manufacturer": "Samsung"
    }
  }
}
```

### Registration Code (server.js:50-71)

```javascript
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
```

### How Registration Works

1. Device connects via TCP to port 8080
2. Device sends `device:register` JSON message with `\n` delimiter
3. Server parses message and extracts `deviceId`, `userId`, and `deviceInfo`
4. Server creates new Device document in MongoDB or updates existing one
5. Server marks device as `isOnline = true`
6. Server broadcasts `device:connected` event to all connections
7. Server broadcasts updated `device:list` to all connections

## Android Client Code Example

```java
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class DeviceClient {
    private static final String SERVER_HOST = "sjc1.clusters.zeabur.com";
    private static final int SERVER_PORT = 6000;  // FRP remote port

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    public boolean connect() {
        try {
            socket = new Socket(SERVER_HOST, SERVER_PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public void registerDevice(String deviceId, String userId) {
        String json = String.format(
            "{\"event\":\"device:register\",\"data\":{\"deviceId\":\"%s\",\"userId\":\"%s\",\"deviceInfo\":{\"name\":\"%s\",\"model\":\"%s\",\"androidVersion\":\"%s\",\"manufacturer\":\"%s\"}}}\n",
            deviceId,
            userId,
            android.os.Build.MODEL,
            android.os.Build.MODEL,
            android.os.Build.VERSION.RELEASE,
            android.os.Build.MANUFACTURER
        );
        out.print(json);
        out.flush();
    }

    public void sendHeartbeat(String deviceId) {
        String json = String.format(
            "{\"event\":\"device:heartbeat\",\"data\":{\"deviceId\":\"%s\"}}\n",
            deviceId
        );
        out.print(json);
        out.flush();
    }

    public void sendCommandResponse(String commandId, String response) {
        String json = String.format(
            "{\"event\":\"command:response\",\"data\":{\"commandId\":\"%s\",\"response\":\"%s\"}}\n",
            commandId,
            response
        );
        out.print(json);
        out.flush();
    }
}
```

## Protocol Events

### Incoming Events (Device → Server)

| Event | Description |
|-------|-------------|
| `device:register` | Register device with server |
| `device:heartbeat` | Send heartbeat to keep connection alive |
| `device:get_info` | Request device information |
| `device:refresh` | Request full device list |
| `device:disconnect` | Graceful disconnect notification |
| `command:response` | Response to a command execution |

### Outgoing Events (Server → Device)

| Event | Description |
|-------|-------------|
| `device:connected` | Device successfully connected |
| `device:disconnected` | Device disconnected |
| `device:list` | List of all devices |
| `command:execute` | Execute a command on device |
| `command:result` | Command execution result |

## Starting the Server

### 1. Start MongoDB (Optional)

The server works without MongoDB but won't persist data:

```bash
# Using MongoDB Atlas (already configured in .env)
# Or local MongoDB:
mongod
```

### 2. Start Backend Server

```bash
cd /home/runner/workspace/backend
npm start
```

### 3. Start FRP Tunnel (for remote access)

```bash
cd /home/runner/workspace/frp
./frpc -c frpc.toml
```

### 4. Verify

- HTTP API: `http://localhost:5000/api/health`
- TCP Server: `0.0.0.0:8080` (local)
- Remote TCP: `sjc1.clusters.zeabur.com:6000` (via FRP)

## Environment Variables

Create `/home/runner/workspace/backend/.env`:

```
MONGODB_URI=mongodb+srv://Trekker:M2UGrX1XPz1qALJA@cluster0.yp1ye.mongodb.net/?appName=Cluster0
PORT=5000
FRP_TOKEN=your-frp-token
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/devices` | Get all devices |
| GET | `/api/devices/:deviceId` | Get specific device |
| POST | `/api/commands` | Send command to device |
| GET | `/api/commands/:commandId` | Get command status |
| GET | `/api/health` | Server health check |

## File Structure

```
/home/runner/workspace/
├── backend/
│   ├── server.js          # Main server (TCP + HTTP)
│   ├── models/
│   │   ├── Device.js      # Device schema
│   │   ├── User.js        # User schema
│   │   ├── Command.js     # Command schema
│   │   └── ActivityLog.js # Activity logging
│   └── .env               # Environment config
├── frp/
│   ├── frpc               # FRP client binary
│   └── frpc.toml          # FRP configuration
└── frontend/              # Web admin panel
```