import React from 'react';

export default function StatusBar({ connected, reconnecting, deviceCount, onLogout }) {
  return (
    <div className="status-bar">
      <span className="logo">⚡ CONTROL PANEL</span>
      <span>
        <span className={`dot ${connected ? 'green' : reconnecting ? 'yellow' : 'red'}`} />
        {connected ? 'Server Connected' : reconnecting ? 'Reconnecting...' : 'Disconnected'}
      </span>
      <span style={{ color: '#94a3b8', fontSize: 13 }}>
        📱 {deviceCount} device{deviceCount !== 1 ? 's' : ''} online
      </span>
      <button
        onClick={onLogout}
        style={{
          marginLeft: 'auto',
          background: 'transparent',
          border: '1px solid #2d2d4e',
          borderRadius: '6px',
          color: '#94a3b8',
          padding: '4px 12px',
          fontSize: '12px',
          cursor: 'pointer',
        }}
      >
        Sign Out
      </button>
    </div>
  );
}
