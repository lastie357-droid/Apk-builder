import React, { useState, useEffect, useRef } from 'react';

function formatDate(ts) {
  if (!ts) return '—';
  return new Date(Number(ts)).toLocaleString();
}

function formatDuration(secs) {
  if (!secs && secs !== 0) return '—';
  const s = Number(secs);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const r = s % 60;
  return r > 0 ? `${m}m ${r}s` : `${m}m`;
}

function callTypeBadge(type) {
  const t = Number(type);
  if (t === 1) return { label: 'Incoming', color: '#22c55e', icon: '📲' };
  if (t === 2) return { label: 'Outgoing', color: '#3b82f6', icon: '📤' };
  if (t === 3) return { label: 'Missed',   color: '#ef4444', icon: '📵' };
  if (t === 5) return { label: 'Rejected', color: '#f59e0b', icon: '🚫' };
  return { label: 'Unknown', color: '#64748b', icon: '📞' };
}

function buildVcf(contacts) {
  return contacts.map(c => {
    const name   = c.name || c.displayName || 'Unknown';
    const phones = Array.isArray(c.phones)
      ? c.phones
      : c.phone
        ? [{ number: c.phone, type: 'CELL' }]
        : c.phoneNumbers
          ? c.phoneNumbers
          : [];
    const emails = Array.isArray(c.emails) ? c.emails : [];

    const lines = ['BEGIN:VCARD', 'VERSION:3.0', `FN:${name}`, `N:${name};;;; `];

    phones.forEach(p => {
      const num  = p.number || p.value || p;
      const pType = p.type ? p.type.toUpperCase() : 'CELL';
      lines.push(`TEL;TYPE=${pType}:${num}`);
    });

    emails.forEach(e => {
      const addr = e.address || e.value || e;
      lines.push(`EMAIL:${addr}`);
    });

    lines.push('END:VCARD');
    return lines.join('\r\n');
  }).join('\r\n');
}

