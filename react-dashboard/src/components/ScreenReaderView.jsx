import React, { useState, useRef, useEffect } from 'react';

export default function ScreenReaderView({ device, sendCommand, results }) {
  const deviceId = device.deviceId;
  const isOnline = device.isOnline;

  const [readerOutput, setReaderOutput] = useState('');
  const [readerLoading, setReaderLoading] = useState(false);
  const [currentApp, setCurrentApp] = useState('');
  const seenReader = useRef(new Set());

  useEffect(() => {
    results.forEach(r => {
      if ((r.command === 'read_screen' || r.command === 'get_current_app') &&
           r.success && !seenReader.current.has(r.id)) {
        seenReader.current.add(r.id);
        setReaderLoading(false);
        try {
          const d = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
          if (r.command === 'read_screen') {
            const screen = d.screen || d;
            const elements = screen.elements || d.elements || [];
            const texts = elements
              .map(el => el.text || el.contentDescription || el.desc || '')
              .filter(Boolean);
            setCurrentApp(screen.packageName || d.packageName || '');
            setReaderOutput(texts.join('\n') || d.screenText || '(no text found)');
          } else {
            const appName = d.appName || d.packageName || 'unknown';
            setCurrentApp(appName);
            setReaderOutput('Current app: ' + appName);
          }
        } catch (_) {
          setReaderOutput(typeof r.response === 'string' ? r.response : JSON.stringify(r.response));
          setReaderLoading(false);
        }
      }
    });
  }, [results]);

  const readScreen = () => {
    setReaderLoading(true);
    setReaderOutput('');
    sendCommand(deviceId, 'read_screen', {});
  };

  const getCurrentApp = () => {
    setReaderLoading(true);
    setReaderOutput('');
    sendCommand(deviceId, 'get_current_app', {});
  };

  return (
    <div style={{
      display: 'flex', flexDirection: 'column', height: '100%',
      fontFamily: 'system-ui,sans-serif', color: '#e2e8f0', background: '#0f172a',
    }}>
      {/* Header */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 12,
        padding: '14px 18px', borderBottom: '1px solid #1e293b', background: '#0f172a',
      }}>
        <span style={{ fontSize: 22 }}>📺</span>
        <div>
          <div style={{ fontWeight: 700, fontSize: 16 }}>Screen Reader</div>
          <div style={{ fontSize: 12, color: '#64748b' }}>
            {currentApp ? `App: ${currentApp}` : 'Read UI text from the device screen'}
          </div>
        </div>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          <button
            onClick={readScreen}
            disabled={!isOnline || readerLoading}
            style={btnStyle('#1d4ed8')}
          >
            {readerLoading ? '⏳ Reading…' : '📺 Read Screen'}
          </button>
          <button
            onClick={getCurrentApp}
            disabled={!isOnline || readerLoading}
            style={btnStyle('#334155')}
          >
            📱 Current App
          </button>
          {readerOutput && (
            <button onClick={() => setReaderOutput('')} style={btnStyle('#334155')}>
              ✕ Clear
            </button>
          )}
        </div>
      </div>

      {/* Output area */}
      <div style={{ flex: 1, padding: '16px 18px', overflowY: 'auto' }}>
        {readerLoading && (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#64748b', fontSize: 14 }}>
            ⏳ Reading screen…
          </div>
        )}
        {!readerLoading && !readerOutput && (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', color: '#334155', textAlign: 'center' }}>
            <div style={{ fontSize: 48, marginBottom: 12 }}>📺</div>
            <div style={{ fontSize: 14, marginBottom: 6 }}>Press Read Screen to inspect UI text</div>
            <div style={{ fontSize: 12, color: '#475569' }}>
              Reads all visible text elements from the device screen using the accessibility service
            </div>
          </div>
        )}
        {readerOutput && (
          <pre style={{
            color: '#94a3b8', fontSize: 13, whiteSpace: 'pre-wrap',
            wordBreak: 'break-word', margin: 0, lineHeight: 1.7,
            background: '#1e293b', borderRadius: 10, padding: '14px 16px',
            border: '1px solid #334155',
          }}>
            {readerOutput}
          </pre>
        )}
      </div>
    </div>
  );
}

const btnStyle = bg => ({
  background: bg, color: '#fff', border: 'none', borderRadius: 6,
  padding: '7px 14px', cursor: 'pointer', fontSize: 12,
  fontFamily: 'inherit', fontWeight: 600, transition: 'opacity .15s',
});
