import React, { useState, useRef } from 'react';
import Captcha from './Captcha.jsx';

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
    maxWidth: 460,
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
    marginBottom: 24,
    marginTop: 0,
  },
  field: { marginBottom: 16 },
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
    transition: 'border-color 0.2s',
  },
  tcBox: {
    background: 'rgba(239,68,68,0.07)',
    border: '1px solid rgba(239,68,68,0.25)',
    borderRadius: 8,
    padding: '12px 14px',
    marginBottom: 16,
  },
  tcTitle: {
    color: '#f87171',
    fontWeight: 700,
    fontSize: 12,
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
    marginBottom: 6,
  },
  tcText: {
    color: '#94a3b8',
    fontSize: 12,
    lineHeight: 1.6,
  },
  checkRow: {
    display: 'flex',
    alignItems: 'flex-start',
    gap: 10,
    marginBottom: 20,
    cursor: 'pointer',
  },
  checkLabel: {
    color: '#94a3b8',
    fontSize: 13,
    lineHeight: 1.5,
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
  btnDisabled: {
    opacity: 0.5,
    cursor: 'not-allowed',
  },
  errBox: {
    background: 'rgba(239,68,68,0.1)',
    border: '1px solid rgba(239,68,68,0.3)',
    borderRadius: 8,
    padding: '10px 12px',
    color: '#f87171',
    fontSize: 13,
    marginBottom: 16,
  },
  switchRow: {
    textAlign: 'center',
    color: '#64748b',
    fontSize: 13,
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
  tcLink: {
    color: '#818cf8',
    cursor: 'pointer',
    background: 'none',
    border: 'none',
    fontSize: 13,
    padding: 0,
    textDecoration: 'underline',
  },
};

export default function UserRegister({ onRegistered, onSwitchToLogin, onShowTerms }) {
  const [name, setName]         = useState('');
  const [email, setEmail]       = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm]   = useState('');
  const [accepted, setAccepted] = useState(false);
  const [captcha, setCaptcha]   = useState('');
  const [captchaId, setCaptchaId] = useState('');
  const [error, setError]       = useState('');
  const [loading, setLoading]   = useState(false);
  const captchaRef = useRef(null);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    if (password !== confirm) {
      setError('Passwords do not match.');
      return;
    }
    if (!accepted) {
      setError('You must accept the Terms & Conditions to register.');
      return;
    }
    setLoading(true);
    try {
      const res  = await fetch('/api/user-auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, email, password, licenseAccepted: true, captchaId, captcha }),
      });
      const data = await res.json();
      if (data.success && data.token && data.user) {
        localStorage.setItem('user_token', data.token);
        localStorage.setItem('user_info', JSON.stringify(data.user));
        onRegistered(data.user);
      } else if (data.success) {
        onRegistered(email);
      } else {
        setError(data.error || 'Registration failed. Please try again.');
        captchaRef.current?.refresh();
        setCaptcha('');
      }
    } catch {
      setError('Unable to reach server. Please try again.');
      captchaRef.current?.refresh();
      setCaptcha('');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={s.overlay}>
      <div style={s.card}>
        <div style={s.logoRow}>
          <span style={s.logoIcon}>🛡️</span>
          <span style={s.logoText}>Create Account</span>
        </div>
        <p style={s.subtitle}>7-day free trial · No credit card required</p>

        <div style={s.tcBox}>
          <div style={s.tcTitle}>⚠️ Legal Disclaimer</div>
          <div style={s.tcText}>
            This software is provided strictly for <strong>testing and educational purposes only</strong>.
            Users are solely responsible for compliance with applicable laws. Unauthorized use against
            devices you do not own or have explicit permission to access is illegal and strictly prohibited.
          </div>
        </div>

        {error && <div style={s.errBox}>{error}</div>}

        <form onSubmit={handleSubmit}>
          <div style={s.field}>
            <label style={s.label}>Full Name</label>
            <input style={s.input} type="text" value={name} onChange={e => setName(e.target.value)}
              placeholder="Your name" required disabled={loading} />
          </div>
          <div style={s.field}>
            <label style={s.label}>Email Address</label>
            <input style={s.input} type="email" value={email} onChange={e => setEmail(e.target.value)}
              placeholder="you@example.com" required disabled={loading} />
          </div>
          <div style={s.field}>
            <label style={s.label}>Password</label>
            <input style={s.input} type="password" value={password} onChange={e => setPassword(e.target.value)}
              placeholder="At least 6 characters" required disabled={loading} />
          </div>
          <div style={s.field}>
            <label style={s.label}>Confirm Password</label>
            <input style={s.input} type="password" value={confirm} onChange={e => setConfirm(e.target.value)}
              placeholder="Repeat password" required disabled={loading} />
          </div>

          <Captcha
            ref={captchaRef}
            value={captcha}
            onChange={setCaptcha}
            onIdChange={setCaptchaId}
            disabled={loading}
          />

          <label style={s.checkRow} onClick={() => setAccepted(a => !a)}>
            <input type="checkbox" checked={accepted} onChange={() => setAccepted(a => !a)}
              style={{ marginTop: 2, accentColor: '#6366f1', cursor: 'pointer' }} />
            <span style={s.checkLabel}>
              I have read and agree to the{' '}
              <button type="button" style={s.tcLink} onClick={(e) => { e.stopPropagation(); onShowTerms && onShowTerms(); }}>
                Terms &amp; Conditions
              </button>
              , and understand this software is for testing/educational use only.
            </span>
          </label>

          <button type="submit" style={{ ...s.btn, ...(loading ? s.btnDisabled : {}) }} disabled={loading}>
            {loading ? 'Creating Account…' : 'Create Account & Start Free Trial'}
          </button>
        </form>

        <div style={s.switchRow}>
          Already have an account?{' '}
          <button style={s.switchLink} onClick={onSwitchToLogin}>Sign in</button>
        </div>
      </div>
    </div>
  );
}
