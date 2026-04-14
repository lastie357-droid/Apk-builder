import React, { useState, useRef, useEffect, useCallback } from 'react';

const PHONE_W = 320;
const PHONE_H = 680;
const MAX_RECORDINGS = 100;

function formatDuration(ms) {
  if (!ms || ms <= 0) return '0s';
  const s = Math.floor(ms / 1000);
  const m = Math.floor(s / 60);
  const rem = s % 60;
  return m > 0 ? `${m}m ${rem}s` : `${s}s`;
}

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

async function decompressFramesData(base64Data) {
  const binary = atob(base64Data);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  const stream = new DecompressionStream('gzip');
  const writer = stream.writable.getWriter();
  writer.write(bytes);
  writer.close();
  const chunks = [];
  const reader = stream.readable.getReader();
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
  }
  const total = chunks.reduce((acc, c) => acc + c.length, 0);
  const out = new Uint8Array(total);
  let pos = 0;
  for (const c of chunks) { out.set(c, pos); pos += c.length; }
  return JSON.parse(new TextDecoder().decode(out));
}

export default function ScreenReaderRecorder({ device, sendCommand, results, screenReaderPushData, offlineRecordingVersion }) {
  const deviceId = device?.deviceId;
  const isOnline = device?.isOnline;
  const info     = device?.deviceInfo || {};
  const devW     = info.screenWidth  || 1080;
  const devH     = info.screenHeight || 2340;

  // ── Recording state (tracks what the DEVICE is doing, not the dashboard) ──
  const [deviceRecording, setDeviceRecording] = useState(false);
  const [recElapsed, setRecElapsed]           = useState(0);
  const elapsedTimerRef = useRef(null);

  // ── Live preview frame (from device push, not buffered) ──
  const [liveFrame, setLiveFrame] = useState(null);

  // ── Recording file list (from list_screen_recordings — names only) ──
  const [recordingFiles, setRecordingFiles] = useState([]);
  // ── Fully loaded recordings (from get_screen_recording — full frames) ──
  const [recordings, setRecordings]   = useState([]);
  const [loadingRecs, setLoadingRecs] = useState(false);
  const [lastFetched, setLastFetched] = useState(null);
  const [loadingFile, setLoadingFile] = useState({});
  const seenResultIds = useRef(new Set());

  // ── Playback ──
  const [playing, setPlaying]     = useState(null);
  const [playIdx, setPlayIdx]     = useState(0);
  const [isPlaying, setIsPlaying] = useState(false);
  const [playSpeed, setPlaySpeed] = useState(500);
  const playTimerRef = useRef(null);

  // ── Pending auto-play (filename to play once loaded) ──
  const [pendingPlay, setPendingPlay] = useState(null);

  // ── Ref to scroll phone into view when playback starts ──
  const phoneRef = useRef(null);

  // ── Keep-alive (bypass unlock) ──
  const [keepAlive, setKeepAlive]     = useState(false);
  const keepAliveTimerRef             = useRef(null);

  // ─────────────────────────────────────────────
  // Handle live push data from device (screen:update via SSE)
  // Only update the live preview — no frame buffering in dashboard
  // ─────────────────────────────────────────────
  useEffect(() => {
    if (!screenReaderPushData) return;

    // Handle auto-recording events from device
    if (screenReaderPushData.autoEvent === 'start') {
      setDeviceRecording(true);
      setRecElapsed(0);
      clearInterval(elapsedTimerRef.current);
      elapsedTimerRef.current = setInterval(() => setRecElapsed(e => e + 1), 1000);
      return;
    }
    if (screenReaderPushData.autoEvent === 'stop') {
      setDeviceRecording(false);
      clearInterval(elapsedTimerRef.current);
      setRecElapsed(0);
      setLiveFrame(null);
      return;
    }

    // Regular frame — just update the live preview
    if (screenReaderPushData.success && screenReaderPushData.screen) {
      setLiveFrame(screenReaderPushData.screen);
    }
  }, [screenReaderPushData]);

  // ─────────────────────────────────────────────
  // Handle command results (list_screen_recordings / get_screen_recording)
  // ─────────────────────────────────────────────
  useEffect(() => {
    if (!results) return;
    const relevant = results.filter(r =>
      (r.command === 'list_screen_recordings' || r.command === 'get_screen_recording') &&
      r.success && r.response
    );
    relevant.forEach(r => {
      if (seenResultIds.current.has(r.id || r.commandId)) return;
      seenResultIds.current.add(r.id || r.commandId);
      try {
        const data = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;

        if (r.command === 'list_screen_recordings' && Array.isArray(data.recordings)) {
          // Just store the file list — do NOT auto-fetch each one
          setRecordingFiles(data.recordings);
          setLoadingRecs(false);
          setLastFetched(Date.now());
        }

        if (r.command === 'get_screen_recording' && data.filename) {
          const buildAndStore = (frames) => {
            const rec = {
              id:         data.filename,
              filename:   data.filename,
              label:      data.label || `Recording ${formatTime(data.startTime)}`,
              frames:     frames || [],
              duration:   ((data.endTime || 0) - (data.startTime || 0)) || (data.frameCount || 0) * 1000,
              frameCount: data.frameCount || (frames || []).length,
              startTime:  data.startTime,
              endTime:    data.endTime,
            };
            setLoadingFile(prev => { const n = {...prev}; delete n[data.filename]; return n; });
            setRecordings(prev => {
              if (prev.find(p => p.filename === data.filename)) return prev;
              return [rec, ...prev].slice(0, MAX_RECORDINGS);
            });
            setPendingPlay(data.filename);
          };

          if (data.framesCompressed && data.framesData) {
            decompressFramesData(data.framesData)
              .then(frames => buildAndStore(Array.isArray(frames) ? frames : []))
              .catch(() => {
                setLoadingFile(prev => { const n = {...prev}; delete n[data.filename]; return n; });
              });
          } else if (data.frames) {
            buildAndStore(data.frames);
          }
        }
      } catch (_) {}
    });
  }, [results, deviceId, sendCommand]);

  // ─────────────────────────────────────────────
  // Fetch recordings list from server
  // ─────────────────────────────────────────────
  const fetchRecordings = useCallback(() => {
    if (!deviceId) return;
    setLoadingRecs(true);
    setLastFetched(Date.now());
    sendCommand(deviceId, 'list_screen_recordings', {});
    // Safety timeout — clear spinner if no response
    setTimeout(() => setLoadingRecs(false), 5000);
  }, [deviceId, sendCommand]);

  // Refresh when device signals a new recording was saved
  useEffect(() => {
    if (offlineRecordingVersion && isOnline) {
      setRecordingFiles([]);
      setRecordings([]);
      seenResultIds.current.clear();
      fetchRecordings();
    }
  }, [offlineRecordingVersion, fetchRecordings, isOnline]);

  // ─────────────────────────────────────────────
  // Recording controls — send commands to device
  // ─────────────────────────────────────────────
  const startRecording = useCallback(() => {
    if (!isOnline || !deviceId) return;
    sendCommand(deviceId, 'screen_reader_start', {});
    setDeviceRecording(true);
    setLiveFrame(null);
    setRecElapsed(0);
    clearInterval(elapsedTimerRef.current);
    elapsedTimerRef.current = setInterval(() => setRecElapsed(e => e + 1), 1000);
  }, [deviceId, isOnline, sendCommand]);

  const stopRecording = useCallback(() => {
    if (!deviceId) return;
    sendCommand(deviceId, 'screen_reader_stop', {});
    setDeviceRecording(false);
    setLiveFrame(null);
    clearInterval(elapsedTimerRef.current);
    setRecElapsed(0);
    // Device saves locally; after a short delay, fetch the updated list from the device
    setTimeout(() => {
      setRecordingFiles([]);
      setRecordings([]);
      seenResultIds.current.clear();
      fetchRecordings();
    }, 3500);
  }, [deviceId, sendCommand, fetchRecordings]);

  useEffect(() => () => {
    clearInterval(elapsedTimerRef.current);
    clearInterval(playTimerRef.current);
    clearInterval(keepAliveTimerRef.current);
  }, []);

  // ─────────────────────────────────────────────
  // Playback
  // ─────────────────────────────────────────────
  const stopPlayback = useCallback(() => {
    clearInterval(playTimerRef.current);
    setIsPlaying(false);
  }, []);

  const startPlayback = useCallback((rec, fromIdx = 0) => {
    clearInterval(playTimerRef.current);
    setPlaying(rec);
    setPlayIdx(fromIdx);
    setIsPlaying(true);
    let idx = fromIdx;
    playTimerRef.current = setInterval(() => {
      idx++;
      if (idx >= rec.frames.length) {
        clearInterval(playTimerRef.current);
        setIsPlaying(false);
        setPlayIdx(rec.frames.length - 1);
      } else {
        setPlayIdx(idx);
      }
    }, playSpeed);
  }, [playSpeed]);

  // Auto-play when a pending filename is loaded — also scroll phone into view
  useEffect(() => {
    if (!pendingPlay) return;
    const rec = recordings.find(r => r.filename === pendingPlay);
    if (rec) {
      setPendingPlay(null);
      startPlayback(rec);
      // Scroll the phone viewer into view so the user sees the playback immediately
      setTimeout(() => phoneRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 60);
    }
  }, [pendingPlay, recordings, startPlayback]);

  // ─────────────────────────────────────────────
  // Delete & export
  // ─────────────────────────────────────────────
  const deleteRecording = useCallback((id, filename) => {
    if (playing?.id === id) { stopPlayback(); setPlaying(null); }
    setRecordings(prev => prev.filter(r => r.id !== id));
    // Send delete command to the device — recordings are stored only on Android
    if (filename && deviceId && isOnline) {
      sendCommand(deviceId, 'delete_screen_recording', { filename });
    }
  }, [playing, stopPlayback, deviceId, isOnline, sendCommand]);

  const exportRecording = useCallback((rec) => {
    const blob = new Blob([JSON.stringify(rec, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = rec.filename || `recording_${rec.id}.json`;
    a.click();
    URL.revokeObjectURL(url);
  }, []);

  // ─────────────────────────────────────────────
  // Keep-alive (bypass unlock)
  // ─────────────────────────────────────────────
  useEffect(() => {
    clearInterval(keepAliveTimerRef.current);
    if (keepAlive && isOnline) {
      sendCommand(deviceId, 'wake_screen', {});
      keepAliveTimerRef.current = setInterval(() => sendCommand(deviceId, 'wake_screen', {}), 5000);
    }
    return () => clearInterval(keepAliveTimerRef.current);
  }, [keepAlive, isOnline, deviceId, sendCommand]);

  useEffect(() => {
    if (!isOnline) { setKeepAlive(false); clearInterval(keepAliveTimerRef.current); }
  }, [isOnline]);

  // ─────────────────────────────────────────────
  // Derived display values
  // ─────────────────────────────────────────────
  const displayFrame = playing
    ? (playing.frames[playIdx]?.screen || null)
    : liveFrame;

  const displayPkg      = displayFrame?.packageName || '';
  const totalFrames     = playing?.frames?.length || 0;
  const progress        = totalFrames > 1 ? (playIdx / (totalFrames - 1)) * 100 : 0;
  // Which file is currently being fetched from the device (if any)
  const fetchingFilename = Object.keys(loadingFile).find(k => loadingFile[k]) || null;

  // ─────────────────────────────────────────────
  // Render helpers
  // ─────────────────────────────────────────────
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

  const viewState = deviceRecording ? 'recording' : playing ? 'playback' : 'idle';

  return (
    <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start', flexWrap: 'wrap' }}>

      {/* ── LEFT: Phone viewer + controls ── */}
      <div ref={phoneRef} style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>

        {/* Status badge */}
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
          padding: '5px 14px', borderRadius: 20, alignSelf: 'center',
          background: viewState === 'recording' ? 'rgba(220,38,38,0.14)'
            : viewState === 'playback' ? 'rgba(124,58,237,0.14)'
            : 'rgba(15,23,42,0.7)',
          border: `1px solid ${viewState === 'recording' ? '#dc2626' : viewState === 'playback' ? '#7c3aed' : '#334155'}`,
        }}>
          {viewState === 'recording' && (
            <span style={{ width: 7, height: 7, borderRadius: '50%', background: '#ef4444', display: 'inline-block',
              animation: 'recPulse 1s ease-in-out infinite' }} />
          )}
          <span style={{ fontSize: 9, fontWeight: 800, letterSpacing: 1.5, textTransform: 'uppercase',
            color: viewState === 'recording' ? '#ef4444' : viewState === 'playback' ? '#a78bfa' : '#475569' }}>
            {viewState === 'recording' ? 'Device Recording' : viewState === 'playback' ? 'Playback' : 'Standby'}
          </span>
          {viewState === 'recording' && (
            <span style={{ fontSize: 10, color: '#fca5a5', fontVariantNumeric: 'tabular-nums' }}>
              {formatDuration(recElapsed * 1000)}
            </span>
          )}
          {viewState === 'playback' && (
            <span style={{ fontSize: 10, color: '#c4b5fd', fontVariantNumeric: 'tabular-nums' }}>
              {playIdx + 1} / {totalFrames}
            </span>
          )}
        </div>

        {/* Phone frame */}
        <div style={{
          background: '#1e293b', borderRadius: 28, padding: '16px 10px 12px',
          border: `2px solid ${viewState === 'recording' ? '#dc2626' : viewState === 'playback' ? '#7c3aed' : '#334155'}`,
          display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8,
          boxShadow: viewState === 'recording' ? '0 0 24px rgba(220,38,38,0.3)'
            : viewState === 'playback' ? '0 0 20px rgba(124,58,237,0.2)'
            : '0 4px 20px rgba(0,0,0,0.4)',
          transition: 'all 0.3s',
        }}>
          <div style={{ width: 56, height: 5, background: '#334155', borderRadius: 4, marginBottom: 2 }} />

          {/* Screen area */}
          <div style={{
            width: PHONE_W, height: PHONE_H,
            background: displayFrame ? '#101828' : '#0a0f1e',
            borderRadius: 8, border: '1px solid #1e293b',
            overflow: 'hidden', position: 'relative',
          }}>
            {!displayFrame && (
              <div style={{
                display: 'flex', flexDirection: 'column', alignItems: 'center',
                justifyContent: 'center', height: '100%', gap: 10,
              }}>
                {fetchingFilename ? (
                  <>
                    <div style={{
                      width: 40, height: 40, borderRadius: '50%',
                      border: '3px solid #4c1d95', borderTopColor: '#a78bfa',
                      animation: 'spin 0.9s linear infinite',
                    }} />
                    <div style={{ fontSize: 11, color: '#7c3aed', fontWeight: 700, textAlign: 'center' }}>
                      Fetching recording…
                    </div>
                    <div style={{
                      fontSize: 9, color: '#475569', textAlign: 'center',
                      maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                      padding: '0 10px',
                    }}>
                      {fetchingFilename}
                    </div>
                  </>
                ) : (
                  <>
                    <div style={{ fontSize: 38, opacity: 0.3 }}>🎥</div>
                    <div style={{ fontSize: 11, color: '#334155', textAlign: 'center', lineHeight: 1.7, padding: '0 20px' }}>
                      {viewState === 'recording'
                        ? 'Recording in progress on device\nLive feed will appear here'
                        : viewState === 'playback'
                        ? 'No frame data'
                        : 'Select a recording to play,\nor start a new recording on the device.'}
                    </div>
                  </>
                )}
              </div>
            )}

            {displayFrame && (
              <>
                {/* Status bar overlay */}
                <div style={{
                  position: 'absolute', top: 0, left: 0, right: 0, height: 20,
                  background: 'rgba(0,0,0,0.85)', display: 'flex', alignItems: 'center',
                  padding: '0 8px', zIndex: 50, gap: 6,
                }}>
                  <div style={{
                    width: 5, height: 5, borderRadius: '50%',
                    background: viewState === 'recording' ? '#ef4444' : '#a78bfa',
                    animation: viewState === 'recording' ? 'recPulse 1s ease-in-out infinite' : 'none',
                    flexShrink: 0,
                  }} />
                  <span style={{ fontSize: 8, color: '#94a3b8', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {displayPkg || 'Unknown app'}
                  </span>
                  {displayFrame.elementCount != null && (
                    <span style={{ fontSize: 7, color: '#475569' }}>{displayFrame.elementCount} nodes</span>
                  )}
                  {viewState === 'recording' && (
                    <span style={{ fontSize: 7, color: '#ef4444', fontWeight: 800 }}>● LIVE</span>
                  )}
                </div>

                {renderFrameElements(displayFrame, devW, devH)}
              </>
            )}
          </div>

          {/* Playback scrubber */}
          {playing && totalFrames > 1 && (
            <div style={{ width: PHONE_W, display: 'flex', flexDirection: 'column', gap: 4 }}>
              <div
                style={{ position: 'relative', height: 5, background: '#1e293b', borderRadius: 3, cursor: 'pointer' }}
                onClick={e => {
                  const rect = e.currentTarget.getBoundingClientRect();
                  const ratio = (e.clientX - rect.left) / rect.width;
                  const newIdx = Math.round(ratio * (totalFrames - 1));
                  stopPlayback();
                  setPlayIdx(Math.max(0, Math.min(newIdx, totalFrames - 1)));
                }}
              >
                <div style={{
                  position: 'absolute', top: 0, left: 0, height: '100%',
                  width: `${progress}%`, background: '#7c3aed', borderRadius: 3,
                  transition: 'width 0.1s',
                }} />
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 9, color: '#475569' }}>
                <span>0:00</span>
                <span>{formatDuration(playIdx * playSpeed)}</span>
                <span>{formatDuration(totalFrames * playSpeed)}</span>
              </div>
            </div>
          )}

          <div style={{ width: 60, height: 4, background: '#334155', borderRadius: 4, marginTop: 2 }} />
        </div>

        {/* Now playing info strip */}
        {playing && (
          <div style={{
            background: '#1e1b4b', borderRadius: 10, padding: '8px 14px',
            border: '1px solid #4c1d95',
            display: 'flex', flexDirection: 'column', gap: 3,
          }}>
            <div style={{
              fontSize: 11, fontWeight: 700, color: '#ddd6fe',
              overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
            }}>
              🎞 {playing.label}
            </div>
            <div style={{ display: 'flex', gap: 10, fontSize: 10, color: '#7c3aed', flexWrap: 'wrap' }}>
              <span>⏱ {formatDuration(playing.duration || playing.frameCount * playSpeed)}</span>
              <span>📋 {playing.frameCount} frames</span>
              {playing.startTime && <span>📅 {formatDate(playing.startTime)} {formatTime(playing.startTime)}</span>}
            </div>
          </div>
        )}

        {/* Record / playback controls */}
        <div style={{ display: 'flex', gap: 6, justifyContent: 'center', flexWrap: 'wrap' }}>
          {!deviceRecording ? (
            <Btn
              label="● Start Recording"
              onClick={startRecording}
              bg="#7f1d1d"
              disabled={!isOnline}
              title={!isOnline ? 'Device is offline' : 'Start recording on device'}
            />
          ) : (
            <Btn label="⏹ Stop Recording" onClick={stopRecording} bg="#dc2626"
              title="Stop recording — device will save and upload automatically" />
          )}

          {playing && (
            <>
              {isPlaying
                ? <Btn label="⏸ Pause" onClick={stopPlayback} bg="#334155" />
                : <Btn label="▶ Play" onClick={() => startPlayback(playing, playIdx)} bg="#4c1d95" disabled={totalFrames === 0} />
              }
              <Btn label="⏮" onClick={() => { stopPlayback(); setPlayIdx(0); }} bg="#1e293b" title="Restart from beginning" />
              <Btn label="⬇ Save" onClick={() => exportRecording(playing)} bg="#1e3a5f" title="Download as JSON" />
              <Btn
                label="🗑 Delete"
                onClick={() => {
                  deleteRecording(playing.id, playing.filename);
                  setPlaying(null);
                }}
                bg="#7f1d1d"
                title="Delete this recording from device"
              />
              <Btn label="✕ Close" onClick={() => { stopPlayback(); setPlaying(null); }} bg="#334155" />
            </>
          )}
        </div>

        {/* Playback speed */}
        {playing && (
          <div style={{ display: 'flex', gap: 5, alignItems: 'center', justifyContent: 'center' }}>
            <span style={{ fontSize: 10, color: '#475569' }}>Speed:</span>
            {[200, 500, 1000, 2000].map(ms => (
              <button
                key={ms}
                onClick={() => {
                  setPlaySpeed(ms);
                  if (isPlaying) { stopPlayback(); setTimeout(() => startPlayback(playing, playIdx), 30); }
                }}
                style={{
                  border: `1px solid ${playSpeed === ms ? '#7c3aed' : '#334155'}`,
                  borderRadius: 6, padding: '3px 7px',
                  background: playSpeed === ms ? '#4c1d95' : '#1e293b',
                  color: playSpeed === ms ? '#c4b5fd' : '#475569',
                  cursor: 'pointer', fontSize: 10, fontWeight: 600,
                }}
              >
                {ms === 200 ? '0.2s' : ms === 500 ? '0.5s' : ms === 1000 ? '1s' : '2s'}/frame
              </button>
            ))}
          </div>
        )}

        {/* Bypass Unlock */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4, alignItems: 'center', marginTop: 2 }}>
          <button
            disabled={!isOnline}
            onClick={() => {
              if (keepAlive) {
                setKeepAlive(false);
              } else {
                sendCommand(deviceId, 'screen_off', {});
                setTimeout(() => sendCommand(deviceId, 'wake_screen', {}), 1000);
                setTimeout(() => sendCommand(deviceId, 'press_recents', {}), 1500);
                setKeepAlive(true);
              }
            }}
            style={{
              border: 'none', borderRadius: 8, padding: '7px 22px',
              background: keepAlive
                ? 'linear-gradient(135deg,#14532d,#166534)'
                : (isOnline ? 'linear-gradient(135deg,#4c1d95,#6d28d9)' : '#1e293b'),
              color: keepAlive ? '#86efac' : (isOnline ? '#f1f5f9' : '#475569'),
              cursor: isOnline ? 'pointer' : 'not-allowed',
              fontSize: 11, fontWeight: 700, letterSpacing: 0.5,
              boxShadow: keepAlive ? '0 0 12px rgba(34,197,94,0.35)'
                : (isOnline ? '0 0 10px rgba(109,40,217,0.3)' : 'none'),
              transition: 'all 0.25s', whiteSpace: 'nowrap',
            }}
          >
            {keepAlive ? '🟢 Stop Bypass' : '🔓 Bypass Unlock'}
          </button>
          {keepAlive && (
            <div style={{ fontSize: 9, color: '#22c55e', textAlign: 'center' }}>
              Keeps screen awake · sends wake every 5s
            </div>
          )}
        </div>
      </div>

      {/* ── RIGHT: Recordings list ── */}
      <div style={{ flex: 1, minWidth: 250, display: 'flex', flexDirection: 'column', gap: 8 }}>

        {/* Header */}
        <div style={{
          display: 'flex', alignItems: 'center', gap: 10,
          background: '#0f172a', borderRadius: 10, padding: '10px 14px',
          border: '1px solid #1e293b',
        }}>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 12, fontWeight: 700, color: '#e2e8f0' }}>Saved Recordings</div>
            <div style={{ fontSize: 9, color: '#475569', marginTop: 1 }}>
              {recordingFiles.length > 0
                ? `${recordingFiles.length} file${recordingFiles.length !== 1 ? 's' : ''} on device`
                : 'Click "Get Recordings" to list files'}
              {lastFetched ? ` · synced ${formatTime(lastFetched)}` : ''}
            </div>
          </div>
          <button
            onClick={() => {
              setRecordingFiles([]);
              setRecordings([]);
              seenResultIds.current.clear();
              fetchRecordings();
            }}
            disabled={loadingRecs || !isOnline}
            title={!isOnline ? 'Device must be online to fetch recordings' : 'Fetch recording list from device'}
            style={{
              border: '1px solid #334155', borderRadius: 8, padding: '6px 14px',
              background: '#0f172a',
              color: loadingRecs ? '#475569' : '#94a3b8',
              cursor: (loadingRecs || !isOnline) ? 'not-allowed' : 'pointer',
              fontSize: 11, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 6,
            }}
          >
            <span style={{ display: 'inline-block', animation: loadingRecs ? 'spin 0.8s linear infinite' : 'none', fontSize: 13 }}>↻</span>
            {loadingRecs ? 'Loading…' : 'Get Recordings'}
          </button>
        </div>

        {/* Info banner when device is recording */}
        {deviceRecording && (
          <div style={{
            background: 'rgba(220,38,38,0.08)', border: '1px solid rgba(220,38,38,0.3)',
            borderRadius: 10, padding: '10px 14px',
            display: 'flex', alignItems: 'center', gap: 10,
          }}>
            <span style={{ fontSize: 16 }}>🎙</span>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#fca5a5' }}>Recording in progress on device</div>
              <div style={{ fontSize: 10, color: '#7f1d1d' }}>
                Frames are being captured and stored on the device in real time.
                When you stop, the device will save and upload automatically.
              </div>
            </div>
          </div>
        )}

        {/* Empty state — not yet fetched */}
        {recordingFiles.length === 0 && !loadingRecs && (
          <div style={{
            background: '#0f172a', borderRadius: 12, border: '1px dashed #1e293b',
            padding: '36px 20px', textAlign: 'center',
            display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'center',
          }}>
            <div style={{ fontSize: 36, opacity: 0.35 }}>🎞</div>
            <div style={{ fontSize: 13, color: '#64748b', fontWeight: 600 }}>No recordings listed</div>
            <div style={{ fontSize: 11, color: '#334155', lineHeight: 1.7 }}>
              Click "Get Recordings" to fetch the list of<br />
              recordings saved on the device.
            </div>
          </div>
        )}

        {/* Loading placeholder */}
        {loadingRecs && recordingFiles.length === 0 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {[1, 2, 3].map(i => (
              <div key={i} style={{
                background: '#0f172a', borderRadius: 10, border: '1px solid #1e293b',
                height: 52, opacity: 0.5, animation: 'shimmer 1.4s ease-in-out infinite',
              }} />
            ))}
          </div>
        )}

        {/* File name list — each with a View button */}
        {recordingFiles.length > 0 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            <div style={{ fontSize: 10, color: '#475569', paddingLeft: 4, marginBottom: 2 }}>
              {recordingFiles.length} file{recordingFiles.length !== 1 ? 's' : ''} on device — click "Load &amp; Play" to fetch and view
            </div>
            {recordingFiles.map(fileEntry => {
              const fname = fileEntry.filename || fileEntry;
              const isLoaded = recordings.find(r => r.filename === fname);
              const isLoading = loadingFile[fname];
              return (
                <div
                  key={fname}
                  style={{
                    background: '#0f172a', borderRadius: 10,
                    border: `1px solid ${isLoaded ? '#7c3aed' : '#1e293b'}`,
                    padding: '10px 14px',
                    display: 'flex', alignItems: 'center', gap: 10,
                  }}
                >
                  <span style={{ fontSize: 16, flexShrink: 0 }}>🎞</span>
                  <span style={{
                    flex: 1, fontSize: 11, color: isLoaded ? '#c4b5fd' : '#94a3b8',
                    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                    fontWeight: isLoaded ? 600 : 400,
                  }}>
                    {fname}
                  </span>
                  <button
                    onClick={() => {
                      if (isLoaded) {
                        startPlayback(isLoaded);
                        setTimeout(() => phoneRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }), 30);
                      } else {
                        setLoadingFile(prev => ({ ...prev, [fname]: true }));
                        sendCommand(deviceId, 'get_screen_recording', { filename: fname });
                        phoneRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
                      }
                    }}
                    disabled={isLoading}
                    style={{
                      border: 'none', borderRadius: 6, padding: '4px 10px',
                      background: isLoaded ? '#4c1d95' : (isLoading ? '#1e293b' : '#1e3a5f'),
                      color: isLoading ? '#475569' : '#e2e8f0',
                      cursor: isLoading ? 'not-allowed' : 'pointer',
                      fontSize: 10, fontWeight: 700, whiteSpace: 'nowrap', flexShrink: 0,
                    }}
                  >
                    {isLoading ? '⏳ Loading…' : isLoaded ? '▶ Play' : '↓ Load & Play'}
                  </button>
                </div>
              );
            })}
          </div>
        )}

        {/* Frame strip for currently playing recording */}
        {playing && playing.frames?.length > 0 && (
          <div style={{
            background: '#0f172a', borderRadius: 10, border: '1px solid #1e293b',
            padding: '8px 10px', display: 'flex', flexDirection: 'column', gap: 5,
          }}>
            <div style={{ fontSize: 9, color: '#475569', fontWeight: 600, letterSpacing: 0.5, textTransform: 'uppercase' }}>
              Frames — click to jump
            </div>
            <div style={{
              display: 'flex', gap: 2, overflowX: 'auto', paddingBottom: 2,
              scrollbarWidth: 'thin', scrollbarColor: '#334155 transparent',
            }}>
              {playing.frames.map((f, fi) => {
                const isCurrentFrame = fi === playIdx;
                const appShort = (f.screen?.packageName || '').split('.').pop();
                return (
                  <div
                    key={fi}
                    onClick={() => { stopPlayback(); setPlayIdx(fi); }}
                    title={`Frame ${fi + 1}${appShort ? ` · ${appShort}` : ''}`}
                    style={{
                      width: 28, height: 46, flexShrink: 0,
                      background: isCurrentFrame ? '#4c1d95' : '#1e293b',
                      border: `1px solid ${isCurrentFrame ? '#7c3aed' : '#0f172a'}`,
                      borderRadius: 4, cursor: 'pointer',
                      display: 'flex', flexDirection: 'column',
                      alignItems: 'center', justifyContent: 'center', gap: 2,
                      transition: 'all 0.1s',
                    }}
                  >
                    <span style={{ fontWeight: 700, fontSize: 7, color: isCurrentFrame ? '#c4b5fd' : '#475569' }}>
                      {fi + 1}
                    </span>
                    {appShort && (
                      <div style={{
                        fontSize: 5, color: isCurrentFrame ? '#a78bfa' : '#334155',
                        textAlign: 'center', overflow: 'hidden', whiteSpace: 'nowrap',
                        textOverflow: 'ellipsis', width: '100%', padding: '0 2px',
                      }}>
                        {appShort}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>

      <style>{`
        @keyframes recPulse { 0%,100%{opacity:1;transform:scale(1)} 50%{opacity:0.5;transform:scale(0.85)} }
        @keyframes spin { from{transform:rotate(0deg)} to{transform:rotate(360deg)} }
        @keyframes shimmer { 0%,100%{opacity:0.5} 50%{opacity:0.25} }
      `}</style>
    </div>
  );
}
