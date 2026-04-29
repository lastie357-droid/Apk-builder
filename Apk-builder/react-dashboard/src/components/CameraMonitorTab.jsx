import React, { useState, useRef, useEffect, useCallback } from 'react';

const BTN = (props) => (
  <button
    {...props}
    style={{
      background: props.danger ? 'rgba(239,68,68,0.15)' : props.active ? 'rgba(99,102,241,0.25)' : 'rgba(30,27,75,0.8)',
      border: `1px solid ${props.danger ? 'rgba(239,68,68,0.5)' : props.active ? '#6366f1' : 'rgba(99,102,241,0.3)'}`,
      color: props.danger ? '#f87171' : props.active ? '#a5b4fc' : '#94a3b8',
      borderRadius: 8,
      padding: '7px 14px',
      fontSize: 13,
      cursor: props.disabled ? 'not-allowed' : 'pointer',
      opacity: props.disabled ? 0.5 : 1,
      fontWeight: 500,
      whiteSpace: 'nowrap',
      display: 'inline-flex',
      alignItems: 'center',
      gap: 6,
      ...props.style,
    }}
    onClick={props.disabled ? undefined : props.onClick}
  >
    {props.children}
  </button>
);

function formatTime(sec) {
  const m = Math.floor(sec / 60).toString().padStart(2, '0');
  const s = (sec % 60).toString().padStart(2, '0');
  return `${m}:${s}`;
}

function formatBytes(b) {
  if (!b) return '—';
  if (b < 1024) return `${b} B`;
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
  return `${(b / 1024 / 1024).toFixed(2)} MB`;
}