function downloadVcf(contacts, deviceName) {
  const date = new Date().toISOString().slice(0, 10);
  const safeName = (deviceName || 'device').replace(/[^a-z0-9_\-]/gi, '_');
  const filename = `${safeName}_${date}.vcf`;
  const blob = new Blob([buildVcf(contacts)], { type: 'text/vcard;charset=utf-8' });
  const url  = URL.createObjectURL(blob);
  const a    = document.createElement('a');
  a.href     = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

const TAB_STYLE_BASE = {
  padding: '7px 18px',
  borderRadius: 6,
  border: '1px solid transparent',
  cursor: 'pointer',
  fontSize: 13,
  fontWeight: 500,
  transition: 'all 0.15s',
};

const card = {
  background: 'rgba(255,255,255,0.03)',
  border: '1px solid rgba(255,255,255,0.08)',
  borderRadius: 10,
  padding: '14px 16px',
};

export default function ContactsCallLogTab({ device, sendCommand, results }) {
  const deviceId  = device?.deviceId;
  const deviceName = device?.deviceName || device?.deviceInfo?.name || deviceId || 'device';
  const isOnline  = device?.isOnline;

  const [subTab, setSubTab] = useState('contacts');

  // ── Contacts state ────────────────────────────────────────────────────
  const [contacts, setContacts]         = useState([]);
  const [contactSearch, setContactSearch] = useState('');
  const [contactLoading, setContactLoading] = useState(false);
  const [contactStatus, setContactStatus]   = useState('');

  // ── Call log state ────────────────────────────────────────────────────
  const [calls, setCalls]           = useState([]);
  const [callLoading, setCallLoading] = useState(false);
  const [callStatus, setCallStatus]   = useState('');
  const [callSearch, setCallSearch]   = useState('');
  const [callTypeFilter, setCallTypeFilter] = useState('all');

  const seenResults = useRef(new Set());

  const showContactStatus = (msg) => {
    setContactStatus(msg);
    setTimeout(() => setContactStatus(''), 3500);
  };
  const showCallStatus = (msg) => {
    setCallStatus(msg);
    setTimeout(() => setCallStatus(''), 3500);
  };

  // Auto-fetch contacts when device comes online
  useEffect(() => {
    if (isOnline) {
      setContactLoading(true);
      sendCommand(deviceId, 'get_all_contacts', {});
    }
  }, [isOnline]);

  // Handle command results
  useEffect(() => {
    if (!results) return;
    results.forEach(r => {
      if (seenResults.current.has(r.id)) return;
      seenResults.current.add(r.id);

      if (r.command === 'get_all_contacts') {
        setContactLoading(false);
        if (r.success && r.response) {
          try {
            const d = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
            const list = d.contacts || d.data || (Array.isArray(d) ? d : []);
            setContacts(list);
            showContactStatus(`Loaded ${list.length} contact${list.length !== 1 ? 's' : ''}`);
          } catch (_) {
            showContactStatus('Failed to parse contacts');
          }
        } else {
          showContactStatus('Failed to load contacts: ' + (r.error || 'Unknown error'));
        }
      }

      if (r.command === 'get_all_call_logs') {
        setCallLoading(false);
        if (r.success && r.response) {
          try {
            const d = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
            const list = d.callLogs || d.calls || d.data || (Array.isArray(d) ? d : []);
            setCalls(list);
            showCallStatus(`Loaded ${list.length} call${list.length !== 1 ? 's' : ''}`);
          } catch (_) {
            showCallStatus('Failed to parse call logs');
          }
        } else {
          showCallStatus('Failed to load call logs: ' + (r.error || 'Unknown error'));
        }
      }
    });
  }, [results]);

  // ── Filtered lists ────────────────────────────────────────────────────
  const filteredContacts = contacts.filter(c => {
    if (!contactSearch) return true;
    const q   = contactSearch.toLowerCase();
    const nm  = (c.name || c.displayName || '').toLowerCase();
    const ph  = JSON.stringify(c.phones || c.phone || c.phoneNumbers || '').toLowerCase();
    return nm.includes(q) || ph.includes(q);
  });

  const filteredCalls = calls.filter(c => {
    const q = callSearch.toLowerCase();
    const matchSearch = !q ||
      (c.name || '').toLowerCase().includes(q) ||
      (c.number || c.phoneNumber || '').toLowerCase().includes(q);
    const matchType = callTypeFilter === 'all' || String(c.type) === callTypeFilter;
    return matchSearch && matchType;
  });

  const activeTabStyle = (id) => ({
    ...TAB_STYLE_BASE,
    background: subTab === id ? 'rgba(99,102,241,0.25)' : 'rgba(255,255,255,0.04)',
    border: subTab === id ? '1px solid rgba(99,102,241,0.6)' : '1px solid rgba(255,255,255,0.08)',
    color: subTab === id ? '#a5b4fc' : '#94a3b8',
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14, padding: '4px 0' }}>

      {/* Sub-tab switcher */}
      <div style={{ display: 'flex', gap: 8 }}>
        <button style={activeTabStyle('contacts')} onClick={() => setSubTab('contacts')}>
          👥 Contacts
        </button>
        <button style={activeTabStyle('call_log')} onClick={() => setSubTab('call_log')}>
          📞 Call Log
        </button>
      </div>

      {/* ── CONTACTS ────────────────────────────────────────────────── */}
      {subTab === 'contacts' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>

          {/* Toolbar */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <input
              type="text"
              placeholder="Search by name or phone…"
              value={contactSearch}
              onChange={e => setContactSearch(e.target.value)}
              style={{
                flex: 1, minWidth: 180, background: 'rgba(255,255,255,0.06)',
                border: '1px solid rgba(255,255,255,0.1)', borderRadius: 7,
                padding: '8px 12px', color: '#f0f0ff', fontSize: 13, outline: 'none',
              }}
            />
            <button
              onClick={() => {
                if (!isOnline) return;
                setContactLoading(true);
                sendCommand(deviceId, 'get_all_contacts', {});
              }}
              disabled={!isOnline || contactLoading}
              style={{
                background: 'rgba(99,102,241,0.2)', border: '1px solid rgba(99,102,241,0.5)',
                color: '#a5b4fc', borderRadius: 7, padding: '8px 14px', fontSize: 13,
                cursor: (!isOnline || contactLoading) ? 'not-allowed' : 'pointer', opacity: (!isOnline || contactLoading) ? 0.5 : 1,
              }}
            >
              {contactLoading ? '⏳ Loading…' : '↺ Refresh'}
            </button>
            <button
              onClick={() => {
                if (contacts.length === 0) return;
                downloadVcf(contacts, deviceName);
              }}
              disabled={contacts.length === 0}
              title={`Download as ${(deviceName || 'device').replace(/[^a-z0-9_\-]/gi, '_')}_${new Date().toISOString().slice(0,10)}.vcf`}
              style={{
                background: contacts.length === 0 ? 'rgba(255,255,255,0.04)' : 'rgba(34,197,94,0.15)',
                border: contacts.length === 0 ? '1px solid rgba(255,255,255,0.08)' : '1px solid rgba(34,197,94,0.4)',
                color: contacts.length === 0 ? '#64748b' : '#4ade80',
                borderRadius: 7, padding: '8px 14px', fontSize: 13,
                cursor: contacts.length === 0 ? 'not-allowed' : 'pointer',
              }}
            >
              ⬇️ Download VCF ({contacts.length})
            </button>
          </div>

          {contactStatus && (
            <div style={{ fontSize: 12, color: '#94a3b8', padding: '4px 0' }}>{contactStatus}</div>
          )}

          {/* Contact list */}
          {filteredContacts.length === 0 ? (
            <div style={{ ...card, textAlign: 'center', color: '#64748b', padding: 40 }}>
              {contactLoading ? '⏳ Fetching contacts from device…' : contacts.length === 0 ? 'No contacts loaded yet' : 'No contacts match your search'}
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div style={{ fontSize: 12, color: '#64748b', paddingLeft: 2 }}>
                {filteredContacts.length} of {contacts.length} contact{contacts.length !== 1 ? 's' : ''}
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, maxHeight: 500, overflowY: 'auto' }}>
                {filteredContacts.map((c, i) => {
                  const name   = c.name || c.displayName || 'Unknown';
                  const phones = Array.isArray(c.phones)
                    ? c.phones.map(p => p.number || p.value || p).join(', ')
                    : c.phone || (Array.isArray(c.phoneNumbers) ? c.phoneNumbers.map(p => p.number || p.value || p).join(', ') : '') || '—';
                  const email  = Array.isArray(c.emails)
                    ? c.emails.map(e => e.address || e.value || e).join(', ')
                    : c.email || '';
                  const initials = name.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase();
                  const hue = Math.abs(name.split('').reduce((h, ch) => (h << 5) - h + ch.charCodeAt(0), 0)) % 360;

                  return (
                    <div key={i} style={{
                      ...card,
                      display: 'flex', alignItems: 'center', gap: 12, padding: '10px 14px',
                    }}>
                      <div style={{
                        width: 36, height: 36, borderRadius: '50%', flexShrink: 0,
                        background: `hsl(${hue},55%,28%)`,
                        border: `1px solid hsl(${hue},55%,40%)`,
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        fontSize: 13, fontWeight: 700, color: `hsl(${hue},80%,75%)`,
                      }}>
                        {initials || '?'}
                      </div>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontWeight: 600, color: '#e2e8f0', fontSize: 14, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                          {name}
                        </div>
                        <div style={{ fontSize: 12, color: '#94a3b8', marginTop: 2 }}>{phones}</div>
                        {email && <div style={{ fontSize: 11, color: '#64748b', marginTop: 1 }}>{email}</div>}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </div>
      )}

      {/* ── CALL LOG ─────────────────────────────────────────────────── */}
      {subTab === 'call_log' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>

          {/* Toolbar */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <button
              onClick={() => {
                if (!isOnline || callLoading) return;
                setCallLoading(true);
                sendCommand(deviceId, 'get_all_call_logs', { limit: 200 });
              }}
              disabled={!isOnline || callLoading}
              style={{
                background: (!isOnline || callLoading) ? 'rgba(255,255,255,0.04)' : 'rgba(99,102,241,0.2)',
                border: (!isOnline || callLoading) ? '1px solid rgba(255,255,255,0.08)' : '1px solid rgba(99,102,241,0.5)',
                color: (!isOnline || callLoading) ? '#64748b' : '#a5b4fc',
                borderRadius: 7, padding: '8px 16px', fontSize: 13, fontWeight: 600,
                cursor: (!isOnline || callLoading) ? 'not-allowed' : 'pointer',
              }}
            >
              {callLoading ? '⏳ Fetching…' : '📞 Fetch Call Logs'}
            </button>

            {calls.length > 0 && (
              <>
                <input
                  type="text"
                  placeholder="Search name or number…"
                  value={callSearch}
                  onChange={e => setCallSearch(e.target.value)}
                  style={{
                    flex: 1, minWidth: 160, background: 'rgba(255,255,255,0.06)',
                    border: '1px solid rgba(255,255,255,0.1)', borderRadius: 7,
                    padding: '8px 12px', color: '#f0f0ff', fontSize: 13, outline: 'none',
                  }}
                />
                <select
                  value={callTypeFilter}
                  onChange={e => setCallTypeFilter(e.target.value)}
                  style={{
                    background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.1)',
                    borderRadius: 7, padding: '8px 10px', color: '#f0f0ff', fontSize: 13, outline: 'none',
                  }}
                >
                  <option value="all">All Types</option>
                  <option value="1">Incoming</option>
                  <option value="2">Outgoing</option>
                  <option value="3">Missed</option>
                  <option value="5">Rejected</option>
                </select>
              </>
            )}
          </div>

          {callStatus && (
            <div style={{ fontSize: 12, color: '#94a3b8', padding: '4px 0' }}>{callStatus}</div>
          )}

          {calls.length === 0 && !callLoading ? (
            <div style={{ ...card, textAlign: 'center', color: '#64748b', padding: 48 }}>
              Press <strong style={{ color: '#a5b4fc' }}>Fetch Call Logs</strong> to load call history from the device
            </div>
          ) : callLoading ? (
            <div style={{ ...card, textAlign: 'center', color: '#64748b', padding: 48 }}>
              ⏳ Fetching call logs from device…
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div style={{ fontSize: 12, color: '#64748b', paddingLeft: 2 }}>
                {filteredCalls.length} of {calls.length} call{calls.length !== 1 ? 's' : ''}
              </div>
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
                  <thead>
                    <tr style={{ borderBottom: '1px solid rgba(255,255,255,0.08)' }}>
                      {['Type', 'Name / Number', 'Duration', 'Date'].map(h => (
                        <th key={h} style={{ padding: '8px 12px', textAlign: 'left', color: '#64748b', fontWeight: 600, fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.5px' }}>
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {filteredCalls.map((c, i) => {
                      const badge = callTypeBadge(c.type);
                      const number = c.number || c.phoneNumber || '—';
                      const name   = c.name || c.contactName;
                      return (
                        <tr key={i} style={{ borderBottom: '1px solid rgba(255,255,255,0.04)', transition: 'background 0.1s' }}
                          onMouseEnter={e => e.currentTarget.style.background = 'rgba(255,255,255,0.03)'}
                          onMouseLeave={e => e.currentTarget.style.background = 'transparent'}
                        >
                          <td style={{ padding: '9px 12px', whiteSpace: 'nowrap' }}>
                            <span style={{
                              display: 'inline-flex', alignItems: 'center', gap: 5,
                              background: `${badge.color}18`,
                              border: `1px solid ${badge.color}44`,
                              color: badge.color, borderRadius: 5,
                              padding: '2px 8px', fontSize: 11, fontWeight: 600,
                            }}>
                              {badge.icon} {badge.label}
                            </span>
                          </td>
                          <td style={{ padding: '9px 12px' }}>
                            {name && <div style={{ color: '#e2e8f0', fontWeight: 500 }}>{name}</div>}
                            <div style={{ color: name ? '#94a3b8' : '#e2e8f0', fontFamily: 'monospace', fontSize: 12 }}>{number}</div>
                          </td>
                          <td style={{ padding: '9px 12px', color: '#94a3b8', whiteSpace: 'nowrap' }}>
                            {formatDuration(c.duration)}
                          </td>
                          <td style={{ padding: '9px 12px', color: '#94a3b8', whiteSpace: 'nowrap', fontSize: 12 }}>
                            {formatDate(c.date || c.timestamp)}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
