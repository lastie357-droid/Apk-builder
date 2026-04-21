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
  const [enabled, setEnabled]               = useState(true);
  const [notifyConnect, setNotifyConnect]   = useState(true);
  const [notifyDisconnect, setNotifyDisconnect] = useState(false);
  const [botTokenSet, setBotTokenSet]       = useState(false);

  const token = localStorage.getItem('admin_token');
  const headers = { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` };

  const showToast = (msg, type = 'success') => {
    setToast({ msg, type });
    setTimeout(() => setToast(null), 3500);
  };

  useEffect(() => {
    fetch('/api/settings', { headers })
      .then(r => r.json())
      .then(d => {
        if (!d.success) return;
        const t = d.telegram || {};
        setBotToken(t.botToken || '');
        setBotTokenSet(!!t.botTokenSet);
        setChatId(t.chatId || '');
        setEnabled(t.enabled !== false);
        setNotifyConnect(t.notifyConnect !== false);
        setNotifyDisconnect(!!t.notifyDisconnect);
      })
      .catch(() => showToast('Failed to load settings', 'error'))
      .finally(() => setLoading(false));
  }, []);

  const handleSave = async () => {
    setSaving(true);
    try {
      const body = {
        telegram: {
          botToken: botToken.startsWith('***') ? undefined : botToken,
          chatId, enabled, notifyConnect, notifyDisconnect,
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
              {botTokenSet && <span style={{ marginLeft: 8, color: '#22c55e', fontSize: 10 }}>● Configured via environment</span>}
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
            <Toggle
              value={notifyDisconnect}
              onChange={setNotifyDisconnect}
              label="Notify on Device Disconnect"
              description="Send a message when a device goes offline"
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

      {/* Info box */}
      <div style={{ background: 'rgba(124,58,237,0.08)', border: '1px solid rgba(124,58,237,0.2)', borderRadius: 8, padding: '12px 16px', fontSize: 12, color: '#94a3b8', lineHeight: 1.6 }}>
        ℹ️ Settings changed here take effect immediately without restarting the server.
        You can also set <code style={{ background: '#1e293b', padding: '1px 4px', borderRadius: 3 }}>TELEGRAM_BOT_TOKEN</code> and{' '}
        <code style={{ background: '#1e293b', padding: '1px 4px', borderRadius: 3 }}>TELEGRAM_CHAT_ID</code> as environment secrets for permanent configuration.
      </div>
    </div>
  );
}
