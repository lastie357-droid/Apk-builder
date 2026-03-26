import React, { useState, useRef, useEffect, useCallback } from 'react';

const PHONE_W = 360;
const PHONE_H = 780;

export default function ScreenReaderView({ device, sendCommand, results }) {
  const deviceId = device.deviceId;
  const isOnline = device.isOnline;
  const info     = device.deviceInfo || {};

  const [streaming, setStreaming]       = useState(false);
  const [streamInterval, setStreamInterval] = useState(2000);
  const [savedCaptures, setSavedCaptures] = useState([]);
  const [viewCapture, setViewCapture]   = useState(null);
  const [activeView, setActiveView]     = useState('visual'); // 'visual' | 'elements' | 'raw'
  const [touchHint, setTouchHint]       = useState(null);
  const streamTimer  = useRef(null);
  const autoTimer    = useRef(null);
  const seenIds      = useRef(new Set());

  // Device screen resolution (from deviceInfo or defaults)
  const devW = info.screenWidth  || 1080;
  const devH = info.screenHeight || 2340;
  const scaleX = PHONE_W / devW;
  const scaleY = PHONE_H / devH;

  // Latest screen data from results
  const latestScreenResult = results
    .filter(r => r.command === 'read_screen' && r.success && r.response)
    .slice(0, 1)[0];

  let screenData = null;
  try {
    const parsed = typeof latestScreenResult?.response === 'string'
      ? JSON.parse(latestScreenResult.response)
      : latestScreenResult?.response;
    screenData = parsed?.screen || null;
  } catch (_) {}

  // Auto-streaming mode
  const startStreaming = useCallback(() => {
    if (streamTimer.current) clearInterval(streamTimer.current);
    sendCommand(deviceId, 'read_screen');
    streamTimer.current = setInterval(() => {
      sendCommand(deviceId, 'read_screen');
    }, streamInterval);
    setStreaming(true);
  }, [deviceId, sendCommand, streamInterval]);

  const stopStreaming = useCallback(() => {
    if (streamTimer.current) clearInterval(streamTimer.current);
    streamTimer.current = null;
    setStreaming(false);
  }, []);

  useEffect(() => {
    return () => { if (streamTimer.current) clearInterval(streamTimer.current); };
  }, []);

  // Restart stream when interval changes
  useEffect(() => {
    if (streaming) {
      stopStreaming();
      startStreaming();
    }
  }, [streamInterval]);

  const captureOnce = () => sendCommand(deviceId, 'read_screen');

  const saveCapture = () => {
    if (!screenData) return;
    const text = buildTextDump(screenData);
    const cap  = { id: Date.now(), text, packageName: screenData.packageName, timestamp: new Date().toLocaleString(), elementCount: (screenData.elements||[]).length };
    setSavedCaptures(prev => [cap, ...prev]);
    const filename = `/sdcard/screen_reader/capture_${Date.now()}.txt`;
    sendCommand(deviceId, 'write_file', { filePath: filename, content: text, isBase64: false });
  };

  const downloadCapture = (cap) => {
    const blob = new Blob([cap.text], { type: 'text/plain' });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href = url; a.download = `screen_${cap.id}.txt`; a.click();
    URL.revokeObjectURL(url);
  };

  // Click element on visual view → send touch command
  const handleElementClick = (el) => {
    if (!el.bounds || !isOnline) return;
    const cx = (el.bounds.left + el.bounds.right) / 2;
    const cy = (el.bounds.top  + el.bounds.bottom) / 2;
    setTouchHint({ x: cx * scaleX, y: cy * scaleY });
    sendCommand(deviceId, 'touch', { x: Math.round(cx), y: Math.round(cy), duration: 100 });
    setTimeout(() => setTouchHint(null), 600);
  };

  const navBtn = (label, cmd, icon) => (
    <button className="sr-nav-btn" onClick={() => sendCommand(deviceId, cmd)} disabled={!isOnline}>
      {icon} {label}
    </button>
  );

  const elements = screenData?.elements || [];
  const visibleEls = elements.filter(el => el.text || el.contentDescription);

  // ── Visual phone view ──────────────────────────────────────────────────
  const renderVisualView = () => (
    <div className="sr-phone-frame">
      <div className="sr-phone-bezel">
        <div className="sr-phone-camera-bar">
          <div className="sr-phone-camera" />
          <div className="sr-phone-speaker" />
        </div>
        <div className="sr-phone-screen" style={{ width: PHONE_W, height: PHONE_H, position: 'relative', overflow: 'hidden', background: screenData ? '#000' : '#0f172a' }}>
          {!screenData && (
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#94a3b8' }}>
              <div style={{ fontSize: 36 }}>📺</div>
              <div style={{ fontSize: 12, marginTop: 8 }}>{streaming ? 'Waiting for screen data…' : 'Press Read or Stream'}</div>
            </div>
          )}
          {screenData && (
            <>
              <div className="sr-phone-status-bar">
                <span>{screenData.packageName?.split('.').pop() || 'App'}</span>
                <span style={{ marginLeft: 'auto' }}>
                  {new Date().getHours()}:{String(new Date().getMinutes()).padStart(2,'0')}
                </span>
              </div>
              {/* Render elements as positioned boxes */}
              {visibleEls.map((el, i) => {
                if (!el.bounds) return null;
                const left   = el.bounds.left   * scaleX;
                const top    = (el.bounds.top   * scaleY) + 20; // offset for status bar
                const width  = (el.bounds.right - el.bounds.left) * scaleX;
                const height = (el.bounds.bottom - el.bounds.top) * scaleY;
                if (width < 1 || height < 1) return null;
                return (
                  <div
                    key={i}
                    className={`sr-el-box ${el.clickable ? 'clickable' : ''} ${el.editable ? 'editable' : ''}`}
                    style={{ position: 'absolute', left, top, width, height }}
                    onClick={() => el.clickable && handleElementClick(el)}
                    title={el.text || el.contentDescription}
                  >
                    {height > 12 && (
                      <span className="sr-el-label" style={{ fontSize: Math.min(height * 0.4, 9) }}>
                        {(el.text || el.contentDescription || '').slice(0, 20)}
                      </span>
                    )}
                  </div>
                );
              })}
              {touchHint && (
                <div className="sr-touch-ripple" style={{ left: touchHint.x - 12, top: touchHint.y - 12 }} />
              )}
            </>
          )}
        </div>
        <div className="sr-phone-home-bar">
          <div className="sr-phone-home-pill" />
        </div>
      </div>
    </div>
  );

  // ── Element list view ──────────────────────────────────────────────────
  const renderElementsView = () => (
    <div className="sr-elements-panel">
      {!screenData && <div className="sr-placeholder">No screen data — press Read Screen</div>}
      {screenData && (
        <>
          <div className="sr-meta-bar">
            <span className="sr-pkg-badge">{screenData.packageName || '—'}</span>
            <span style={{ color: '#94a3b8', fontSize: 12 }}>{visibleEls.length} elements</span>
          </div>
          <div className="sr-elements-list">
            {visibleEls.map((el, i) => {
              const cls = (el.className || '').split('.').pop();
              return (
                <div key={i} className="sr-element" style={{ paddingLeft: Math.min((el.depth||0),8)*10 + 8 }}>
                  <div className="sr-el-class">{cls}</div>
                  {el.text && <div className="sr-el-text">"{el.text}"</div>}
                  {el.contentDescription && !el.text && <div className="sr-el-desc">[{el.contentDescription}]</div>}
                  <div className="sr-el-tags">
                    {el.clickable && <span className="sr-tag sr-tag-click">clickable</span>}
                    {el.editable  && <span className="sr-tag sr-tag-edit">editable</span>}
                    {el.selected  && <span className="sr-tag sr-tag-sel">selected</span>}
                    {el.bounds    && <span className="sr-tag" style={{ background: 'transparent', color: '#475569' }}>{Math.round(el.bounds.left)},{Math.round(el.bounds.top)}</span>}
                  </div>
                </div>
              );
            })}
          </div>
        </>
      )}
    </div>
  );

  return (
    <div className="screen-reader-view sr-redesign">
      <div className="sr-top-bar">
        <div className="sr-nav-section">
          {navBtn('Back', 'press_back', '◀')}
          {navBtn('Home', 'press_home', '🏠')}
          {navBtn('Recents', 'press_recents', '⬜')}
          {navBtn('Notifs', 'open_notifications', '🔔')}
        </div>
        <div className="sr-stream-controls">
          {!streaming ? (
            <button className="sr-btn-primary" onClick={startStreaming} disabled={!isOnline}>
              📡 Stream
            </button>
          ) : (
            <button className="sr-btn-stop" onClick={stopStreaming}>
              ⏹ Stop
            </button>
          )}
          <select
            value={streamInterval}
            onChange={e => setStreamInterval(Number(e.target.value))}
            className="sr-interval-select"
            disabled={!isOnline}
          >
            <option value={500}>0.5s</option>
            <option value={1000}>1s</option>
            <option value={2000}>2s</option>
            <option value={3000}>3s</option>
            <option value={5000}>5s</option>
          </select>
          <button className="sr-btn-capture" onClick={captureOnce} disabled={!isOnline}>
            📷 Read
          </button>
        </div>
        <div className="sr-view-tabs">
          <button className={`sr-vtab ${activeView==='visual'?'active':''}`} onClick={() => setActiveView('visual')}>📱 Visual</button>
          <button className={`sr-vtab ${activeView==='elements'?'active':''}`} onClick={() => setActiveView('elements')}>🌳 Elements</button>
        </div>
      </div>

      <div className="sr-main-layout">
        <div className="sr-phone-col">
          {activeView === 'visual'   && renderVisualView()}
          {activeView === 'elements' && renderElementsView()}

          {screenData && (
            <div className="sr-info-bar">
              <span style={{ color: '#7c3aed' }}>{screenData.packageName}</span>
              <span style={{ color: '#94a3b8' }}>{elements.length} nodes · {visibleEls.length} visible</span>
              {streaming && <span style={{ color: '#22c55e' }}>● streaming</span>}
              {latestScreenResult?.time && <span style={{ color: '#64748b' }}>{latestScreenResult.time.toLocaleTimeString()}</span>}
            </div>
          )}

          {screenData && (
            <div className="sr-save-bar">
              <button className="sr-btn-save" onClick={saveCapture} disabled={!isOnline}>
                💾 Save Capture
              </button>
              <span style={{ fontSize: 11, color: '#64748b' }}>Saves to /sdcard/screen_reader/</span>
            </div>
          )}
        </div>

        <div className="sr-captures-col">
          <div className="sr-saved-header">💾 Saved Captures ({savedCaptures.length})</div>
          {savedCaptures.length === 0 ? (
            <div className="sr-rec-empty">
              <div>💾</div>
              <div>No captures yet</div>
            </div>
          ) : (
            <div className="sr-saved-list">
              {savedCaptures.map(cap => (
                <div key={cap.id} className="sr-saved-item">
                  <div className="sr-saved-info">
                    <div className="sr-saved-pkg">{cap.packageName || 'Unknown'}</div>
                    <div className="sr-saved-meta">
                      <span>{cap.elementCount} els</span>
                      <span>{cap.timestamp}</span>
                    </div>
                  </div>
                  <div className="sr-saved-actions">
                    <button className="sc-action-btn sc-view" onClick={() => setViewCapture(cap)} title="View">👁</button>
                    <button className="sc-action-btn sc-dl" onClick={() => downloadCapture(cap)} title="Download">⬇</button>
                    <button className="sc-action-btn sc-del" onClick={() => setSavedCaptures(p => p.filter(c => c.id !== cap.id))} title="Delete">🗑</button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {viewCapture && (
        <div className="modal-overlay" onClick={() => setViewCapture(null)}>
          <div className="modal-box" style={{ maxWidth: 600, maxHeight: '80vh', overflow: 'auto' }} onClick={e => e.stopPropagation()}>
            <div className="modal-title">📺 {viewCapture.packageName} — {viewCapture.timestamp}</div>
            <pre style={{ fontSize: 12, whiteSpace: 'pre-wrap', color: '#94a3b8', maxHeight: 400, overflow: 'auto', background: '#0f0f1a', padding: 12, borderRadius: 8 }}>{viewCapture.text}</pre>
            <div className="modal-actions">
              <button className="btn-secondary" onClick={() => setViewCapture(null)}>Close</button>
              <button className="btn-primary" onClick={() => downloadCapture(viewCapture)}>⬇ Download</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function buildTextDump(screenData) {
  const lines = [];
  lines.push(`=== Screen Capture ===`);
  lines.push(`Package: ${screenData.packageName || 'Unknown'}`);
  lines.push(`Captured: ${new Date().toISOString()}`);
  lines.push(`Elements: ${(screenData.elements || []).length}`);
  lines.push('');
  (screenData.elements || []).forEach(el => {
    if (!el.text && !el.contentDescription) return;
    const indent = '  '.repeat(Math.min(el.depth || 0, 8));
    const cls    = (el.className || '').split('.').pop();
    lines.push(`${indent}[${cls}]`);
    if (el.text) lines.push(`${indent}  Text: "${el.text}"`);
    if (el.contentDescription) lines.push(`${indent}  Desc: ${el.contentDescription}`);
    const attrs = [];
    if (el.clickable) attrs.push('clickable');
    if (el.editable) attrs.push('editable');
    if (attrs.length) lines.push(`${indent}  (${attrs.join(', ')})`);
    if (el.bounds) lines.push(`${indent}  Bounds: ${JSON.stringify(el.bounds)}`);
  });
  return lines.join('\n');
}
