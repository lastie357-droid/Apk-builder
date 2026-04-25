const nodemailer = require('nodemailer');

// ── HTML email template ──────────────────────────────────────────────────────
function buildHtml(name, code) {
  return `<!DOCTYPE html>
<html>
<body style="font-family:Arial,sans-serif;background:#0f0f1a;margin:0;padding:20px;">
  <div style="max-width:480px;margin:0 auto;background:#16213e;border-radius:12px;padding:36px;border:1px solid #2d2d4e;">
    <div style="text-align:center;margin-bottom:24px;">
      <span style="font-size:36px;">🛡️</span>
      <h2 style="color:#f0f0ff;margin:8px 0 4px;">Email Verification</h2>
      <p style="color:#94a3b8;margin:0;font-size:13px;">Remote Access Control Platform</p>
    </div>
    <p style="color:#94a3b8;font-size:14px;">Hi <strong style="color:#f0f0ff;">${name}</strong>,</p>
    <p style="color:#94a3b8;font-size:14px;">Your verification code:</p>
    <div style="text-align:center;margin:28px 0;">
      <div style="background:#1a1a2e;border:2px solid #7c3aed;border-radius:10px;padding:20px;display:inline-block;">
        <span style="font-size:36px;font-weight:700;color:#a78bfa;letter-spacing:10px;">${code}</span>
      </div>
    </div>
    <p style="color:#94a3b8;font-size:13px;text-align:center;">
      Expires in <strong style="color:#f0f0ff;">15 minutes</strong>.
    </p>
    <hr style="border:none;border-top:1px solid #2d2d4e;margin:24px 0;">
    <p style="color:#4b5563;font-size:11px;text-align:center;">
      For testing and educational purposes only.<br>
      If you did not request this, ignore this email.
    </p>
  </div>
</body>
</html>`;
}

// ── 1. Resend.com (free tier: 3 000 emails/month, no domain needed) ──────────
async function trySendViaResend(to, name, code) {
  const apiKey = process.env.RESEND_API_KEY;
  if (!apiKey) return null;

  const res = await fetch('https://api.resend.com/emails', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${apiKey}`,
    },
    body: JSON.stringify({
      from: 'Remote Access Panel <onboarding@resend.dev>',
      to: [to],
      subject: `Your verification code: ${code}`,
      html: buildHtml(name, code),
    }),
  });

  const data = await res.json();
  if (!res.ok) throw new Error(data.message || JSON.stringify(data));
  console.log(`[EMAIL] Sent via Resend to ${to} (id: ${data.id})`);
  return { provider: 'resend', id: data.id };
}

// ── 2. Brevo / Sendinblue (free tier: 300 emails/day, no domain needed) ──────
async function trySendViaBrevo(to, name, code) {
  const apiKey = process.env.BREVO_API_KEY || process.env.SENDINBLUE_API_KEY;
  if (!apiKey) return null;

  const res = await fetch('https://api.brevo.com/v3/smtp/email', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'api-key': apiKey,
    },
    body: JSON.stringify({
      sender: { name: 'Remote Access Panel', email: 'noreply@remoteaccess.dev' },
      to: [{ email: to, name }],
      subject: `Your verification code: ${code}`,
      htmlContent: buildHtml(name, code),
    }),
  });

  const data = await res.json();
  if (!res.ok) throw new Error(data.message || JSON.stringify(data));
  console.log(`[EMAIL] Sent via Brevo to ${to} (messageId: ${data.messageId})`);
  return { provider: 'brevo', messageId: data.messageId };
}

// ── 3. Configured SMTP (any provider: Gmail, Outlook, etc.) ──────────────────
async function trySendViaSmtp(to, name, code) {
  const host = process.env.SMTP_HOST;
  const user = process.env.SMTP_USER;
  const pass = process.env.SMTP_PASS;
  if (!host || !user || !pass) return null;

  const port = parseInt(process.env.SMTP_PORT) || 587;
  const transporter = nodemailer.createTransport({
    host, port,
    secure: port === 465,
    auth: { user, pass },
    tls: { rejectUnauthorized: false },
  });

  const from = process.env.SMTP_FROM || user;
  const info = await transporter.sendMail({
    from: `"Remote Access Panel" <${from}>`,
    to,
    subject: `Your verification code: ${code}`,
    html: buildHtml(name, code),
  });

  console.log(`[EMAIL] Sent via SMTP to ${to} (messageId: ${info.messageId})`);
  return { provider: 'smtp', messageId: info.messageId };
}

// ── 4. Ethereal auto-provisioned test account (zero config, inbox preview) ───
let _etherealTransporter = null;
let _etherealUser = null;

async function trySendViaEthereal(to, name, code) {
  if (!_etherealTransporter) {
    const account = await nodemailer.createTestAccount();
    _etherealUser = account.user;
    _etherealTransporter = nodemailer.createTransport({
      host: 'smtp.ethereal.email',
      port: 587,
      auth: { user: account.user, pass: account.pass },
    });
    console.log(`[EMAIL] Ethereal test account created: ${account.user}`);
    console.log(`[EMAIL] View sent emails at: https://ethereal.email/login`);
    console.log(`[EMAIL] Ethereal login → user: ${account.user}  pass: ${account.pass}`);
  }

  const info = await _etherealTransporter.sendMail({
    from: '"Remote Access Panel" <noreply@remoteaccess.dev>',
    to,
    subject: `Your verification code: ${code}`,
    html: buildHtml(name, code),
  });

  const previewUrl = nodemailer.getTestMessageUrl(info);
  console.log(`[EMAIL] ✉️  Ethereal preview URL → ${previewUrl}`);
  return { provider: 'ethereal', previewUrl };
}

// ── Main export ───────────────────────────────────────────────────────────────
async function sendVerificationEmail(toEmail, name, code) {
  const providers = [
    { name: 'Resend',   fn: () => trySendViaResend(toEmail, name, code)   },
    { name: 'Brevo',    fn: () => trySendViaBrevo(toEmail, name, code)    },
    { name: 'SMTP',     fn: () => trySendViaSmtp(toEmail, name, code)     },
    { name: 'Ethereal', fn: () => trySendViaEthereal(toEmail, name, code) },
  ];

  for (const provider of providers) {
    try {
      const result = await provider.fn();
      if (result !== null) {
        return { success: true, ...result };
      }
    } catch (err) {
      console.warn(`[EMAIL] ${provider.name} failed: ${err.message}`);
    }
  }

  // Final fallback — show code in logs (admin can relay it manually)
  console.log('');
  console.log('╔══════════════════════════════════════════════════════╗');
  console.log('║            VERIFICATION CODE (NO EMAIL SENT)         ║');
  console.log(`║  To:   ${toEmail.padEnd(46)}║`);
  console.log(`║  Code: ${code.padEnd(46)}║`);
  console.log('╚══════════════════════════════════════════════════════╝');
  console.log('');

  return { success: true, simulated: true };
}

module.exports = { sendVerificationEmail };
