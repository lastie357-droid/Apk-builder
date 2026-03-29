import React, { useState, useRef, useEffect, useCallback } from 'react';

const SYSTEM_ICONS = {
  'com.whatsapp': '💬', 'com.instagram.android': '📸', 'com.facebook.katana': '👤',
  'org.telegram.messenger': '✈️', 'com.snapchat.android': '👻',
  'com.twitter.android': '🐦', 'com.google.android.gm': '📧',
  'com.google.android.chrome': '🌐', 'com.google.android.youtube': '▶️',
};
const appIcon = pkg => SYSTEM_ICONS[pkg] || '📦';

export default function ControlCenter({ device, sendCommand, results, streamFrame, send }) {
  const deviceId  = device.deviceId;
  const isOnline  = device.isOnline;
  const devInfo   = device.deviceInfo || {};
  const devW      = devInfo.screenWidth  || null;
  const devH      = devInfo.screenHeight || null;

  // ── Stream state ──────────────────────────────────────────────────────
  const [streaming, setStreaming]   = useState(false);
  const [fps, setFps]               = useState(0);
  const frameCountRef               = useRef(0);
  const lastFpsRef                  = useRef(Date.now());
  const streamingRef                = useRef(false);
  const autoStopRef                 = useRef(null);

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
  const imgRef = useRef(null);
  const handleStreamClick = useCallback((e) => {
    if (!streaming || !devW || !devH) return;
    const rect = imgRef.current.getBoundingClientRect();
    const px   = (e.clientX - rect.left) / rect.width;
    const py   = (e.clientY - rect.top)  / rect.height;
    const tx   = Math.round(px * devW);
    const ty   = Math.round(py * devH);
    sendCommand(deviceId, 'touch', { x: tx, y: ty, duration: 100 });
  }, [streaming, devW, devH, deviceId, sendCommand]);

  // ── Screen reader ─────────────────────────────────────────────────────
  const [readerOutput, setReaderOutput] = useState('');
  const [readerLoading, setReaderLoading] = useState(false);
  const seenReader = useRef(new Set());

  useEffect(() => {
    results.forEach(r => {
      if ((r.command === 'read_screen' || r.command === 'get_current_app') &&
           r.success && !seenReader.current.has(r.id)) {
        seenReader.current.add(r.id);
        setReaderLoading(false);
        try {
          const d = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
          if (r.command === 'read_screen') {
            const texts = (d.elements || []).map(el => el.text || el.contentDescription || '').filter(Boolean);
            setReaderOutput(texts.join('\n') || d.screenText || '(no text found)');
          } else {
            setReaderOutput(`Current app: ${d.appName || d.packageName || 'unknown'}`);
          }
        } catch (_) {
          setReaderOutput(typeof r.response === 'string' ? r.response : JSON.stringify(r.response));
          setReaderLoading(false);
        }
      }
    });
  }, [results]);

  const readScreen = () => {
    setReaderLoading(true);
    setReaderOutput('');
    sendCommand(deviceId, 'read_screen', {});
  };
  const getCurrentApp = () => {
    setReaderLoading(true);
    setReaderOutput('');
    sendCommand(deviceId, 'get_current_app', {});
  };

  // ── Paste / Input ─────────────────────────────────────────────────────
  const [pasteText, setPasteText] = useState('');
  const [showPasteInput, setShowPasteInput] = useState(false);

  const doPaste = () => {
    if (!pasteText.trim()) return;
    sendCommand(deviceId, 'set_clipboard', { text: pasteText });
    setTimeout(() => sendCommand(deviceId, 'input_text', { text: pasteText }), 200);
    setShowPasteInput(false);
  };

  // ── App Folder ────────────────────────────────────────────────────────
  const [apps, setApps]               = useState([]);
  const [appsLoading, setAppsLoading] = useState(false);
  const [appSearch, setAppSearch]     = useState('');
  const [confirmApp, setConfirmApp]   = useState(null);
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

  // ── Helper: send a command ────────────────────────────────────────────
  const cmd = (command, params = {}) => sendCommand(deviceId, command, params);

  // ── Tab state for Master Control ──────────────────────────────────────
  const [activeTab, setActiveTab] = useState('screen-control');

  // ── Stream display size ───────────────────────────────────────────────
  const STREAM_W = 300;
  const STREAM_H = devW && devH ? Math.min(640, Math.round(STREAM_W * devH / devW)) : 540;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, gap: 12 }}>

      {/* ── TOP ROW: Stream + Master Control ──────────────────────────── */}
      <div style={{ display: 'flex', flex: '0 0 auto', gap: 14, alignItems: 'flex-start' }}>

        {/* ── LEFT: Screen Stream ─────────────────────────────────────── */}
        <div style={{ flex: '0 0 auto', display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={{
            background: '#1e293b', borderRadius: 10, padding: '8px 12px',
            display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap',
          }}>
            <span style={{ fontWeight: 700, color: '#94a3b8', fontSize: 12, letterSpacing: 1, textTransform: 'uppercase' }}>
              📺 Screen Stream
            </span>
            {streaming
              ? <span style={{ fontSize: 12, color: '#22c55e', marginLeft: 'auto' }}>● LIVE · {fps} fps</span>
              : <span style={{ fontSize: 12, color: '#64748b', marginLeft: 'auto' }}>Stopped</span>
            }
            <button
              onClick={streaming ? stopStream : startStream}
              disabled={!isOnline}
              style={{ ...btnStyle, background: streaming ? '#7f1d1d' : '#166534', color: '#fff', fontSize: 12, padding: '4px 12px' }}
            >
              {streaming ? '⏹ Stop' : '▶ Start'}
            </button>
          </div>

          <div
            style={{
              width: STREAM_W, height: STREAM_H,
              background: '#0f172a', borderRadius: 12, border: '1px solid #1e293b',
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
                <div style={{ fontSize: 36 }}>📱</div>
                <div style={{ fontSize: 12, marginTop: 8 }}>
                  {streaming ? 'Waiting for frame…' : 'Start stream to view screen'}
                </div>
              </div>
            )}
          </div>
          {devW && devH && (
            <div style={{ fontSize: 10, color: '#475569', textAlign: 'center' }}>{devW}×{devH}</div>
          )}
        </div>

        {/* ── RIGHT: Master Control ──────────────────────────────────── */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 0, minWidth: 0, background: '#1e293b', borderRadius: 12, border: '1px solid #334155', overflow: 'hidden' }}>

          {/* Master Control header */}
          <div style={{ padding: '10px 14px', background: '#162032', borderBottom: '1px solid #334155' }}>
            <span style={{ fontWeight: 700, color: '#94a3b8', fontSize: 12, letterSpacing: 1, textTransform: 'uppercase' }}>
              🎛 Master Control
            </span>
          </div>

          {/* Tab bar */}
          <div style={{ display: 'flex', background: '#0f172a', borderBottom: '1px solid #334155' }}>
            <TabBtn
              label="📱 Screen Control"
              active={activeTab === 'screen-control'}
              onClick={() => setActiveTab('screen-control')}
            />
            <TabBtn
              label="👁 Screen Reader"
              active={activeTab === 'screen-reader'}
              onClick={() => setActiveTab('screen-reader')}
            />
          </div>

          {/* Tab content */}
          <div style={{ padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 10 }}>

            {/* ── SCREEN READER TAB (shows first when active) ── */}
            {activeTab === 'screen-reader' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  <button
                    onClick={readScreen}
                    disabled={!isOnline || readerLoading}
                    style={{ ...btnStyle, fontSize: 12, flex: 1 }}
                  >
                    {readerLoading ? '⏳ Reading…' : '📺 Read Screen'}
                  </button>
                  <button
                    onClick={getCurrentApp}
                    disabled={!isOnline || readerLoading}
                    style={{ ...btnStyle, fontSize: 12, flex: 1 }}
                  >
                    📱 Current App
                  </button>
                </div>
                {readerOutput && (
                  <pre style={{
                    background: '#0f172a', border: '1px solid #1e293b', borderRadius: 8,
                    padding: '10px 12px', color: '#94a3b8', fontSize: 11,
                    maxHeight: 300, overflowY: 'auto', whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word', margin: 0,
                  }}>
                    {readerOutput}
                  </pre>
                )}
                {!readerOutput && !readerLoading && (
                  <div style={{ color: '#475569', fontSize: 12, textAlign: 'center', padding: '20px 0' }}>
                    Press a button above to read the screen
                  </div>
                )}
              </div>
            )}

            {/* ── SCREEN CONTROL TAB ── */}
            {activeTab === 'screen-control' && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>

                {/* Control Pad */}
                <div style={{ background: '#162032', borderRadius: 10, border: '1px solid #334155', padding: '10px 12px' }}>
                  <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 1, fontWeight: 700, marginBottom: 10 }}>
                    Control Pad
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
                    {/* Up row */}
                    <NavBtn icon="↑" label="Swipe Up"    onClick={() => cmd('swipe', { x1: 540, y1: 1600, x2: 540, y2: 400, duration: 300 })} disabled={!isOnline} />
                    {/* Middle row */}
                    <div style={{ display: 'flex', gap: 6 }}>
                      <NavBtn icon="←" label="Swipe Left"  onClick={() => cmd('swipe', { x1: 900, y1: 960, x2: 180, y2: 960, duration: 300 })} disabled={!isOnline} />
                      <NavBtn icon="⌂" label="Home"        onClick={() => cmd('press_home')} disabled={!isOnline} color="#3b82f6" />
                      <NavBtn icon="→" label="Swipe Right" onClick={() => cmd('swipe', { x1: 180, y1: 960, x2: 900, y2: 960, duration: 300 })} disabled={!isOnline} />
                    </div>
                    {/* Down row */}
                    <NavBtn icon="↓" label="Swipe Down"  onClick={() => cmd('swipe', { x1: 540, y1: 400, x2: 540, y2: 1600, duration: 300 })} disabled={!isOnline} />
                  </div>
                </div>

                {/* System Buttons */}
                <div style={{ background: '#162032', borderRadius: 10, border: '1px solid #334155', padding: '10px 12px' }}>
                  <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 1, fontWeight: 700, marginBottom: 8 }}>
                    System Buttons
                  </div>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    <ActionBtn icon="◀" label="Back"     onClick={() => cmd('press_back')}    disabled={!isOnline} />
                    <ActionBtn icon="⌂" label="Home"     onClick={() => cmd('press_home')}    disabled={!isOnline} color="#3b82f6" />
                    <ActionBtn icon="⬜" label="Recents"  onClick={() => cmd('press_recents')} disabled={!isOnline} />
                    <ActionBtn icon="🗂" label="Tasks"    onClick={() => cmd('open_task_manager')} disabled={!isOnline} />
                    <ActionBtn icon="↵" label="Enter"    onClick={() => cmd('press_enter')}   disabled={!isOnline} color="#7c3aed" />
                    <ActionBtn
                      icon="📋" label="Paste"
                      onClick={() => setShowPasteInput(v => !v)}
                      disabled={!isOnline}
                      color={showPasteInput ? '#b45309' : undefined}
                    />
                  </div>
                  {showPasteInput && (
                    <div style={{ display: 'flex', gap: 6, marginTop: 8 }}>
                      <input
                        value={pasteText}
                        onChange={e => setPasteText(e.target.value)}
                        onKeyDown={e => e.key === 'Enter' && doPaste()}
                        placeholder="Text to paste / type…"
                        style={{ flex: 1, background: '#0f172a', border: '1px solid #334155', borderRadius: 6, padding: '6px 10px', color: '#f1f5f9', fontSize: 13 }}
                      />
                      <button onClick={doPaste} disabled={!pasteText.trim() || !isOnline} style={{ ...btnStyle, background: '#1d4ed8', color: '#fff' }}>Send</button>
                    </div>
                  )}
                </div>

                {/* Power / Wake */}
                <div style={{ background: '#162032', borderRadius: 10, border: '1px solid #334155', padding: '10px 12px' }}>
                  <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 1, fontWeight: 700, marginBottom: 8 }}>
                    Power
                  </div>
                  <div style={{ display: 'flex', gap: 6 }}>
                    <ActionBtn icon="💡" label="Wake Screen" onClick={() => cmd('wake_screen')} disabled={!isOnline} color="#ca8a04" />
                    <ActionBtn icon="🌑" label="Screen Off"  onClick={() => cmd('screen_off')}  disabled={!isOnline} color="#475569" />
                  </div>
                </div>

              </div>
            )}
          </div>
        </div>
      </div>

      {/* ── BOTTOM: App Folder ─────────────────────────────────────────── */}
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', background: '#1e293b', borderRadius: 12, border: '1px solid #334155', overflow: 'hidden' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px', borderBottom: '1px solid #334155', background: '#162032', flexWrap: 'wrap' }}>
          <span style={{ fontWeight: 700, color: '#94a3b8', fontSize: 12, letterSpacing: 1, textTransform: 'uppercase' }}>📂 App Folder</span>
          {apps.length > 0 && <span style={{ fontSize: 11, color: '#64748b' }}>{apps.length} apps</span>}
          <button onClick={loadApps} disabled={!isOnline || appsLoading} style={{ ...btnStyle, fontSize: 11, padding: '3px 10px', marginLeft: 4 }}>
            {appsLoading ? '…' : apps.length ? '↻ Refresh' : '📦 Load Apps'}
          </button>
          {apps.length > 0 && (
            <input
              value={appSearch}
              onChange={e => setAppSearch(e.target.value)}
              placeholder="Search apps…"
              style={{ flex: 1, minWidth: 120, background: '#0f172a', border: '1px solid #334155', borderRadius: 6, padding: '4px 10px', color: '#f1f5f9', fontSize: 12 }}
            />
          )}
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: 10 }}>
          {!apps.length && !appsLoading && (
            <div style={{ textAlign: 'center', color: '#475569', padding: '30px 0', fontSize: 13 }}>
              Click "Load Apps" to see installed apps
            </div>
          )}
          {appsLoading && (
            <div style={{ textAlign: 'center', color: '#64748b', padding: '30px 0', fontSize: 13 }}>Loading apps…</div>
          )}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 6 }}>
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

      {/* ── Confirm Dialog ─────────────────────────────────────────────── */}
      {confirmApp && (
        <div style={{
          position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.7)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 9999,
        }}>
          <div style={{ background: '#1e293b', borderRadius: 12, padding: 24, width: 340, border: '1px solid #334155' }}>
            <div style={{ fontWeight: 700, marginBottom: 8, color: '#f1f5f9' }}>
              Confirm {confirmApp.action.replace(/_/g, ' ')}
            </div>
            <div style={{ color: '#94a3b8', fontSize: 13, marginBottom: 18, wordBreak: 'break-all' }}>
              {confirmApp.pkg}
            </div>
            <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
              <button onClick={() => setConfirmApp(null)} style={{ ...btnStyle, background: '#334155', color: '#f1f5f9' }}>Cancel</button>
              <button onClick={confirmAndDo} style={{ ...btnStyle, background: '#dc2626', color: '#fff' }}>Confirm</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function TabBtn({ label, active, onClick }) {
  return (
    <button
      onClick={onClick}
      style={{
        flex: 1, padding: '9px 6px', border: 'none', borderBottom: active ? '2px solid #3b82f6' : '2px solid transparent',
        background: 'transparent', color: active ? '#f1f5f9' : '#64748b',
        fontSize: 12, fontWeight: active ? 700 : 500, cursor: 'pointer',
        transition: 'color 0.15s, border-color 0.15s', letterSpacing: 0.3,
      }}
    >
      {label}
    </button>
  );
}

function NavBtn({ icon, label, onClick, disabled, color }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={label}
      style={{
        width: 52, height: 52, border: 'none', borderRadius: 10,
        background: color || '#334155', color: '#f1f5f9',
        fontSize: 20, cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.4 : 1,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontWeight: 600, transition: 'background 0.15s',
      }}
    >
      {icon}
    </button>
  );
}

