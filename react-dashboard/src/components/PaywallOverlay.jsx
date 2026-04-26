import React, { useState } from 'react';

const styles = {
  wrap: {
    position: 'absolute', inset: 0,
    background: 'radial-gradient(circle at 50% 30%, rgba(124,58,237,0.15) 0%, rgba(15,23,42,0.95) 60%, rgba(15,23,42,1) 100%)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    padding: 20, zIndex: 5,
  },
  card: {
    background: '#16213e',
    border: '1px solid rgba(124,58,237,0.4)',
    borderRadius: 16,
    boxShadow: '0 20px 60px rgba(0,0,0,0.6), 0 0 80px rgba(124,58,237,0.15)',
    width: '100%',
    maxWidth: 460,
    padding: 28,
    color: '#e2e8f0',
    textAlign: 'center',
  },
  lockBadge: {
    width: 64, height: 64, borderRadius: '50%',
    background: 'linear-gradient(135deg, #7c3aed 0%, #4f46e5 100%)',
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    fontSize: 30, margin: '0 auto 16px',
    boxShadow: '0 8px 24px rgba(124,58,237,0.4)',
  },
  title: { fontSize: 20, fontWeight: 700, marginBottom: 6 },
  subtitle: { fontSize: 13, color: '#94a3b8', marginBottom: 18, lineHeight: 1.5 },
  priceRow: {
    background: '#0f172a', border: '1px solid #1e293b', borderRadius: 10,
    padding: '14px 18px', marginBottom: 18,
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
  },
  priceLeft:  { fontSize: 13, color: '#94a3b8', textAlign: 'left' },
  priceLabel: { fontSize: 11, color: '#64748b', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 2 },
  priceValue: { fontSize: 24, fontWeight: 700, color: '#a5b4fc' },
  priceUnit:  { fontSize: 12, color: '#64748b', marginLeft: 4 },
  cta: {
    display: 'block', width: '100%',
    background: 'linear-gradient(135deg, #7c3aed 0%, #4f46e5 100%)',
    color: '#fff', textDecoration: 'none',
    border: 'none', borderRadius: 10,
    padding: '14px 18px',
    fontSize: 15, fontWeight: 700,
    cursor: 'pointer',
    boxShadow: '0 8px 24px rgba(124,58,237,0.35)',
    transition: 'transform 0.1s',
  },
  ctaHint: {
    fontSize: 11, color: '#64748b', marginTop: 10, lineHeight: 1.5,
  },
  steps: {
    background: 'rgba(124,58,237,0.06)',
    border: '1px solid rgba(124,58,237,0.2)',
    borderRadius: 10,
    padding: '12px 16px',
    margin: '18px 0 14px',
    fontSize: 12, color: '#cbd5e1',
    textAlign: 'left',
    lineHeight: 1.7,
  },
  emailRow: {
    background: '#0f172a', border: '1px solid #1e293b', borderRadius: 8,
    padding: '8px 12px', fontSize: 12, color: '#a5b4fc',
    marginTop: 6, fontFamily: '"JetBrains Mono","Fira Code",monospace',
    overflow: 'hidden', textOverflow: 'ellipsis',
  },
  rowBtns: { display: 'flex', gap: 8, marginTop: 14 },
  ghostBtn: {
    flex: 1, background: 'transparent',
    border: '1px solid #334155', color: '#94a3b8',
    borderRadius: 8, padding: '9px 12px', fontSize: 12,
    cursor: 'pointer', fontWeight: 600,
  },
};

export default function PaywallOverlay({
  email,
  paywall,        // { priceUsd, extendDays, paymentUrl }
  onBack,
  onRefresh,
  trialEndDate,
}) {
  const [refreshing, setRefreshing] = useState(false);
  const price   = paywall?.priceUsd   ?? 25;
  const extend  = paywall?.extendDays ?? 30;
  const url     = paywall?.paymentUrl || '#';

  const handleRefresh = async () => {
    if (!onRefresh) return;
    setRefreshing(true);
    try { await onRefresh(); } finally { setRefreshing(false); }
  };

  const expiredOn = trialEndDate ? new Date(trialEndDate).toLocaleDateString() : null;

  return (
    <div style={styles.wrap}>
      <div style={styles.card}>
        <div style={styles.lockBadge}>🔒</div>
        <div style={styles.title}>Device Control is Locked</div>
        <div style={styles.subtitle}>
          Your 7-day free trial{expiredOn ? ` ended on ${expiredOn}` : ' has ended'}.
          {' '}Buy us a coffee to unlock device control for the next {extend} days.
        </div>

        <div style={styles.priceRow}>
          <div style={styles.priceLeft}>
            <div style={styles.priceLabel}>One-time payment</div>
            <div style={{ fontSize: 13, color: '#cbd5e1' }}>Unlocks {extend} days of access</div>
          </div>
          <div>
            <span style={styles.priceValue}>${price}</span>
            <span style={styles.priceUnit}>USD</span>
          </div>
        </div>

        <a
          href={url}
          target="_blank"
          rel="noopener noreferrer"
          style={styles.cta}
        >
          ☕ Buy us a coffee — Unlock now
        </a>
        <div style={styles.ctaHint}>
          Opens NOWPayments in a new tab. Pay with crypto — Bitcoin, USDT, ETH and more.
        </div>

        <div style={styles.steps}>
          <div style={{ fontWeight: 600, color: '#a5b4fc', marginBottom: 6 }}>How it works</div>
          1. Click <strong>Buy us a coffee</strong> above.<br />
          2. On the NOWPayments page, confirm your email below is filled in:
          <div style={styles.emailRow}>{email || '— please log in —'}</div>
          3. Pay with your preferred crypto.<br />
          4. Come back and click <strong>I&apos;ve paid — refresh</strong>.
        </div>

        <div style={styles.rowBtns}>
          <button onClick={onBack}    style={styles.ghostBtn}>← Back to devices</button>
          <button onClick={handleRefresh} disabled={refreshing} style={{ ...styles.ghostBtn, opacity: refreshing ? 0.6 : 1 }}>
            {refreshing ? '⏳ Checking…' : "✓ I've paid — refresh"}
          </button>
        </div>
      </div>
    </div>
  );
}
