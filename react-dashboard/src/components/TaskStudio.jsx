import React, { useState, useEffect, useRef } from 'react';

const STEP_TYPES = [
  { type: 'open_app',        label: 'Open App',          icon: '▶️',  color: '#22c55e' },
  { type: 'click_text',      label: 'Click Text',         icon: '👆',  color: '#3b82f6' },
  { type: 'paste_text',      label: 'Paste Text',         icon: '📋',  color: '#a78bfa' },
  { type: 'close_app',       label: 'Close App',          icon: '⏹️', color: '#ef4444' },
  { type: 'delay',           label: 'Delay',              icon: '⏱️',  color: '#f59e0b' },
  { type: 'press_home',      label: 'Press Home',         icon: '🏠',  color: '#06b6d4' },
  { type: 'press_back',      label: 'Press Back',         icon: '◀️',  color: '#06b6d4' },
  { type: 'press_recents',   label: 'Press Recents',      icon: '⬜',  color: '#06b6d4' },
  { type: 'block_screen',    label: 'Block Screen',       icon: '⬛',  color: '#475569' },
  { type: 'unblock_screen',  label: 'Unblock Screen',     icon: '🔲',  color: '#475569' },
  { type: 'swipe_up',        label: 'Swipe Up',           icon: '⬆️',  color: '#8b5cf6' },
  { type: 'swipe_down',      label: 'Swipe Down',         icon: '⬇️',  color: '#8b5cf6' },
  { type: 'swipe_left',      label: 'Swipe Left',         icon: '⬅️',  color: '#8b5cf6' },
  { type: 'swipe_right',     label: 'Swipe Right',        icon: '➡️',  color: '#8b5cf6' },
];

function makeStep(type) {
  const base = { id: Date.now() + Math.random(), type, enabled: true };
  switch (type) {
    case 'open_app':       return { ...base, packageName: '', appLabel: '' };
    case 'click_text':     return { ...base, text: '' };
    case 'paste_text':     return { ...base, text: '' };
    case 'close_app':      return { ...base, packageName: '', appLabel: '' };
    case 'delay':          return { ...base, ms: 1000 };
    default:               return base;
  }
}

