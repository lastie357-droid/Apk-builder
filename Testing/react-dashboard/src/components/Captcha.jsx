import React, { useEffect, useState, useCallback, useImperativeHandle, forwardRef } from 'react';

const Captcha = forwardRef(function Captcha(
  { value, onChange, onIdChange, disabled, label = 'Security check' },
  ref
) {
  const [svg, setSvg] = useState('');
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState('');

  const refresh = useCallback(async () => {
    setLoading(true);
    setErr('');
    try {
      const r = await fetch('/api/captcha', { cache: 'no-store' });
      const d = await r.json();
      if (d && d.success && d.captchaId && d.svg) {
        setSvg(d.svg);
        onIdChange && onIdChange(d.captchaId);
        onChange && onChange('');
      } else {
        setErr('Could not load captcha.');
      }
    } catch {
      setErr('Could not load captcha.');
    } finally {
      setLoading(false);
    }
  }, [onIdChange, onChange]);

  useEffect(() => { refresh(); }, [refresh]);

  useImperativeHandle(ref, () => ({ refresh }), [refresh]);

  return (
    <div style={{ marginBottom: 16 }}>
      <label style={{
        display: 'block',
        color: '#94a3b8',
        fontSize: 11,
        fontWeight: 600,
        marginBottom: 6,
        letterSpacing: '0.6px',
        textTransform: 'uppercase',
      }}>{label}</label>
      <div style={{
        display: 'flex',
        gap: 8,
        alignItems: 'stretch',
        marginBottom: 6,
      }}>
        <div style={{
          flex: '0 0 auto',
          background: '#0f172a',
          border: '1px solid rgba(99,102,241,0.25)',
          borderRadius: 8,
          padding: 4,
          minHeight: 56,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}>
          {loading
            ? <span style={{ color: '#64748b', fontSize: 12, padding: '0 28px' }}>Loading…</span>
            : svg
              ? <span dangerouslySetInnerHTML={{ __html: svg }} style={{ display: 'inline-flex' }} />
              : <span style={{ color: '#f87171', fontSize: 12, padding: '0 28px' }}>{err || '—'}</span>
          }
        </div>
        <button
          type="button"
          onClick={refresh}
          disabled={disabled || loading}
          title="Get a new code"
          style={{
            background: 'rgba(99,102,241,0.12)',
            border: '1px solid rgba(99,102,241,0.35)',
            borderRadius: 8,
            color: '#a5b4fc',
            cursor: disabled || loading ? 'not-allowed' : 'pointer',
            padding: '0 12px',
            fontSize: 16,
            opacity: disabled || loading ? 0.6 : 1,
          }}
        >↻</button>
      </div>
      <input
        type="text"
        value={value}
        onChange={e => onChange && onChange(e.target.value)}
        placeholder="Type the characters above"
        required
        disabled={disabled}
        autoComplete="off"
        spellCheck={false}
        style={{
          width: '100%',
          background: 'rgba(255,255,255,0.05)',
          border: '1px solid rgba(99,102,241,0.25)',
          borderRadius: 8,
          padding: '10px 12px',
          color: '#e2e8f0',
          fontSize: 14,
          outline: 'none',
          boxSizing: 'border-box',
          letterSpacing: '2px',
          fontFamily: '"JetBrains Mono","Fira Code",monospace',
        }}
      />
    </div>
  );
});

export default Captcha;
