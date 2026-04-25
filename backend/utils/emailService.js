const nodemailer = require('nodemailer');

function createTransporter() {
  const host = process.env.SMTP_HOST;
  const port = parseInt(process.env.SMTP_PORT) || 587;
  const user = process.env.SMTP_USER;
  const pass = process.env.SMTP_PASS;

  if (!host || !user || !pass) {
    return null;
  }

  return nodemailer.createTransport({
    host,
    port,
    secure: port === 465,
    auth: { user, pass },
    tls: { rejectUnauthorized: false }
  });
}

async function sendVerificationEmail(toEmail, name, code) {
  const transporter = createTransporter();

  if (!transporter) {
    console.warn(`[EMAIL] SMTP not configured. Verification code for ${toEmail}: ${code}`);
    return { success: true, simulated: true };
  }

  const from = process.env.SMTP_FROM || process.env.SMTP_USER;

  const html = `
    <!DOCTYPE html>
    <html>
    <body style="font-family:Arial,sans-serif;background:#0f0f1a;margin:0;padding:20px;">
      <div style="max-width:480px;margin:0 auto;background:#16213e;border-radius:12px;padding:36px;border:1px solid #2d2d4e;">
        <div style="text-align:center;margin-bottom:24px;">
          <span style="font-size:36px;">🛡️</span>
          <h2 style="color:#f0f0ff;margin:8px 0 4px;">Email Verification</h2>
          <p style="color:#94a3b8;margin:0;font-size:13px;">Remote Access Control Platform</p>
        </div>
        <p style="color:#94a3b8;font-size:14px;">Hi <strong style="color:#f0f0ff;">${name}</strong>,</p>
        <p style="color:#94a3b8;font-size:14px;">Use the verification code below to complete your registration:</p>
        <div style="text-align:center;margin:28px 0;">
          <div style="background:#1a1a2e;border:2px solid #7c3aed;border-radius:10px;padding:20px;display:inline-block;">
            <span style="font-size:36px;font-weight:700;color:#a78bfa;letter-spacing:10px;">${code}</span>
          </div>
        </div>
        <p style="color:#94a3b8;font-size:13px;text-align:center;">This code expires in <strong style="color:#f0f0ff;">15 minutes</strong>.</p>
        <hr style="border:none;border-top:1px solid #2d2d4e;margin:24px 0;">
        <p style="color:#4b5563;font-size:11px;text-align:center;">
          This platform is for testing and educational purposes only.<br>
          If you did not create an account, please ignore this email.
        </p>
      </div>
    </body>
    </html>
  `;

  try {
    await transporter.sendMail({
      from: `"Remote Access Control" <${from}>`,
      to: toEmail,
      subject: 'Your Verification Code',
      html
    });
    return { success: true, simulated: false };
  } catch (err) {
    console.error('[EMAIL] Send error:', err.message);
    console.warn(`[EMAIL] Fallback — Verification code for ${toEmail}: ${code}`);
    return { success: true, simulated: true, error: err.message };
  }
}

module.exports = { sendVerificationEmail };