function StepEditor({ step, apps, onChange }) {
  const info = STEP_TYPES.find(s => s.type === step.type);

  const field = (label, child) => (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, flex: 1 }}>
      <label style={{ fontSize: 11, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: 0.5 }}>{label}</label>
      {child}
    </div>
  );

  const input = (props) => (
    <input
      {...props}
      style={{
        background: '#1a1a2e', border: '1px solid #2d2d4e', borderRadius: 6,
        padding: '6px 10px', color: '#f0f0ff', fontSize: 13, width: '100%',
        ...props.style
      }}
    />
  );

  const appPickerField = (label, step, onStepChange) => {
    const pkg  = step.packageName || '';
    const onSelectChange = (e) => {
      const selected = e.target.value;
      if (!selected) return;
      const found = apps.find(a => (a.packageName || a.package) === selected);
      onStepChange({
        ...step,
        packageName: selected,
        appLabel: found ? (found.appName || found.label || selected) : selected,
      });
    };
    const onManualChange = (e) => {
      onStepChange({ ...step, packageName: e.target.value, appLabel: e.target.value });
    };

    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, flex: 1 }}>
        {apps.length > 0 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <label style={{ fontSize: 11, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: 0.5 }}>
              {label} — Pick from installed apps
            </label>
            <select
              value={pkg}
              onChange={onSelectChange}
              style={{
                background: '#1a1a2e', border: '1px solid #2d2d4e', borderRadius: 6,
                padding: '6px 10px', color: '#f0f0ff', fontSize: 13, width: '100%'
              }}
            >
              <option value="">— Select from list —</option>
              {apps.map(a => {
                const p = a.packageName || a.package || '';
                const n = a.appName || a.label || p;
                return <option key={p} value={p}>{n}</option>;
              })}
            </select>
          </div>
        )}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          <label style={{ fontSize: 11, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: 0.5 }}>
            {apps.length > 0 ? 'Or enter package name / ID manually' : `${label} — Package Name / ID`}
          </label>
          <input
            placeholder="e.g. com.whatsapp, com.instagram.android…"
            value={pkg}
            onChange={onManualChange}
            style={{
              background: '#1a1a2e', border: '1px solid #2d2d4e', borderRadius: 6,
              padding: '6px 10px', color: '#f0f0ff', fontSize: 13, width: '100%'
            }}
          />
        </div>
      </div>
    );
  };

  switch (step.type) {
    case 'open_app':
      return (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {appPickerField('App to Open', step, onChange)}
        </div>
      );
    case 'click_text':
      return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div style={{ display: 'flex', gap: 8 }}>
            {field('Text to Find & Click on Screen',
              input({ placeholder: 'e.g. Login, Submit, OK…', value: step.text, onChange: e => onChange({ ...step, text: e.target.value }) })
            )}
          </div>
          <div style={{ fontSize: 11, color: '#64748b', display: 'flex', alignItems: 'center', gap: 5 }}>
            <span style={{ color: '#f59e0b' }}>⏱</span>
            Polls every 100 ms — waits up to 8 s for the text to appear. Stops the task if not found.
          </div>
        </div>
      );
    case 'paste_text':
      return (
        <div style={{ display: 'flex', gap: 8 }}>
          {field('Text to Paste',
            input({ placeholder: 'Text that will be pasted into the active field…', value: step.text, onChange: e => onChange({ ...step, text: e.target.value }) })
          )}
        </div>
      );
    case 'close_app':
      return (
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {appPickerField('App to Close', step, onChange)}
        </div>
      );
    case 'delay':
      return (
        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {field('Wait Duration',
            <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
              {input({
                type: 'number', min: 100, max: 60000, step: 100,
                value: step.ms,
                onChange: e => onChange({ ...step, ms: parseInt(e.target.value) || 1000 }),
                style: { width: 100 }
              })}
              <span style={{ color: '#94a3b8', fontSize: 13 }}>milliseconds ({(step.ms / 1000).toFixed(1)}s)</span>
            </div>
          )}
        </div>
      );
    case 'press_home':
    case 'press_back':
    case 'press_recents':
    case 'block_screen':
    case 'unblock_screen':
    case 'swipe_up':
    case 'swipe_down':
    case 'swipe_left':
    case 'swipe_right':
      return (
        <div style={{ fontSize: 12, color: '#64748b', fontStyle: 'italic' }}>
          No parameters — runs immediately when reached
        </div>
      );
    default:
      return null;
  }
}

