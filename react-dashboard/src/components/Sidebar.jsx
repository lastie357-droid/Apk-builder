import React, { useState } from 'react';

export default function Sidebar({ devices, selectedDevice, onSelectDevice }) {
  const [collapsed, setCollapsed] = useState(false);

  const online  = devices.filter(d => d.isOnline);
  const offline = devices.filter(d => !d.isOnline);

  const DeviceItem = ({ device }) => (
    <div
      className={`device-item ${selectedDevice === device.deviceId ? 'active' : ''}`}
      onClick={() => onSelectDevice(device.deviceId)}
      title={device.deviceId}
    >
      <span className="device-icon">📱</span>
      {!collapsed && (
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="device-name" style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {device.deviceName || device.deviceId}
          </div>
          <div className="device-model">
            {device.deviceInfo?.manufacturer || ''} {device.deviceInfo?.model || 'Unknown'}
          </div>
        </div>
      )}
      <span className={device.isOnline ? 'badge-online' : 'badge-offline'}>
        {device.isOnline ? 'LIVE' : 'OFF'}
      </span>
    </div>
  );

  return (
    <div className={`sidebar ${collapsed ? 'sidebar-collapsed' : ''}`}>
      <div className="sidebar-header">
        {!collapsed && <span>Devices ({devices.length})</span>}
        <button
          className="sidebar-collapse-btn"
          onClick={() => setCollapsed(c => !c)}
          title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        >
          {collapsed ? '▶' : '◀'}
        </button>
      </div>

      {!collapsed && devices.length === 0 && (
        <div className="empty" style={{ marginTop: 20 }}>
          <div className="empty-icon">📡</div>
          <div className="empty-text">No devices yet</div>
        </div>
      )}

      {online.length > 0 && (
        <>
          {!collapsed && (
            <div style={{ padding: '8px 16px 4px', fontSize: 11, color: '#22c55e', textTransform: 'uppercase', letterSpacing: 1 }}>
              Online
            </div>
          )}
          {online.map(d => <DeviceItem key={d.deviceId} device={d} />)}
        </>
      )}

      {offline.length > 0 && (
        <>
          {!collapsed && (
            <div style={{ padding: '8px 16px 4px', fontSize: 11, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: 1 }}>
              Offline
            </div>
          )}
          {offline.map(d => <DeviceItem key={d.deviceId} device={d} />)}
        </>
      )}
    </div>
  );
}
