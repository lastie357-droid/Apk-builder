import React, { useState } from 'react';

const s = {
  overlay: {
    minHeight: '100vh',
    background: 'linear-gradient(135deg, #0f0f1a 0%, #1a1a2e 50%, #16213e 100%)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '24px 16px',
    fontFamily: '"Inter", "Segoe UI", sans-serif',
  },
  card: {
    background: 'rgba(255,255,255,0.04)',
    border: '1px solid rgba(99,102,241,0.25)',
    borderRadius: 16,
    padding: '36px 32px',
    width: '100%',
    maxWidth: 420,
    boxShadow: '0 24px 64px rgba(0,0,0,0.5)',
  },
  logoRow: {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    marginBottom: 6,
  },
  logoIcon: { fontSize: 28 },
  logoText: {
    fontSize: 20,
    fontWeight: 700,
    color: '#a5b4fc',
    letterSpacing: '-0.3px',
  },
  subtitle: {
    color: '#64748b',
    fontSize: 13,
    marginBottom: 28,
    marginTop: 0,
  },
  field: { marginBottom: 18 },
  label: {
    display: 'block',
    color: '#94a3b8',
    fontSize: 12,
    fontWeight: 600,
    marginBottom: 6,
    letterSpacing: '0.5px',
    textTransform: 'uppercase',
  },
  input: {
    width: '100%',
    background: 'rgba(255,255,255,0.06)',
    border: '1px solid rgba(99,102,241,0.3)',
    borderRadius: 8,
    padding: '10px 12px',
    color: '#e2e8f0',
    fontSize: 14,
    outline: 'none',
    boxSizing: 'border-box',
  },
  btn: {
    width: '100%',
    background: 'linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)',
    border: 'none',
    borderRadius: 8,
    color: '#fff',
    fontSize: 14,
    fontWeight: 600,
    padding: '12px 0',
    cursor: 'pointer',
    transition: 'opacity 0.2s',
    marginBottom: 16,
  },
  btnDisabled: { opacity: 0.5, cursor: 'not-allowed' },
  errBox: {
    background: 'rgba(239,68,68,0.1)',
    border: '1px solid rgba(239,68,68,0.3)',
    borderRadius: 8,
    padding: '10px 12px',
    color: '#f87171',
    fontSize: 13,
    marginBottom: 16,
  },
  divider: {
    borderTop: '1px solid rgba(99,102,241,0.15)',
    marginBottom: 16,
    marginTop: 4,
  },
  switchRow: {
    textAlign: 'center',
    color: '#64748b',
    fontSize: 13,
    marginBottom: 10,
  },
  switchLink: {
    color: '#818cf8',
    cursor: 'pointer',
    fontWeight: 600,
    background: 'none',
    border: 'none',
    fontSize: 13,
    padding: 0,
    textDecoration: 'underline',
  },
  adminLink: {
    textAlign: 'center',
    marginTop: 4,
  },
};

export default function UserLogin({ onLogin, onSwitchToRegister, onNeedsVerification, onSwitchToAdmin }) {
  const [email, setEmail]       = useState('');
  const [password, setPassword] = useState('');
  const [error, setError]       = useState('');
  const [loading, setLoading]   = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const res  = await fetch('/api/user-auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
      });
      const data = await res.json();
      if (data.success) {
        localStorage.setItem('user_token', data.token);
        localStorage.setItem('user_info', JSON.stringify(data.user));
        onLogin(data.user);
      } else if (data.needsVerification) {
        onNeedsVerification && onNeedsVerification(data.email || email);
      } else {
        setError(data.error || 'Invalid credentials.');
      }
    } catch {
      setError('Unable to reach server. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={s.overlay}>
      <div style={s.card}>
        <div style={s.logoRow}>
          <span style={s.logoIcon}>🛡️</span>
          <span style={s.logoText}>Sign In</span>
        </div>
        <p style={s.subtitle}>Access your control dashboard</p>

        {error && <div style={s.errBox}>{error}</div>}

        <form onSubmit={handleSubmit}>
          <div style={s.field}>
            <label style={s.label}>Email Address</label>
            <input style={s.input} type="email" value={email} onChange={e => setEmail(e.target.value)}
              placeholder="you@example.com" required disabled={loading} autoComplete="email" />
          </div>
          <div style={s.field}>
            <label style={s.label}>Password</label>
            <input style={s.input} type="password" value={password} onChange={e => setPassword(e.target.value)}
              placeholder="Your password" required disabled={loading} autoComplete="current-password" />
          </div>

          <button type="submit" style={{ ...s.btn, ...(loading ? s.btnDisabled : {}) }} disabled={loading}>
            {loading ? 'Signing In…' : 'Sign In'}
          </button>
        </form>

        <div style={s.switchRow}>
          Don't have an account?{' '}
          <button style={s.switchLink} onClick={onSwitchToRegister}>Create one</button>
        </div>

        <hr style={s.divider} />

        <div style={s.adminLink}>
          <button style={{ ...s.switchLink, color: '#475569', fontSize: 12 }} onClick={onSwitchToAdmin}>
            Admin login →
          </button>
        </div>
      </div>
    </div>
  );
}