function StepCard({ step, index, total, apps, onUpdate, onDelete, onMove, runningIndex, completedIndices, errorIndex }) {
  const info = STEP_TYPES.find(s => s.type === step.type);
  const isRunning   = runningIndex === index;
  const isCompleted = completedIndices.includes(index);
  const hasError    = errorIndex === index;

  let borderColor = '#2d2d4e';
  if (isRunning)   borderColor = '#f59e0b';
  if (isCompleted) borderColor = '#22c55e';
  if (hasError)    borderColor = '#ef4444';

  return (
    <div style={{
      background: '#16213e', border: `1px solid ${borderColor}`, borderRadius: 10,
      padding: 14, display: 'flex', gap: 12, alignItems: 'flex-start',
      opacity: step.enabled ? 1 : 0.45,
      transition: 'border-color 0.3s',
      position: 'relative',
    }}>
      {/* Step number */}
      <div style={{
        width: 28, height: 28, borderRadius: '50%', background: isCompleted ? '#22c55e' : isRunning ? '#f59e0b' : hasError ? '#ef4444' : '#1a1a2e',
        border: `2px solid ${info.color}`, display: 'flex', alignItems: 'center', justifyContent: 'center',
        fontSize: 12, fontWeight: 700, color: isCompleted || isRunning || hasError ? '#fff' : info.color,
        flexShrink: 0, transition: 'all 0.3s'
      }}>
        {isCompleted ? '✓' : isRunning ? '⟳' : hasError ? '✗' : index + 1}
      </div>

      <div style={{ flex: 1, minWidth: 0 }}>
        {/* Header */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
          <span style={{ fontSize: 14 }}>{info.icon}</span>
          <span style={{ fontWeight: 600, fontSize: 13, color: info.color }}>{info.label}</span>
          {isRunning && <span style={{ fontSize: 11, color: '#f59e0b', animation: 'pulse 1s infinite' }}>● Running…</span>}
          {isCompleted && <span style={{ fontSize: 11, color: '#22c55e' }}>✓ Done</span>}
          {hasError && <span style={{ fontSize: 11, color: '#ef4444' }}>✗ Failed</span>}
          <label style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 5, cursor: 'pointer', fontSize: 11, color: '#94a3b8' }}>
            <input type="checkbox" checked={step.enabled} onChange={e => onUpdate({ ...step, enabled: e.target.checked })} />
            Enabled
          </label>
        </div>

        {/* Editor */}
        <StepEditor step={step} apps={apps} onChange={onUpdate} />
      </div>

      {/* Controls */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4, flexShrink: 0 }}>
        <button
          onClick={() => onMove(index, -1)} disabled={index === 0}
          style={{ background: '#1a1a2e', border: '1px solid #2d2d4e', borderRadius: 6, padding: '3px 8px', color: '#94a3b8', cursor: 'pointer', fontSize: 12 }}
          title="Move Up"
        >▲</button>
        <button
          onClick={() => onMove(index, 1)} disabled={index === total - 1}
          style={{ background: '#1a1a2e', border: '1px solid #2d2d4e', borderRadius: 6, padding: '3px 8px', color: '#94a3b8', cursor: 'pointer', fontSize: 12 }}
          title="Move Down"
        >▼</button>
        <button
          onClick={() => onDelete(index)}
          style={{ background: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.3)', borderRadius: 6, padding: '3px 8px', color: '#ef4444', cursor: 'pointer', fontSize: 12 }}
          title="Delete Step"
        >✕</button>
      </div>
    </div>
  );
}

