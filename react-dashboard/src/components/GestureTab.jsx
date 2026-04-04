import React, { useState, useEffect, useRef, useCallback } from 'react';

const COLORS = ['#00ff88', '#00ccff', '#ff6b35', '#ffd700', '#cc77ff'];

function findPatternBox(elements, deviceW, deviceH) {
  for (const el of elements) {
    const vid = (el.viewId || '').toLowerCase();
    if ((vid.includes('lockpatternview') || vid.includes('lock_pattern')) && el.bounds) {
      const w = el.bounds.right - el.bounds.left;
      const h = el.bounds.bottom - el.bounds.top;
      if (w > 50 && h > 50) return el.bounds;
    }
  }
  for (const el of elements) {
    const cls = (el.className || '').toLowerCase();
    if (cls.includes('lockpattern') && el.bounds) {
      const w = el.bounds.right - el.bounds.left;
      const h = el.bounds.bottom - el.bounds.top;
      if (w > 50 && h > 50) return el.bounds;
    }
  }
  const candidates = elements.filter(el => {
    if (!el.bounds) return false;
    if (el.text || el.contentDescription || el.hintText) return false;
    if (el.editable || el.scrollable) return false;
    const w = el.bounds.right - el.bounds.left;
    const h = el.bounds.bottom - el.bounds.top;
    if (w < 150 || h < 150) return false;
    const ratio = w / h;
    if (ratio < 0.75 || ratio > 1.35) return false;
    const centerY = (el.bounds.top + el.bounds.bottom) / 2;
    if (centerY < (deviceH || 1600) * 0.25) return false;
    return el.clickable;
  });
  if (!candidates.length) return null;
  candidates.sort((a, b) => {
    const ra = Math.abs(1 - (a.bounds.right - a.bounds.left) / (a.bounds.bottom - a.bounds.top));
    const rb = Math.abs(1 - (b.bounds.right - b.bounds.left) / (b.bounds.bottom - b.bounds.top));
    if (Math.abs(ra - rb) < 0.08) {
      const aa = (a.bounds.right - a.bounds.left) * (a.bounds.bottom - a.bounds.top);
      const ab = (b.bounds.right - b.bounds.left) * (b.bounds.bottom - b.bounds.top);
      return ab - aa;
    }
    return ra - rb;
  });
  return candidates[0].bounds;
}

function remapNodesToBox(nodes, box, deviceW, deviceH) {
  const nxVals = nodes.map(n => n.nx);
  const nyVals = nodes.map(n => n.ny);
  const minNx = Math.min(...nxVals), maxNx = Math.max(...nxVals);
  const minNy = Math.min(...nyVals), maxNy = Math.max(...nyVals);
  const rangeNx = maxNx - minNx || 1;
  const rangeNy = maxNy - minNy || 1;
  const boxW = box.right  - box.left;
  const boxH = box.bottom - box.top;
  const mgn = 0.17;
  const innerL = box.left + boxW * mgn;
  const innerT = box.top  + boxH * mgn;
  const innerW = boxW * (1 - 2 * mgn);
  const innerH = boxH * (1 - 2 * mgn);
  return nodes.map(n => ({
    nx:   (innerL + ((n.nx - minNx) / rangeNx) * innerW) / (deviceW  || 720),
    ny:   (innerT + ((n.ny - minNy) / rangeNy) * innerH) / (deviceH || 1600),
    node: n.node,
  }));
}

const PATTERN_SIZES = [
  { id: 'large',    label: 'Large',    nodeR: 24, frameW: 280, frameH: 480, fontSize: 14, hintSize: 11 },
  { id: 'normal',   label: 'Normal',   nodeR: 18, frameW: 240, frameH: 420, fontSize: 12, hintSize: 10 },
  { id: 'medium',   label: 'Medium',   nodeR: 14, frameW: 200, frameH: 360, fontSize: 10, hintSize: 9 },
  { id: 'small',    label: 'Small',    nodeR: 10, frameW: 170, frameH: 300, fontSize: 8,  hintSize: 8 },
  { id: 'mini',     label: 'Mini',     nodeR: 7,  frameW: 140, frameH: 250, fontSize: 7,  hintSize: 7 },
];

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

