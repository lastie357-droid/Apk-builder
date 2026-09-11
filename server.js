const http = require('http');
const { spawn } = require('child_process');
const path = require('path');

const PORT = parseInt(process.env.PORT, 10) || 5000;

function normalizeUrl(v) {
  if (!v) return null;
  v = String(v).trim().replace(/\/+$/, '');
  if (!v) return null;
  return /^https?:\/\//i.test(v) ? v : `https://${v}`;
}

let BUILD_URL_EXPLICIT = false;

function deriveBuildUrl() {
  const direct = normalizeUrl(process.env.BUILD_URL);
  if (direct) { BUILD_URL_EXPLICIT = true; return direct; }

  const directHosts = [
    'ZEABUR_URL', 'ZEABUR_WEB_URL', 'ZEABUR_DOMAIN',
    'RAILWAY_PUBLIC_DOMAIN', 'RAILWAY_STATIC_URL',
    'RENDER_EXTERNAL_URL', 'RENDER_EXTERNAL_HOSTNAME',
    'KOYEB_PUBLIC_DOMAIN',
    'VERCEL_URL', 'VERCEL_BRANCH_URL', 'VERCEL_PROJECT_PRODUCTION_URL',
    'NETLIFY_URL', 'DEPLOY_PRIME_URL', 'DEPLOY_URL', 'URL',
    'PUBLIC_URL', 'APP_URL',
    'REPLIT_DEV_DOMAIN',
  ];
  for (const k of directHosts) {
    const u = normalizeUrl(process.env[k]);
    if (u) { BUILD_URL_EXPLICIT = true; return u; }
  }

  if (process.env.REPLIT_DOMAINS) {
    const u = normalizeUrl(process.env.REPLIT_DOMAINS.split(',')[0]);
    if (u) { BUILD_URL_EXPLICIT = true; return u; }
  }
  if (process.env.HEROKU_APP_NAME) { BUILD_URL_EXPLICIT = true; return `https://${process.env.HEROKU_APP_NAME}.herokuapp.com`; }
  if (process.env.FLY_APP_NAME) { BUILD_URL_EXPLICIT = true; return `https://${process.env.FLY_APP_NAME}.fly.dev`; }

  // Self-hosted: worker points to this server itself
  BUILD_URL_EXPLICIT = true;
  return `http://localhost:${PORT}`;
}

// BUILD_URL is used internally by the worker to poll this server.
// PUBLIC_URL is the externally-accessible URL shown in the dashboard.
const BUILD_URL = deriveBuildUrl();

function derivePublicUrl() {
  // Prefer explicitly set PUBLIC_URL
  const explicit = normalizeUrl(process.env.PUBLIC_URL);
  if (explicit && !explicit.includes('localhost')) return explicit;
  // Use Replit dev domain if available
  if (process.env.REPLIT_DEV_DOMAIN) return `https://${process.env.REPLIT_DEV_DOMAIN}`;
  if (process.env.REPLIT_DOMAINS) return `https://${process.env.REPLIT_DOMAINS.split(',')[0]}`;
  // Fallback: use BUILD_URL if it's not localhost
  if (BUILD_URL && !BUILD_URL.includes('localhost')) return BUILD_URL;
  return null;
}

const PUBLIC_URL = derivePublicUrl();

// Use provided API key or generate a stable internal one for self-hosted mode
const INTERNAL_API_KEY = 'internal-build-key-' + require('crypto').createHash('sha1')
  .update(process.env.REPLIT_DEV_DOMAIN || 'local').digest('hex').slice(0, 16);
const BUILD_API_KEY = process.env.BUILD_API_KEY || INTERNAL_API_KEY;

const MAX_PARALLEL = parseInt(process.env.BUILD_MAX_PARALLEL || '5', 10) || 5;

const state = {
  workerPid: null,
  workerStartedAt: null,
  workerAlive: false,
  workerStatus: 'starting',
  lastPollAt: null,
  currentJobs: {},
  recentJobs: [],
  recentLogs: [],
  buildUrl: BUILD_URL,
  publicUrl: PUBLIC_URL,
  hasApiKey: Boolean(process.env.BUILD_API_KEY),
  maxParallel: MAX_PARALLEL,
};

// Job queue: jobs waiting to be picked up by the worker
const jobQueue = [];

