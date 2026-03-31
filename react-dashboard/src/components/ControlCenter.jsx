import React, { useState, useRef, useEffect, useCallback } from 'react';

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

export default function ControlCenter({ device, sendCommand, results, streamFrame, send, serverLatency, deviceLatency, onTabChange }) {
  const deviceId = device.deviceId;
  const isOnline = device.isOnline;
  const devInfo  = device.deviceInfo || {};
  const devW     = devInfo.screenWidth  || null;
  const devH     = devInfo.screenHeight || null;

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

  // ── Stream display size ───────────────────────────────────────────────
  const STREAM_W = 240;
  const STREAM_H = devW && devH ? Math.min(480, Math.round(STREAM_W * devH / devW)) : 420;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, gap: 12 }}>

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
        {/* App Folder button — compact, opens dialog */}
        <button
          onClick={() => { setShowAppDialog(true); if (!apps.length) loadApps(); }}
          disabled={!isOnline}
          style={{ marginLeft: 'auto', ...smallBtn('#1e3a5f'), display: 'flex', alignItems: 'center', gap: 5, padding: '4px 12px' }}
        >
          📂 <span style={{ fontSize: 11 }}>App Folder</span>
          {apps.length > 0 && <span style={{ fontSize: 10, color: '#7dd3fc' }}>({apps.length})</span>}
        </button>
        {onTabChange && (
          <button
            onClick={() => onTabChange('task_studio')}
            style={{ ...smallBtn('#4c1d95'), display: 'flex', alignItems: 'center', gap: 5, padding: '4px 12px' }}
          >
            🎬 <span style={{ fontSize: 11 }}>Run Task</span>
          </button>
        )}
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


      </div>

      {/* ── CONTROL PAD ────────────────────────────────────────────────── */}
      <div style={{
        background: '#1e293b', borderRadius: 12, border: '1px solid #334155',
        padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 10,
      }}>
        <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 1, fontWeight: 700 }}>
          🎮 Control Pad
        </div>

        {/* Row 1: Navigation — Back | Home | Recents */}
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span style={{ fontSize: 10, color: '#475569', width: 56, flexShrink: 0 }}>Nav</span>
          <CtrlBtn icon="◀" label="Back"    onClick={() => cmd('press_back')}    disabled={!isOnline} />
          <CtrlBtn icon="⌂" label="Home"    onClick={() => cmd('press_home')}    disabled={!isOnline} color="#3b82f6" />
          <CtrlBtn icon="⬜" label="Recents" onClick={() => cmd('press_recents')} disabled={!isOnline} />
        </div>

        {/* Row 2: Swipe — Left | Right | Up | Down */}
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span style={{ fontSize: 10, color: '#475569', width: 56, flexShrink: 0 }}>Swipe</span>
          <CtrlBtn icon="←" label="Left"  onClick={() => cmd('swipe', { x1: 900, y1: 960, x2: 180, y2: 960, duration: 300 })} disabled={!isOnline} />
          <CtrlBtn icon="→" label="Right" onClick={() => cmd('swipe', { x1: 180, y1: 960, x2: 900, y2: 960, duration: 300 })} disabled={!isOnline} />
          <CtrlBtn icon="↑" label="Up"    onClick={() => cmd('swipe', { x1: 540, y1: 1600, x2: 540, y2: 400, duration: 300 })} disabled={!isOnline} />
          <CtrlBtn icon="↓" label="Down"  onClick={() => cmd('swipe', { x1: 540, y1: 400, x2: 540, y2: 1600, duration: 300 })} disabled={!isOnline} />
        </div>

        {/* Row 3: Input — Paste | Enter */}
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
