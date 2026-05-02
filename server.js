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
    if (u) return u;
  }

  if (process.env.REPLIT_DOMAINS) {
    const u = normalizeUrl(process.env.REPLIT_DOMAINS.split(',')[0]);
    if (u) return u;
  }
  if (process.env.HEROKU_APP_NAME) return `https://${process.env.HEROKU_APP_NAME}.herokuapp.com`;
  if (process.env.FLY_APP_NAME)    return `https://${process.env.FLY_APP_NAME}.fly.dev`;

  return `http://localhost:${PORT}`;
}

const BUILD_URL = deriveBuildUrl();
const BUILD_API_KEY = process.env.BUILD_API_KEY || '';
const MAX_PARALLEL = parseInt(process.env.BUILD_MAX_PARALLEL || '5', 10) || 5;

const state = {
  workerPid: null,
  workerStartedAt: null,
  workerAlive: false,
  workerStatus: 'starting',
  lastPollAt: null,
  // Now a map of job-id -> currently-building job. Up to MAX_PARALLEL entries.
  currentJobs: {},
  recentJobs: [],
  recentLogs: [],
  buildUrl: BUILD_URL,
  hasApiKey: Boolean(BUILD_API_KEY),
  maxParallel: MAX_PARALLEL,
};

const MAX_RECENT_JOBS = 30;
const MAX_RECENT_LOGS = 400;

function pushLog(line) {
  state.recentLogs.push({ t: Date.now(), line });
  if (state.recentLogs.length > MAX_RECENT_LOGS) {
    state.recentLogs.splice(0, state.recentLogs.length - MAX_RECENT_LOGS);
  }
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
    };
    state.workerStatus = 'building';
  } else if (accessId && !state.currentJobs[jobId].accessId) {
    state.currentJobs[jobId].accessId = accessId;
  }
  return state.currentJobs[jobId];
}

// Lines from the worker now look like:  "[<JOB_ID>] <message>"
// when the worker is running concurrent jobs. Untagged lines are global
// worker output (startup banner, slot accounting, etc.).
const TAG_RE = /^\[([A-Za-z0-9_-]+)\]\s?(.*)$/;