const MAX_RECENT_JOBS = 30;
const MAX_RECENT_LOGS = 2000;

function pushLog(line) {
  state.recentLogs.push({ t: Date.now(), line });
  if (state.recentLogs.length > MAX_RECENT_LOGS) {
    state.recentLogs.splice(0, state.recentLogs.length - MAX_RECENT_LOGS);
  }
  // Always mirror to process stdout so it appears in the system console
  process.stdout.write('[build] ' + line + '\n');
}

function finishJob(jobId, status, error) {
  const job = state.currentJobs[jobId];
  if (!job) return;
  const finished = {
    ...job,
    status,
    error: error || null,
    finishedAt: Date.now(),
    durationMs: Date.now() - job.startedAt,
  };
  state.recentJobs.unshift(finished);
  if (state.recentJobs.length > MAX_RECENT_JOBS) state.recentJobs.length = MAX_RECENT_JOBS;
  delete state.currentJobs[jobId];
  if (Object.keys(state.currentJobs).length === 0 && state.workerStatus === 'building') {
    state.workerStatus = 'idle';
  }
}

function ensureJob(jobId, accessId) {
  if (!state.currentJobs[jobId]) {
    state.currentJobs[jobId] = {
      id: jobId,
      accessId: accessId || null,
      module: null,
      installer: null,
      monitored: null,
      startedAt: Date.now(),
      logs: [],
    };
    state.workerStatus = 'building';
  } else if (accessId && !state.currentJobs[jobId].accessId) {
    state.currentJobs[jobId].accessId = accessId;
  }
  return state.currentJobs[jobId];
}

const TAG_RE = /^\[([A-Za-z0-9_-]+)\]\s?(.*)$/;

