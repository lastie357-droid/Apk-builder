import { useEffect, useRef, useCallback, useState } from 'react';

/**
 * useTcpStream — replaces useWebSocket entirely.
 *
 * Server → Browser:  EventSource (SSE over a persistent HTTP/TCP connection).
 *                    No WebSocket protocol, no WS framing overhead.
 * Browser → Server:  Plain HTTP POST (fire-and-forget, no queue).
 *                    Each request gets its own TCP connection from the browser
 *                    pool — truly parallel, no head-of-line blocking.
 *
 * The hook exposes the same { connected, reconnecting, send } API as the old
 * useWebSocket hook so no component needs to change.
 */
export function useTcpStream(onMessage) {
  const [connected, setConnected]     = useState(false);
  const [reconnecting, setReconnecting] = useState(false);

  const esRef         = useRef(null);
  const retryRef      = useRef(null);
  const sseIdRef      = useRef(null);   // assigned by server via session:init
  const onMessageRef  = useRef(onMessage);
  onMessageRef.current = onMessage;

  const connect = useCallback(() => {
    // Admin token takes precedence (admin dashboard); otherwise fall back to
    // the user JWT so the user dashboard can also stream events.
    const token = localStorage.getItem('admin_token') || localStorage.getItem('user_token');
    if (!token) return;

    // EventSource opens a persistent TCP connection; browser reconnects automatically.
    const es = new EventSource(`/api/events?token=${encodeURIComponent(token)}`);
    esRef.current = es;

    es.onopen = () => {
      setConnected(true);
      setReconnecting(false);
      clearTimeout(retryRef.current);
    };

    es.onmessage = (e) => {
      try {
        const msg = JSON.parse(e.data);
        // Capture our sseClientId the first time the server sends it
        if (msg.event === 'session:init' && msg.data?.sseClientId) {
          sseIdRef.current = msg.data.sseClientId;
          sessionStorage.setItem('sseClientId', msg.data.sseClientId);
        }
        onMessageRef.current(msg.event, msg.data);
      } catch (_) {}
    };

    es.onerror = () => {
      setConnected(false);
      setReconnecting(true);
      es.close();
      // EventSource would retry automatically but we want consistent 3-s backoff
      retryRef.current = setTimeout(connect, 3000);
    };
  }, []);

  useEffect(() => {
    connect();
    return () => {
      clearTimeout(retryRef.current);
      if (esRef.current) esRef.current.close();
    };
  }, [connect]);

  /**
   * send(event, data) — maps legacy WS event names to HTTP POST endpoints.
   * Commands are dispatched immediately over independent TCP connections
   * (browser connection pool), so multiple commands are truly parallel.
   */
  const send = useCallback((event, data) => {
    const token = localStorage.getItem('admin_token') || localStorage.getItem('user_token');

    // ── command:send → POST /api/commands ────────────────────────────
    if (event === 'command:send') {
      const { deviceId, command, params } = data || {};
      fetch('/api/commands', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json',
                   'Authorization': `Bearer ${token}` },
        body: JSON.stringify({ deviceId, command, params: params ?? null,
                               sseClientId: sseIdRef.current }),
      })
        .then(async r => {
          const d = await r.json().catch(() => ({}));
          // 402 → trial expired; surface as a paywall event so the dashboard
          // can re-render the lock screen even if the user state was stale.
          if (r.status === 402) {
            onMessageRef.current('subscription:locked', { paywall: d.paywall, deviceId, command });
            onMessageRef.current('command:error', { message: d.message || 'Subscription required', deviceId, command });
            return null;
          }
          return d;
        })
        .then(d => {
          if (!d) return;
          if (d.commandId) {
            // Synthesise a command:sent event so App.jsx pending-map stays in sync
            onMessageRef.current('command:sent', {
              commandId: d.commandId, command: d.command,
              deviceId: d.deviceId, params: d.params,
              status: 'executing', timestamp: d.timestamp,
            });
          } else if (d.error) {
            onMessageRef.current('command:error', { message: d.error, deviceId, command });
          }
        })
        .catch(err => {
          onMessageRef.current('command:error', { message: err.message });
        });
      return;
    }

    // ── dashboard:ping → POST /api/dashboard/ping ────────────────────
    if (event === 'dashboard:ping') {
      const sentAt = data?.sentAt ?? Date.now();
      fetch('/api/dashboard/ping', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sentAt }),
      })
        .then(r => r.json())
        .then(d => onMessageRef.current('dashboard:pong', { sentAt: d.sentAt, serverAt: d.serverAt }))
        .catch(() => {});
      return;
    }

    // ── recording:start → POST /api/recordings/:deviceId/start ───────
    if (event === 'recording:start') {
      const { deviceId } = data || {};
      if (!deviceId) return;
      fetch(`/api/recordings/${encodeURIComponent(deviceId)}/start`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json',
                   'Authorization': `Bearer ${token}` },
        body: JSON.stringify({}),
      }).catch(() => {});
      return;
    }

    // ── recording:stop → POST /api/recordings/:deviceId/stop ─────────
    if (event === 'recording:stop') {
      const { deviceId } = data || {};
      if (!deviceId) return;
      fetch(`/api/recordings/${encodeURIComponent(deviceId)}/stop`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json',
                   'Authorization': `Bearer ${token}` },
        body: JSON.stringify({}),
      }).catch(() => {});
      return;
    }

    // ── fallback: ignore (was dashboard:get_devices, commands:get_registry
    //    — server pushes those on SSE connect automatically) ──────────
  }, []);

  return { connected, reconnecting, send };
}