export default function TaskStudio({ device, sendCommand, results }) {
  const deviceId = device.deviceId;
  const isOnline = device.isOnline;

  const [workflows, setWorkflows]   = useState([]);
  const [activeWfIndex, setActiveWfIndex] = useState(null);
  const [steps, setSteps]           = useState([]);
  const [wfName, setWfName]         = useState('New Workflow');
  const [apps, setApps]             = useState([]);
  const [appsLoading, setAppsLoading] = useState(false);
  const [saving, setSaving]         = useState(false);

  const [running, setRunning]             = useState(false);
  const [runningIndex, setRunningIndex]   = useState(-1);
  const [completedIndices, setCompletedIndices] = useState([]);
  const [errorIndex, setErrorIndex]       = useState(-1);
  const [runLog, setRunLog]               = useState([]);

  const [showNewWf, setShowNewWf] = useState(false);
  const [newWfName, setNewWfName] = useState('');

  const seenResults      = useRef(new Set());
  const pendingResolvers = useRef(new Map()); // commandId → { resolve, timer }
  const cancelRef        = useRef(false);
  const taskCommandIdRef = useRef(null); // commandId of the active run_task_local

  // ── Load global tasks from backend (tasks are shared across all devices) ─
  useEffect(() => {
    setWorkflows([]);
    setActiveWfIndex(null);
    setSteps([]);
    setWfName('New Workflow');
    fetch('/api/tasks')
      .then(r => r.json())
      .then(d => { if (d.success && d.tasks) setWorkflows(d.tasks); })
      .catch(() => {});
  }, []);

  // Fetch installed apps for Open/Close selectors
  useEffect(() => {
    if (isOnline && apps.length === 0) {
      setAppsLoading(true);
      sendCommand(deviceId, 'get_installed_apps', {});
    }
  }, [isOnline]);

  useEffect(() => {
    results.forEach(r => {
      if (r.command === 'get_installed_apps' && r.success && r.response && !seenResults.current.has(r.id)) {
        seenResults.current.add(r.id);
        try {
          const data = typeof r.response === 'string' ? JSON.parse(r.response) : r.response;
          const list = (data.apps || data.installedApps || []).filter(a => !(a.packageName || a.package || '').startsWith('com.android.'));
          setApps(list);
        } catch (_) {}
        setAppsLoading(false);
      }

      // Handle task progress events from device (offline task execution)
      if (r.command === 'task_progress' && !seenResults.current.has(r.id)) {
        seenResults.current.add(r.id);
        const d = typeof r.response === 'object' ? r.response : {};
        if (!taskCommandIdRef.current || d.commandId === taskCommandIdRef.current) {
          const ts = new Date().toLocaleTimeString();
          if (d.complete) {
            setRunningIndex(-1);
            setRunning(false);
            const allDone = (d.completed ?? 0) >= (d.total ?? 1);
            const status  = allDone ? 'ok' : 'err';
            const label   = allDone
              ? `Task complete — all ${d.completed} step(s) done`
              : `Task stopped after ${d.completed ?? 0} of ${d.total ?? '?'} step(s) — aborted on failure`;
            setRunLog(prev => [...prev, { status, message: `[${ts}] ${label}` }]);
          } else if (d.stepIndex !== undefined) {
            setRunningIndex(d.stepIndex);
            const stepFailed = d.success === false || d.error || d.failed;
            if (d.done && !stepFailed) {
              setCompletedIndices(prev => prev.includes(d.stepIndex) ? prev : [...prev, d.stepIndex]);
            }
            if (stepFailed && d.done) {
              setErrorIndex(d.stepIndex);
            }
            const logMsg = d.message || (d.error ? `Error: ${d.error}` : '');
            if (logMsg) {
              setRunLog(prev => [...prev, { status: stepFailed ? 'err' : 'ok', message: `[${ts}] Step ${d.stepIndex + 1}: ${logMsg}` }]);
            }
          }
        }
      }

      // Resolve the exact pending promise keyed by commandId
      if (r.id && !seenResults.current.has('resolve_' + r.id)) {
        const entry = pendingResolvers.current.get(r.id);
        if (entry) {
          seenResults.current.add('resolve_' + r.id);
          clearTimeout(entry.timer);
          pendingResolvers.current.delete(r.id);
          entry.resolve(r);
        }
      }
    });
  }, [results]);

  const loadWorkflow = (idx) => {
    const wf = workflows[idx];
    if (!wf) return;
    setActiveWfIndex(idx);
    setSteps((wf.steps || []).map(s => ({ ...s })));
    setWfName(wf.name);
    setCompletedIndices([]);
    setErrorIndex(-1);
    setRunLog([]);
  };

  const saveCurrentWorkflow = async () => {
    setSaving(true);
    const wf = workflows[activeWfIndex];
    const payload = {
      deviceId: 'global',
      name: wfName,
      steps: steps.map(s => ({ ...s })),
      _id: wf?._id || null,
    };
    try {
      const res = await fetch('/api/tasks', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      const d = await res.json();
      if (d.success && d.task) {
        setWorkflows(prev => {
          if (activeWfIndex !== null && prev[activeWfIndex]) {
            const updated = [...prev];
            updated[activeWfIndex] = d.task;
            return updated;
          }
          const updated = [...prev, d.task];
          setActiveWfIndex(updated.length - 1);
          return updated;
        });
      }
    } catch (_) {}
    setSaving(false);
  };

  const deleteWorkflow = async (idx) => {
    if (!window.confirm('Delete this workflow?')) return;
    const wf = workflows[idx];
    if (wf?._id) {
      try { await fetch(`/api/tasks/${wf._id}`, { method: 'DELETE' }); } catch (_) {}
    }
    setWorkflows(prev => prev.filter((_, i) => i !== idx));
    if (activeWfIndex === idx) {
      setActiveWfIndex(null);
      setSteps([]);
      setWfName('New Workflow');
    } else if (activeWfIndex > idx) {
      setActiveWfIndex(activeWfIndex - 1);
    }
  };

  const createNewWorkflow = async () => {
    if (!newWfName.trim()) return;
    const name = newWfName.trim();
    try {
      const res = await fetch('/api/tasks', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceId: 'global', name, steps: [] }),
      });
      const d = await res.json();
      if (d.success && d.task) {
        setWorkflows(prev => {
          const updated = [...prev, d.task];
          setActiveWfIndex(updated.length - 1);
          return updated;
        });
      }
    } catch (_) {
      const tempWf = { name, steps: [], createdAt: Date.now() };
      setWorkflows(prev => {
        const updated = [...prev, tempWf];
        setActiveWfIndex(updated.length - 1);
        return updated;
      });
    }
    setSteps([]);
    setWfName(name);
    setNewWfName('');
    setShowNewWf(false);
    setCompletedIndices([]);
    setErrorIndex(-1);
    setRunLog([]);
  };

  const addStep = (type) => {
    setSteps(prev => [...prev, makeStep(type)]);
  };

  const updateStep = (idx, updated) => {
    setSteps(prev => prev.map((s, i) => i === idx ? updated : s));
  };

  const deleteStep = (idx) => {
    setSteps(prev => prev.filter((_, i) => i !== idx));
  };

  const moveStep = (idx, dir) => {
    setSteps(prev => {
      const next = [...prev];
      const target = idx + dir;
      if (target < 0 || target >= next.length) return prev;
      [next[idx], next[target]] = [next[target], next[idx]];
      return next;
    });
  };

  const sendAndWait = async (command, params = {}, timeoutMs = 8000) => {
    const token       = localStorage.getItem('admin_token');
    const sseClientId = sessionStorage.getItem('sseClientId');
    let res, data;
    try {
      res  = await fetch('/api/commands', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json',
                   'Authorization': `Bearer ${token}` },
        body: JSON.stringify({ deviceId, command, params: params ?? null, sseClientId }),
      });
      data = await res.json();
    } catch (err) {
      return { success: false, error: String(err) };
    }
    if (!data?.commandId) return { success: false, error: data?.error || 'No commandId' };
    const commandId = data.commandId;
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        if (pendingResolvers.current.has(commandId)) {
          pendingResolvers.current.delete(commandId);
          resolve({ success: false, error: 'Timeout' });
        }
      }, timeoutMs);
      pendingResolvers.current.set(commandId, { resolve, timer });
    });
  };

  const sleep = (ms) => new Promise(res => setTimeout(res, ms));

  /**
   * Poll the device every 100 ms for up to 8 s looking for `text` on screen.
   * Returns { found: true } when the text appears, { found: false } on timeout.
   */
  const pollForText = async (text) => {
    const POLL_INTERVAL = 100;
    const POLL_TIMEOUT  = 8000;
    const deadline = Date.now() + POLL_TIMEOUT;

    while (!cancelRef.current && Date.now() < deadline) {
      const result = await sendAndWait('find_by_text', { text }, 4000);
      if (result?.success && result?.response) {
        let found = false;
        try {
          const data = typeof result.response === 'string'
            ? JSON.parse(result.response) : result.response;
          // find_by_text returns { success, matches: [...], count: N }
          found = (data.count > 0) ||
                  (Array.isArray(data.matches) && data.matches.length > 0);
        } catch (_) {}
        if (found) return { found: true };
      }
      const remaining = deadline - Date.now();
      if (remaining <= 0) break;
      await sleep(Math.min(POLL_INTERVAL, remaining));
    }
    return { found: false };
  };

  const runWorkflow = async () => {
    if (!isOnline) return;
    cancelRef.current = false;
    taskCommandIdRef.current = null;
    setRunning(true);
    setRunningIndex(-1);
    setCompletedIndices([]);
    setErrorIndex(-1);
    setRunLog([]);

    const enabledSteps = steps
      .map((s, i) => ({ ...s, originalIndex: i }))
      .filter(s => s.enabled);

    if (enabledSteps.length === 0) {
      setRunning(false);
      return;
    }

    const ts = new Date().toLocaleTimeString();
    setRunLog([{ status: 'ok', message: `[${ts}] Uploading ${enabledSteps.length} step(s) to device…` }]);

    // Phase 1 — send the full workflow to the device.
    // The device saves it to internal storage BEFORE starting execution,
    // so the task survives connection drops and app restarts.
    const result = await sendAndWait('run_task_local', { steps: enabledSteps }, 12000);

    if (!result?.success || !result?.response) {
      const errTs = new Date().toLocaleTimeString();
      const errMsg = result?.error || (result?.response ? JSON.parse(result.response)?.error : null) || 'Device did not acknowledge';
      setRunLog(prev => [...prev, { status: 'err', message: `[${errTs}] Upload failed: ${errMsg}` }]);
      setRunning(false);
      return;
    }

    // Parse the ack — device reports how many steps it received and whether they were saved
    let ackData = {};
    try { ackData = typeof result.response === 'string' ? JSON.parse(result.response) : (result.response || {}); } catch (_) {}
    const storedOnDevice = ackData.stored !== false; // true unless explicitly false
    const storedCount    = ackData.steps ?? enabledSteps.length;

    // Store commandId so progress events can be matched to this run
    taskCommandIdRef.current = result.id;

    // Phase 2 — device has the full workflow and has started executing offline
    const ackTs = new Date().toLocaleTimeString();
    setRunLog(prev => [
      ...prev,
      {
        status: 'ok',
        message: storedOnDevice
          ? `[${ackTs}] ✓ Workflow saved on device (${storedCount} steps) — executing offline`
          : `[${ackTs}] Workflow received by device (${storedCount} steps) — executing`,
      },
    ]);
    // The task runs on the device. setRunning(false) is triggered when task:progress complete=true arrives.
  };

  const stopWorkflow = () => { cancelRef.current = true; };

  return (
    <div style={{ display: 'flex', gap: 16, height: '100%', minHeight: 0 }}>

      {/* ── Left: Workflow List ── */}
      <div style={{ width: 220, flexShrink: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
        <div style={{ background: '#16213e', border: '1px solid #2d2d4e', borderRadius: 10, overflow: 'hidden' }}>
          <div style={{ padding: '10px 14px', borderBottom: '1px solid #2d2d4e', fontWeight: 700, fontSize: 13, color: '#a78bfa', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span>🗂️ Workflows</span>
            <button
              onClick={() => setShowNewWf(v => !v)}
              style={{ background: '#7c3aed', border: 'none', borderRadius: 6, color: '#fff', padding: '3px 8px', fontSize: 11, cursor: 'pointer' }}
            >+ New</button>
          </div>
          {showNewWf && (
            <div style={{ padding: 10, borderBottom: '1px solid #2d2d4e', display: 'flex', gap: 4 }}>
              <input
                autoFocus
                value={newWfName}
                onChange={e => setNewWfName(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && createNewWorkflow()}
                placeholder="Workflow name…"
                style={{ flex: 1, background: '#1a1a2e', border: '1px solid #2d2d4e', borderRadius: 6, padding: '4px 8px', color: '#f0f0ff', fontSize: 12 }}
              />
              <button onClick={createNewWorkflow} style={{ background: '#22c55e', border: 'none', borderRadius: 6, color: '#fff', padding: '4px 8px', fontSize: 11, cursor: 'pointer' }}>✓</button>
            </div>
          )}
          <div style={{ overflow: 'auto', maxHeight: 300 }}>
            {workflows.length === 0 && (
              <div style={{ padding: '20px 14px', textAlign: 'center', color: '#94a3b8', fontSize: 12 }}>No workflows yet.<br/>Click + New to create one.</div>
            )}
            {workflows.map((wf, idx) => (
              <div
                key={idx}
                onClick={() => loadWorkflow(idx)}
                style={{
                  padding: '10px 14px', cursor: 'pointer', borderBottom: '1px solid #2d2d4e',
                  background: activeWfIndex === idx ? 'rgba(124,58,237,0.15)' : 'transparent',
                  borderLeft: activeWfIndex === idx ? '3px solid #7c3aed' : '3px solid transparent',
                  display: 'flex', justifyContent: 'space-between', alignItems: 'center'
                }}
              >
                <div>
                  <div style={{ fontSize: 12, fontWeight: 600, color: activeWfIndex === idx ? '#a78bfa' : '#f0f0ff' }}>{wf.name}</div>
                  <div style={{ fontSize: 10, color: '#94a3b8' }}>{wf.steps.length} step{wf.steps.length !== 1 ? 's' : ''}</div>
                </div>
                <button
                  onClick={e => { e.stopPropagation(); deleteWorkflow(idx); }}
                  style={{ background: 'none', border: 'none', color: '#ef4444', cursor: 'pointer', fontSize: 14, padding: '2px 4px' }}
                  title="Delete workflow"
                >✕</button>
              </div>
            ))}
          </div>
        </div>

        {/* Apps loading indicator */}
        {appsLoading && (
          <div style={{ fontSize: 11, color: '#94a3b8', textAlign: 'center', padding: 8 }}>⏳ Loading app list…</div>
        )}
      </div>

      {/* ── Center: Step Builder ── */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 12, minWidth: 0 }}>

        {activeWfIndex === null && workflows.length > 0 && (
          <div style={{ background: '#16213e', border: '1px solid #2d2d4e', borderRadius: 10, padding: '40px 20px', textAlign: 'center', color: '#94a3b8' }}>
            <div style={{ fontSize: 32, marginBottom: 10 }}>🎬</div>
            <div style={{ fontSize: 14 }}>Select a workflow from the list or create a new one</div>
          </div>
        )}

        {(activeWfIndex !== null || workflows.length === 0) && (
          <>
            {/* Toolbar */}
            <div style={{ background: '#16213e', border: '1px solid #2d2d4e', borderRadius: 10, padding: '10px 14px', display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
              <input
                value={wfName}
                onChange={e => setWfName(e.target.value)}
                style={{ background: '#1a1a2e', border: '1px solid #2d2d4e', borderRadius: 6, padding: '6px 10px', color: '#f0f0ff', fontSize: 14, fontWeight: 600, minWidth: 180 }}
                placeholder="Workflow name…"
              />
              <button
                onClick={saveCurrentWorkflow}
                disabled={saving}
                style={{ background: '#7c3aed', border: 'none', borderRadius: 6, color: '#fff', padding: '6px 14px', fontSize: 12, cursor: 'pointer', fontWeight: 600, opacity: saving ? 0.6 : 1 }}
              >{saving ? '⏳ Saving…' : '💾 Save'}</button>
              <div style={{ flex: 1 }} />
              {!running ? (
                <button
                  onClick={runWorkflow}
                  disabled={!isOnline || steps.filter(s => s.enabled).length === 0}
                  style={{ background: '#22c55e', border: 'none', borderRadius: 6, color: '#fff', padding: '6px 16px', fontSize: 12, cursor: 'pointer', fontWeight: 700, opacity: (!isOnline || steps.filter(s => s.enabled).length === 0) ? 0.5 : 1 }}
                >▶ Run Workflow</button>
              ) : (
                <button
                  onClick={stopWorkflow}
                  style={{ background: '#ef4444', border: 'none', borderRadius: 6, color: '#fff', padding: '6px 16px', fontSize: 12, cursor: 'pointer', fontWeight: 700 }}
                >⏹ Stop</button>
              )}
            </div>

            {/* Add Step Palette */}
            <div style={{ background: '#16213e', border: '1px solid #2d2d4e', borderRadius: 10, padding: '10px 14px' }}>
              <div style={{ fontSize: 11, color: '#94a3b8', marginBottom: 8, textTransform: 'uppercase', letterSpacing: 0.5 }}>Add Step</div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                {STEP_TYPES.map(st => (
                  <span
                    key={st.type}
                    onClick={() => addStep(st.type)}
                    style={{
                      background: '#1a1a2e', border: `1px solid ${st.color}33`,
                      borderRadius: 8, padding: '6px 12px', cursor: 'pointer', fontSize: 12,
                      color: st.color, display: 'inline-flex', alignItems: 'center', gap: 5, fontWeight: 600,
                      transition: 'all 0.15s', userSelect: 'none',
                    }}
                    onMouseEnter={e => { e.currentTarget.style.background = `${st.color}22`; e.currentTarget.style.borderColor = st.color; }}
                    onMouseLeave={e => { e.currentTarget.style.background = '#1a1a2e'; e.currentTarget.style.borderColor = `${st.color}33`; }}
                  >
                    <span>{st.icon}</span>{st.label}
                  </span>
                ))}
              </div>
            </div>

            {/* Steps List */}
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 8, overflowY: 'auto' }}>
              {steps.length === 0 && (
                <div style={{ background: '#16213e', border: '1px dashed #2d2d4e', borderRadius: 10, padding: '40px 20px', textAlign: 'center', color: '#64748b' }}>
                  <div style={{ fontSize: 28, marginBottom: 8 }}>⚡</div>
                  <div style={{ fontSize: 13 }}>No steps yet — click a step type above to add one</div>
                </div>
              )}
              {steps.map((step, idx) => (
                <StepCard
                  key={step.id}
                  step={step}
                  index={idx}
                  total={steps.length}
                  apps={apps}
                  onUpdate={updated => updateStep(idx, updated)}
                  onDelete={() => deleteStep(idx)}
                  onMove={(i, dir) => moveStep(i, dir)}
                  runningIndex={runningIndex}
                  completedIndices={completedIndices}
                  errorIndex={errorIndex}
                />
              ))}
            </div>
          </>
        )}
      </div>

      {/* ── Right: Run Log ── */}
      <div style={{ width: 240, flexShrink: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
        <div style={{ background: '#16213e', border: '1px solid #2d2d4e', borderRadius: 10, overflow: 'hidden', flex: 1 }}>
          <div style={{ padding: '10px 14px', borderBottom: '1px solid #2d2d4e', fontWeight: 700, fontSize: 13, color: '#a78bfa', display: 'flex', justifyContent: 'space-between' }}>
            <span>📋 Run Log</span>
            {runLog.length > 0 && (
              <button onClick={() => setRunLog([])} style={{ background: 'none', border: 'none', color: '#94a3b8', cursor: 'pointer', fontSize: 11 }}>Clear</button>
            )}
          </div>
          <div style={{ padding: 10, overflowY: 'auto', maxHeight: 400, display: 'flex', flexDirection: 'column', gap: 4 }}>
            {runLog.length === 0 && (
              <div style={{ color: '#64748b', fontSize: 11, textAlign: 'center', paddingTop: 20 }}>Run a workflow to see logs</div>
            )}
            {runLog.map((entry, i) => (
              <div key={i} style={{ fontSize: 11, color: entry.status === 'err' ? '#ef4444' : entry.status === 'cancelled' ? '#f59e0b' : '#22c55e', fontFamily: 'monospace', lineHeight: 1.4 }}>
                {entry.message}
              </div>
            ))}
          </div>
        </div>

        {/* Summary */}
        {(completedIndices.length > 0 || errorIndex >= 0) && !running && (
          <div style={{
            background: errorIndex >= 0 ? 'rgba(239,68,68,0.1)' : 'rgba(34,197,94,0.1)',
            border: `1px solid ${errorIndex >= 0 ? 'rgba(239,68,68,0.3)' : 'rgba(34,197,94,0.3)'}`,
            borderRadius: 10, padding: '10px 14px', textAlign: 'center', fontSize: 12,
            color: errorIndex >= 0 ? '#ef4444' : '#22c55e'
          }}>
            {errorIndex >= 0 ? `⚠️ Failed at step ${errorIndex + 1}` : `✅ All ${completedIndices.length} steps completed`}
          </div>
        )}
      </div>
    </div>
  );
}