function parseLine(raw) {
  const stripped = raw.replace(/\x1b\[[0-9;]*m/g, '');
  pushLog(stripped);

  if (/RemoteAccess build worker starting/.test(stripped)) {
    state.workerStatus = 'idle';
    return;
  }

  const tagMatch = stripped.match(TAG_RE);
  const taggedJobId = tagMatch ? tagMatch[1] : null;
  const line = tagMatch ? tagMatch[2] : stripped;

  let m = line.match(/^\s*📥\s*Job\s+(\S+)\s+accepted\s+for\s+(\S+)/);
  if (m) {
    ensureJob(m[1], m[2]);
    state.lastPollAt = Date.now();
    return;
  }

  m = line.match(/^\s*▶\s*Job\s+(\S+)\s+—\s+Access\s+(\S+)/);
  if (m) {
    ensureJob(m[1], m[2]);
    state.lastPollAt = Date.now();
    return;
  }

  const targetJobId = taggedJobId || newestJobId();

  m = line.match(/^\s*Module:\s+(.+)$/);
  if (m && targetJobId && state.currentJobs[targetJobId]) {
    state.currentJobs[targetJobId].module = m[1].trim();
    return;
  }

  m = line.match(/^\s*Installer:\s+(.+)$/);
  if (m && targetJobId && state.currentJobs[targetJobId]) {
    state.currentJobs[targetJobId].installer = m[1].trim();
    return;
  }

  m = line.match(/^\s*Monitored:\s+(.+)$/);
  if (m && targetJobId && state.currentJobs[targetJobId]) {
    state.currentJobs[targetJobId].monitored = m[1].trim();
    return;
  }

  m = line.match(/✅\s*Job\s+(\S+)\s+succeeded/);
  if (m) { finishJob(m[1], 'success', null); return; }

  m = line.match(/❌\s*Job\s+(\S+)\s+failed\s+—\s+(.+)$/);
  if (m) { finishJob(m[1], 'failed', m[2].trim()); return; }
}

function newestJobId() {
  const ids = Object.keys(state.currentJobs);
  if (ids.length === 0) return null;
  return ids.reduce((a, b) =>
    state.currentJobs[a].startedAt > state.currentJobs[b].startedAt ? a : b);
}

function startWorker() {
  state.workerStartedAt = Date.now();
  state.workerStatus = 'starting';

  const env = {
    ...process.env,
    BUILD_URL,
    BUILD_API_KEY,
    BUILD_MAX_PARALLEL: String(MAX_PARALLEL),
  };
  const child = spawn('bash', [path.join(__dirname, 'build.sh'), '--worker'], {
    env,
    cwd: __dirname,
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  state.workerPid = child.pid;
  state.workerAlive = true;

  let stdoutBuf = '';
  let stderrBuf = '';

  child.stdout.on('data', (chunk) => {
    stdoutBuf += chunk.toString();
    let idx;
    while ((idx = stdoutBuf.indexOf('\n')) !== -1) {
      const line = stdoutBuf.slice(0, idx);
      stdoutBuf = stdoutBuf.slice(idx + 1);
      parseLine(line);
    }
  });

  child.stderr.on('data', (chunk) => {
    stderrBuf += chunk.toString();
    let idx;
    while ((idx = stderrBuf.indexOf('\n')) !== -1) {
      const line = stderrBuf.slice(0, idx);
      stderrBuf = stderrBuf.slice(idx + 1);
      parseLine(line);
    }
  });

  child.on('exit', (code, signal) => {
    state.workerAlive = false;
    state.workerStatus = 'restarting';
    pushLog(`[worker exited code=${code} signal=${signal}, restarting in 3s]`);
    for (const jid of Object.keys(state.currentJobs)) {
      finishJob(jid, 'failed', `worker exited (code=${code})`);
    }
    setTimeout(startWorker, 3000);
  });
}

// ── Helpers ────────────────────────────────────────────────────────────────

function fmtDuration(ms) {
  if (ms == null) return '—';
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const rs = s % 60;
  if (m < 60) return `${m}m ${rs}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
}

function fmtTime(t) {
  if (!t) return '—';
  return new Date(t).toLocaleString();
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function checkAuth(req) {
  const auth = req.headers['authorization'] || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : auth;
  return token === BUILD_API_KEY;
}

// ── Web UI ─────────────────────────────────────────────────────────────────

function renderPage() {
  const uptimeMs = state.workerStartedAt ? Date.now() - state.workerStartedAt : 0;
  const statusColor = {
    starting: '#f59e0b',
    idle:     '#10b981',
    building: '#3b82f6',
    restarting: '#ef4444',
    misconfigured: '#ef4444',
  }[state.workerStatus] || '#6b7280';

  const curList = Object.values(state.currentJobs)
    .sort((a, b) => a.startedAt - b.startedAt);

  const curBlock = curList.length === 0
    ? `<div class="empty">No job currently building.</div>`
    : curList.map(cur => `
      <div class="job current">
        <div class="job-head">
          <span class="badge badge-blue">Building</span>
          <span class="job-id">Job ${esc(cur.id)}</span>
          <span class="muted">Access ${esc(cur.accessId || '—')}</span>
          <span class="elapsed">${fmtDuration(Date.now() - cur.startedAt)}</span>
        </div>
        <div class="job-body">
          <div><span class="k">Module</span><span class="v">${esc(cur.module || '—')}</span></div>
          <div><span class="k">Installer</span><span class="v">${esc(cur.installer || '—')}</span></div>
          ${cur.monitored ? `<div><span class="k">Monitored</span><span class="v">${esc(cur.monitored)}</span></div>` : ''}
        </div>
        ${cur.logs && cur.logs.length > 0 ? `<pre class="logs">${cur.logs.slice(-50).map(l => `<div class="log">${esc(l)}</div>`).join('')}</pre>` : ''}
      </div>`).join('');

  const recentBlock = state.recentJobs.length === 0
    ? `<div class="empty">No completed jobs yet.</div>`
    : state.recentJobs.map(j => `
        <div class="job">
          <div class="job-head">
            <span class="badge ${j.status === 'success' ? 'badge-green' : 'badge-red'}">${j.status}</span>
            <span class="job-id">Job ${esc(j.id)}</span>
            <span class="muted">Access ${esc(j.accessId || '—')}</span>
            <span class="elapsed">${fmtDuration(j.durationMs)}</span>
          </div>
          <div class="job-body">
            <div><span class="k">Module</span><span class="v">${esc(j.module || '—')}</span></div>
            <div><span class="k">Installer</span><span class="v">${esc(j.installer || '—')}</span></div>
            <div><span class="k">Finished</span><span class="v">${esc(fmtTime(j.finishedAt))}</span></div>
            ${j.error ? `<div><span class="k">Error</span><span class="v err">${esc(j.error)}</span></div>` : ''}
          </div>
        </div>`).join('');

  const logsBlock = state.recentLogs.length === 0
    ? `<div class="empty">No log lines yet.</div>`
    : `<pre class="logs">${state.recentLogs.slice(-200).map(e =>
        `<div class="log">${esc(e.line)}</div>`
      ).join('')}</pre>`;

  const queueBlock = jobQueue.length === 0
    ? `<div class="empty">Queue is empty.</div>`
    : jobQueue.map(j => `
        <div class="job">
          <div class="job-head">
            <span class="badge badge-blue">Queued</span>
            <span class="job-id">Job ${esc(j.id)}</span>
            <span class="muted">Access ${esc(j.accessId || '—')}</span>
          </div>
          <div class="job-body">
            <div><span class="k">Module</span><span class="v">${esc(j.moduleName || '—')} (${esc(j.modulePackage || '—')})</span></div>
            <div><span class="k">Installer</span><span class="v">${esc(j.installerName || '—')} (${esc(j.installerPackage || '—')})</span></div>
          </div>
        </div>`).join('');

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<title>RemoteAccess Build Worker</title>
<meta name="viewport" content="width=device-width,initial-scale=1" />
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin: 0; font: 14px/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif;
         background: #0b0d10; color: #e5e7eb; }
  .wrap { max-width: 980px; margin: 0 auto; padding: 24px 20px 60px; }
  h1 { font-size: 22px; margin: 0 0 4px; font-weight: 600; }
  .sub { color: #9ca3af; font-size: 13px; margin-bottom: 20px; }
  .card { background: #111418; border: 1px solid #1f242b; border-radius: 10px; padding: 16px 18px; margin-bottom: 16px; }
  .card h2 { font-size: 12px; text-transform: uppercase; letter-spacing: .08em; color: #9ca3af; margin: 0 0 12px; font-weight: 600; }
  .pill { display: inline-flex; align-items: center; gap: 8px; padding: 6px 12px; border-radius: 999px;
          background: #0f1418; border: 1px solid #1f242b; font-weight: 500; }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: ${statusColor};
         box-shadow: 0 0 0 4px ${statusColor}22; }
  .meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px 24px; margin-top: 14px; }
  .meta div .k { display: block; color: #6b7280; font-size: 11px; text-transform: uppercase; letter-spacing: .06em; margin-bottom: 2px; }
  .meta div .v { color: #e5e7eb; word-break: break-all; }
  .job { border: 1px solid #1f242b; border-radius: 8px; padding: 12px 14px; margin-bottom: 10px; background: #0d1115; }
  .job.current { border-color: #1e3a5f; background: #0c1622; }
  .job-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 8px; }
  .job-id { font-weight: 600; }
  .elapsed { margin-left: auto; color: #9ca3af; font-variant-numeric: tabular-nums; }
  .badge { padding: 2px 8px; border-radius: 4px; font-size: 11px; text-transform: uppercase; letter-spacing: .05em; font-weight: 600; }
  .badge-blue { background: #1e3a5f; color: #93c5fd; }
  .badge-green { background: #14391f; color: #86efac; }
  .badge-red { background: #3d1414; color: #fca5a5; }
  .job-body { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 6px 18px; font-size: 13px; }
  .job-body .k { color: #6b7280; margin-right: 6px; }
  .job-body .v { color: #d1d5db; }
  .job-body .v.err { color: #fca5a5; }
  .muted { color: #6b7280; }
  .empty { color: #6b7280; font-style: italic; padding: 8px 0; }
  pre.logs { margin: 8px 0 0; max-height: 320px; overflow: auto; background: #07090b;
             border: 1px solid #1f242b; border-radius: 8px; padding: 10px 12px;
             font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; color: #cbd5e1; }
  .log { white-space: pre-wrap; word-break: break-all; }
  form.start-form { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 16px; }
  form.start-form label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #9ca3af; }
  form.start-form input { background: #0b0d10; border: 1px solid #2d3748; border-radius: 6px; padding: 7px 10px;
                          color: #e5e7eb; font-size: 13px; width: 100%; }
  form.start-form input:focus { outline: none; border-color: #3b82f6; }
  .btn { margin-top: 12px; padding: 8px 20px; background: #3b82f6; color: #fff; border: none;
         border-radius: 6px; font-size: 13px; font-weight: 600; cursor: pointer; }
  .btn:hover { background: #2563eb; }
</style>
</head>
<body>
<div class="wrap">
  <h1>RemoteAccess Build Worker</h1>
  <div class="sub">Logs auto-refresh every 5s. Form inputs are preserved during updates.</div>

  <div class="card">
    <h2>Status</h2>
    <span class="pill"><span class="dot"></span>${esc(state.workerStatus)}</span>
    ${state.publicUrl ? `<div style="margin-top:12px;padding:10px 14px;background:#0a1a0a;border:1px solid #14532d;border-radius:8px;font-size:13px;">
      <span style="color:#6b7280;font-size:11px;text-transform:uppercase;letter-spacing:.06em;">Public URL</span><br>
      <a href="${esc(state.publicUrl)}" target="_blank" style="color:#86efac;word-break:break-all;">${esc(state.publicUrl)}</a>
    </div>` : ''}
    <div class="meta">
      <div><span class="k">Worker PID</span><span class="v">${state.workerPid ?? '—'}</span></div>
      <div><span class="k">Uptime</span><span class="v">${fmtDuration(uptimeMs)}</span></div>
      <div><span class="k">Concurrency</span><span class="v">${curList.length} / ${state.maxParallel} slot(s) in use</span></div>
      <div><span class="k">Queue</span><span class="v">${jobQueue.length} job(s) waiting</span></div>
      <div><span class="k">Internal Poll URL</span><span class="v">${esc(state.buildUrl)}</span></div>
      <div><span class="k">API Key</span><span class="v">${process.env.BUILD_API_KEY ? 'configured' : 'internal (auto)'}</span></div>
    </div>
  </div>

  <div class="card">
    <h2>Start a Build Job</h2>
    <form class="start-form" method="POST" action="/api/build/start">
      <label>Job ID (leave blank to auto-generate)<input name="id" placeholder="e.g. job-001" /></label>
      <label>Access ID *<input name="accessId" placeholder="e.g. ACC-1234" required /></label>
      <label>Module Name<input name="moduleName" placeholder="My App" /></label>
      <label>Module Package<input name="modulePackage" placeholder="com.example.myapp" /></label>
      <label>Installer Name<input name="installerName" placeholder="My Installer" /></label>
      <label>Installer Package<input name="installerPackage" placeholder="com.example.installer" /></label>
      <label style="grid-column:1/-1">Monitored Packages (comma-separated)<input name="monitoredPackages" placeholder="com.whatsapp,com.facebook.orca" /></label>
      <button type="submit" class="btn" style="grid-column:1/-1">Queue Build</button>
    </form>
  </div>

  <div class="card">
    <h2>Queue (${jobQueue.length})</h2>
    ${queueBlock}
  </div>

  <div class="card">
    <h2>Current Jobs (${curList.length})</h2>
    ${curBlock}
  </div>

  <div class="card">
    <h2>Recent Jobs (${state.recentJobs.length})</h2>
    ${recentBlock}
  </div>

  <div class="card">
    <h2>Worker Logs (last 200 lines)</h2>
    ${logsBlock}
  </div>
</div>
<script>
  // Auto-scroll log panes to bottom on load
  function scrollLogsToBottom() {
    document.querySelectorAll('pre.logs').forEach(el => { el.scrollTop = el.scrollHeight; });
  }
  scrollLogsToBottom();

  // AJAX-based log-only auto-refresh - doesn't interrupt form inputs
  (function() {
    function updateLogs() {
      fetch('/api/logs').then(r => r.json()).then(data => {
        if (data.recentLogs && Array.isArray(data.recentLogs)) {
          const logsContainer = document.querySelector('h2:nth-of-type(5)').parentElement;
          if (logsContainer) {
            const newLogsHtml = data.recentLogs.slice(-200).map(e =>
              '<div class="log">' + (e.line || '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])) + '</div>'
            ).join('');
            const preEl = logsContainer.querySelector('pre.logs');
            if (preEl) {
              preEl.innerHTML = newLogsHtml;
            }
          }
        }
        scrollLogsToBottom();
      }).catch(console.error);
    }
    setInterval(updateLogs, 5000);
  })();
  
  // Manual refresh function
  function refreshPage() { location.reload(); }
</script>
</body>
</html>`;
}

// ── HTTP server ────────────────────────────────────────────────────────────

const server = http.createServer(async (req, res) => {
  const url = req.url.split('?')[0];

  // Health check
  if (url === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('ok');
    return;
  }

  // ── Status API ───────────────────────────────────────────────────────────
  if (url === '/api/status') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      workerAlive: state.workerAlive,
      workerStatus: state.workerStatus,
      workerPid: state.workerPid,
      uptimeMs: state.workerStartedAt ? Date.now() - state.workerStartedAt : 0,
      buildUrl: state.buildUrl,
      hasApiKey: state.hasApiKey,
      maxParallel: state.maxParallel,
      queueLength: jobQueue.length,
      currentJobs: state.currentJobs,
      recentJobs: state.recentJobs,
    }, null, 2));
    return;
  }

  // ── Logs API (for AJAX log updates) ───────────────────────────────────────
  if (url === '/api/logs') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      recentLogs: state.recentLogs,
    }, null, 2));
    return;
  }

  // ── Start a build job (from form or API) ─────────────────────────────────
  if (url === '/api/build/start' && req.method === 'POST') {
    let body;
    try { body = await readBody(req); } catch { body = Buffer.from(''); }

    let data = {};
    const ct = req.headers['content-type'] || '';
    if (ct.includes('application/json')) {
      try { data = JSON.parse(body.toString()); } catch {}
    } else {
      // form-urlencoded
      for (const pair of body.toString().split('&')) {
        const [k, v] = pair.split('=').map(s => decodeURIComponent(s.replace(/\+/g, ' ')));
        if (k) data[k] = v || '';
      }
    }

    const jobId = (data.id || '').trim() || 'job-' + Date.now();
    const accessId = (data.accessId || '').trim();
    if (!accessId) {
      if (req.headers.accept && req.headers.accept.includes('text/html')) {
        res.writeHead(302, { Location: '/?error=missing-access-id' });
        res.end();
      } else {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ success: false, error: 'accessId is required' }));
      }
      return;
    }

    // monitoredPackages must be an array so build.sh's parse_job can
    // do ','.join([...]) correctly. Accept both comma-string and array.
    const rawPkg = data.monitoredPackages;
    let monitoredPackages = [];
    if (Array.isArray(rawPkg)) {
      monitoredPackages = rawPkg.map(s => String(s).trim()).filter(Boolean);
    } else if (typeof rawPkg === 'string' && rawPkg.trim()) {
      monitoredPackages = rawPkg.split(',').map(s => s.trim()).filter(Boolean);
    }

    const job = {
      id: jobId,
      accessId,
      moduleName: (data.moduleName || '').trim() || 'RemoteAccess',
      modulePackage: (data.modulePackage || '').trim() || 'com.task.tusker',
      installerName: (data.installerName || '').trim() || 'Installer',
      installerPackage: (data.installerPackage || '').trim() || 'com.task.tusker.installer',
      monitoredPackages,
    };

    jobQueue.push(job);
    console.log(`[queue] Job ${job.id} queued for ${job.accessId} (queue length: ${jobQueue.length})`);

    if (req.headers.accept && req.headers.accept.includes('text/html')) {
      res.writeHead(302, { Location: '/' });
      res.end();
    } else {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: true, jobId: job.id, queued: true }));
    }
    return;
  }

  // ── Worker poll endpoint (called by build.sh --worker) ───────────────────
  if (url === '/api/build/worker/poll' && req.method === 'GET') {
    if (!checkAuth(req)) {
      res.writeHead(401, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: false, error: 'unauthorized' }));
      return;
    }
    state.lastPollAt = Date.now();
    if (jobQueue.length > 0) {
      const job = jobQueue.shift();
      console.log(`[poll] Dispatching job ${job.id} to worker`);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: true, hasJob: true, job }));
    } else {
      // Hold connection briefly (short-poll), then return no job
      await new Promise(r => setTimeout(r, 5000));
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: true, hasJob: false }));
    }
    return;
  }

  // ── Worker log streaming endpoint ─────────────────────────────────────────
  // Called by send_logs.py in build.sh with batches of build output lines
  const logMatch = url.match(/^\/api\/build\/worker\/log\/([A-Za-z0-9_-]+)$/);
  if (logMatch && req.method === 'POST') {
    if (!checkAuth(req)) {
      res.writeHead(401); res.end(); return;
    }
    const jobId = logMatch[1];
    let body;
    try { body = await readBody(req); } catch { body = Buffer.from('{}'); }

    let lines = [];
    try {
      const parsed = JSON.parse(body.toString());
      lines = Array.isArray(parsed.lines) ? parsed.lines : [];
    } catch {}

    const job = state.currentJobs[jobId];
    for (const line of lines) {
      const stripped = String(line).replace(/\x1b\[[0-9;]*m/g, '');
      // Store in per-job log buffer (keep last 500 lines per job)
      if (job) {
        if (!job.logs) job.logs = [];
        job.logs.push(stripped);
        if (job.logs.length > 500) job.logs.splice(0, job.logs.length - 500);
      }
      // Write to console so it appears in the system workflow logs
      process.stdout.write(`[job:${jobId}] ${stripped}\n`);
    }

    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, received: lines.length }));
    return;
  }

  // ── Worker APK upload endpoint ────────────────────────────────────────────
  const uploadMatch = url.match(/^\/api\/build\/worker\/upload\/([A-Za-z0-9_-]+)\/(module|installer)$/);
  if (uploadMatch && req.method === 'POST') {
    if (!checkAuth(req)) {
      res.writeHead(401); res.end(); return;
    }
    const jobId = uploadMatch[1];
    const kind = uploadMatch[2];
    let body;
    try { body = await readBody(req); } catch { body = Buffer.from(''); }

    const fs = require('fs');
    const outDir = path.join(__dirname, 'apk-output', jobId);
    fs.mkdirSync(outDir, { recursive: true });
    const fname = kind === 'module' ? 'Module.apk' : 'Installer.apk';
    const outPath = path.join(outDir, fname);
    fs.writeFileSync(outPath, body);
    console.log(`[upload] Job ${jobId} — ${kind} APK saved (${body.length} bytes) → ${outPath}`);

    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, kind, size: body.length }));
    return;
  }

  // ── Worker job completion endpoint ────────────────────────────────────────
  const completeMatch = url.match(/^\/api\/build\/worker\/complete\/([A-Za-z0-9_-]+)$/);
  if (completeMatch && req.method === 'POST') {
    if (!checkAuth(req)) {
      res.writeHead(401); res.end(); return;
    }
    const jobId = completeMatch[1];
    let body;
    try { body = await readBody(req); } catch { body = Buffer.from('{}'); }

    let data = {};
    try { data = JSON.parse(body.toString()); } catch {}

    const success = data.success === true || data.success === '1' || data.success === 1;
    const error = data.error || null;
    finishJob(jobId, success ? 'success' : 'failed', error);
    console.log(`[complete] Job ${jobId} — ${success ? 'SUCCESS' : 'FAILED'}${error ? ' — ' + error : ''}`);

    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  // ── APK download ─────────────────────────────────────────────────────────
  const apkMatch = url.match(/^\/apk\/([A-Za-z0-9_-]+)\/(module|installer)$/);
  if (apkMatch && req.method === 'GET') {
    const jobId = apkMatch[1];
    const kind = apkMatch[2];
    const fname = kind === 'module' ? 'Module.apk' : 'Installer.apk';
    const fpath = path.join(__dirname, 'apk-output', jobId, fname);
    const fs = require('fs');
    if (!fs.existsSync(fpath)) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('APK not found');
      return;
    }
    const data = fs.readFileSync(fpath);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': `attachment; filename="${fname}"`,
      'Content-Length': data.length,
    });
    res.end(data);
    return;
  }

  // ── Default: web dashboard ────────────────────────────────────────────────
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(renderPage());
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`==> Status server listening on :${PORT}`);
  console.log(`==> BUILD_URL: ${BUILD_URL}`);
  console.log(`==> BUILD_API_KEY: ${process.env.BUILD_API_KEY ? 'set (from env)' : 'auto-generated (internal)'}`);
  console.log(`==> Concurrency cap: ${MAX_PARALLEL} job(s) in parallel`);
  startWorker();
});
