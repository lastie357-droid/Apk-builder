import React, { useEffect, useRef, useState, useCallback } from 'react';

const SOURCE_COLORS = {
    frps:   '#38bdf8',
    frpc:   '#818cf8',
    server: '#94a3b8',
    system: '#6ee7b7',
};

const LEVEL_COLORS = {
    info:  '#94a3b8',
    warn:  '#fbbf24',
    error: '#f87171',
};

const SOURCES = ['all', 'server', 'frps', 'frpc', 'system'];

function fmtTime(ts) {
    const d = new Date(ts);
    return d.toTimeString().slice(0, 8) + '.' + String(d.getMilliseconds()).padStart(3, '0');
}

export default function ServerLogsTab() {
    const [logs, setLogs]           = useState([]);
    const [filter, setFilter]       = useState('all');
    const [search, setSearch]       = useState('');
    const [paused, setPaused]       = useState(false);
    const [connected, setConnected] = useState(false);
    const bottomRef  = useRef(null);
    const pausedRef  = useRef(false);
    const esRef      = useRef(null);

    pausedRef.current = paused;

    const connect = useCallback(() => {
        if (esRef.current) esRef.current.close();
        const token = localStorage.getItem('admin_token');
        const url   = `/api/logs/stream${token ? `?token=${encodeURIComponent(token)}` : ''}`;
        const es    = new EventSource(url);
        esRef.current = es;

        es.onopen = () => setConnected(true);
        es.onerror = () => { setConnected(false); };
        es.onmessage = (e) => {
            if (pausedRef.current) return;
            try {
                const entry = JSON.parse(e.data);
                setLogs(prev => [...prev, entry].slice(-1000));
            } catch (_) {}
        };
    }, []);

    useEffect(() => {
        connect();
        return () => { if (esRef.current) esRef.current.close(); };
    }, [connect]);

    useEffect(() => {
        if (!paused && bottomRef.current) {
            bottomRef.current.scrollIntoView({ behavior: 'smooth' });
        }
    }, [logs, paused]);

    const clearLogs = () => setLogs([]);

    const visible = logs.filter(l => {
        if (filter !== 'all' && l.source !== filter) return false;
        if (search && !l.message.toLowerCase().includes(search.toLowerCase())) return false;
        return true;
    });

    return (
        <div style={{ display: 'flex', flexDirection: 'column', height: '100%', gap: 12 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                <span style={{ fontSize: 18, fontWeight: 700, color: '#e2e8f0' }}>🖥️ Server Logs</span>
                <span style={{
                    fontSize: 11, padding: '2px 8px', borderRadius: 12,
                    background: connected ? 'rgba(34,197,94,0.15)' : 'rgba(239,68,68,0.15)',
                    color: connected ? '#22c55e' : '#ef4444',
                    border: `1px solid ${connected ? 'rgba(34,197,94,0.3)' : 'rgba(239,68,68,0.3)'}`,
                }}>
                    {connected ? '● Live' : '○ Disconnected'}
                </span>

                <div style={{ display: 'flex', gap: 6, marginLeft: 'auto', flexWrap: 'wrap' }}>
                    <input
                        value={search}
                        onChange={e => setSearch(e.target.value)}
                        placeholder="Search logs…"
                        style={{
                            background: '#1e1b4b', border: '1px solid #2d2d4e', borderRadius: 6,
                            color: '#e2e8f0', padding: '4px 10px', fontSize: 12, width: 160,
                        }}
                    />
                    <select
                        value={filter}
                        onChange={e => setFilter(e.target.value)}
                        style={{
                            background: '#1e1b4b', border: '1px solid #2d2d4e', borderRadius: 6,
                            color: '#e2e8f0', padding: '4px 8px', fontSize: 12,
                        }}
                    >
                        {SOURCES.map(s => <option key={s} value={s}>{s === 'all' ? 'All Sources' : s}</option>)}
                    </select>
                    <button
                        onClick={() => setPaused(p => !p)}
                        style={{
                            background: paused ? 'rgba(251,191,36,0.15)' : 'rgba(99,102,241,0.15)',
                            border: `1px solid ${paused ? '#fbbf24' : '#6366f1'}`,
                            color: paused ? '#fbbf24' : '#a5b4fc',
                            borderRadius: 6, padding: '4px 12px', fontSize: 12, cursor: 'pointer',
                        }}
                    >
                        {paused ? '▶ Resume' : '⏸ Pause'}
                    </button>
                    <button
                        onClick={clearLogs}
                        style={{
                            background: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.3)',
                            color: '#f87171', borderRadius: 6, padding: '4px 12px', fontSize: 12, cursor: 'pointer',
                        }}
                    >
                        🗑 Clear
                    </button>
                </div>
            </div>

            <div style={{
                flex: 1, overflowY: 'auto', background: '#0a0a12',
                border: '1px solid #1e1b4b', borderRadius: 10,
                fontFamily: 'monospace', fontSize: 12, padding: '10px 14px',
                minHeight: 0,
            }}>
                {visible.length === 0 && (
                    <div style={{ color: '#475569', textAlign: 'center', marginTop: 40 }}>
                        {connected ? 'No logs yet…' : 'Connecting to log stream…'}
                    </div>
                )}
                {visible.map((log, i) => (
                    <div
                        key={i}
                        style={{
                            display: 'flex', gap: 10, padding: '2px 0',
                            borderBottom: '1px solid rgba(255,255,255,0.03)',
                            alignItems: 'flex-start',
                        }}
                    >
                        <span style={{ color: '#475569', whiteSpace: 'nowrap', flexShrink: 0 }}>
                            {fmtTime(log.ts)}
                        </span>
                        <span style={{
                            color: SOURCE_COLORS[log.source] || '#94a3b8',
                            whiteSpace: 'nowrap', flexShrink: 0,
                            width: 48, textAlign: 'right',
                        }}>
                            {log.source}
                        </span>
                        <span style={{
                            color: LEVEL_COLORS[log.level] || '#94a3b8',
                            whiteSpace: 'nowrap', flexShrink: 0,
                            width: 34,
                        }}>
                            {log.level?.toUpperCase()}
                        </span>
                        <span style={{ color: '#cbd5e1', wordBreak: 'break-all' }}>
                            {log.message}
                        </span>
                    </div>
                ))}
                <div ref={bottomRef} />
            </div>

            <div style={{ fontSize: 11, color: '#475569', textAlign: 'right' }}>
                {visible.length} / {logs.length} entries
            </div>
        </div>
    );
}
