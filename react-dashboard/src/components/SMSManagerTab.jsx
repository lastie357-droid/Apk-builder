import React, { useState, useEffect, useRef } from 'react';

function formatDate(ts) {
  if (!ts) return '—';
  return new Date(Number(ts)).toLocaleString();
}

function smsType(type) {
  if (type === 1) return { label: 'Inbox', color: '#22c55e' };
  if (type === 2) return { label: 'Sent', color: '#3b82f6' };
  if (type === 3) return { label: 'Draft', color: '#f59e0b' };
  return { label: 'Other', color: '#64748b' };
}

export default function SMSManagerTab({ device, sendCommand, results }) {
  const deviceId = device?.deviceId;
  const isOnline = device?.isOnline;

  const [messages, setMessages] = useState([]);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [confirmDelete, setConfirmDelete] = useState(null);
  const [statusMsg, setStatusMsg] = useState('');
  const [filterType, setFilterType] = useState('all');
  const seenResults = useRef(new Set());

  const showStatus = (msg) => {
    setStatusMsg(msg);
    setTimeout(() => setStatusMsg(''), 3000);
  };

  const loadSMS = () => {
    if (!isOnline) return;
    setLoading(true);
    sendCommand(deviceId, 'get_all_sms', { limit: 100 });
  };

  // SMS messages are loaded manually via the Load button

  useEffect(() => {
    if (!results) return;
    results.forEach(r => {
      if (seenResults.current.has(r.id)) return;
      seenResults.current.add(r.id);

      if (r.command === 'get_all_sms') {
        setLoading(false);
        if (r.success && r.response) {
          try {
            const d = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
            if (d.messages) setMessages(d.messages);
          } catch (_) {}
        } else {
          showStatus('Failed to load SMS: ' + (r.error || 'Unknown error'));
        }
      }

      if (r.command === 'delete_sms') {
        if (r.success) {
          const d = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
          const deletedId = d?.smsId;
          if (deletedId) setMessages(prev => prev.filter(m => String(m.id) !== String(deletedId)));
          showStatus('Message deleted');
        } else {
          showStatus('Delete failed: ' + (r.error || 'Unknown error'));
        }
      }
    });
  }, [results]);

  const doDelete = (smsId) => {
    sendCommand(deviceId, 'delete_sms', { smsId: String(smsId) });
    setConfirmDelete(null);
  };

  const filtered = messages.filter(m => {
    if (filterType === 'inbox' && m.type !== 1) return false;
    if (filterType === 'sent' && m.type !== 2) return false;
    const q = search.toLowerCase();
    if (!q) return true;
    return (m.address || '').toLowerCase().includes(q) || (m.body || '').toLowerCase().includes(q);
  });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', fontFamily: 'system-ui,sans-serif', color: '#e2e8f0', background: '#0f172a' }}>

      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '14px 18px', borderBottom: '1px solid #1e293b', background: '#0f172a' }}>
        <span style={{ fontSize: 22 }}>💬</span>
        <div>
          <div style={{ fontWeight: 700, fontSize: 16 }}>SMS Manager</div>
          <div style={{ fontSize: 12, color: '#64748b' }}>Last 100 messages · sorted by date</div>
        </div>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
          <span style={{ fontSize: 12, color: '#64748b' }}>{messages.length} messages</span>
          <button onClick={loadSMS} disabled={!isOnline || loading} style={btnStyle('#334155')}>
            {loading ? '⏳ Loading…' : '↻ Refresh'}
          </button>
        </div>
      </div>

      {/* Status bar */}
      {statusMsg && (
        <div style={{ padding: '8px 18px', background: '#1e293b', fontSize: 12, color: '#94a3b8', borderBottom: '1px solid #1e293b' }}>
          {statusMsg}
        </div>
      )}

      {/* Toolbar */}
      <div style={{ display: 'flex', gap: 10, padding: '10px 14px', borderBottom: '1px solid #1e293b', alignItems: 'center', flexWrap: 'wrap' }}>
        <input
          value={search}
          onChange={e => setSearch(e.target.value)}
          placeholder="Search by number or text…"
          style={inputStyle}
        />
        <select
          value={filterType}
          onChange={e => setFilterType(e.target.value)}
          style={{ ...inputStyle, width: 120 }}
        >
          <option value="all">All ({messages.length})</option>
          <option value="inbox">Inbox ({messages.filter(m => m.type === 1).length})</option>
          <option value="sent">Sent ({messages.filter(m => m.type === 2).length})</option>
        </select>
      </div>

      {/* SMS List */}
      <div style={{ flex: 1, overflowY: 'auto' }}>
        {!isOnline && (
          <div style={{ padding: 32, textAlign: 'center', color: '#475569', fontSize: 13 }}>
            <div style={{ fontSize: 32, marginBottom: 8 }}>📴</div>
            Device offline
          </div>
        )}
        {isOnline && loading && (
          <div style={{ padding: 32, textAlign: 'center', color: '#64748b', fontSize: 13 }}>
            ⏳ Loading messages…
          </div>
        )}
        {isOnline && !loading && filtered.length === 0 && (
          <div style={{ padding: 32, textAlign: 'center', color: '#475569', fontSize: 13 }}>
            <div style={{ fontSize: 32, marginBottom: 8 }}>💬</div>
            {messages.length === 0 ? 'No messages loaded. Click Refresh.' : 'No messages match your search.'}
          </div>
        )}
        {filtered.map(msg => {
          const typeInfo = smsType(msg.type);
          return (
            <div key={msg.id} style={{
              padding: '12px 16px',
              borderBottom: '1px solid #1e293b',
              display: 'flex', gap: 12, alignItems: 'flex-start',
            }}>
              <div style={{ flexShrink: 0, marginTop: 2 }}>
                <div style={{
                  width: 36, height: 36, borderRadius: '50%',
                  background: typeInfo.color + '22',
                  border: '1px solid ' + typeInfo.color + '55',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: 16,
                }}>
                  {msg.type === 2 ? '📤' : '📥'}
                </div>
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, marginBottom: 3 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ fontWeight: 700, fontSize: 13, color: '#f1f5f9' }}>{msg.address || 'Unknown'}</span>
                    <span style={{ fontSize: 10, color: typeInfo.color, background: typeInfo.color + '22', borderRadius: 4, padding: '1px 6px', fontWeight: 600 }}>
                      {typeInfo.label}
                    </span>
                    {!msg.read && <span style={{ fontSize: 10, color: '#f59e0b', background: '#f59e0b22', borderRadius: 4, padding: '1px 6px' }}>UNREAD</span>}
                  </div>
                  <span style={{ fontSize: 11, color: '#475569', whiteSpace: 'nowrap', flexShrink: 0 }}>{formatDate(msg.date)}</span>
                </div>
                <div style={{ fontSize: 12, color: '#94a3b8', lineHeight: 1.5, wordBreak: 'break-word' }}>
                  {msg.body || '(no body)'}
                </div>
                <div style={{ marginTop: 6, display: 'flex', gap: 8, alignItems: 'center' }}>
                  <span style={{ fontSize: 10, color: '#334155', fontFamily: 'monospace' }}>ID: {msg.id}</span>
                  <button
                    onClick={() => setConfirmDelete(msg)}
                    disabled={!isOnline}
                    style={{ ...btnStyle('#7f1d1d'), fontSize: 11, padding: '2px 8px' }}
                  >
                    🗑 Delete
                  </button>
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {/* Confirm delete dialog */}
      {confirmDelete && (
        <div style={{
          position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.75)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 9999,
        }} onClick={e => { if (e.target === e.currentTarget) setConfirmDelete(null); }}>
          <div style={{
            background: '#1e293b', borderRadius: 14, padding: 24, width: 360, maxWidth: '92vw',
            border: '1px solid #334155',
          }}>
            <div style={{ fontWeight: 700, color: '#f1f5f9', fontSize: 15, marginBottom: 8 }}>Delete Message?</div>
            <div style={{ fontSize: 12, color: '#64748b', marginBottom: 4 }}>From: {confirmDelete.address}</div>
            <div style={{ fontSize: 12, color: '#94a3b8', marginBottom: 18, background: '#0f172a', borderRadius: 8, padding: '8px 12px', maxHeight: 80, overflow: 'hidden', lineHeight: 1.5 }}>
              {confirmDelete.body}
            </div>
            <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
              <button onClick={() => setConfirmDelete(null)} style={{ ...btnStyle('#334155'), padding: '7px 18px' }}>Cancel</button>
              <button onClick={() => doDelete(confirmDelete.id)} style={{ ...btnStyle('#dc2626'), padding: '7px 18px' }}>Delete</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

const btnStyle = bg => ({
  background: bg, color: '#fff', border: 'none', borderRadius: 6,
  padding: '6px 12px', cursor: 'pointer', fontSize: 12,
  fontFamily: 'inherit', transition: 'opacity .15s',
});

const inputStyle = {
  background: '#1e293b', border: '1px solid #334155', borderRadius: 6,
  color: '#e2e8f0', fontSize: 12, padding: '7px 10px',
  flex: 1, boxSizing: 'border-box', fontFamily: 'inherit',
};