function PatternDrawer({ onSend, isOnline, sizePreset }) {
  const GRID = 3;
  const { nodeR: NODE_R, frameW: FRAME_W, frameH: FRAME_H, fontSize: FONT_SIZE, hintSize: HINT_SIZE } = sizePreset;
  const STATUS_H   = 32;
  const EMERG_H    = 28;
  const H_MARGIN   = Math.round(FRAME_W * 0.22);
  const H_SPACING  = (FRAME_W - 2 * H_MARGIN) / (GRID - 1);
  const GRID_TOP   = Math.round(FRAME_H * 0.52);
  const V_SPACING  = Math.round(FRAME_H * 0.155);

  const nodePos = (idx) => {
    const col = idx % GRID;
    const row = Math.floor(idx / GRID);
    return {
      x: H_MARGIN + col * H_SPACING,
      y: GRID_TOP + row * V_SPACING,
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
  }, [FRAME_W, FRAME_H, NODE_R, GRID_TOP, H_MARGIN, H_SPACING, V_SPACING, EMERG_H]);

  const getSVGPos = useCallback((clientX, clientY) => {
    if (!svgRef.current) return { x: 0, y: 0 };
    const rect = svgRef.current.getBoundingClientRect();
    return {
      x: (clientX - rect.left) * (FRAME_W / rect.width),
      y: (clientY - rect.top)  * (FRAME_H / rect.height),
    };
  }, [FRAME_W, FRAME_H]);

  const onPointerDown = useCallback((e) => {
    if (!isOnline) return;
    e.preventDefault();
    try { e.currentTarget.setPointerCapture(e.pointerId); } catch (_) {}
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

  const onPointerUp = useCallback(() => {
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
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10 }}>
      <div style={{
        background: '#0f1923',
        border: '3px solid #334155',
        borderRadius: 28,
        padding: '10px 8px 12px',
        boxShadow: '0 8px 32px rgba(0,0,0,0.5), inset 0 0 0 1px rgba(255,255,255,0.04)',
        position: 'relative',
        width: FRAME_W + 16,
        transition: 'width 0.2s',
      }}>
        <div style={{ width: 50, height: 5, background: '#1e293b', borderRadius: 3, margin: '0 auto 8px' }} />

        <svg
          ref={svgRef}
          width={FRAME_W}
          height={FRAME_H}
          style={{
            display: 'block',
            background: '#0b1220',
            borderRadius: 16,
            cursor: isOnline ? 'crosshair' : 'not-allowed',
            touchAction: 'none',
            userSelect: 'none',
            transition: 'width 0.2s, height 0.2s',
          }}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerLeave={onPointerUp}
        >
          <rect x={0} y={0} width={FRAME_W} height={STATUS_H} fill="#0d1929" />
          <text x={FRAME_W / 2} y={STATUS_H / 2 + 5} textAnchor="middle" fill="#475569" fontSize={HINT_SIZE - 1} fontFamily="monospace">
            12:00
          </text>

          <text x={FRAME_W / 2} y={Math.round(FRAME_H * 0.22)} textAnchor="middle" fill="#374151" fontSize={FONT_SIZE + 6} fontFamily="system-ui">🔒</text>
          <text x={FRAME_W / 2} y={Math.round(FRAME_H * 0.35)} textAnchor="middle" fill="#374151" fontSize={HINT_SIZE} fontFamily="system-ui">
            {sequence.length > 0 ? sequence.map(n => n + 1).join(' → ') : 'Draw pattern'}
          </text>

          <rect x={0} y={FRAME_H - EMERG_H} width={FRAME_W} height={EMERG_H} fill="#0a111e" />
          <text x={FRAME_W / 2} y={FRAME_H - EMERG_H / 2 + 4} textAnchor="middle" fill="#ef4444" fontSize={HINT_SIZE - 1} fontFamily="system-ui" fontWeight="600">
            Emergency call
          </text>

          {pathD && (
            <path d={pathD} fill="none" stroke="#6366f1" strokeWidth="2.5"
              strokeLinecap="round" strokeLinejoin="round" opacity="0.9" />
          )}

          {drawing && cursorPos && lastNode && (
            <line
              x1={lastNode.x} y1={lastNode.y}
              x2={cursorPos.x} y2={cursorPos.y}
              stroke="#6366f155" strokeWidth="2" strokeDasharray="4 3"
            />
          )}

          {Array.from({ length: GRID * GRID }, (_, i) => {
            const { x, y } = nodePos(i);
            const inSeq    = sequence.includes(i);
            const seqIndex = sequence.indexOf(i);
            return (
              <g key={i}>
                <circle cx={x} cy={y} r={NODE_R} fill="none"
                  stroke={inSeq ? '#6366f1' : '#1e293b'} strokeWidth="2" />
                <circle cx={x} cy={y} r={inSeq ? NODE_R * 0.55 : NODE_R * 0.35}
                  fill={inSeq ? '#6366f1' : '#334155'} />
                {inSeq && (
                  <text x={x} y={y + FONT_SIZE * 0.4} textAnchor="middle" fill="#fff"
                    fontSize={FONT_SIZE} fontWeight="700" fontFamily="monospace">
                    {seqIndex + 1}
                  </text>
                )}
              </g>
            );
          })}

          {!isOnline && (
            <rect x={0} y={0} width={FRAME_W} height={FRAME_H} fill="rgba(0,0,0,0.55)" rx="16" />
          )}
          {!isOnline && (
            <text x={FRAME_W / 2} y={FRAME_H / 2} textAnchor="middle" fill="#64748b" fontSize="14">
              Device offline
            </text>
          )}
        </svg>

        <div style={{ width: 70, height: 4, background: '#334155', borderRadius: 2, margin: '10px auto 0' }} />
      </div>

      <div style={{ display: 'flex', gap: 8, width: '100%', maxWidth: FRAME_W + 16 }}>
        <button
          onClick={sendPattern}
          disabled={!isOnline || sequence.length < 2}
          style={{
            flex: 1, padding: '8px 0', borderRadius: 8, border: 'none', cursor: 'pointer',
            background: sequence.length >= 2 && isOnline ? '#6366f1' : '#1e293b',
            color: sequence.length >= 2 && isOnline ? '#fff' : '#475569',
            fontWeight: 700, fontSize: 12, transition: 'background 0.2s',
          }}
        >
          Send Pattern to Device
        </button>
        <button
          onClick={clearPattern}
          style={{
            padding: '8px 12px', borderRadius: 8, border: '1px solid #334155',
            background: 'transparent', color: '#64748b', cursor: 'pointer', fontSize: 12,
          }}
        >
          Clear
        </button>
      </div>

      <div style={{ fontSize: 10, color: '#334155', textAlign: 'center' }}>
        Drag through dots to draw a pattern, then send to device
      </div>
    </div>
  );
}

// ── Live Stream Canvas ────────────────────────────────────────────────────────
function LiveStreamCanvas({ points, replayPoints, replayActive, width = 320, height = 200 }) {
  const canvasRef = useRef(null);
  const rafRef    = useRef(null);
  const pad = 16;

  const drawPoints = useCallback((ctx, pts, w, h, alpha = 1) => {
    if (!pts || pts.length === 0) return;
    const pointers = {};
    pts.forEach(p => {
      if (!pointers[p.id]) pointers[p.id] = [];
      pointers[p.id].push(p);
    });
    Object.entries(pointers).forEach(([pid, pPts], idx) => {
      const color = COLORS[idx % COLORS.length];
      ctx.strokeStyle = color;
      ctx.globalAlpha = alpha * 0.9;
      ctx.lineWidth   = 3;
      ctx.lineCap     = 'round';
      ctx.lineJoin    = 'round';
      ctx.beginPath();
      let started = false;
      pPts.forEach(p => {
        const x = p.nx * (w - pad * 2) + pad;
        const y = p.ny * (h - pad * 2) + pad;
        if (!started) { ctx.moveTo(x, y); started = true; }
        else           ctx.lineTo(x, y);
      });
      ctx.stroke();
      if (pPts.length >= 1) {
        ctx.globalAlpha = alpha;
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.arc(pPts[0].nx * (w - pad * 2) + pad, pPts[0].ny * (h - pad * 2) + pad, 5, 0, Math.PI * 2);
        ctx.fill();
      }
    });
    ctx.globalAlpha = 1;
  }, [pad]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, width, height);
    ctx.fillStyle = '#0b1220';
    ctx.fillRect(0, 0, width, height);
    drawPoints(ctx, points, width, height);
  }, [points, width, height, drawPoints]);

  useEffect(() => {
    if (!replayActive || !replayPoints || replayPoints.length === 0) return;
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');

    const minT = replayPoints[0]?.t ?? 0;
    const maxT = replayPoints[replayPoints.length - 1]?.t ?? 1;
    const totalDur = maxT - minT || 1;
    const REPLAY_DURATION = 2000; // animate over 2s
    const startWall = performance.now();

    const animate = (now) => {
      const elapsed = now - startWall;
      const progress = Math.min(elapsed / REPLAY_DURATION, 1);
      const cutoffT   = minT + progress * totalDur;

      const visible = replayPoints.filter(p => p.t <= cutoffT);

      ctx.clearRect(0, 0, width, height);
      ctx.fillStyle = '#0b1220';
      ctx.fillRect(0, 0, width, height);
      drawPoints(ctx, visible, width, height);

      if (progress < 1) {
        rafRef.current = requestAnimationFrame(animate);
      }
    };

    rafRef.current = requestAnimationFrame(animate);
    return () => { if (rafRef.current) cancelAnimationFrame(rafRef.current); };
  }, [replayActive, replayPoints, width, height, drawPoints]);

  return (
    <canvas
      ref={canvasRef}
      width={width}
      height={height}
      style={{ borderRadius: 10, border: '1px solid #1e293b', display: 'block' }}
    />
  );
}

