import React, { useState } from 'react';

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

function friendlyPkg(pkg) {
  if (!pkg) return 'Unknown';
  const known = {
    'com.whatsapp': 'WhatsApp',
    'com.instagram.android': 'Instagram',
    'com.facebook.katana': 'Facebook',
    'org.telegram.messenger': 'Telegram',
    'com.snapchat.android': 'Snapchat',
    'com.zhiliaoapp.musically': 'TikTok',
    'com.twitter.android': 'Twitter/X',
    'com.facebook.orca': 'Messenger',
    'com.google.android.gm': 'Gmail',
    'com.android.chrome': 'Chrome',
    'com.google.android.youtube': 'YouTube',
    'com.google.android.apps.maps': 'Maps',
  };
  return known[pkg] || pkg.split('.').pop();
}

export default function RecentActivityTab({ device, activityEntries }) {
  const [filter, setFilter] = useState('');

  const entries = activityEntries || [];
  const filtered = filter
    ? entries.filter(e => (e.packageName || '').includes(filter) || (e.appName || '').toLowerCase().includes(filter.toLowerCase()))
    : entries;

  const grouped = [];
  let lastDate = '';
  filtered.forEach(e => {
    const date = (e.timestamp || '').slice(0, 10);
    if (date && date !== lastDate) {
      grouped.push({ type: 'date', date });
      lastDate = date;
    }
    grouped.push({ type: 'entry', ...e });
  });

  return (
    <div className="activity-tab">
      <div className="activity-toolbar">
        <div className="activity-title">
          📱 Recent Activity
          <span className="notif-badge">{entries.length}</span>
        </div>
        <input
          className="activity-search"
          placeholder="Filter by app…"
          value={filter}
          onChange={e => setFilter(e.target.value)}
        />
      </div>

      <div className="activity-feed">
        {filtered.length === 0 ? (
          <div className="notif-empty">
            <div style={{ fontSize: 40 }}>📱</div>
            <div>No activity yet</div>
            <div style={{ fontSize: 12, color: '#64748b' }}>
              {device.isOnline ? 'Watching for app opens…' : 'Device offline'}
            </div>
          </div>
        ) : (
          grouped.map((item, i) => {
            if (item.type === 'date') {
              return (
                <div key={`date-${i}`} className="activity-date-divider">
                  {item.date}
                </div>
              );
            }
            const color = getAppColor(item.packageName);
            const name = item.appName || friendlyPkg(item.packageName);
            return (
              <div key={`${item.packageName}-${item.timestamp}-${i}`} className="activity-entry">
                <div
                  className="activity-app-icon"
                  style={{ background: color + '22', borderColor: color + '55', color }}
                >
                  {name.slice(0, 2).toUpperCase()}
                </div>
                <div className="activity-entry-info">
                  <div className="activity-app-name">{name}</div>
                  <div className="activity-pkg">{item.packageName}</div>
                </div>
                <div className="activity-time">
                  {(item.timestamp || '').slice(11, 19)}
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
