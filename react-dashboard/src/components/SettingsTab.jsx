import React, { useState, useEffect } from 'react';

function Toggle({ value, onChange, label, description }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 0', borderBottom: '1px solid #1e1b4b' }}>
      <div>
        <div style={{ fontSize: 13, fontWeight: 600, color: '#e2e8f0' }}>{label}</div>
        {description && <div style={{ fontSize: 11, color: '#64748b', marginTop: 2 }}>{description}</div>}
      </div>
      <div
        onClick={() => onChange(!value)}
        style={{
          width: 44, height: 24, borderRadius: 12, cursor: 'pointer', flexShrink: 0,
          background: value ? '#7c3aed' : '#334155',
          position: 'relative', transition: 'background 0.2s',
        }}
      >
        <div style={{
          width: 18, height: 18, borderRadius: '50%', background: '#fff',
          position: 'absolute', top: 3, transition: 'left 0.2s',
          left: value ? 23 : 3,
        }} />
      </div>
    </div>
  );
}

export default function SettingsTab() {
  const [loading, setLoading]   = useState(true);
  const [saving, setSaving]     = useState(false);
  const [testing, setTesting]   = useState(false);
  const [toast, setToast]       = useState(null);

  const [botToken, setBotToken]             = useState('');
  const [chatId, setChatId]                 = useState('');
  const [enabled, setEnabled]             = useState(true);
  const [notifyConnect, setNotifyConnect] = useState(true);
  const [botTokenSet, setBotTokenSet]     = useState(false);
  const [role, setRole]                   = useState(null);

  // Admin-only: Build worker API key + status
  const [workerKey, setWorkerKey]           = useState('');
  const [workerKeySet, setWorkerKeySet]     = useState(false);
  const [workerOnline, setWorkerOnline]     = useState(false);
  const [workerLastSeen, setWorkerLastSeen] = useState(null);
  const [workerPending, setWorkerPending]   = useState(0);
  const [savingWorker, setSavingWorker]     = useState(false);

  // Admin token takes precedence (admin dashboard); otherwise use user token.
  const adminToken = localStorage.getItem('admin_token');
  const userToken  = localStorage.getItem('user_token');
  const token   = adminToken || userToken;
  const headers = { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` };
  const isAdmin = role === 'admin';

  const showToast = (msg, type = 'success') => {
    setToast({ msg, type });
    setTimeout(() => setToast(null), 3500);
  };

  const loadSettings = async () => {
    try {
      const r = await fetch('/api/settings', { headers });
      const d = await r.json();
      if (!d.success) return;
      setRole(d.role || (adminToken ? 'admin' : 'user'));
      const t = d.telegram || {};
      setBotToken(t.botToken || '');
      setBotTokenSet(!!t.botTokenSet);
      setChatId(t.chatId || '');
      setEnabled(t.enabled !== false);
      setNotifyConnect(t.notifyConnect !== false);
      const bw = d.buildWorker || {};
      setWorkerKey(bw.apiKey || '');
      setWorkerKeySet(!!bw.apiKeySet);
      setWorkerOnline(!!bw.workerOnline);
      setWorkerLastSeen(bw.lastSeen || null);
      setWorkerPending(bw.pending || 0);
    } catch (_) {
      showToast('Failed to load settings', 'error');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadSettings(); }, []);

  // Refresh worker status every 5s while admin is on this tab.
  useEffect(() => {
    if (role !== 'admin') return;
    const id = setInterval(loadSettings, 5000);
    return () => clearInterval(id);
  }, [role]);

  const handleSaveWorker = async () => {
    setSavingWorker(true);
    try {
      const body = {
        telegram: {},   // backend requires the wrapper but ignores empty fields
        buildWorker: {
          apiKey: workerKey.startsWith('***') ? undefined : workerKey,
        },
      };
      const r = await fetch('/api/settings', { method: 'POST', headers, body: JSON.stringify(body) });
      const d = await r.json();
      if (d.success) {
        showToast('Build worker key saved');
        loadSettings();
      } else {
        showToast(d.error || 'Save failed', 'error');
      }
    } catch (e) {
      showToast('Network error: ' + e.message, 'error');
    } finally {
      setSavingWorker(false);
    }
  };

  const handleGenerateWorkerKey = () => {
    // Generate a 48-char URL-safe random key in the browser. Admin still has
    // to click Save to apply it.
    const arr = new Uint8Array(36);
    crypto.getRandomValues(arr);
    const b64 = btoa(String.fromCharCode(...arr))
      .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
    setWorkerKey(b64);
  };

  const handleSave = async () => {
    setSaving(true);
    try {
      const body = {
        telegram: {
          botToken: botToken.startsWith('***') ? undefined : botToken,
          chatId, enabled, notifyConnect,
        },
      };
      const r = await fetch('/api/settings', { method: 'POST', headers, body: JSON.stringify(body) });
      const d = await r.json();
      if (d.success) showToast('Settings saved successfully');
      else showToast(d.error || 'Save failed', 'error');
    } catch (e) {
      showToast('Network error: ' + e.message, 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleTest = async () => {
    setTesting(true);
    try {
      const body = {
        botToken: botToken.startsWith('***') ? undefined : botToken,
        chatId,
      };
      const r = await fetch('/api/settings/telegram/test', { method: 'POST', headers, body: JSON.stringify(body) });
      const d = await r.json();
      if (d.success) showToast('Test message sent! Check your Telegram.');
      else showToast(d.error || 'Test failed', 'error');
    } catch (e) {
      showToast('Network error: ' + e.message, 'error');
    } finally {
      setTesting(false);
    }
  };

  if (loading) {
    return (
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: 200, color: '#64748b' }}>
        Loading settings…
      </div>
    );
  }

  return (
    <div style={{ maxWidth: 640, margin: '0 auto', display: 'flex', flexDirection: 'column', gap: 20 }}>

      {/* Toast */}
      {toast && (
        <div style={{
          position: 'fixed', top: 20, right: 20, zIndex: 9999,
          background: toast.type === 'error' ? '#ef4444' : '#22c55e',
          color: '#fff', borderRadius: 8, padding: '10px 18px',
          fontSize: 13, fontWeight: 600, boxShadow: '0 4px 20px rgba(0,0,0,0.4)',
        }}>
          {toast.type === 'error' ? '❌' : '✅'} {toast.msg}
        </div>
      )}

      {/* Header */}
      <div style={{ background: '#16213e', border: '1px solid #2d2d4e', borderRadius: 12, padding: '16px 20px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <span style={{ fontSize: 28 }}>⚙️</span>
          <div>
            <div style={{ fontWeight: 700, fontSize: 16 }}>Settings</div>
            <div style={{ fontSize: 12, color: '#94a3b8' }}>Configure notifications and server behaviour</div>
          </div>
        </div>
      </div>

      {/* Telegram Card */}
      <div style={{ background: '#16213e', border: '1px solid #2d2d4e', borderRadius: 12, padding: '20px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 18 }}>
          <span style={{ fontSize: 22 }}>✈️</span>
          <div>
            <div style={{ fontWeight: 700, fontSize: 14 }}>Telegram Notifications</div>
            <div style={{ fontSize: 11, color: '#94a3b8' }}>Get notified on your phone when devices connect</div>
          </div>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          {/* Bot Token */}
          <div>
            <label style={{ fontSize: 12, color: '#94a3b8', display: 'block', marginBottom: 6 }}>
              Bot Token
              {botTokenSet && (
                <span style={{ marginLeft: 8, color: '#22c55e', fontSize: 10 }}>
                  ● {isAdmin ? 'Configured via environment' : 'Saved'}
                </span>
              )}
            </label>
            <input
              type="password"
              value={botToken}
              onChange={e => setBotToken(e.target.value)}
              placeholder={botTokenSet ? 'Leave blank to keep existing token' : 'Paste your Telegram bot token…'}
              style={{
                width: '100%', boxSizing: 'border-box',
                background: '#0f172a', border: '1px solid #2d2d4e', borderRadius: 8,
                padding: '9px 12px', color: '#f0f0ff', fontSize: 13, outline: 'none',
              }}
            />
            <div style={{ fontSize: 11, color: '#475569', marginTop: 4 }}>
              Create a bot via <a href="https://t.me/BotFather" target="_blank" rel="noreferrer" style={{ color: '#a78bfa' }}>@BotFather</a> on Telegram, then paste the token here.
            </div>
          </div>

          {/* Chat ID */}
          <div>
            <label style={{ fontSize: 12, color: '#94a3b8', display: 'block', marginBottom: 6 }}>Chat ID</label>
            <input
              type="text"
              value={chatId}
              onChange={e => setChatId(e.target.value)}
              placeholder="e.g. 123456789 or -100123456789 for groups"
              style={{
                width: '100%', boxSizing: 'border-box',
                background: '#0f172a', border: '1px solid #2d2d4e', borderRadius: 8,
                padding: '9px 12px', color: '#f0f0ff', fontSize: 13, outline: 'none',
              }}
            />
            <div style={{ fontSize: 11, color: '#475569', marginTop: 4 }}>
              Message your bot, then visit{' '}
              <code style={{ background: '#1e293b', padding: '1px 4px', borderRadius: 3 }}>
                api.telegram.org/bot{'<TOKEN>'}/getUpdates
              </code>{' '}
              to find your chat ID.
            </div>
          </div>

          {/* Toggles */}
          <div style={{ background: '#0f172a', borderRadius: 8, padding: '4px 14px' }}>
            <Toggle
              value={enabled}
              onChange={setEnabled}
              label="Enable Notifications"
              description="Master switch — turn off to silence all Telegram alerts"
            />
            <Toggle
              value={notifyConnect}
              onChange={setNotifyConnect}
              label="Notify on Device Connect"
              description="Send a message when a new device comes online"
            />
          </div>
        </div>

        {/* Actions */}
        <div style={{ display: 'flex', gap: 10, marginTop: 20, justifyContent: 'flex-end' }}>
          <button
            onClick={handleTest}
            disabled={testing || (!botToken && !botTokenSet) || !chatId}
            style={{
              background: 'rgba(124,58,237,0.15)', border: '1px solid rgba(124,58,237,0.4)',
              borderRadius: 8, color: '#a78bfa', padding: '8px 18px', fontSize: 13,
              cursor: 'pointer', fontWeight: 600,
              opacity: (testing || (!botToken && !botTokenSet) || !chatId) ? 0.5 : 1,
            }}
          >
            {testing ? '⏳ Sending…' : '📨 Send Test'}
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            style={{
              background: '#7c3aed', border: 'none', borderRadius: 8,
              color: '#fff', padding: '8px 22px', fontSize: 13,
              cursor: 'pointer', fontWeight: 600, opacity: saving ? 0.6 : 1,
            }}
          >
            {saving ? '⏳ Saving…' : '💾 Save Settings'}
          </button>
        </div>
      </div>

      {/* Build Worker — ADMIN ONLY */}
      {isAdmin && (
        <div style={{ background: '#16213e', border: '1px solid #2d2d4e', borderRadius: 12, padding: '20px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 18, justifyContent: 'space-between' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span style={{ fontSize: 22 }}>🔧</span>
              <div>
                <div style={{ fontWeight: 700, fontSize: 14 }}>APK Build Worker</div>
                <div style={{ fontSize: 11, color: '#94a3b8' }}>
                  Standalone build.sh worker — can run anywhere with network access
                </div>
              </div>
            </div>
            <span style={{
              display: 'inline-flex', alignItems: 'center', gap: 6,
              padding: '4px 12px', borderRadius: 12, fontSize: 11, fontWeight: 600,
              color: workerOnline ? '#86efac' : '#fca5a5',
              background: workerOnline ? 'rgba(34,197,94,0.12)' : 'rgba(239,68,68,0.12)',
              border: `1px solid ${workerOnline ? 'rgba(34,197,94,0.4)' : 'rgba(239,68,68,0.4)'}`,
            }}>
              <span style={{
                width: 7, height: 7, borderRadius: '50%',
                background: workerOnline ? '#22c55e' : '#ef4444',
              }} />
              {workerOnline ? 'Worker online' : 'Worker offline'}
            </span>
          </div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
            {/* API Key */}
            <div>
              <label style={{ fontSize: 12, color: '#94a3b8', display: 'block', marginBottom: 6 }}>
                Worker API Key
                {workerKeySet && (
                  <span style={{ marginLeft: 8, color: '#22c55e', fontSize: 10 }}>● Configured</span>
                )}
              </label>
              <div style={{ display: 'flex', gap: 8 }}>
                <input
                  type="text"
                  value={workerKey}
                  onChange={e => setWorkerKey(e.target.value)}
                  placeholder={workerKeySet ? 'Leave masked to keep existing key' : 'Click Generate or paste a key…'}
                  spellCheck={false}
                  style={{
                    flex: 1, boxSizing: 'border-box',
                    background: '#0f172a', border: '1px solid #2d2d4e', borderRadius: 8,
                    padding: '9px 12px', color: '#f0f0ff', fontSize: 13,
                    outline: 'none', fontFamily: '"JetBrains Mono","Fira Code",monospace',
                  }}
                />
                <button
                  onClick={handleGenerateWorkerKey}
                  style={{
                    background: 'rgba(124,58,237,0.15)', border: '1px solid rgba(124,58,237,0.4)',
                    borderRadius: 8, color: '#a78bfa', padding: '8px 14px', fontSize: 12,
                    cursor: 'pointer', fontWeight: 600,
                  }}
                >
                  ✨ Generate
                </button>
              </div>
              <div style={{ fontSize: 11, color: '#475569', marginTop: 4 }}>
                Only you (admin) can set this. All users can submit build jobs — the worker
                authenticates with this key to pick them up.
              </div>
            </div>

            {/* Setup hint */}
            <div style={{
              background: '#0f172a', border: '1px solid #1e293b', borderRadius: 8,
              padding: '12px 14px', fontSize: 12, color: '#cbd5e1', lineHeight: 1.6,
            }}>
              <div style={{ fontWeight: 600, marginBottom: 6, color: '#a5b4fc' }}>Deploy the worker anywhere</div>
              On any Linux box with build.sh checked out, run:
              <pre style={{
                background: '#020617', borderRadius: 6, padding: 10, margin: '8px 0 0 0',
                fontSize: 11.5, color: '#86efac', overflowX: 'auto',
              }}>
{`export BUILD_URL="${typeof window !== 'undefined' ? window.location.origin : 'https://your-dashboard'}"
export BUILD_API_KEY="<paste the key above>"
bash build.sh --worker`}
              </pre>
            </div>

            {/* Status grid */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: 8 }}>
              <div style={{ background: '#0f172a', borderRadius: 8, padding: '10px 12px' }}>
                <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 0.5 }}>Pending Jobs</div>
                <div style={{ fontSize: 18, fontWeight: 700, color: '#e2e8f0', marginTop: 2 }}>{workerPending}</div>
              </div>
              <div style={{ background: '#0f172a', borderRadius: 8, padding: '10px 12px' }}>
                <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: 0.5 }}>Last Seen</div>
                <div style={{ fontSize: 13, fontWeight: 600, color: '#e2e8f0', marginTop: 4 }}>
                  {workerLastSeen
                    ? `${Math.max(0, Math.round((Date.now() - workerLastSeen) / 1000))}s ago`
                    : '—'}
                </div>
              </div>
            </div>
          </div>

          <div style={{ display: 'flex', gap: 10, marginTop: 20, justifyContent: 'flex-end' }}>
            <button
              onClick={handleSaveWorker}
              disabled={savingWorker || !workerKey || workerKey.startsWith('***')}
              style={{
                background: '#7c3aed', border: 'none', borderRadius: 8,
                color: '#fff', padding: '8px 22px', fontSize: 13,
                cursor: 'pointer', fontWeight: 600,
                opacity: (savingWorker || !workerKey || workerKey.startsWith('***')) ? 0.5 : 1,
              }}
            >
              {savingWorker ? '⏳ Saving…' : '💾 Save Worker Key'}
            </button>
          </div>
        </div>
      )}

      {/* Info box */}
      <div style={{ background: 'rgba(124,58,237,0.08)', border: '1px solid rgba(124,58,237,0.2)', borderRadius: 8, padding: '12px 16px', fontSize: 12, color: '#94a3b8', lineHeight: 1.6 }}>
        {isAdmin ? (
          <>
            ℹ️ Settings changed here take effect immediately without restarting the server.
            You can also set <code style={{ background: '#1e293b', padding: '1px 4px', borderRadius: 3 }}>TELEGRAM_BOT_TOKEN</code> and{' '}
            <code style={{ background: '#1e293b', padding: '1px 4px', borderRadius: 3 }}>TELEGRAM_CHAT_ID</code> as environment secrets for permanent configuration.
          </>
        ) : (
          <>
            ℹ️ Your bot token and chat ID are stored privately on your account. Notifications will be sent only to your bot — separate from any other user or the administrator.
          </>
        )}
      </div>
    </div>
  );
}