const btnStyle = (bg, disabled = false) => ({
  background: disabled ? '#1e293b' : bg,
  border: 'none', borderRadius: 6,
  color: disabled ? '#475569' : '#fff',
  padding: '5px 12px', cursor: disabled ? 'not-allowed' : 'pointer',
  fontSize: 12, fontWeight: 600, transition: 'opacity 0.15s',
  opacity: disabled ? 0.6 : 1,
});

const formatDur = ms => ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
const formatTime = ms => ms ? new Date(ms).toLocaleString() : '';

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
  const [sizePreset, setSizePreset]     = useState(PATTERN_SIZES[1]);

  const [smartMode, setSmartMode]       = useState(true);
  const [detectedBox, setDetectedBox]   = useState(null);
  const pendingPatternRef               = useRef(null);
  const waitingForScreenRef             = useRef(false);

  // ── Auto Capture (NO1/NO2) ─────────────────────────────────────────────────
  const [autoCaptureActive, setAutoCaptureActive] = useState(false);
  const [autoCaptureStatus, setAutoCaptureStatus] = useState('');
  const autoCaptureRef = useRef(false);
  const sendCmdRef     = useRef(null);

  // ── Live Stream ────────────────────────────────────────────────────────────
  const [liveActive, setLiveActive]           = useState(false);
  const [livePoints, setLivePoints]           = useState([]);
  const [liveStreamFile, setLiveStreamFile]   = useState(null);
  const [liveStreams, setLiveStreams]          = useState([]);
  const [lsLoading, setLsLoading]             = useState(false);
  const [replayingLive, setReplayingLive]     = useState(null);
  const [replayPoints, setReplayPoints]       = useState([]);
  const [replayActive, setReplayActive]       = useState(false);
  const livePollingRef = useRef(null);
  const replayTimerRef = useRef(null);

  const deviceW = device?.deviceInfo?.screenWidth  || 720;
  const deviceH = device?.deviceInfo?.screenHeight || 1600;

  const seenResults = useRef(new Set());

  const status = msg => setStatusMsg(msg);

  const sendCmd = useCallback((cmd, params = {}) => {
    if (deviceId) sendCommand(deviceId, cmd, params);
  }, [deviceId, sendCommand]);

  // Keep ref updated for cleanup callbacks
  useEffect(() => { sendCmdRef.current = sendCmd; }, [sendCmd]);
  useEffect(() => { autoCaptureRef.current = autoCaptureActive; }, [autoCaptureActive]);

  // ── Auto-stop capture when GestureTab unmounts (tab switch / back) ──────────
  useEffect(() => {
    return () => {
      if (autoCaptureRef.current && sendCmdRef.current) {
        sendCmdRef.current('gesture_auto_capture_stop', {});
      }
      if (livePollingRef.current) clearInterval(livePollingRef.current);
    };
  }, []);

  const loadList = useCallback(() => {
    setLoading(true);
    sendCmd('gesture_list');
  }, [sendCmd]);

  const loadLiveStreams = useCallback(() => {
    setLsLoading(true);
    sendCmd('gesture_live_list');
  }, [sendCmd]);

  // ── Live stream polling ────────────────────────────────────────────────────
  const startLivePolling = useCallback(() => {
    if (livePollingRef.current) clearInterval(livePollingRef.current);
    livePollingRef.current = setInterval(() => {
      sendCmd('gesture_live_points');
    }, 300);
  }, [sendCmd]);

  const stopLivePolling = useCallback(() => {
    if (livePollingRef.current) { clearInterval(livePollingRef.current); livePollingRef.current = null; }
  }, []);

  // ── Result handler ─────────────────────────────────────────────────────────
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

      // Auto Capture responses
      if (r.command === 'gesture_auto_capture_start') {
        if (data.success) {
          setAutoCaptureActive(true);
          const msg = data.locked_first
            ? 'Device was unlocked — locking screen, waking and pressing recents. Capture will begin.'
            : (data.message || 'Auto-capture started');
          setAutoCaptureStatus(msg);
          status(msg);
        } else {
          setAutoCaptureActive(false);
          setAutoCaptureStatus('Failed: ' + (data.error || ''));
          status('Auto-capture failed: ' + (data.error || ''));
        }
      }
      if (r.command === 'gesture_auto_capture_stop') {
        setAutoCaptureActive(false);
        const msg = data.saved
          ? `Capture stopped — gesture saved (${data.result?.pointCount ?? 0} points)`
          : (data.message || 'Auto-capture stopped');
        setAutoCaptureStatus(msg);
        status(msg);
        if (data.saved) loadList();
      }

      // Live Stream responses
      if (r.command === 'gesture_live_start') {
        if (data.success) {
          setLiveActive(true);
          setLivePoints([]);
          setLiveStreamFile(null);
          status('Live stream started — recording device interaction…');
          startLivePolling();
        } else {
          status('Live stream failed: ' + (data.error || ''));
        }
      }
      if (r.command === 'gesture_live_stop') {
        setLiveActive(false);
        stopLivePolling();
        if (data.success && data.filename) {
          setLiveStreamFile(data.filename);
          status(`Live stream saved: ${data.filename}`);
          loadLiveStreams();
        } else {
          status(data.message || 'Live stream stopped');
        }
      }
      if (r.command === 'gesture_live_points') {
        if (data.success && data.points) {
          const pts = typeof data.points === 'string' ? JSON.parse(data.points) : data.points;
          const mapped = [];
          for (let i = 0; i < pts.length; i++) {
            const p = pts[i];
            mapped.push({ id: p.id ?? 0, nx: p.nx, ny: p.ny, t: p.t ?? i });
          }
          setLivePoints(mapped);
        }
      }
      if (r.command === 'gesture_live_list') {
        setLsLoading(false);
        if (data.success && data.gestures) {
          setLiveStreams(data.gestures.filter(g => g.label && g.label.startsWith('live_')));
        }
      }
      if (r.command === 'gesture_live_delete') {
        if (data.success) {
          setLiveStreams(prev => prev.filter(g => g.filename !== data.filename));
          if (liveStreamFile === data.filename) setLiveStreamFile(null);
          status('Live stream deleted');
        }
      }
      if (r.command === 'gesture_live_replay') {
        setReplayingLive(null);
        if (data.success && data.points) {
          const pts = typeof data.points === 'string' ? JSON.parse(data.points) : data.points;
          const mapped = pts.map((p, i) => ({ id: p.id ?? 0, nx: p.nx, ny: p.ny, t: p.t ?? i }));
          setReplayPoints(mapped);
          setReplayActive(false);
          // brief delay then animate
          setTimeout(() => setReplayActive(true), 50);
          setTimeout(() => setReplayActive(false), 2500);
          status(`Replaying ${data.filename} — drawing ${mapped.length} points`);
        } else {
          status('Replay failed: ' + (data.error || ''));
        }
      }

      if (r.command === 'read_screen' && waitingForScreenRef.current && pendingPatternRef.current) {
        waitingForScreenRef.current = false;
        const screen = data?.screen || data;
        const elements = screen?.elements || [];
        const box = findPatternBox(elements, deviceW, deviceH);
        const { nodes, sequence } = pendingPatternRef.current;
        pendingPatternRef.current = null;
        if (box) {
          setDetectedBox(box);
          const mapped = remapNodesToBox(nodes, box, deviceW, deviceH);
          sendCmd('gesture_draw_pattern', { nodes: mapped, sequence });
          status(`Smart pattern sent — pattern box at ${box.left},${box.top} (${box.right - box.left}×${box.bottom - box.top}px) · nodes: [${sequence.map(n => n + 1).join('→')}]`);
        } else {
          setDetectedBox(null);
          sendCmd('gesture_draw_pattern', { nodes, sequence });
          status('Pattern box not detected — sent using full-screen fallback · nodes: [' + sequence.map(n => n + 1).join('→') + ']');
        }
      }
    });
  }, [results, deviceW, deviceH, loadList, loadLiveStreams, startLivePolling, stopLivePolling]);

  function handleSendPattern(nodes, sequence) {
    // Wake screen → press Recents (1 s delay) → then play the pattern
    status('Waking screen and pressing Recents before pattern…');
    sendCmd('wake_screen');
    setTimeout(() => {
      sendCmd('press_recents');
      setTimeout(() => {
        if (smartMode && isOnline) {
          pendingPatternRef.current = { nodes, sequence };
          waitingForScreenRef.current = true;
          sendCmd('read_screen');
          status('Scanning device screen for pattern area…');
        } else {
          sendCmd('gesture_draw_pattern', { nodes, sequence });
          status(`Sending pattern [${sequence.map(n => n + 1).join('→')}] to device…`);
        }
      }, 1000);
    }, 1000);
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

  // Auto capture controls
  function startAutoCapture() {
    sendCmd('gesture_auto_capture_start');
    setAutoCaptureStatus('Sending start command…');
    status('Starting auto-capture…');
  }

  function stopAutoCapture() {
    sendCmd('gesture_auto_capture_stop');
    setAutoCaptureStatus('Stopping…');
    status('Stopping auto-capture…');
  }

  // Live stream controls
  function startLiveStream() {
    setLivePoints([]);
    setReplayActive(false);
    sendCmd('gesture_live_start');
    status('Starting live stream…');
  }

  function stopLiveStream() {
    sendCmd('gesture_live_stop');
    stopLivePolling();
    status('Stopping live stream…');
  }

  function deleteLiveStream(filename) {
    if (window.confirm(`Delete live stream "${filename}"?`)) {
      sendCmd('gesture_live_delete', { filename });
    }
  }

  function replayLiveStream(filename) {
    setReplayingLive(filename);
    sendCmd('gesture_live_replay', { filename });
    status(`Loading replay for ${filename}…`);
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', fontFamily: 'system-ui,sans-serif', color: '#e2e8f0', background: '#0f172a' }}>

      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '14px 18px', borderBottom: '1px solid #1e293b' }}>
        <span style={{ fontSize: 22 }}>✋</span>
        <div>
          <div style={{ fontWeight: 700, fontSize: 16 }}>Gesture Studio</div>
          <div style={{ fontSize: 12, color: '#64748b' }}>Draw patterns, auto-capture, and live stream device interaction</div>
        </div>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          <button onClick={openGestures} disabled={!isOnline} style={btnStyle('#1d4ed8', !isOnline)}>
            View Saved Gestures ({gestures.length > 0 ? gestures.length : '…'})
          </button>
        </div>
      </div>

      {statusMsg && (
        <div style={{ padding: '8px 18px', background: '#1e293b', fontSize: 12, color: '#94a3b8', borderBottom: '1px solid #1e293b' }}>
          {statusMsg}
        </div>
      )}

      <div style={{ flex: 1, display: 'flex', overflow: 'hidden' }}>

        {/* Left column: pattern drawer */}
        <div style={{ width: 340, borderRight: '1px solid #1e293b', display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '16px 12px', gap: 12, overflowY: 'auto' }}>

          <div style={{ width: '100%', background: '#1e293b', borderRadius: 10, padding: '10px 12px', border: '1px solid #334155' }}>
            <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 8 }}>
              Pattern Size
            </div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {PATTERN_SIZES.map(ps => (
                <button
                  key={ps.id}
                  onClick={() => setSizePreset(ps)}
                  style={{
                    padding: '4px 10px', borderRadius: 6, border: 'none', cursor: 'pointer', fontSize: 11, fontWeight: 600,
                    background: sizePreset.id === ps.id ? '#6366f1' : '#0f172a',
                    color: sizePreset.id === ps.id ? '#fff' : '#64748b',
                    transition: 'background 0.15s',
                  }}
                >
                  {ps.label}
                </button>
              ))}
            </div>
            <div style={{ fontSize: 10, color: '#475569', marginTop: 6 }}>
              Node size: {sizePreset.nodeR}px · Font: {sizePreset.fontSize}px · Frame: {sizePreset.frameW}×{sizePreset.frameH}
            </div>
          </div>

          <div style={{ width: '100%', background: '#1e293b', borderRadius: 10, padding: '10px 12px', border: `1px solid ${smartMode ? '#6366f155' : '#334155'}` }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', textTransform: 'uppercase', letterSpacing: 0.5 }}>
                Smart Mode
              </div>
              <button
                onClick={() => setSmartMode(v => !v)}
                style={{
                  padding: '3px 10px', borderRadius: 20, border: 'none', cursor: 'pointer', fontSize: 11, fontWeight: 700,
                  background: smartMode ? '#6366f1' : '#334155',
                  color: '#fff', transition: 'background 0.2s',
                }}
              >
                {smartMode ? '● ON' : '○ OFF'}
              </button>
            </div>
            <div style={{ fontSize: 10, color: '#475569', lineHeight: 1.5 }}>
              {smartMode
                ? 'Reads the device screen first to locate the exact lock pattern area, then maps the drawn pattern to it.'
                : 'Sends the pattern scaled to the full device screen (manual mode).'}
            </div>
            {smartMode && detectedBox && (
              <div style={{ marginTop: 6, fontSize: 10, color: '#22c55e', padding: '4px 8px', background: '#052e16', borderRadius: 6, border: '1px solid #14532d' }}>
                Last detected pattern box: {detectedBox.left},{detectedBox.top} → {detectedBox.right},{detectedBox.bottom} ({detectedBox.right - detectedBox.left}×{detectedBox.bottom - detectedBox.top}px)
              </div>
            )}
          </div>

          <PatternDrawer onSend={handleSendPattern} isOnline={!!isOnline} sizePreset={sizePreset} />
        </div>

        {/* Right column: Auto Capture + Live Stream */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: 24, gap: 20, overflowY: 'auto' }}>

          {/* ── How to use ─────────────────────────────────────────── */}
          <div style={{ background: '#1e293b', borderRadius: 12, padding: 20, border: '1px solid #334155' }}>
            <div style={{ fontWeight: 700, fontSize: 14, color: '#94a3b8', marginBottom: 12, textTransform: 'uppercase', letterSpacing: 0.5 }}>
              How to use Pattern Draw
            </div>
            <ol style={{ margin: 0, paddingLeft: 18, lineHeight: 2, fontSize: 13, color: '#94a3b8' }}>
              <li>Use the <strong style={{ color: '#6366f1' }}>Pattern Size</strong> toggles to match your target device screen size</li>
              <li>Click and drag through the dots on the phone frame to draw a pattern</li>
              <li>With <strong style={{ color: '#6366f1' }}>Smart Mode ON</strong>, the dashboard reads the device screen first, locates the real lock pattern box, and maps your drawn pattern to its exact position</li>
              <li>With Smart Mode OFF, the pattern is scaled to the full device screen</li>
            </ol>
          </div>

          {/* ── Auto Capture (NO1 / NO2) ────────────────────────────── */}
          <div style={{ background: '#1e293b', borderRadius: 12, padding: 20, border: `1px solid ${autoCaptureActive ? '#16a34a55' : '#334155'}` }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
              <div style={{ fontWeight: 700, fontSize: 14, color: autoCaptureActive ? '#4ade80' : '#94a3b8', textTransform: 'uppercase', letterSpacing: 0.5 }}>
                Auto Capture
              </div>
              {autoCaptureActive && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, color: '#4ade80', background: '#052e16', padding: '2px 8px', borderRadius: 99, fontWeight: 600 }}>
                  <span style={{ animation: 'pulse 1s infinite', display: 'inline-block', width: 7, height: 7, borderRadius: '50%', background: '#4ade80' }}></span>
                  CAPTURING
                </div>
              )}
              <div style={{ fontSize: 11, color: '#6d28d9', background: '#2e1065', padding: '2px 8px', borderRadius: 99, fontWeight: 600, marginLeft: 'auto' }}>
                LOCK SCREEN ONLY
              </div>
            </div>
            <div style={{ fontSize: 13, color: '#64748b', marginBottom: 10, lineHeight: 1.7 }}>
              Creates an <strong style={{ color: '#e2e8f0' }}>invisible silent overlay</strong> on the device that records user input without any visual feedback.
              Only runs when the device is <strong style={{ color: '#e2e8f0' }}>locked</strong>.
              Auto-stops after <strong style={{ color: '#e2e8f0' }}>2 minutes</strong> of no input.
              Stops automatically when you switch tabs here.
            </div>
            <div style={{ fontSize: 12, color: '#475569', marginBottom: 14, padding: '8px 12px', background: '#0f172a', borderRadius: 8, border: '1px solid #1e293b' }}>
              If device is <strong style={{ color: '#94a3b8' }}>unlocked</strong> when you press Start — it will automatically:<br />
              <strong style={{ color: '#a78bfa' }}>① Lock screen → ② Wake screen → ③ Press Recents</strong><br />
              Then capture begins when the lock screen appears.
            </div>
            {autoCaptureStatus && (
              <div style={{ fontSize: 12, color: autoCaptureActive ? '#4ade80' : '#94a3b8', padding: '6px 10px', background: '#0f172a', borderRadius: 6, marginBottom: 12, border: '1px solid #1e293b' }}>
                {autoCaptureStatus}
              </div>
            )}
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <button
                onClick={startAutoCapture}
                disabled={!isOnline || autoCaptureActive}
                style={{ ...btnStyle('#16a34a', !isOnline || autoCaptureActive), padding: '8px 18px' }}
              >
                ⏺ Start Auto-Capture
              </button>
              <button
                onClick={stopAutoCapture}
                disabled={!isOnline || !autoCaptureActive}
                style={{ ...btnStyle('#dc2626', !isOnline || !autoCaptureActive), padding: '8px 18px' }}
              >
                ⏹ Stop & Save
              </button>
            </div>
          </div>

          {/* ── Live Stream ─────────────────────────────────────────── */}
          <div style={{ background: '#1e293b', borderRadius: 12, padding: 20, border: `1px solid ${liveActive ? '#0369a155' : '#334155'}` }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
              <div style={{ fontWeight: 700, fontSize: 14, color: liveActive ? '#38bdf8' : '#94a3b8', textTransform: 'uppercase', letterSpacing: 0.5 }}>
                Live Stream
              </div>
              {liveActive && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, color: '#38bdf8', background: '#082f49', padding: '2px 8px', borderRadius: 99, fontWeight: 600 }}>
                  <span style={{ animation: 'pulse 0.8s infinite', display: 'inline-block', width: 7, height: 7, borderRadius: '50%', background: '#38bdf8' }}></span>
                  STREAMING
                </div>
              )}
            </div>
            <div style={{ fontSize: 13, color: '#64748b', marginBottom: 12, lineHeight: 1.7 }}>
              Silently records all user interaction on the device in real-time.
              View the live interaction below as it happens.
              Replay draws the recorded path directly on the canvas — no overlay on device.
            </div>

            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 16 }}>
              <button
                onClick={startLiveStream}
                disabled={!isOnline || liveActive}
                style={{ ...btnStyle('#0369a1', !isOnline || liveActive), padding: '8px 18px' }}
              >
                ▶ Start Stream
              </button>
              <button
                onClick={stopLiveStream}
                disabled={!isOnline || !liveActive}
                style={{ ...btnStyle('#7c3aed', !isOnline || !liveActive), padding: '8px 18px' }}
              >
                ⏹ Stop & Save
              </button>
              <button
                onClick={loadLiveStreams}
                disabled={lsLoading}
                style={{ ...btnStyle('#334155', lsLoading), padding: '8px 14px' }}
              >
                {lsLoading ? '…' : '↻ Refresh'}
              </button>
            </div>

            {/* Live canvas */}
            <div style={{ marginBottom: 16 }}>
              <div style={{ fontSize: 11, color: '#475569', marginBottom: 6, textTransform: 'uppercase', letterSpacing: 0.5 }}>
                {replayActive ? 'Replaying…' : liveActive ? 'Live interaction' : 'Canvas'}
              </div>
              <LiveStreamCanvas
                points={liveActive ? livePoints : []}
                replayPoints={replayPoints}
                replayActive={replayActive}
                width={460}
                height={200}
              />
            </div>

            {/* Saved live streams list */}
            {liveStreams.length > 0 && (
              <div>
                <div style={{ fontSize: 11, color: '#475569', marginBottom: 8, textTransform: 'uppercase', letterSpacing: 0.5 }}>
                  Saved Streams ({liveStreams.length})
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6, maxHeight: 180, overflowY: 'auto' }}>
                  {liveStreams.map(g => (
                    <div key={g.filename} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 10px', background: '#0f172a', borderRadius: 8, border: '1px solid #1e293b' }}>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontSize: 12, color: '#e2e8f0', fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{g.filename}</div>
                        <div style={{ fontSize: 11, color: '#475569' }}>
                          {g.pointCount != null && `${g.pointCount} pts`}
                          {g.durationMs != null && ` · ${formatDur(g.durationMs)}`}
                        </div>
                      </div>
                      <button
                        onClick={() => replayLiveStream(g.filename)}
                        disabled={!isOnline || replayingLive === g.filename}
                        style={{ ...btnStyle('#1d4ed8', !isOnline || replayingLive === g.filename), padding: '4px 10px', fontSize: 11 }}
                        title="Replay on canvas"
                      >
                        {replayingLive === g.filename ? '…' : '▶ Replay'}
                      </button>
                      <button
                        onClick={() => deleteLiveStream(g.filename)}
                        style={{ ...btnStyle('#7f1d1d'), padding: '4px 8px', fontSize: 11 }}
                        title="Delete stream"
                      >
                        🗑
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}
            {liveStreams.length === 0 && !lsLoading && (
              <div style={{ fontSize: 12, color: '#334155', textAlign: 'center', padding: '12px 0' }}>
                No saved streams yet — start a stream and stop it to save
              </div>
            )}
          </div>

        </div>
      </div>

      <style>{`@keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.4} }`}</style>

      {/* ── Saved Gestures Modal ──────────────────────────────────────────────── */}
      {showGestures && (
        <div
          style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.75)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 9999 }}
          onClick={e => { if (e.target === e.currentTarget) setShowGestures(false); }}
        >
          <div style={{ background: '#1e293b', borderRadius: 14, width: 700, maxWidth: '95vw', maxHeight: '85vh', border: '1px solid #334155', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

            <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '12px 16px', borderBottom: '1px solid #334155', background: '#162032' }}>
              <span style={{ fontWeight: 700, color: '#94a3b8', fontSize: 13, textTransform: 'uppercase', letterSpacing: 1 }}>Saved Gestures</span>
              <span style={{ fontSize: 11, color: '#64748b' }}>{gestures.length} records</span>
              <button onClick={loadList} disabled={loading} style={{ ...btnStyle('#334155', loading), fontSize: 11, padding: '3px 10px', marginLeft: 4 }}>
                {loading ? '…' : '↻ Refresh'}
              </button>
              <button onClick={() => setShowGestures(false)} style={{ marginLeft: 'auto', ...btnStyle('#334155') }}>✕ Close</button>
            </div>

            <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
              <div style={{ width: 260, borderRight: '1px solid #334155', overflowY: 'auto' }}>
                {gestures.length === 0 && !loading && (
                  <div style={{ padding: 24, textAlign: 'center', color: '#475569', fontSize: 13 }}>
                    <div style={{ fontSize: 32, marginBottom: 8 }}>✋</div>
                    No gestures saved yet.
                  </div>
                )}
                {loading && (
                  <div style={{ padding: 24, textAlign: 'center', color: '#475569', fontSize: 13 }}>Loading…</div>
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
                          style={{ ...btnStyle('#1d4ed8', !isOnline || replayingFile === g.filename), fontSize: 11, padding: '3px 8px' }}
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

              <div style={{ flex: 1, padding: 20, display: 'flex', flexDirection: 'column', gap: 16, overflowY: 'auto' }}>
                {!selectedGesture ? (
                  <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', color: '#475569', gap: 12 }}>
                    <div style={{ fontSize: 40 }}>✋</div>
                    <div>Select a gesture from the list to preview</div>
                  </div>
                ) : (
                  <>
                    {selectedData ? (
                      <>
                        <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
                          <div style={{ flex: 1, minWidth: 160 }}>
                            <GesturePreview gesture={selectedData} width={200} height={160} />
                          </div>
                          <div style={{ flex: 1, fontSize: 12, color: '#94a3b8', lineHeight: 2 }}>
                            <div><strong>Label:</strong> {selectedData.label || '—'}</div>
                            <div><strong>Package:</strong> {selectedData.packageId || '—'}</div>
                            <div><strong>Duration:</strong> {selectedData.durationMs ? formatDur(selectedData.durationMs) : '—'}</div>
                            <div><strong>Points:</strong> {selectedData.points?.length ?? '—'}</div>
                            <div><strong>Screen:</strong> {selectedData.screenW}×{selectedData.screenH}</div>
                            <div><strong>Recorded:</strong> {formatTime(selectedData.recordedAt)}</div>
                          </div>
                        </div>
                        <button
                          onClick={() => replay(selectedGesture)}
                          disabled={!isOnline || replayingFile === selectedGesture}
                          style={{ ...btnStyle('#1d4ed8', !isOnline || replayingFile === selectedGesture), width: '100%', padding: '10px 0', fontSize: 13 }}
                        >
                          {replayingFile === selectedGesture ? 'Replaying…' : '▶ Replay on Device'}
                        </button>
                      </>
                    ) : (
                      <div style={{ padding: 24, textAlign: 'center', color: '#475569' }}>Loading gesture data…</div>
                    )}
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
