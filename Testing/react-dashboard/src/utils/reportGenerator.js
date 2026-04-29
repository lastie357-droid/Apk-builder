export function generateReport(result) {
  const { command, response, time, deviceId, success } = result;

  let parsedData = null;
  try {
    parsedData = typeof response === 'string' ? JSON.parse(response) : response;
  } catch (_) {
    parsedData = response;
  }

  const ts = time ? new Date(time).toLocaleString() : new Date().toLocaleString();

  const html = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>${command} — Report</title>
<style>
  :root { --bg: #0f0f1a; --card: #16213e; --border: #2d2d4e; --accent: #7c3aed; --success: #22c55e; --danger: #ef4444; --text: #f0f0ff; --muted: #94a3b8; }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: 'Segoe UI', system-ui, sans-serif; background: var(--bg); color: var(--text); font-size: 14px; padding: 24px; }
  .report-header { border-bottom: 1px solid var(--border); padding-bottom: 16px; margin-bottom: 24px; }
  .report-title { font-size: 24px; font-weight: 700; color: #a78bfa; margin-bottom: 6px; }
  .report-meta { color: var(--muted); font-size: 12px; display: flex; gap: 20px; flex-wrap: wrap; }
  .badge { display: inline-block; padding: 3px 8px; border-radius: 6px; font-size: 11px; font-weight: 600; }
  .badge-ok { background: rgba(34,197,94,0.2); color: var(--success); }
  .badge-fail { background: rgba(239,68,68,0.2); color: var(--danger); }
  table { width: 100%; border-collapse: collapse; background: var(--card); border-radius: 10px; overflow: hidden; margin-bottom: 20px; }
  thead tr { background: #1a1a2e; }
  th { padding: 10px 14px; text-align: left; font-size: 11px; text-transform: uppercase; letter-spacing: 0.6px; color: var(--muted); border-bottom: 1px solid var(--border); }
  td { padding: 10px 14px; border-bottom: 1px solid var(--border); font-size: 13px; vertical-align: top; word-break: break-word; }
  tr:last-child td { border-bottom: none; }
  tr:hover td { background: rgba(124,58,237,0.05); }
  .kv-grid { display: grid; grid-template-columns: 220px 1fr; background: var(--card); border: 1px solid var(--border); border-radius: 10px; overflow: hidden; margin-bottom: 20px; }
  .kv-key { padding: 10px 14px; font-size: 11px; text-transform: uppercase; letter-spacing: 0.5px; color: var(--muted); border-bottom: 1px solid var(--border); background: #1a1a2e; }
  .kv-val { padding: 10px 14px; font-size: 13px; border-bottom: 1px solid var(--border); word-break: break-all; }
  .kv-key:last-of-type, .kv-val:last-child { border-bottom: none; }
  .section-title { font-size: 15px; font-weight: 700; color: #a78bfa; margin-bottom: 10px; margin-top: 24px; }
  .raw-box { background: var(--card); border: 1px solid var(--border); border-radius: 10px; padding: 14px; font-family: 'Courier New', monospace; font-size: 11px; white-space: pre-wrap; word-break: break-all; color: var(--muted); overflow-y: auto; max-height: 600px; }
  .chip { display: inline-block; background: rgba(124,58,237,0.15); border: 1px solid rgba(124,58,237,0.3); color: #a78bfa; padding: 2px 8px; border-radius: 10px; font-size: 11px; margin: 2px; }
  .chip-in { background: rgba(34,197,94,0.1); border-color: rgba(34,197,94,0.3); color: var(--success); }
  .chip-out { background: rgba(239,68,68,0.1); border-color: rgba(239,68,68,0.3); color: var(--danger); }
  .chip-miss { background: rgba(245,158,11,0.1); border-color: rgba(245,158,11,0.3); color: #f59e0b; }
  .tree-item { padding: 4px 0 4px 16px; border-left: 2px solid var(--border); margin-left: 8px; font-size: 12px; }
  .tree-text { color: var(--text); font-weight: 500; }
  .tree-class { color: var(--muted); font-size: 11px; }
  .stats-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; margin-bottom: 20px; }
  .stat-card { background: var(--card); border: 1px solid var(--border); border-radius: 10px; padding: 14px; text-align: center; }
  .stat-val { font-size: 28px; font-weight: 700; color: #a78bfa; }
  .stat-label { font-size: 11px; color: var(--muted); margin-top: 4px; }
  @media print { body { background: white; color: black; } .raw-box, table, .kv-grid { border: 1px solid #ccc; } }
</style>
</head>
<body>
<div class="report-header">
  <div class="report-title">📊 ${command.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase())}</div>
  <div class="report-meta">
    <span>📱 Device: <strong>${deviceId || 'Unknown'}</strong></span>
    <span>🕐 ${ts}</span>
    <span class="badge ${success ? 'badge-ok' : 'badge-fail'}">${success ? '✓ SUCCESS' : '✗ FAILED'}</span>
  </div>
</div>
${buildBody(command, parsedData)}
</body>
</html>`;

  const win = window.open('', '_blank');
  if (win) {
    win.document.write(html);
    win.document.close();
  }
}

function buildBody(command, data) {
  if (!data) return '<div class="raw-box">(no data)</div>';

  if (typeof data === 'string') {
    return `<div class="raw-box">${escHtml(data)}</div>`;
  }

  if (!data.success && data.error) {
    return `<div class="kv-grid"><div class="kv-key">Error</div><div class="kv-val" style="color:#ef4444">${escHtml(data.error)}</div></div>`;
  }

  switch (command) {
    case 'get_all_sms':
    case 'get_sms_from_number':
      return renderSMS(data);

    case 'get_all_contacts':
    case 'search_contacts':
      return renderContacts(data);

    case 'get_all_call_logs':
    case 'get_call_logs_by_type':
    case 'get_call_logs_from_number':
      return renderCallLogs(data);

    case 'get_call_statistics':
      return renderCallStats(data);

    case 'get_installed_apps':
      return renderApps(data);

    case 'list_files':
    case 'search_files':
      return renderFiles(data);

    case 'get_keylogs':
      return renderKeylogs(data);

    case 'get_notifications':
    case 'get_notifications_from_app':
      return renderNotifications(data);

    case 'get_device_info':
    case 'get_battery_info':
    case 'get_network_info':
    case 'get_system_info':
    case 'get_wifi_networks':
    case 'ping':
    case 'get_location':
    case 'get_clipboard':
    case 'get_accessibility_status':
    case 'get_recording_status':
      return renderKeyValue(data);

    case 'read_screen':
      return renderScreenReader(data);

    case 'take_photo':
    case 'take_screenshot':
      return renderPhoto(data);

    case 'get_audio':
      return renderAudio(data);

    case 'list_recordings':
      return renderRecordings(data);

    default:
      return `<div class="raw-box">${escHtml(JSON.stringify(data, null, 2))}</div>`;
  }
}

function escHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function renderSMS(data) {
  const msgs = data.messages || data.sms || [];
  let html = `<div class="section-title">💬 SMS Messages (${msgs.length})</div>`;
  if (!msgs.length) return html + '<p style="color:#94a3b8;text-align:center;padding:20px">No messages found</p>';
  html += `<table><thead><tr><th>#</th><th>Type</th><th>Number</th><th>Date</th><th>Message</th></tr></thead><tbody>`;
  msgs.forEach((m, i) => {
    const type = m.type === 1 || m.type === 'inbox' ? 'inbox' : m.type === 2 || m.type === 'sent' ? 'sent' : (m.type || '');
    html += `<tr><td>${i + 1}</td><td><span class="chip">${escHtml(type)}</span></td><td>${escHtml(m.address || m.number || m.phoneNumber || '—')}</td><td style="white-space:nowrap">${escHtml(m.date || m.timestamp || '—')}</td><td>${escHtml(m.body || m.message || '—')}</td></tr>`;
  });
  return html + '</tbody></table>';
}

function renderContacts(data) {
  const contacts = data.contacts || [];
  let html = `<div class="section-title">👥 Contacts (${contacts.length})</div>`;
  if (!contacts.length) return html + '<p style="color:#94a3b8;text-align:center;padding:20px">No contacts found</p>';
  html += `<table><thead><tr><th>#</th><th>Name</th><th>Phone</th><th>Email</th></tr></thead><tbody>`;
  contacts.forEach((c, i) => {
    const phones = Array.isArray(c.phones) ? c.phones.map(p => `<span class="chip">${escHtml(String(p.number || p))}</span>`).join('') : escHtml(c.phone || c.phoneNumber || '—');
    const emails = Array.isArray(c.emails) ? c.emails.map(e => `<span class="chip">${escHtml(String(e.address || e))}</span>`).join('') : escHtml(c.email || '—');
    html += `<tr><td>${i + 1}</td><td><strong>${escHtml(c.name || c.displayName || '—')}</strong></td><td>${phones}</td><td>${emails}</td></tr>`;
  });
  return html + '</tbody></table>';
}

function renderCallLogs(data) {
  const logs = data.callLogs || data.logs || data.calls || [];
  let html = `<div class="section-title">📞 Call Logs (${logs.length})</div>`;
  if (!logs.length) return html + '<p style="color:#94a3b8;text-align:center;padding:20px">No calls found</p>';
  html += `<table><thead><tr><th>#</th><th>Type</th><th>Number</th><th>Name</th><th>Duration</th><th>Date</th></tr></thead><tbody>`;
  logs.forEach((c, i) => {
    const typeNum = parseInt(c.type) || 0;
    const typeLabel = typeNum === 1 ? 'Incoming' : typeNum === 2 ? 'Outgoing' : typeNum === 3 ? 'Missed' : (c.type || '—');
    const typeClass = typeNum === 1 ? 'chip-in' : typeNum === 2 ? 'chip-out' : 'chip-miss';
    const dur = c.duration ? `${c.duration}s` : '—';
    html += `<tr><td>${i + 1}</td><td><span class="chip ${typeClass}">${escHtml(typeLabel)}</span></td><td>${escHtml(c.number || c.phoneNumber || '—')}</td><td>${escHtml(c.name || '—')}</td><td>${escHtml(dur)}</td><td style="white-space:nowrap">${escHtml(c.date || c.timestamp || '—')}</td></tr>`;
  });
  return html + '</tbody></table>';
}

function renderCallStats(data) {
  const s = data.statistics || data;
  let html = '<div class="section-title">📊 Call Statistics</div><div class="stats-grid">';
  const fields = [
    ['totalCalls', 'Total Calls'], ['incomingCalls', 'Incoming'], ['outgoingCalls', 'Outgoing'],
    ['missedCalls', 'Missed'], ['totalDuration', 'Total Duration (s)'], ['avgDuration', 'Avg Duration (s)'],
  ];
  fields.forEach(([k, label]) => {
    if (s[k] !== undefined) {
      html += `<div class="stat-card"><div class="stat-val">${escHtml(String(s[k]))}</div><div class="stat-label">${label}</div></div>`;
    }
  });
  return html + '</div>' + renderKeyValue(s);
}

function renderApps(data) {
  const apps = data.apps || data.installedApps || [];
  let html = `<div class="section-title">📦 Installed Apps (${apps.length})</div>`;
  html += `<table><thead><tr><th>#</th><th>App Name</th><th>Package</th><th>Version</th><th>System</th></tr></thead><tbody>`;
  apps.forEach((a, i) => {
    html += `<tr><td>${i + 1}</td><td><strong>${escHtml(a.name || a.appName || '—')}</strong></td><td style="font-family:monospace;font-size:11px">${escHtml(a.packageName || a.package || '—')}</td><td>${escHtml(a.version || a.versionName || '—')}</td><td>${a.isSystem ? '<span class="chip">System</span>' : ''}</td></tr>`;
  });
  return html + '</tbody></table>';
}

function renderFiles(data) {
  const files = data.files || data.items || [];
  let html = `<div class="section-title">📁 Files (${files.length})</div>`;
  html += `<table><thead><tr><th>#</th><th>Name</th><th>Type</th><th>Size</th><th>Modified</th><th>Path</th></tr></thead><tbody>`;
  files.forEach((f, i) => {
    const icon = f.isDirectory || f.type === 'directory' ? '📁' : '📄';
    html += `<tr><td>${i + 1}</td><td>${icon} ${escHtml(f.name || '—')}</td><td>${escHtml(f.type || f.mimeType || '—')}</td><td>${escHtml(f.size ? formatBytes(f.size) : '—')}</td><td style="white-space:nowrap">${escHtml(f.lastModified || f.modified || '—')}</td><td style="font-family:monospace;font-size:11px">${escHtml(f.path || f.absolutePath || '—')}</td></tr>`;
  });
  return html + '</tbody></table>';
}

function renderKeylogs(data) {
  const logs = data.keylogs || data.logs || [];
  let html = `<div class="section-title">⌨️ Keylogs (${logs.length})</div>`;
  if (!logs.length) return html + '<p style="color:#94a3b8;text-align:center;padding:20px">No keylogs found</p>';
  html += `<table><thead><tr><th>#</th><th>App</th><th>Text</th><th>Time</th></tr></thead><tbody>`;
  logs.forEach((l, i) => {
    html += `<tr><td>${i + 1}</td><td>${escHtml(l.packageName || l.app || '—')}</td><td>${escHtml(l.text || l.key || '—')}</td><td style="white-space:nowrap">${escHtml(l.timestamp || l.time || '—')}</td></tr>`;
  });
  return html + '</tbody></table>';
}

function renderNotifications(data) {
  const notifs = data.notifications || [];
  let html = `<div class="section-title">🔔 Notifications (${notifs.length})</div>`;
  if (!notifs.length) return html + '<p style="color:#94a3b8;text-align:center;padding:20px">No notifications</p>';
  html += `<table><thead><tr><th>#</th><th>App</th><th>Title</th><th>Text</th><th>Time</th></tr></thead><tbody>`;
  notifs.forEach((n, i) => {
    html += `<tr><td>${i + 1}</td><td><span class="chip">${escHtml(n.packageName || n.app || '—')}</span></td><td><strong>${escHtml(n.title || '—')}</strong></td><td>${escHtml(n.text || n.body || '—')}</td><td style="white-space:nowrap">${escHtml(n.time || n.timestamp || '—')}</td></tr>`;
  });
  return html + '</tbody></table>';
}

function renderKeyValue(data) {
  const skip = ['success', 'error', 'message'];
  const keys = Object.keys(data).filter(k => !skip.includes(k));
  if (!keys.length) return '<div class="raw-box">No data</div>';
  let html = '<div class="kv-grid">';
  keys.forEach(k => {
    const v = data[k];
    const display = typeof v === 'object' ? JSON.stringify(v, null, 2) : String(v);
    html += `<div class="kv-key">${escHtml(k)}</div><div class="kv-val">${escHtml(display)}</div>`;
  });
  return html + '</div>';
}

function renderScreenReader(data) {
  const screen = data.screen || {};
  const elements = screen.elements || [];
  let html = `<div class="section-title">📺 Screen Content — ${escHtml(screen.packageName || '—')}</div>`;
  html += `<div class="kv-grid"><div class="kv-key">Package</div><div class="kv-val">${escHtml(screen.packageName || '—')}</div><div class="kv-key">Elements</div><div class="kv-val">${elements.length}</div></div>`;
  if (elements.length) {
    html += `<div class="section-title">UI Elements</div>`;
    html += `<table><thead><tr><th>Type</th><th>Text</th><th>Desc</th><th>Clickable</th><th>Editable</th></tr></thead><tbody>`;
    elements.forEach(el => {
      if (el.text || el.contentDescription) {
        html += `<tr><td style="font-size:11px">${escHtml(el.className || '—')}</td><td>${escHtml(el.text || '—')}</td><td>${escHtml(el.contentDescription || '—')}</td><td>${el.clickable ? '✓' : ''}</td><td>${el.editable || el.editText ? '✓' : ''}</td></tr>`;
      }
    });
    html += '</tbody></table>';
  }
  return html;
}

function renderPhoto(data) {
  const b64 = data.base64 || data.imageData || data.data;
  if (b64) {
    return `<div class="section-title">📸 Image</div><div style="text-align:center;margin:20px 0"><img src="data:image/jpeg;base64,${b64}" style="max-width:100%;border-radius:10px;border:1px solid var(--border)" /></div>${renderKeyValue({...data, base64: undefined, imageData: undefined, data: undefined})}`;
  }
  return renderKeyValue(data);
}

function renderAudio(data) {
  const b64 = data.base64 || data.audioData || data.data;
  if (b64) {
    return `<div class="section-title">🎵 Audio Recording</div><div style="margin:20px 0"><audio controls src="data:audio/mpeg;base64,${b64}" style="width:100%"></audio></div><p style="font-size:12px;color:var(--muted);text-align:center">File: ${escHtml(data.filePath || data.filename || '—')}</p>`;
  }
  return renderKeyValue(data);
}

function renderRecordings(data) {
  const recs = data.recordings || [];
  let html = `<div class="section-title">🎙️ Audio Recordings (${recs.length})</div>`;
  html += `<table><thead><tr><th>#</th><th>Filename</th><th>Size</th><th>Path</th></tr></thead><tbody>`;
  recs.forEach((r, i) => {
    html += `<tr><td>${i + 1}</td><td>${escHtml(r.filename || r.name || '—')}</td><td>${escHtml(r.size ? formatBytes(r.size) : '—')}</td><td style="font-family:monospace;font-size:11px">${escHtml(r.path || r.filePath || '—')}</td></tr>`;
  });
  return html + '</tbody></table>';
}

function formatBytes(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1048576).toFixed(1) + ' MB';
}
