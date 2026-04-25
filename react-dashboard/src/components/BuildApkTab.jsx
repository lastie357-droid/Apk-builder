import React, { useState, useEffect, useRef, useCallback } from 'react';

// Build APK tab — lets a logged-in user customise the installer + module
// app names and package names, then kicks off build.sh on the server. The
// resulting APKs are scoped to the user's Access ID so multiple users can
// build in parallel without overwriting each other's outputs.

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
  fieldGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))',
    gap: 14,
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
};

const PKG_REGEX  = /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/;
const NAME_REGEX = /^[\w .&'-]{1,40}$/;

function getToken() {
  return localStorage.getItem('user_token') || localStorage.getItem('admin_token') || '';
}
function authHeaders() {
  const t = getToken();
  // Admin tokens are 64-char hex; user tokens are JWTs. Backend's
  // requireUserOrAdmin accepts both via Authorization: Bearer <token>.
  return t ? { Authorization: `Bearer ${t}` } : {};
}

export default function BuildApkTab({ user }) {
  const [moduleName,       setModuleName]       = useState('System Service');
  const [modulePackage,    setModulePackage]    = useState('com.task.tusker');
  const [installerName,    setInstallerName]    = useState('Assist');
  const [installerPackage, setInstallerPackage] = useState('com.onerule.task');

  const [errors, setErrors]       = useState({});
  const [running, setRunning]     = useState(false);
  const [logs, setLogs]           = useState([]);
  const [lastResult, setLastResult] = useState(null); // {success, error}
  const [accessId, setAccessId]   = useState(user?.accessId || '');
  const [downloads, setDownloads] = useState({ module: false, installer: false });
  const logEndRef = useRef(null);
  const pollIdRef = useRef(null);

  // ── Auto-scroll log pane ────────────────────────────────────────────────
  useEffect(() => {
    if (logEndRef.current) logEndRef.current.scrollTop = logEndRef.current.scrollHeight;
  }, [logs]);

  // ── Refresh status (and prefill access id) on mount ─────────────────────
  const fetchStatus = useCallback(async () => {
    try {
      const r = await fetch('/api/build/status', { headers: authHeaders() });
      if (!r.ok) return null;
      const d = await r.json();
      if (d.success) {
        setRunning(!!d.running);
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

  // ── Poll while a build is running ───────────────────────────────────────
  useEffect(() => {
    if (!running) {
      if (pollIdRef.current) { clearInterval(pollIdRef.current); pollIdRef.current = null; }
      // Once a build finishes, see if download files exist
      if (accessId) checkDownloadAvailability();
      return;
    }
    pollIdRef.current = setInterval(fetchStatus, 1500);
    return () => {
      if (pollIdRef.current) { clearInterval(pollIdRef.current); pollIdRef.current = null; }
    };
  }, [running, fetchStatus, accessId]);

  const checkDownloadAvailability = useCallback(async () => {
    // Probe via HEAD request — the download endpoint returns 404 if the file
    // is missing, 200 if present.
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

  const validate = () => {
    const e = {};
    if (!NAME_REGEX.test(moduleName.trim()))           e.moduleName       = '1-40 chars: letters, digits, space, . & \' -';
    if (!PKG_REGEX.test(modulePackage.trim()))         e.modulePackage    = 'e.g. com.example.app (lowercase, dot-separated)';
    if (!NAME_REGEX.test(installerName.trim()))        e.installerName    = '1-40 chars: letters, digits, space, . & \' -';
    if (!PKG_REGEX.test(installerPackage.trim()))      e.installerPackage = 'e.g. com.example.installer';
    if (modulePackage.trim() === installerPackage.trim() && !e.modulePackage && !e.installerPackage) {
      e.installerPackage = 'Must differ from module package';
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
          moduleName:       moduleName.trim(),
          modulePackage:    modulePackage.trim(),
          installerName:    installerName.trim(),
          installerPackage: installerPackage.trim(),
        }),
      });
      const d = await r.json();
      if (!r.ok || !d.success) {
        setLastResult({ success: false, error: d.error || 'Build request failed' });
        return;
      }
      if (d.accessId) setAccessId(d.accessId);
      setRunning(true);
      setLogs(['▶ Build queued — waiting for log stream…']);
    } catch (err) {
      setLastResult({ success: false, error: err.message });
    }
  };

  const downloadApk = (type) => {
    // Use a hidden form-style fetch with auth header → blob → object URL,
    // so the browser triggers a save dialog without losing the Bearer token.
    fetch(`/api/build/download/${type}`, { headers: authHeaders() })
      .then(async (r) => {
        if (!r.ok) {
          const d = await r.json().catch(() => ({}));
          throw new Error(d.error || `HTTP ${r.status}`);
        }
        return r.blob();
      })
      .then((blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = type === 'module' ? 'Module.apk' : 'Installer.apk';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      })
      .catch((err) => alert(`Download failed: ${err.message}`));
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
        <div style={styles.title}>📦 Build Custom APK</div>
        <div style={styles.subtitle}>
          Choose your installer and module names + package IDs, then build.
          Your Access ID is baked into every device that registers with these APKs.
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

        <div style={styles.fieldGrid}>
          {fmtField('moduleName',       moduleName,       setModuleName,       'Module App Name',     'e.g. "System Service"')}
          {fmtField('modulePackage',    modulePackage,    setModulePackage,    'Module Package',      'e.g. com.task.tusker')}
          {fmtField('installerName',    installerName,    setInstallerName,    'Installer App Name',  'e.g. "Assist"')}
          {fmtField('installerPackage', installerPackage, setInstallerPackage, 'Installer Package',   'e.g. com.onerule.task')}
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
