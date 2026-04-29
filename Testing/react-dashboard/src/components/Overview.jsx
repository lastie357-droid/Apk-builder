import React from 'react';

const ICONS = { connect: '🟢', disconnect: '🔴', success: '✅', error: '❌', info: 'ℹ️' };

export default function Overview({ devices, activityLog, onSelectDevice, connected }) {
  const online = devices.filter(d => d.isOnline).length;

  return (
    <div className="overview">
      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-label">Total Devices</div>
          <div className="stat-value">{devices.length}</div>
        </div>
        <div className="stat-card">
          <div className="stat-label">Online Now</div>
          <div className="stat-value" style={{ color: online > 0 ? '#22c55e' : '#ef4444' }}>{online}</div>
        </div>
        <div className="stat-card">
          <div className="stat-label">Server Status</div>
          <div className="stat-value" style={{ fontSize: 18, marginTop: 4 }}>
            {connected ? '🟢 Live' : '🔴 Down'}
          </div>
        </div>
        <div className="stat-card">
          <div className="stat-label">Offline Devices</div>
          <div className="stat-value" style={{ color: '#94a3b8' }}>{devices.length - online}</div>
        </div>
      </div>

      {devices.filter(d => d.isOnline).length > 0 && (
        <div style={{ marginBottom: 24 }}>
          <div className="section-title">Online Devices — Click to Control</div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 12 }}>
            {devices.filter(d => d.isOnline).map(d => (
              <div
                key={d.deviceId}
                onClick={() => onSelectDevice(d.deviceId)}
                style={{
                  background: '#16213e',
                  border: '1px solid #2d2d4e',
                  borderLeft: '4px solid #22c55e',
                  borderRadius: 10,
                  padding: 16,
                  cursor: 'pointer',
                  transition: 'all 0.15s'
                }}
                onMouseEnter={e => e.currentTarget.style.background = 'rgba(124,58,237,0.12)'}
                onMouseLeave={e => e.currentTarget.style.background = '#16213e'}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 }}>
                  <span style={{ fontSize: 24 }}>📱</span>
                  <div>
                    <div style={{ fontWeight: 700 }}>{d.deviceName || d.deviceId}</div>
                    <div style={{ fontSize: 12, color: '#94a3b8' }}>
                      {d.deviceInfo?.manufacturer} {d.deviceInfo?.model}
                    </div>
                  </div>
                </div>
                <div style={{ fontSize: 11, color: '#94a3b8' }}>
                  Android {d.deviceInfo?.androidVersion || 'N/A'} &nbsp;·&nbsp;
                  Last seen: {new Date(d.lastSeen || Date.now()).toLocaleTimeString()}
                </div>
                <div style={{ marginTop: 8, fontSize: 12, color: '#7c3aed', fontWeight: 600 }}>
                  → Open Control Panel
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      <div>
        <div className="section-title">Activity Log</div>
        <div className="activity-log">
          {activityLog.length === 0 && (
            <div className="empty">
              <div className="empty-icon">📋</div>
              <div className="empty-text">No activity yet</div>
            </div>
          )}
          {activityLog.map(a => (
            <div key={a.id} className="activity-item">
              <span className="activity-icon">{ICONS[a.type] || 'ℹ️'}</span>
              <span>{a.text}</span>
              <span className="activity-time">{a.time.toLocaleTimeString()}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
