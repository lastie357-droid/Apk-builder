import React, { useState, useRef } from 'react';
import Captcha from './Captcha.jsx';

export default function Login({ onLogin, onSwitchToUser }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [captcha, setCaptcha]   = useState('');
  const [captchaId, setCaptchaId] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const captchaRef = useRef(null);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const res = await fetch('/api/admin/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password, captchaId, captcha }),
      });
      const data = await res.json();
      if (data.success) {
        localStorage.setItem('admin_token', data.token);
        onLogin();
      } else {
        setError(data.error || 'Invalid credentials.');
        captchaRef.current?.refresh();
      }
    } catch (err) {
      setError('Unable to reach server. Please try again.');
      captchaRef.current?.refresh();
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={styles.overlay}>
      <div style={styles.card}>
        <div style={styles.logoRow}>
          <span style={styles.logoIcon}>🛡️</span>
          <span style={styles.logoText}>Admin Dashboard</span>
        </div>
        <p style={styles.subtitle}>Sign in to continue</p>
        <form onSubmit={handleSubmit} style={styles.form}>
          <div style={styles.field}>
            <label style={styles.label}>Username</label>
            <input
              style={styles.input}
              type="text"
              autoComplete="username"
              value={username}
              onChange={e => setUsername(e.target.value)}
              required
              disabled={loading}
              placeholder="Enter username"
            />
          </div>
          <div style={styles.field}>
            <label style={styles.label}>Password</label>
            <input
              style={styles.input}
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              required
              disabled={loading}
              placeholder="Enter password"
            />
          </div>
          <Captcha
            ref={captchaRef}
            value={captcha}
            onChange={setCaptcha}
            onIdChange={setCaptchaId}
            disabled={loading}
          />
          {error && <div style={styles.error}>{error}</div>}
          <button type="submit" style={loading ? { ...styles.button, ...styles.buttonDisabled } : styles.button} disabled={loading}>
            {loading ? 'Signing in…' : 'Sign In'}
          </button>
        </form>
      </div>
    </div>
  );
}

const styles = {
  overlay: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    height: '100vh',
    width: '100vw',
    background: 'var(--bg-primary, #0f0f1a)',
  },
  card: {
    background: 'var(--bg-card, #16213e)',
    border: '1px solid var(--border, #2d2d4e)',
    borderRadius: '12px',
    padding: '40px 36px',
    width: '100%',
    maxWidth: '380px',
    boxShadow: '0 8px 40px rgba(0,0,0,0.5)',
  },
  logoRow: {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
    marginBottom: '6px',
  },
  logoIcon: {
    fontSize: '28px',
  },
  logoText: {
    fontSize: '20px',
    fontWeight: '700',
    color: 'var(--text-primary, #f0f0ff)',
    letterSpacing: '0.5px',
  },
  subtitle: {
    color: 'var(--text-secondary, #94a3b8)',
    fontSize: '13px',
    marginBottom: '28px',
  },
  form: {
    display: 'flex',
    flexDirection: 'column',
    gap: '16px',
  },
  field: {
    display: 'flex',
    flexDirection: 'column',
    gap: '6px',
  },
  label: {
    fontSize: '12px',
    fontWeight: '600',
    color: 'var(--text-secondary, #94a3b8)',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
  },
  input: {
    background: 'var(--bg-secondary, #1a1a2e)',
    border: '1px solid var(--border, #2d2d4e)',
    borderRadius: '8px',
    padding: '10px 14px',
    color: 'var(--text-primary, #f0f0ff)',
    fontSize: '14px',
    outline: 'none',
    transition: 'border-color 0.2s',
  },
  error: {
    background: 'rgba(239,68,68,0.12)',
    border: '1px solid var(--danger, #ef4444)',
    borderRadius: '6px',
    color: 'var(--danger, #ef4444)',
    padding: '8px 12px',
    fontSize: '13px',
  },
  button: {
    background: 'var(--accent, #7c3aed)',
    color: '#fff',
    border: 'none',
    borderRadius: '8px',
    padding: '11px',
    fontSize: '14px',
    fontWeight: '600',
    cursor: 'pointer',
    marginTop: '4px',
    transition: 'background 0.2s',
  },
  buttonDisabled: {
    background: 'var(--text-secondary, #94a3b8)',
    cursor: 'not-allowed',
  },
};