export default function CameraMonitorTab({ device, sendCommand, results }) {
  const deviceId = device?.deviceId;
  const isOnline = device?.isOnline;
  const token = localStorage.getItem('admin_token');

  // ── Camera selection ──────────────────────────────────────────────────
  const [selectedCamera, setSelectedCamera] = useState('0');
  const [cameras, setCameras] = useState([]);

  // ── Stream state ──────────────────────────────────────────────────────
  const [streaming, setStreaming] = useState(false);
  const streamingRef = useRef(false);
  const [hasFrame, setHasFrame] = useState(false);
  const [fps, setFps] = useState(0);
  const [streamIdle, setStreamIdle] = useState(false);
  const [intervalMs, setIntervalMs] = useState(2000);
  const lastFrameTime = useRef(null);
  const lastPollTs = useRef(0);
  const idleTimerRef = useRef(null);
  const autoStopRef = useRef(null);

  // ── Recording state ───────────────────────────────────────────────────
  const [recording, setRecording] = useState(false);
  const recordingRef = useRef(false);
  const [recSeconds, setRecSeconds] = useState(0);
  const recTimerRef = useRef(null);
  const [recFilename, setRecFilename] = useState(null);

  // ── Dot hide state ────────────────────────────────────────────────────
  const [dotHidden, setDotHidden] = useState(false);
  const [dotLoading, setDotLoading] = useState(false);

  // ── Recordings list ───────────────────────────────────────────────────
  const [recordings, setRecordings] = useState([]);
  const [loadingRecs, setLoadingRecs] = useState(false);
  const [downloadingFile, setDownloadingFile] = useState(null);

  // ── Canvas / render ───────────────────────────────────────────────────
  const canvasRef = useRef(null);
  const rafRef = useRef(null);
  const frameCountRef = useRef(0);

  useEffect(() => {
    streamingRef.current = streaming;
  }, [streaming]);
  useEffect(() => {
    recordingRef.current = recording;
  }, [recording]);

  // ── Watch command results for recordings list & downloads ─────────────
  useEffect(() => {
    if (!results || !Array.isArray(results)) return;
    const latest = results[results.length - 1];
    if (!latest) return;
    const resp = latest.response;
    // Available cameras list
    if (resp?.cameras && Array.isArray(resp.cameras)) {
      setCameras(resp.cameras);
      if (resp.cameras.length > 0 && !selectedCamera) {
        setSelectedCamera(resp.cameras[0].cameraId || resp.cameras[0].id || '0');
      }
    }
    // Recordings list
    if (resp?.recordings !== undefined) {
      setRecordings(resp.recordings || []);
      setLoadingRecs(false);
    }
    // Recording download — trigger browser download when data arrives
    if (resp?.data && resp?.filename && resp?.mimeType === 'video/mp4') {
      try {
        const binary = atob(resp.data);
        const bytes = new Uint8Array(binary.length);
        for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
        const blob = new Blob([bytes], { type: 'video/mp4' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = resp.filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
      } catch (e) {
        console.error('Download error:', e);
      }
      setDownloadingFile(null);
    }
  }, [results]);

  // ── Load cameras on mount (only if not already loaded) ────────────────
  // Avoid re-requesting on every reconnect / isOnline flip — the camera
  // list is static for a given device.
  useEffect(() => {
    if (!deviceId || !isOnline) return;
    if (cameras.length > 0) return;
    sendCommand(deviceId, 'get_available_cameras', {});
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deviceId, isOnline]);

  // ── Paint frame onto canvas ───────────────────────────────────────────
  const paintFrame = useCallback((base64) => {
    if (!base64) return;
    frameCountRef.current += 1;
    const now = Date.now();
    if (lastFrameTime.current) {
      const diff = now - lastFrameTime.current;
      if (diff > 0) setFps(Math.round(1000 / diff));
    }
    lastFrameTime.current = now;
    setStreamIdle(false);
    setHasFrame(true);
    if (idleTimerRef.current) clearTimeout(idleTimerRef.current);
    idleTimerRef.current = setTimeout(() => setStreamIdle(true), 8000);

    const img = new window.Image();
    img.onload = () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current);
      rafRef.current = requestAnimationFrame(() => {
        const canvas = canvasRef.current;
        if (!canvas) return;
        const ctx = canvas.getContext('2d');
        if (canvas.width !== img.naturalWidth || canvas.height !== img.naturalHeight) {
          canvas.width = img.naturalWidth || 640;
          canvas.height = img.naturalHeight || 480;
        }
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      });
    };
    img.src = `data:image/jpeg;base64,${base64}`;
  }, []);

  // ── Polling: pull latest camera frame from server ─────────────────────
  useEffect(() => {
    if (!streaming || !isOnline) return;
    const POLL_MS = Math.max(500, intervalMs);
    const poll = async () => {
      try {
        const r = await fetch(`/api/camera/latest/${deviceId}?token=${encodeURIComponent(token)}`);
        if (!r.ok) return;
        const d = await r.json();
        if (d.success && d.frameData && (d._ts || 0) > lastPollTs.current) {
          lastPollTs.current = d._ts || Date.now();
          paintFrame(d.frameData);
        }
      } catch (_) {}
    };
    poll();
    const id = setInterval(poll, POLL_MS);
    return () => clearInterval(id);
  }, [streaming, isOnline, deviceId, token, intervalMs, paintFrame]);

  // ── Cleanup on unmount ────────────────────────────────────────────────
  useEffect(() => () => {
    if (streamingRef.current) sendCommand(deviceId, 'camera_stream_stop', {});
    if (recordingRef.current) sendCommand(deviceId, 'camera_record_stop', {});
    if (autoStopRef.current) clearTimeout(autoStopRef.current);
    if (idleTimerRef.current) clearTimeout(idleTimerRef.current);
    if (recTimerRef.current) clearInterval(recTimerRef.current);
    if (rafRef.current) cancelAnimationFrame(rafRef.current);
  }, []);

  // ── Stream controls ───────────────────────────────────────────────────
  const startStream = useCallback(() => {
    if (streamingRef.current) return;
    sendCommand(deviceId, 'camera_stream_start', { cameraId: selectedCamera, intervalMs });
    setStreaming(true);
    lastPollTs.current = 0;
    setFps(0);
    setHasFrame(false);
    setStreamIdle(false);
    if (autoStopRef.current) clearTimeout(autoStopRef.current);
    autoStopRef.current = setTimeout(() => {
      if (streamingRef.current) {
        sendCommand(deviceId, 'camera_stream_stop', {});
        setStreaming(false);
        setFps(0);
      }
    }, 10 * 60 * 1000);
  }, [deviceId, selectedCamera, intervalMs, sendCommand]);

  const stopStream = useCallback(() => {
    sendCommand(deviceId, 'camera_stream_stop', {});
    setStreaming(false);
    setFps(0);
    if (autoStopRef.current) clearTimeout(autoStopRef.current);
  }, [deviceId, sendCommand]);

  // ── Record controls ───────────────────────────────────────────────────
  const startRecord = useCallback(() => {
    if (recordingRef.current) return;
    if (!streamingRef.current) startStream();
    sendCommand(deviceId, 'camera_record_start', { cameraId: selectedCamera });
    setRecording(true);
    setRecSeconds(0);
    setRecFilename(null);
    recTimerRef.current = setInterval(() => setRecSeconds(s => s + 1), 1000);
  }, [deviceId, selectedCamera, startStream, sendCommand]);

  const stopRecord = useCallback(() => {
    sendCommand(deviceId, 'camera_record_stop', {});
    setRecording(false);
    if (recTimerRef.current) { clearInterval(recTimerRef.current); recTimerRef.current = null; }
    setTimeout(() => fetchRecordings(), 1500);
  }, [deviceId, sendCommand]);

  // ── Dot toggle ────────────────────────────────────────────────────────
  const toggleDot = useCallback(async () => {
    setDotLoading(true);
    const cmd = dotHidden ? 'camera_show_dot' : 'camera_hide_dot';
    sendCommand(deviceId, cmd, {});
    setTimeout(() => {
      setDotHidden(h => !h);
      setDotLoading(false);
    }, 800);
  }, [deviceId, dotHidden, sendCommand]);

  // ── Recordings list ───────────────────────────────────────────────────
  const fetchRecordings = useCallback(() => {
    setLoadingRecs(true);
    sendCommand(deviceId, 'list_camera_recordings', {});
    setTimeout(() => setLoadingRecs(false), 2000);
  }, [deviceId, sendCommand]);

  const downloadRecording = useCallback((filename) => {
    setDownloadingFile(filename);
    sendCommand(deviceId, 'get_camera_recording', { filename });
    setTimeout(() => setDownloadingFile(null), 10000);
  }, [deviceId, sendCommand]);

  const deleteRecording = useCallback((filename) => {
    sendCommand(deviceId, 'delete_camera_recording', { filename });
    setRecordings(prev => prev.filter(r => r.filename !== filename));
  }, [deviceId, sendCommand]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, flex: 1, minHeight: 0 }}>

      {/* ── Top controls bar ── */}
      <div style={{
        display: 'flex', flexWrap: 'wrap', gap: 10, alignItems: 'center',
        background: 'rgba(15,12,41,0.6)', border: '1px solid rgba(99,102,241,0.2)',
        borderRadius: 10, padding: '10px 14px',
      }}>
        {/* Camera selector */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ color: '#64748b', fontSize: 12 }}>Camera</span>
          <button
            onClick={() => { if (selectedCamera === '0') setSelectedCamera('1'); else setSelectedCamera('0'); }}
            disabled={streaming}
            style={{
              background: 'rgba(30,27,75,0.8)', border: '1px solid rgba(99,102,241,0.3)',
              color: '#a5b4fc', borderRadius: 6, padding: '5px 12px', fontSize: 12,
              cursor: streaming ? 'not-allowed' : 'pointer', opacity: streaming ? 0.5 : 1,
            }}
          >
            {selectedCamera === '0' ? '📷 Back' : '🤳 Front'}
          </button>
        </div>

        {/* Interval selector */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ color: '#64748b', fontSize: 12 }}>Interval</span>
          <select
            value={intervalMs}
            onChange={e => setIntervalMs(Number(e.target.value))}
            disabled={streaming}
            style={{
              background: '#0f0c29', border: '1px solid rgba(99,102,241,0.3)',
              color: '#a5b4fc', borderRadius: 6, padding: '5px 8px', fontSize: 12,
              cursor: streaming ? 'not-allowed' : 'pointer',
            }}
          >
            <option value={1000}>1s</option>
            <option value={2000}>2s</option>
            <option value={3000}>3s</option>
            <option value={5000}>5s</option>
          </select>
        </div>

        {/* Stream controls */}
        {!streaming ? (
          <BTN onClick={startStream} disabled={!isOnline}>▶ Start Stream</BTN>
        ) : (
          <BTN onClick={stopStream} danger>⏹ Stop Stream</BTN>
        )}

        {/* Record controls */}
        {!recording ? (
          <BTN onClick={startRecord} disabled={!isOnline || !streaming} style={{ background: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.3)', color: '#f87171' }}>
            ⏺ Record
          </BTN>
        ) : (
          <BTN onClick={stopRecord} danger active>
            ⏹ Stop {formatTime(recSeconds)}
          </BTN>
        )}

        {/* Hide dot toggle */}
        <BTN onClick={toggleDot} disabled={!isOnline || dotLoading} active={dotHidden}>
          {dotLoading ? '...' : dotHidden ? '🟢 Show Dot' : '🔴 Hide Dot'}
        </BTN>

        {/* Status indicators */}
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 10, alignItems: 'center' }}>
          {streaming && (
            <span style={{
              fontSize: 12, color: streamIdle ? '#64748b' : '#10b981',
              display: 'flex', alignItems: 'center', gap: 4,
            }}>
              <span style={{ display: 'inline-block', width: 7, height: 7, borderRadius: '50%', background: streamIdle ? '#64748b' : '#10b981', animation: streamIdle ? 'none' : 'pulse 1.5s infinite' }} />
              {streamIdle ? 'Idle' : `${fps} fps`}
            </span>
          )}
          {recording && (
            <span style={{ fontSize: 12, color: '#f87171', display: 'flex', alignItems: 'center', gap: 4 }}>
              <span style={{ display: 'inline-block', width: 7, height: 7, borderRadius: '50%', background: '#ef4444', animation: 'pulse 1s infinite' }} />
              REC {formatTime(recSeconds)}
            </span>
          )}
          {dotHidden && (
            <span style={{ fontSize: 11, color: '#f59e0b', background: 'rgba(245,158,11,0.1)', border: '1px solid rgba(245,158,11,0.3)', borderRadius: 6, padding: '2px 8px' }}>
              Dot Hidden
            </span>
          )}
        </div>
      </div>

      {/* ── Main area: feed + recordings ── */}
      <div style={{ display: 'flex', gap: 16, flex: 1, minHeight: 0 }}>

        {/* Camera feed */}
        <div style={{
          flex: 1, background: '#000', borderRadius: 12, overflow: 'hidden',
          border: '1px solid rgba(99,102,241,0.2)', display: 'flex', flexDirection: 'column',
          alignItems: 'center', justifyContent: 'center', minHeight: 320, position: 'relative',
        }}>
          {!streaming && !hasFrame ? (
            <div style={{ textAlign: 'center', color: '#475569' }}>
              <div style={{ fontSize: 48, marginBottom: 12 }}>📷</div>
              <div style={{ fontSize: 14, marginBottom: 6 }}>Camera feed inactive</div>
              <div style={{ fontSize: 12, color: '#334155' }}>Press Start Stream to begin</div>
            </div>
          ) : (
            <canvas
              ref={canvasRef}
              style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain', display: 'block' }}
            />
          )}

          {/* Overlay status */}
          {streaming && !hasFrame && (
            <div style={{
              position: 'absolute', inset: 0, display: 'flex', alignItems: 'center',
              justifyContent: 'center', flexDirection: 'column', gap: 8, color: '#64748b',
            }}>
              <div style={{ fontSize: 32 }}>⏳</div>
              <div style={{ fontSize: 13 }}>Waiting for first frame...</div>
            </div>
          )}

          {/* Recording badge */}
          {recording && (
            <div style={{
              position: 'absolute', top: 12, left: 12,
              background: 'rgba(239,68,68,0.85)', borderRadius: 6, padding: '3px 10px',
              color: '#fff', fontSize: 12, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 5,
            }}>
              <span style={{ width: 7, height: 7, borderRadius: '50%', background: '#fff', display: 'inline-block', animation: 'pulse 1s infinite' }} />
              REC {formatTime(recSeconds)}
            </div>
          )}

          {/* Camera ID badge */}
          {streaming && (
            <div style={{
              position: 'absolute', top: 12, right: 12,
              background: 'rgba(0,0,0,0.6)', borderRadius: 6, padding: '3px 10px',
              color: '#94a3b8', fontSize: 11,
            }}>
              {selectedCamera === '0' ? '📷 Back' : '🤳 Front'}
            </div>
          )}
        </div>

        {/* Recordings panel */}
        <div style={{
          width: 280, background: 'rgba(15,12,41,0.6)',
          border: '1px solid rgba(99,102,241,0.2)', borderRadius: 12,
          display: 'flex', flexDirection: 'column', overflow: 'hidden',
        }}>
          <div style={{
            padding: '12px 14px', borderBottom: '1px solid rgba(99,102,241,0.15)',
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          }}>
            <span style={{ color: '#a5b4fc', fontSize: 13, fontWeight: 600 }}>📹 Recordings</span>
            <button
              onClick={fetchRecordings}
              disabled={!isOnline || loadingRecs}
              style={{
                background: 'transparent', border: 'none', color: '#6366f1',
                fontSize: 12, cursor: 'pointer', padding: '2px 6px',
              }}
            >
              {loadingRecs ? '...' : '↺ Refresh'}
            </button>
          </div>

          <div style={{ flex: 1, overflowY: 'auto', padding: '8px 0' }}>
            {recordings.length === 0 ? (
              <div style={{ textAlign: 'center', color: '#475569', padding: '24px 16px', fontSize: 12 }}>
                <div style={{ fontSize: 24, marginBottom: 8 }}>🎬</div>
                No recordings yet.<br />
                <span style={{ color: '#334155' }}>Press ⏺ Record to start.</span>
              </div>
            ) : recordings.map(rec => (
              <div key={rec.filename} style={{
                padding: '8px 14px', borderBottom: '1px solid rgba(99,102,241,0.08)',
                display: 'flex', flexDirection: 'column', gap: 4,
              }}>
                <div style={{ color: '#cbd5e1', fontSize: 11, wordBreak: 'break-all', fontFamily: 'monospace' }}>
                  {rec.filename}
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <span style={{ color: '#475569', fontSize: 11 }}>
                    {rec.date} · {formatBytes(rec.sizeBytes)}
                  </span>
                  <div style={{ display: 'flex', gap: 4 }}>
                    <button
                      onClick={() => downloadRecording(rec.filename)}
                      disabled={downloadingFile === rec.filename}
                      title="Download via command"
                      style={{
                        background: 'rgba(99,102,241,0.1)', border: '1px solid rgba(99,102,241,0.3)',
                        color: '#818cf8', borderRadius: 4, padding: '2px 6px', fontSize: 11, cursor: 'pointer',
                      }}
                    >
                      {downloadingFile === rec.filename ? '...' : '⬇'}
                    </button>
                    <button
                      onClick={() => deleteRecording(rec.filename)}
                      title="Delete recording"
                      style={{
                        background: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.3)',
                        color: '#f87171', borderRadius: 4, padding: '2px 6px', fontSize: 11, cursor: 'pointer',
                      }}
                    >
                      🗑
                    </button>
                  </div>
                </div>
              </div>
            ))}
          </div>

          {!isOnline && (
            <div style={{
              padding: '8px 14px', background: 'rgba(239,68,68,0.08)',
              borderTop: '1px solid rgba(239,68,68,0.2)',
              color: '#ef4444', fontSize: 11, textAlign: 'center',
            }}>
              Device offline
            </div>
          )}
        </div>
      </div>

      <style>{`
        @keyframes pulse {
          0%, 100% { opacity: 1; }
          50% { opacity: 0.4; }
        }
      `}</style>
    </div>
  );
}