function ActionBtn({ icon, label, onClick, disabled, color }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={label}
      style={{
        display: 'flex', alignItems: 'center', gap: 5,
        padding: '7px 12px', border: 'none', borderRadius: 8,
        background: color || '#334155', color: '#f1f5f9',
        fontSize: 12, fontWeight: 600, cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.4 : 1, transition: 'background 0.15s',
        whiteSpace: 'nowrap',
      }}
    >
      <span style={{ fontSize: 15 }}>{icon}</span> {label}
    </button>
  );
}

function AppRow({ app, isOnline, onLaunch, onForceStop, onUninstall, onClearData }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <div style={{ background: '#162032', borderRadius: 8, border: '1px solid #1e293b', padding: '8px 10px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ fontSize: 20, flexShrink: 0 }}>{appIcon(app.packageName)}</span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontWeight: 600, color: '#f1f5f9', fontSize: 12, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{app.appName || app.packageName}</div>
          <div style={{ fontSize: 10, color: '#475569', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{app.packageName}</div>
        </div>
        <button onClick={onLaunch}  disabled={!isOnline} title="Launch" style={smallBtnStyle('#166534')}>▶ Open</button>
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

const btnStyle = {
  border: 'none', borderRadius: 7, padding: '6px 14px',
  cursor: 'pointer', fontWeight: 600, fontSize: 13,
  background: '#334155', color: '#f1f5f9',
  transition: 'opacity 0.15s',
};

const smallBtnStyle = bg => ({
  border: 'none', borderRadius: 6, padding: '4px 9px',
  cursor: 'pointer', fontWeight: 600, fontSize: 11,
  background: bg, color: '#f1f5f9',
  whiteSpace: 'nowrap',
});