function parseLine(raw) {
  const stripped = raw.replace(/\x1b\[[0-9;]*m/g, '');
  pushLog(stripped);

  if (/RemoteAccess build worker starting/.test(stripped)) {
    state.workerStatus = 'idle';
    return;
  }

  // Pull off the per-job tag, if present.
  const tagMatch = stripped.match(TAG_RE);
  const taggedJobId = tagMatch ? tagMatch[1] : null;
  const line = tagMatch ? tagMatch[2] : stripped;

  // "📥 Job <id> accepted for <access> (slots in use: x/y)"
  let m = line.match(/^\s*📥\s*Job\s+(\S+)\s+accepted\s+for\s+(\S+)/);
  if (m) {
    ensureJob(m[1], m[2]);
    state.lastPollAt = Date.now();
    return;
  }

  // "▶ Job <id> — Access <access>"
  m = line.match(/^\s*▶\s*Job\s+(\S+)\s+—\s+Access\s+(\S+)/);
  if (m) {
    ensureJob(m[1], m[2]);
    state.lastPollAt = Date.now();
    return;
  }

  // Per-job metadata lines — only act on them if we know which job they belong
  // to (i.e. they came in tagged). Untagged lines from the legacy single-job
  // path are attributed to the most-recently-started job for back-compat.
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

  // Success / failure terminators emitted from the per-job subshell.
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

  if (!BUILD_API_KEY) {
    state.workerStatus = 'misconfigured';
    pushLog('[worker not started: BUILD_API_KEY missing]');
    return;
  }
  if (!BUILD_URL_EXPLICIT) {
    state.workerStatus = 'misconfigured';
    pushLog(`[worker not started: BUILD_URL not set — auto-derived to ${BUILD_URL} which is this worker itself. Set BUILD_URL to your dashboard URL.]`);
    return;
  }

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
      process.stdout.write(line + '\n');
      parseLine(line);
    }
  });

  child.stderr.on('data', (chunk) => {
    stderrBuf += chunk.toString();
    let idx;
    while ((idx = stderrBuf.indexOf('\n')) !== -1) {
      const line = stderrBuf.slice(0, idx);
      stderrBuf = stderrBuf.slice(idx + 1);
      process.stderr.write(line + '\n');
      parseLine(line);
    }
  });

  child.on('exit', (code, signal) => {
    state.workerAlive = false;
    state.workerStatus = 'restarting';
    pushLog(`[worker exited code=${code} signal=${signal}, restarting in 3s]`);
    // Mark every in-flight job as failed; the worker process is gone.
    for (const jid of Object.keys(state.currentJobs)) {
      finishJob(jid, 'failed', `worker exited (code=${code})`);
    }
    setTimeout(startWorker, 3000);
  });
}

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

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<title>RemoteAccess Build Worker</title>
<meta http-equiv="refresh" content="5" />
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
  pre.logs { margin: 0; max-height: 320px; overflow: auto; background: #07090b;
             border: 1px solid #1f242b; border-radius: 8px; padding: 10px 12px;
             font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; color: #cbd5e1; }
  .log { white-space: pre-wrap; word-break: break-all; }
</style>
</head>
<body>
<div class="wrap">
  <h1>RemoteAccess Build Worker</h1>
  <div class="sub">Auto-refreshing every 5 seconds</div>

  <div class="card">
    <h2>Status</h2>
    <span class="pill"><span class="dot"></span>${esc(state.workerStatus)}</span>
    <div class="meta">
      <div><span class="k">Worker PID</span><span class="v">${state.workerPid ?? '—'}</span></div>
      <div><span class="k">Uptime</span><span class="v">${fmtDuration(uptimeMs)}</span></div>
      <div><span class="k">Concurrency</span><span class="v">${curList.length} / ${state.maxParallel} slot(s) in use</span></div>
      <div><span class="k">Build URL</span><span class="v">${esc(state.buildUrl)}</span></div>
      <div><span class="k">API Key</span><span class="v">${state.hasApiKey ? 'configured' : 'missing'}</span></div>
    </div>
  </div>

  <div class="card">
    <h2>Current Jobs (${curList.length})</h2>
    ${curBlock}
  </div>

  <div class="card">
    <h2>Recent Jobs (${state.recentJobs.length})</h2>
    ${recentBlock}
  </div>
</div>
</body>
</html>`;
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

const server = http.createServer((req, res) => {
  if (req.url === '/healthz') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('ok');
    return;
  }
  if (req.url === '/api/status') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      workerAlive: state.workerAlive,
      workerStatus: state.workerStatus,
      workerPid: state.workerPid,
      uptimeMs: state.workerStartedAt ? Date.now() - state.workerStartedAt : 0,
      buildUrl: state.buildUrl,
      hasApiKey: state.hasApiKey,
      maxParallel: state.maxParallel,
      currentJobs: state.currentJobs,
      recentJobs: state.recentJobs,
    }, null, 2));
    return;
  }
  if (req.url === '/api/build/worker/poll' && req.method === 'GET') {
    // For testing, return a dummy job
    const job = {
      id: 'test-job-' + Date.now(),
      accessId: 'TEST-123',
      moduleName: 'Test Module',
      modulePackage: 'com.test.module',
      installerName: 'Test Installer',
      installerPackage: 'com.test.installer',
      monitoredPackages: 'com.example.app'
    };
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      success: true,
      hasJob: true,
      job: job
    }));
    return;
  }
  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(renderPage());
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`==> Status server listening on :${PORT}`);
  console.log(`==> BUILD_URL: ${BUILD_URL}`);
  console.log(`==> BUILD_API_KEY: ${BUILD_API_KEY ? 'set' : 'MISSING'}`);
  console.log(`==> Concurrency cap: ${MAX_PARALLEL} job(s) in parallel`);
  startWorker();
});
