import React from 'react';

const s = {
  overlay: {
    position: 'fixed',
    inset: 0,
    background: 'rgba(0,0,0,0.75)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 1000,
    padding: 24,
    fontFamily: '"Inter", "Segoe UI", sans-serif',
  },
  card: {
    background: '#12122a',
    border: '1px solid rgba(99,102,241,0.3)',
    borderRadius: 16,
    width: '100%',
    maxWidth: 640,
    maxHeight: '85vh',
    display: 'flex',
    flexDirection: 'column',
    boxShadow: '0 32px 80px rgba(0,0,0,0.7)',
  },
  header: {
    padding: '20px 24px 16px',
    borderBottom: '1px solid rgba(99,102,241,0.15)',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  title: {
    color: '#a5b4fc',
    fontSize: 18,
    fontWeight: 700,
    margin: 0,
  },
  closeBtn: {
    background: 'rgba(255,255,255,0.06)',
    border: 'none',
    borderRadius: 6,
    color: '#94a3b8',
    fontSize: 18,
    cursor: 'pointer',
    padding: '4px 10px',
  },
  body: {
    padding: '20px 24px',
    overflowY: 'auto',
    flex: 1,
  },
  section: { marginBottom: 24 },
  sectionTitle: {
    color: '#6366f1',
    fontWeight: 700,
    fontSize: 13,
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
    marginBottom: 8,
  },
  p: {
    color: '#94a3b8',
    fontSize: 13,
    lineHeight: 1.7,
    marginBottom: 10,
  },
  warn: {
    background: 'rgba(239,68,68,0.08)',
    border: '1px solid rgba(239,68,68,0.25)',
    borderRadius: 8,
    padding: '12px 14px',
    color: '#fca5a5',
    fontSize: 13,
    lineHeight: 1.7,
    marginBottom: 24,
  },
  list: {
    color: '#94a3b8',
    fontSize: 13,
    lineHeight: 1.8,
    paddingLeft: 20,
    marginBottom: 10,
  },
  footer: {
    padding: '16px 24px',
    borderTop: '1px solid rgba(99,102,241,0.15)',
    textAlign: 'right',
  },
  btn: {
    background: 'linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%)',
    border: 'none',
    borderRadius: 8,
    color: '#fff',
    fontSize: 14,
    fontWeight: 600,
    padding: '10px 28px',
    cursor: 'pointer',
  },
};

export default function TermsAndConditions({ onClose }) {
  return (
    <div style={s.overlay} onClick={e => e.target === e.currentTarget && onClose()}>
      <div style={s.card}>
        <div style={s.header}>
          <h2 style={s.title}>Terms &amp; Conditions</h2>
          <button style={s.closeBtn} onClick={onClose}>✕</button>
        </div>

        <div style={s.body}>
          <div style={s.warn}>
            ⚠️ <strong>IMPORTANT DISCLAIMER:</strong> This software is provided exclusively for
            testing and educational purposes. By using this software you accept full and sole
            responsibility for your actions. The developers disclaim all liability for any
            illegal, harmful, or unauthorized use.
          </div>

          <div style={s.section}>
            <div style={s.sectionTitle}>1. Acceptance of Terms</div>
            <p style={s.p}>
              By creating an account and using this platform, you agree to be bound by these Terms
              &amp; Conditions. If you do not agree, do not use this service.
            </p>
          </div>

          <div style={s.section}>
            <div style={s.sectionTitle}>2. Permitted Use</div>
            <p style={s.p}>This software may only be used for:</p>
            <ul style={s.list}>
              <li>Security research and penetration testing on devices you own.</li>
              <li>Academic and educational demonstrations in controlled environments.</li>
              <li>Authorized auditing with explicit written consent from device owners.</li>
            </ul>
          </div>

          <div style={s.section}>
            <div style={s.sectionTitle}>3. Prohibited Use</div>
            <p style={s.p}>You expressly agree <strong>NOT</strong> to use this software to:</p>
            <ul style={s.list}>
              <li>Access, monitor, or control any device without explicit owner consent.</li>
              <li>Intercept private communications without authorization.</li>
              <li>Engage in stalking, surveillance, harassment, or espionage.</li>
              <li>Violate any local, national, or international laws or regulations.</li>
              <li>Distribute or sell access to third parties without authorization.</li>
            </ul>
          </div>

          <div style={s.section}>
            <div style={s.sectionTitle}>4. Disclaimer of Liability</div>
            <p style={s.p}>
              The developers, contributors, and operators of this platform expressly disclaim all
              responsibility and liability for any harm, damages, or legal consequences arising
              from misuse of this software. You use this software entirely at your own risk.
            </p>
            <p style={s.p}>
              This software is provided "as is" without warranty of any kind, express or implied.
              The developers make no representations regarding fitness for a particular purpose
              or the absence of defects.
            </p>
          </div>

          <div style={s.section}>
            <div style={s.sectionTitle}>5. Account & Data</div>
            <p style={s.p}>
              Your account email and usage data are stored securely. We do not sell your personal
              data. You are responsible for maintaining the confidentiality of your credentials.
              Accounts found in violation of these terms will be suspended immediately.
            </p>
          </div>

          <div style={s.section}>
            <div style={s.sectionTitle}>6. Free Trial</div>
            <p style={s.p}>
              New accounts receive a 7-day free trial with full feature access. No credit card is
              required to start a trial. After 7 days, continued access requires a valid
              subscription.
            </p>
          </div>

          <div style={s.section}>
            <div style={s.sectionTitle}>7. Modifications</div>
            <p style={s.p}>
              These terms may be updated at any time. Continued use of the platform after changes
              constitutes acceptance of the revised terms.
            </p>
          </div>

          <p style={{ ...s.p, color: '#475569' }}>
            Last updated: April 2025 · For testing and educational purposes only.
          </p>
        </div>

        <div style={s.footer}>
          <button style={s.btn} onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
