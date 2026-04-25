import React, { useState, useRef, useEffect } from 'react';

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
    textAlign: 'center',
  },
  icon: { fontSize: 48, marginBottom: 16 },
  title: {
    color: '#a5b4fc',
    fontSize: 22,
    fontWeight: 700,
    marginBottom: 8,
  },
  subtitle: {
    color: '#64748b',
    fontSize: 13,
    marginBottom: 28,
    lineHeight: 1.6,
  },
  emailHighlight: { color: '#818cf8', fontWeight: 600 },
  codeRow: {
    display: 'flex',
    gap: 8,
    justifyContent: 'center',
    marginBottom: 24,
  },
  digitInput: {
    width: 48,
    height: 56,
    background: 'rgba(255,255,255,0.06)',
    border: '1px solid rgba(99,102,241,0.3)',
    borderRadius: 10,
    color: '#e2e8f0',
    fontSize: 24,
    fontWeight: 700,
    textAlign: 'center',
    outline: 'none',
    caretColor: 'transparent',
  },
  digitInputFocused: {
    borderColor: '#6366f1',
    background: 'rgba(99,102,241,0.1)',
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
  successBox: {
    background: 'rgba(34,197,94,0.1)',
    border: '1px solid rgba(34,197,94,0.3)',
    borderRadius: 8,
    padding: '10px 12px',
    color: '#4ade80',
    fontSize: 13,
    marginBottom: 16,
  },
  resendRow: {
    color: '#64748b',
    fontSize: 13,
  },
  resendBtn: {
    color: '#818cf8',
    cursor: 'pointer',
    fontWeight: 600,
    background: 'none',
    border: 'none',
    fontSize: 13,
    padding: 0,
    textDecoration: 'underline',
  },
};

export default function VerifyEmail({ email, previewUrl: initialPreviewUrl, onVerified }) {
  const [digits, setDigits]       = useState(['', '', '', '', '', '']);
  const [error, setError]         = useState('');
  const [success, setSuccess]     = useState('');
  const [loading, setLoading]     = useState(false);
  const [resending, setResending] = useState(false);
  const [focused, setFocused]     = useState(0);
  const [previewUrl, setPreviewUrl] = useState(initialPreviewUrl || '');
  const inputRefs = useRef([]);

  useEffect(() => {
    inputRefs.current[0]?.focus();
  }, []);

  const handleDigit = (idx, val) => {
    const cleaned = val.replace(/\D/g, '').slice(-1);
    const next = [...digits];
    next[idx] = cleaned;
    setDigits(next);
    if (cleaned && idx < 5) {
      inputRefs.current[idx + 1]?.focus();
      setFocused(idx + 1);
    }
  };

  const handleKeyDown = (idx, e) => {
    if (e.key === 'Backspace') {
      if (digits[idx]) {
        const next = [...digits];
        next[idx] = '';
        setDigits(next);
      } else if (idx > 0) {
        inputRefs.current[idx - 1]?.focus();
        setFocused(idx - 1);
      }
    }
  };

  const handlePaste = (e) => {
    e.preventDefault();
    const pasted = e.clipboardData.getData('text').replace(/\D/g, '').slice(0, 6);
    if (!pasted) return;
    const next = ['', '', '', '', '', ''];
    for (let i = 0; i < pasted.length; i++) next[i] = pasted[i];
    setDigits(next);
    const focusIdx = Math.min(pasted.length, 5);
    inputRefs.current[focusIdx]?.focus();
    setFocused(focusIdx);
  };

  const handleVerify = async () => {
    const code = digits.join('');
    if (code.length < 6) {
      setError('Please enter the full 6-digit code.');
      return;
    }
    setError('');
    setSuccess('');
    setLoading(true);
    try {
      const res  = await fetch('/api/user-auth/verify-email', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, code }),
      });
      const data = await res.json();
      if (data.success) {
        localStorage.setItem('user_token', data.token);
        localStorage.setItem('user_info', JSON.stringify(data.user));
        setSuccess('Email verified! Redirecting…');
        setTimeout(() => onVerified(data.user), 1200);
      } else {
        setError(data.error || 'Invalid code. Please try again.');
      }
    } catch {
      setError('Unable to reach server. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const handleResend = async () => {
    setError('');
    setSuccess('');
    setResending(true);
    try {
      const res  = await fetch('/api/user-auth/resend-code', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email }),
      });
      const data = await res.json();
      if (data.success) {
        setSuccess('A new code has been sent.');
        if (data.previewUrl) setPreviewUrl(data.previewUrl);
        setDigits(['', '', '', '', '', '']);
        inputRefs.current[0]?.focus();
      } else {
        setError(data.error || 'Failed to resend. Try again.');
      }
    } catch {
      setError('Unable to reach server.');
    } finally {
      setResending(false);
    }
  };

  return (
    <div style={s.overlay}>
      <div style={s.card}>
        <div style={s.icon}>📧</div>
        <div style={s.title}>Check Your Email</div>
        <p style={s.subtitle}>
          We sent a 6-digit verification code to<br />
          <span style={s.emailHighlight}>{email}</span>.<br />
          Enter it below to activate your account.
        </p>

        {previewUrl && (
          <div style={{
            background: 'rgba(251,191,36,0.08)',
            border: '1px solid rgba(251,191,36,0.3)',
            borderRadius: 8,
            padding: '12px 14px',
            marginBottom: 16,
            fontSize: 12,
            textAlign: 'left',
          }}>
            <div style={{ color: '#fbbf24', fontWeight: 700, marginBottom: 6 }}>
              📬 Email sent to test inbox (no real provider configured)
            </div>
            <div style={{ color: '#94a3b8', marginBottom: 8, lineHeight: 1.5 }}>
              Click below to open your email and get the code. Log in with the credentials shown in server logs.
            </div>
            <a
              href={previewUrl}
              target="_blank"
              rel="noreferrer"
              style={{
                display: 'inline-block',
                background: 'rgba(251,191,36,0.15)',
                border: '1px solid rgba(251,191,36,0.4)',
                borderRadius: 6,
                padding: '6px 12px',
                color: '#fbbf24',
                fontSize: 12,
                fontWeight: 600,
                textDecoration: 'none',
              }}
            >
              Open Email Preview →
            </a>
          </div>
        )}

        {error   && <div style={s.errBox}>{error}</div>}
        {success && <div style={s.successBox}>{success}</div>}

        <div style={s.codeRow} onPaste={handlePaste}>
          {digits.map((d, i) => (
            <input
              key={i}
              ref={el => inputRefs.current[i] = el}
              style={{
                ...s.digitInput,
                ...(focused === i ? s.digitInputFocused : {}),
              }}
              type="text"
              inputMode="numeric"
              maxLength={1}
              value={d}
              onChange={e => handleDigit(i, e.target.value)}
              onKeyDown={e => handleKeyDown(i, e)}
              onFocus={() => setFocused(i)}
              disabled={loading}
            />
          ))}
        </div>

        <button
          style={{ ...s.btn, ...(loading ? s.btnDisabled : {}) }}
          onClick={handleVerify}
          disabled={loading}
        >
          {loading ? 'Verifying…' : 'Verify Email'}
        </button>

        <div style={s.resendRow}>
          Didn't receive it?{' '}
          <button style={s.resendBtn} onClick={handleResend} disabled={resending}>
            {resending ? 'Sending…' : 'Resend code'}
          </button>
        </div>
      </div>
    </div>
  );
}
