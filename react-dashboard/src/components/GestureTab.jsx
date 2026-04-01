import React, { useState, useEffect, useRef, useCallback } from 'react';

const COLORS = ['#00ff88', '#00ccff', '#ff6b35', '#ffd700', '#cc77ff'];

// ── Gesture path preview (SVG) ──────────────────────────────────────────────
function GesturePreview({ gesture, width = 200, height = 160 }) {
  if (!gesture || !gesture.points || gesture.points.length === 0) {
    return (
      <div style={{ width, height, background: '#0f172a', borderRadius: 8, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#475569', fontSize: 12 }}>
        No points
      </div>
    );
  }
  const points = gesture.points;
  const pointers = {};
  points.forEach(p => {
    if (!pointers[p.id]) pointers[p.id] = [];
    pointers[p.id].push(p);
  });
  const pad = 10;

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`}
      style={{ background: '#0f172a', borderRadius: 8, display: 'block', border: '1px solid #1e293b' }}>
      <rect width={width} height={height} fill="#0f172a" rx="8" />
      {Object.entries(pointers).map(([pid, pts], idx) => {
        if (pts.length < 2) {
          const p = pts[0];
          if (!p) return null;
          const cx = (p.nx * (width - pad * 2) + pad).toFixed(1);
          const cy = (p.ny * (height - pad * 2) + pad).toFixed(1);
          return <circle key={pid} cx={cx} cy={cy} r="5" fill={COLORS[idx % COLORS.length]} opacity="0.9" />;
        }
        const color = COLORS[idx % COLORS.length];
        const d = pts.map((p, i) =>
          `${i === 0 ? 'M' : 'L'}${(p.nx * (width - pad * 2) + pad).toFixed(1)},${(p.ny * (height - pad * 2) + pad).toFixed(1)}`
        ).join(' ');
        return (
          <g key={pid}>
            <path d={d} fill="none" stroke={color} strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" opacity="0.85" />
            <circle cx={(pts[0].nx * (width - pad * 2) + pad).toFixed(1)} cy={(pts[0].ny * (height - pad * 2) + pad).toFixed(1)} r="5" fill={color} opacity="0.9" />
            <circle cx={(pts[pts.length-1].nx * (width - pad * 2) + pad).toFixed(1)} cy={(pts[pts.length-1].ny * (height - pad * 2) + pad).toFixed(1)} r="4" fill="none" stroke={color} strokeWidth="2" opacity="0.9" />
          </g>
        );
      })}
    </svg>
  );
}

// ── Phone-frame Pattern Drawer (3×3 grid like a lock screen) ───────────────
function PatternDrawer({ onSend, isOnline }) {
  const GRID = 3;
  const NODE_R = 18;
  const FRAME_W = 240;
  const FRAME_H = 420;
  const STATUS_H = 40;
  // Nodes at 25%, 50%, 75% horizontally and ~38%, 52%, 67% vertically
  // to match real Android lock screen pattern proportions precisely
  const H_MARGIN  = 60;                              // 25% of FRAME_W
  const H_SPACING = (FRAME_W - 2 * H_MARGIN) / (GRID - 1); // 60px
  const GRID_TOP  = 160;                             // ~38% of FRAME_H
  const V_SPACING = 60;                              // ~14% of FRAME_H per step

  const nodePos = (idx) => {
    const col = idx % GRID;
    const row = Math.floor(idx / GRID);
    return {
      x: H_MARGIN + col * H_SPACING,  // 60, 120, 180  → nx 0.25, 0.50, 0.75
      y: GRID_TOP + row * V_SPACING,  // 160, 220, 280 → ny 0.38, 0.52, 0.67
    };
  };

  const [sequence, setSequence]   = useState([]);
  const [drawing, setDrawing]     = useState(false);
  const [cursorPos, setCursorPos] = useState(null);
  const svgRef = useRef(null);

  const getNodeAt = useCallback((clientX, clientY) => {
    if (!svgRef.current) return -1;
    const rect = svgRef.current.getBoundingClientRect();
    const sx = (clientX - rect.left) * (FRAME_W / rect.width);
    const sy = (clientY - rect.top)  * (FRAME_H / rect.height);
    for (let i = 0; i < GRID * GRID; i++) {
      const { x, y } = nodePos(i);
      if (Math.hypot(sx - x, sy - y) < NODE_R + 8) return i;
    }
    return -1;
  }, []);

  const getSVGPos = useCallback((clientX, clientY) => {
    if (!svgRef.current) return { x: 0, y: 0 };
    const rect = svgRef.current.getBoundingClientRect();
    return {
      x: (clientX - rect.left) * (FRAME_W / rect.width),
      y: (clientY - rect.top)  * (FRAME_H / rect.height),
    };
  }, []);

  const onPointerDown = useCallback((e) => {
    if (!isOnline) return;
    e.preventDefault();
    e.currentTarget.setPointerCapture(e.pointerId);
    const node = getNodeAt(e.clientX, e.clientY);
    setSequence(node >= 0 ? [node] : []);
    setDrawing(true);
    setCursorPos(getSVGPos(e.clientX, e.clientY));
  }, [isOnline, getNodeAt, getSVGPos]);

  const onPointerMove = useCallback((e) => {
    if (!drawing) return;
    e.preventDefault();
    const pos = getSVGPos(e.clientX, e.clientY);
    setCursorPos(pos);
    const node = getNodeAt(e.clientX, e.clientY);
    if (node >= 0) {
      setSequence(prev => prev.includes(node) ? prev : [...prev, node]);
    }
  }, [drawing, getNodeAt, getSVGPos]);

  const onPointerUp = useCallback((e) => {
    setDrawing(false);
    setCursorPos(null);
  }, []);

  const clearPattern = () => { setSequence([]); setCursorPos(null); setDrawing(false); };

  const sendPattern = () => {
    if (sequence.length < 2 || !isOnline) return;
    const nodes = sequence.map(idx => {
      const { x, y } = nodePos(idx);
      return { nx: x / FRAME_W, ny: y / FRAME_H, node: idx };
    });
    onSend(nodes, sequence);
    setSequence([]);
  };

  // Build path string for drawn lines
  const buildPath = (seq) => {
    if (seq.length < 2) return '';
    return seq.map((idx, i) => {
      const { x, y } = nodePos(idx);
      return `${i === 0 ? 'M' : 'L'}${x},${y}`;
    }).join(' ');
  };

  const pathD = buildPath(sequence);
  const lastNode = sequence.length > 0 ? nodePos(sequence[sequence.length - 1]) : null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12 }}>
      {/* Phone frame */}
      <div style={{
        background: '#0f1923',
        border: '3px solid #334155',
        borderRadius: 32,
        padding: '10px 8px 14px',
        boxShadow: '0 8px 32px rgba(0,0,0,0.5), inset 0 0 0 1px rgba(255,255,255,0.04)',
        position: 'relative',
        width: FRAME_W + 16,
      }}>
        {/* Speaker notch */}
        <div style={{
          width: 60, height: 6, background: '#1e293b', borderRadius: 3,
          margin: '0 auto 8px',
        }} />

        {/* SVG drawing area */}
        <svg
          ref={svgRef}
          width={FRAME_W}
          height={FRAME_H}
          style={{
            display: 'block',
            background: '#0b1220',
            borderRadius: 18,
            cursor: isOnline ? 'crosshair' : 'not-allowed',
            touchAction: 'none',
            userSelect: 'none',
          }}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerLeave={onPointerUp}
        >
          {/* Status bar */}
          <rect x={0} y={0} width={FRAME_W} height={STATUS_H} fill="#0d1929" />
          <text x={FRAME_W / 2} y={STATUS_H / 2 + 6} textAnchor="middle" fill="#64748b" fontSize="12" fontFamily="monospace">
            {sequence.length > 0 ? `Pattern: ${sequence.map(n => n + 1).join(' → ')}` : 'Draw a pattern'}
          </text>

          {/* Drawn path */}
          {pathD && (
            <path d={pathD} fill="none" stroke="#6366f1" strokeWidth="3"
              strokeLinecap="round" strokeLinejoin="round" opacity="0.9" />
          )}

          {/* Cursor line to current finger position */}
          {drawing && cursorPos && lastNode && (
            <line
              x1={lastNode.x} y1={lastNode.y}
              x2={cursorPos.x} y2={cursorPos.y}
              stroke="#6366f155" strokeWidth="2" strokeDasharray="4 3"
            />
          )}

          {/* Grid nodes */}
          {Array.from({ length: GRID * GRID }, (_, i) => {
            const { x, y } = nodePos(i);
            const inSeq    = sequence.includes(i);
            const seqIndex = sequence.indexOf(i);
            return (
              <g key={i}>
                {/* Outer ring */}
                <circle cx={x} cy={y} r={NODE_R} fill="none"
                  stroke={inSeq ? '#6366f1' : '#1e293b'} strokeWidth="2" />
                {/* Inner dot */}
                <circle cx={x} cy={y} r={inSeq ? 10 : 6}
                  fill={inSeq ? '#6366f1' : '#334155'} />
                {/* Sequence number */}
                {inSeq && (
                  <text x={x} y={y + 5} textAnchor="middle" fill="#fff"
                    fontSize="11" fontWeight="700" fontFamily="monospace">
                    {seqIndex + 1}
                  </text>
                )}
              </g>
            );
          })}

          {/* Offline overlay */}
          {!isOnline && (
            <rect x={0} y={0} width={FRAME_W} height={FRAME_H} fill="rgba(0,0,0,0.55)" rx="18" />
          )}
          {!isOnline && (
            <text x={FRAME_W / 2} y={FRAME_H / 2} textAnchor="middle" fill="#64748b" fontSize="14">
              Device offline
            </text>
          )}
        </svg>

        {/* Home bar */}
        <div style={{ width: 80, height: 4, background: '#334155', borderRadius: 2, margin: '10px auto 0' }} />
      </div>

      {/* Controls */}
      <div style={{ display: 'flex', gap: 8, width: '100%', maxWidth: FRAME_W + 16 }}>
        <button
          onClick={sendPattern}
          disabled={!isOnline || sequence.length < 2}
          style={{
            flex: 1, padding: '9px 0', borderRadius: 8, border: 'none', cursor: 'pointer',
            background: sequence.length >= 2 && isOnline ? '#6366f1' : '#1e293b',
            color: sequence.length >= 2 && isOnline ? '#fff' : '#475569',
            fontWeight: 700, fontSize: 13, transition: 'background 0.2s',
          }}
        >
          Send Pattern to Device
        </button>
        <button
          onClick={clearPattern}
          style={{
            padding: '9px 14px', borderRadius: 8, border: '1px solid #334155',
            background: 'transparent', color: '#64748b', cursor: 'pointer', fontSize: 13,
          }}
        >
          Clear
        </button>
      </div>

      <div style={{ fontSize: 11, color: '#334155', textAlign: 'center' }}>
        Draw a pattern by dragging through the dots, then send it to the device
      </div>
    </div>
  );
}

// ── Gesture list item ────────────────────────────────────────────────────────
const btnStyle = (bg) => ({
  background: bg, border: 'none', borderRadius: 6, color: '#fff',
  padding: '5px 12px', cursor: 'pointer', fontSize: 12, fontWeight: 600,
  transition: 'opacity 0.15s',
});

const formatDur = ms => ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
const formatTime = ms => ms ? new Date(ms).toLocaleString() : '';

// ── Main component ───────────────────────────────────────────────────────────
export default function GestureTab({ device, sendCommand, results }) {
  const deviceId = device?.deviceId;
  const isOnline = device?.isOnline;

  const [gestures, setGestures]         = useState([]);
  const [loading, setLoading]           = useState(false);
  const [selectedGesture, setSelected]  = useState(null);
  const [selectedData, setSelectedData] = useState(null);
  const [statusMsg, setStatusMsg]       = useState('');
  const [replayingFile, setReplayingFile] = useState(null);
  const [showGestures, setShowGestures] = useState(false);

  const seenResults = useRef(new Set());

  const status = msg => setStatusMsg(msg);

  const sendCmd = useCallback((cmd, params = {}) => {
    if (deviceId) sendCommand(deviceId, cmd, params);
  }, [deviceId, sendCommand]);

  const loadList = useCallback(() => {
    setLoading(true);
    sendCmd('gesture_list');
  }, [sendCmd]);

  // Process command results
  useEffect(() => {
    if (!results || results.length === 0) return;
    results.forEach(r => {
      if (seenResults.current.has(r.id)) return;
      seenResults.current.add(r.id);
      let data;
      try { data = typeof r.response === 'string' ? JSON.parse(r.response) : r.response; }
      catch (_) { return; }
      if (!data) return;

      if (r.command === 'gesture_list') {
        setLoading(false);
        if (data.success && data.gestures) setGestures(data.gestures);
      }
      if (r.command === 'gesture_get') {
        if (data.success && data.gesture) setSelectedData(data.gesture);
      }
      if (r.command === 'gesture_replay') {
        setReplayingFile(null);
        status(data.success
          ? `Replayed: ${data.filename} (${data.pointCount} pts)`
          : 'Replay failed: ' + (data.error || ''));
      }
      if (r.command === 'gesture_delete') {
        if (data.success) {
          setGestures(prev => prev.filter(g => g.filename !== data.filename));
          if (selectedGesture === data.filename) { setSelected(null); setSelectedData(null); }
          status('Deleted');
        }
      }
      if (r.command === 'gesture_draw_pattern') {
        status(data.success ? 'Pattern sent to device' : 'Pattern failed: ' + (data.error || ''));
      }
      if (r.command === 'gesture_auto_capture_start') {
        status(data.success ? 'Auto-capture started on device' : 'Failed: ' + (data.error || ''));
      }
      if (r.command === 'gesture_auto_capture_stop') {
        status(data.success ? 'Auto-capture stopped' : 'Failed: ' + (data.error || ''));
        if (data.success) loadList();
      }
    });
  }, [results]);

  function handleSendPattern(nodes, sequence) {
    sendCmd('gesture_draw_pattern', { nodes, sequence });
    status(`Sending pattern [${sequence.map(n => n + 1).join('→')}] to device…`);
  }

  function replay(filename) {
    setReplayingFile(filename);
    sendCmd('gesture_replay', { filename });
    status(`Replaying ${filename}…`);
  }

  function selectGesture(g) {
    setSelected(g.filename);
    setSelectedData(null);
    sendCmd('gesture_get', { filename: g.filename });
  }

  function deleteGesture(filename) {
    if (window.confirm(`Delete gesture "${filename}"?`)) {
      sendCmd('gesture_delete', { filename });
    }
  }

  function openGestures() {
    setShowGestures(true);
    loadList();
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', fontFamily: 'system-ui,sans-serif', color: '#e2e8f0', background: '#0f172a' }}>

      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '14px 18px', borderBottom: '1px solid #1e293b' }}>
        <span style={{ fontSize: 22 }}>✋</span>
        <div>
          <div style={{ fontWeight: 700, fontSize: 16 }}>Gesture Studio</div>
          <div style={{ fontSize: 12, color: '#64748b' }}>Draw patterns and replay stored gestures on the device</div>
        </div>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          <button onClick={openGestures} disabled={!isOnline} style={btnStyle('#1d4ed8')}>
            View Saved Gestures ({gestures.length > 0 ? gestures.length : '…'})
          </button>
        </div>
      </div>

      {/* Status bar */}
      {statusMsg && (
        <div style={{ padding: '8px 18px', background: '#1e293b', fontSize: 12, color: '#94a3b8', borderBottom: '1px solid #1e293b' }}>
          {statusMsg}
        </div>
      )}

      {/* Main content: pattern drawer + instructions */}
      <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>

        {/* Left — pattern drawer */}
        <div style={{ width: 320, borderRight: '1px solid #1e293b', display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '24px 16px', gap: 16, overflowY: 'auto' }}>
          <PatternDrawer onSend={handleSendPattern} isOnline={!!isOnline} />
        </div>

        {/* Right — instructions + auto-capture controls */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: 24, gap: 20, overflowY: 'auto' }}>

          <div style={{ background: '#1e293b', borderRadius: 12, padding: 20, border: '1px solid #334155' }}>
            <div style={{ fontWeight: 700, fontSize: 14, color: '#94a3b8', marginBottom: 12, textTransform: 'uppercase', letterSpacing: 0.5 }}>
              How to use Pattern Draw
            </div>
            <ol style={{ margin: 0, paddingLeft: 18, lineHeight: 2, fontSize: 13, color: '#94a3b8' }}>
              <li>Click and drag through the dots on the phone frame to draw a pattern</li>
              <li>The pattern will follow the selected nodes in order</li>
              <li>Click <strong style={{ color: '#6366f1' }}>Send Pattern to Device</strong> to execute it on the device screen</li>
              <li>The device maps the pattern to its actual screen size automatically</li>
            </ol>
          </div>

          <div style={{ background: '#1e293b', borderRadius: 12, padding: 20, border: '1px solid #334155' }}>
            <div style={{ fontWeight: 700, fontSize: 14, color: '#94a3b8', marginBottom: 12, textTransform: 'uppercase', letterSpacing: 0.5 }}>
              Auto-Capture Gestures
            </div>
            <div style={{ fontSize: 13, color: '#64748b', marginBottom: 14, lineHeight: 1.7 }}>
              The device will silently record complex touch gestures while the screen is on.
              Simple taps and straight swipes are ignored — only multi-point patterns and curved paths are stored.
            </div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <button
                onClick={() => { sendCmd('gesture_auto_capture_start'); status('Starting auto-capture…'); }}
                disabled={!isOnline}
                style={{ ...btnStyle('#16a34a'), padding: '8px 18px' }}
              >
                Start Auto-Capture
              </button>
              <button
                onClick={() => { sendCmd('gesture_auto_capture_stop'); status('Stopping auto-capture…'); }}
                disabled={!isOnline}
                style={{ ...btnStyle('#dc2626'), padding: '8px 18px' }}
              >
                Stop & Save
              </button>
              <button
                onClick={openGestures}
                disabled={!isOnline}
                style={{ ...btnStyle('#334155'), padding: '8px 18px' }}
              >
                View Saved Gestures
              </button>
            </div>
          </div>

        </div>
      </div>

      <style>{`@keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.4} }`}</style>

      {/* Saved Gestures Modal */}
      {showGestures && (
        <div
          style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.75)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 9999 }}
          onClick={e => { if (e.target === e.currentTarget) setShowGestures(false); }}
        >
          <div style={{ background: '#1e293b', borderRadius: 14, width: 700, maxWidth: '95vw', maxHeight: '85vh', border: '1px solid #334155', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

            {/* Modal header */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '12px 16px', borderBottom: '1px solid #334155', background: '#162032' }}>
              <span style={{ fontWeight: 700, color: '#94a3b8', fontSize: 13, textTransform: 'uppercase', letterSpacing: 1 }}>Saved Gestures</span>
              <span style={{ fontSize: 11, color: '#64748b' }}>{gestures.length} records</span>
              <button onClick={loadList} disabled={loading} style={{ ...btnStyle('#334155'), fontSize: 11, padding: '3px 10px', marginLeft: 4 }}>
                {loading ? '…' : '↻ Refresh'}
              </button>
              <button onClick={() => setShowGestures(false)} style={{ marginLeft: 'auto', ...btnStyle('#334155') }}>✕ Close</button>
            </div>

            <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
              {/* Gesture list */}
              <div style={{ width: 260, borderRight: '1px solid #334155', overflowY: 'auto' }}>
                {gestures.length === 0 && !loading && (
                  <div style={{ padding: 24, textAlign: 'center', color: '#475569', fontSize: 13 }}>
                    <div style={{ fontSize: 32, marginBottom: 8 }}>✋</div>
                    No gestures saved yet.
                  </div>
                )}
                {gestures.map(g => (
                  <div
                    key={g.filename}
                    onClick={() => selectGesture(g)}
                    style={{
                      padding: '12px 14px', borderBottom: '1px solid #1e293b',
                      cursor: 'pointer',
                      background: selectedGesture === g.filename ? '#1e3a5f' : 'transparent',
                    }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <div>
                        <div style={{ fontWeight: 600, fontSize: 13, color: '#e2e8f0' }}>{g.label || '—'}</div>
                        <div style={{ fontSize: 11, color: '#64748b', marginTop: 2 }}>{g.packageId || '—'}</div>
                      </div>
                      <div style={{ display: 'flex', gap: 4 }}>
                        <button onClick={e => { e.stopPropagation(); replay(g.filename); }}
                          disabled={!isOnline || replayingFile === g.filename}
                          style={{ ...btnStyle('#1d4ed8'), fontSize: 11, padding: '3px 8px' }}
                          title="Replay gesture on device">
                          {replayingFile === g.filename ? '…' : '▶'}
                        </button>
                        <button onClick={e => { e.stopPropagation(); deleteGesture(g.filename); }}
                          style={{ ...btnStyle('#7f1d1d'), fontSize: 11, padding: '3px 8px' }}
                          title="Delete gesture">
                          🗑
                        </button>
                      </div>
                    </div>
                    <div style={{ display: 'flex', gap: 12, marginTop: 5, fontSize: 11, color: '#64748b' }}>
                      {g.pointCount != null && <span>{g.pointCount} pts</span>}
                      {g.durationMs != null && <span>{formatDur(g.durationMs)}</span>}
                      {g.screenW && <span>{g.screenW}×{g.screenH}</span>}
                    </div>
                  </div>
                ))}
              </div>

              {/* Preview panel */}
              <div style={{ flex: 1, padding: 20, display: 'flex', flexDirection: 'column', gap: 16, overflowY: 'auto' }}>
                {!selectedGesture ? (
                  <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', color: '#475569', gap: 12 }}>
                    <div style={{ fontSize: 40 }}>✋</div>
                    <div>Select a gesture from the list to preview</div>
                  </div>
                ) : (
                  <>
                    <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
                      <div>
                        <div style={{ fontSize: 12, color: '#64748b', marginBottom: 6 }}>Gesture Preview</div>
                        {selectedData
                          ? <GesturePreview gesture={selectedData} width={260} height={200} />
                          : <div style={{ width: 260, height: 200, background: '#1e293b', borderRadius: 8, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#475569', fontSize: 12 }}>Loading…</div>
                        }
                      </div>
                      {selectedData && (
                        <div style={{ flex: 1, minWidth: 160 }}>
                          <div style={{ fontSize: 12, color: '#64748b', marginBottom: 6 }}>Details</div>
                          <div style={{ background: '#0f172a', borderRadius: 8, padding: 14, fontSize: 13 }}>
                            {[
                              ['Label',    selectedData.label],
                              ['Package',  selectedData.packageId],
                              ['Points',   selectedData.points?.length],
                              ['Duration', selectedData.durationMs != null ? formatDur(selectedData.durationMs) : '—'],
                              ['Screen',   selectedData.screenW + '×' + selectedData.screenH],
                            ].map(([k, v]) => (
                              <div key={k} style={{ display: 'flex', justifyContent: 'space-between', padding: '4px 0', borderBottom: '1px solid #1e293b' }}>
                                <span style={{ color: '#64748b' }}>{k}</span>
                                <span style={{ color: '#e2e8f0', fontFamily: 'monospace', fontSize: 12 }}>{v}</span>
                              </div>
                            ))}
                          </div>
                          <div style={{ marginTop: 14, display: 'flex', gap: 8 }}>
                            <button onClick={() => replay(selectedGesture)}
                              disabled={!isOnline || replayingFile === selectedGesture}
                              style={{ ...btnStyle('#1d4ed8'), fontWeight: 700, padding: '8px 20px' }}>
                              {replayingFile === selectedGesture ? '⏳ Replaying…' : '▶ Replay on Device'}
                            </button>
                            <button onClick={() => deleteGesture(selectedGesture)}
                              style={{ ...btnStyle('#7f1d1d'), padding: '8px 16px' }}>
                              🗑 Delete
                            </button>
                          </div>
                        </div>
                      )}
                    </div>
                    <div style={{ fontSize: 11, color: '#334155', fontFamily: 'monospace' }}>{selectedGesture}</div>
                  </>
                )}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
