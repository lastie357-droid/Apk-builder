import React, { useState, useEffect, useRef, useCallback } from 'react';

const formatSize = (bytes) => {
  if (bytes === 0) return '0 B';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
};

const formatDate = (ms) => {
  if (!ms) return '';
  return new Date(ms).toLocaleString();
};

const getFileIcon = (file) => {
  if (file.isDirectory) return '📁';
  const ext = file.name.split('.').pop().toLowerCase();
  const icons = {
    jpg: '🖼', jpeg: '🖼', png: '🖼', gif: '🖼', webp: '🖼', bmp: '🖼',
    mp4: '🎬', mkv: '🎬', avi: '🎬', mov: '🎬', '3gp': '🎬',
    mp3: '🎵', aac: '🎵', wav: '🎵', ogg: '🎵', flac: '🎵',
    pdf: '📄', doc: '📝', docx: '📝', txt: '📝', csv: '📊', xls: '📊', xlsx: '📊',
    apk: '📦', zip: '🗜', rar: '🗜', tar: '🗜', gz: '🗜',
    json: '🔧', xml: '🔧', sql: '🔧',
  };
  return icons[ext] || '📄';
};

const btnStyle = (bg, disabled) => ({
  background: disabled ? '#1e293b' : bg,
  border: 'none', borderRadius: 6, color: disabled ? '#475569' : '#fff',
  padding: '6px 14px', cursor: disabled ? 'not-allowed' : 'pointer',
  fontSize: 12, fontWeight: 600, transition: 'opacity 0.15s',
  opacity: disabled ? 0.6 : 1,
});

const QUICK_PATHS = [
  { label: 'Storage', path: '/sdcard' },
  { label: 'Downloads', path: '/sdcard/Download' },
  { label: 'DCIM', path: '/sdcard/DCIM' },
  { label: 'Pictures', path: '/sdcard/Pictures' },
  { label: 'Documents', path: '/sdcard/Documents' },
  { label: 'WhatsApp', path: '/sdcard/WhatsApp' },
  { label: 'Internal', path: '/data/data' },
];

const IMAGE_EXTS = new Set(['jpg','jpeg','png','gif','webp','bmp']);
const isImage = (name) => IMAGE_EXTS.has((name.split('.').pop() || '').toLowerCase());

export default function FileManagerTab({ device, sendCommand, results }) {
  const deviceId = device?.deviceId;
  const isOnline = device?.isOnline;

  const [currentPath, setCurrentPath] = useState('/sdcard');
  const [files, setFiles] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [downloading, setDownloading] = useState({});
  const [deleting, setDeleting] = useState({});
  const [selected, setSelected] = useState(new Set());
  const [searchQuery, setSearchQuery] = useState('');
  const [sortBy, setSortBy] = useState('name');
  const [sortAsc, setSortAsc] = useState(true);
  const [pathInput, setPathInput] = useState('/sdcard');
  const [preview, setPreview] = useState(null);
  const pendingPreviews = useRef({});

  const seenResults = useRef(new Set());
  const pendingDownloads = useRef({});

  const sendCmd = useCallback((cmd, params = {}) => {
    if (deviceId) sendCommand(deviceId, cmd, params);
  }, [deviceId, sendCommand]);

  const loadPath = useCallback((path) => {
    if (!isOnline) return;
    setLoading(true);
    setError('');
    setFiles([]);
    setSelected(new Set());
    setCurrentPath(path);
    setPathInput(path);
    sendCmd('list_files', { path });
  }, [isOnline, sendCmd]);

  // Files are loaded manually — user navigates to a path via the path input

  useEffect(() => {
    if (!results || results.length === 0) return;
    results.forEach(r => {
      if (seenResults.current.has(r.id)) return;
      seenResults.current.add(r.id);
      let data;
      try { data = typeof r.response === 'string' ? JSON.parse(r.response) : r.response; }
      catch (_) { return; }
      if (!data) return;

      if (r.command === 'list_files') {
        setLoading(false);
        if (data.success) {
          setFiles(data.files || []);
          setCurrentPath(data.path || currentPath);
          setPathInput(data.path || currentPath);
          setError('');
        } else {
          setError(data.error || 'Failed to list files');
          setFiles([]);
        }
      }

      if (r.command === 'read_file') {
        const filePath = data.filePath || Object.keys(pendingDownloads.current)[0] || Object.keys(pendingPreviews.current)[0];
        const isPreview = filePath && pendingPreviews.current[filePath];
        const isDownload = filePath && pendingDownloads.current[filePath];

        if (isPreview) {
          delete pendingPreviews.current[filePath];
          if (data.success && data.content && data.encoding === 'base64') {
            const ext = (filePath.split('.').pop() || 'jpg').toLowerCase();
            const mime = ext === 'png' ? 'image/png' : ext === 'gif' ? 'image/gif' : 'image/jpeg';
            setPreview({ path: filePath, src: `data:${mime};base64,${data.content.replace(/\s/g, '')}` });
          } else {
            setStatus(`Preview failed: ${data.error || 'unknown'}`);
          }
        }

        if (isDownload) {
          delete pendingDownloads.current[filePath];
          const fileName = filePath.split('/').pop();
          setDownloading(prev => { const n = { ...prev }; delete n[filePath]; return n; });
          if (data.success && data.content) {
            try {
              let blob;
              if (data.encoding === 'base64') {
                const binaryStr = atob(data.content.replace(/\s/g, ''));
                const bytes = new Uint8Array(binaryStr.length);
                for (let i = 0; i < binaryStr.length; i++) bytes[i] = binaryStr.charCodeAt(i);
                blob = new Blob([bytes]);
              } else {
                blob = new Blob([data.content], { type: 'text/plain' });
              }
              const url = URL.createObjectURL(blob);
              const a = document.createElement('a');
              a.href = url;
              a.download = fileName;
              document.body.appendChild(a);
              a.click();
              document.body.removeChild(a);
              URL.revokeObjectURL(url);
              setStatus(`Downloaded: ${fileName}`);
            } catch (e) {
              setStatus(`Download error: ${e.message}`);
            }
          } else {
            setStatus(`Failed to download: ${data.error || 'unknown error'}`);
          }
        }
      }

      if (r.command === 'delete_file') {
        const fp = data.filePath;
        setDeleting(prev => { const n = { ...prev }; if (fp) delete n[fp]; return n; });
        if (!data.success) setStatus(`Delete failed: ${data.error || data.message || 'Permission denied — grant All Files Access in Settings'}`);
        // Also clear from selected if failed (keep consistent UI)
        if (data.success) {
          setFiles(prev => prev.filter(f => f.path !== fp));
          setSelected(prev => { const n = new Set(prev); n.delete(fp); return n; });
          setStatus(`Deleted: ${fp?.split('/').pop()}`);
        } else {
          setStatus(`Delete failed: ${data.error || ''}`);
        }
      }
    });
  }, [results]);

  const downloadFile = (file) => {
    if (file.isDirectory) return;
    setDownloading(prev => ({ ...prev, [file.path]: true }));
    pendingDownloads.current[file.path] = true;
    sendCmd('read_file', { filePath: file.path, asBase64: true });
    setStatus(`Downloading ${file.name}...`);
  };

  const previewFile = (file) => {
    if (file.isDirectory || !isImage(file.name)) return;
    pendingPreviews.current[file.path] = true;
    sendCmd('read_file', { filePath: file.path, asBase64: true });
    setStatus(`Loading preview for ${file.name}...`);
  };

  const requestAllFilesAccess = () => {
    sendCmd('request_storage_permission', {});
    setStatus('Requesting All Files Access — auto-granting on device…');
  };

  const deleteFile = (file) => {
    if (!window.confirm(`Delete "${file.name}"?\nPath: ${file.path}`)) return;
    setDeleting(prev => ({ ...prev, [file.path]: true }));
    sendCmd('delete_file', { filePath: file.path });
    setStatus(`Deleting ${file.name}...`);
  };

  const deleteSelected = () => {
    if (selected.size === 0) return;
    if (!window.confirm(`Delete ${selected.size} selected item(s)?`)) return;
    selected.forEach(path => {
      setDeleting(prev => ({ ...prev, [path]: true }));
      sendCmd('delete_file', { filePath: path });
    });
    setStatus(`Deleting ${selected.size} items...`);
  };

  const navigateUp = () => {
    const parts = currentPath.split('/').filter(Boolean);
    if (parts.length === 0) return;
    const parent = '/' + parts.slice(0, -1).join('/');
    loadPath(parent || '/');
  };

  const toggleSelect = (path) => {
    setSelected(prev => {
      const n = new Set(prev);
      if (n.has(path)) n.delete(path);
      else n.add(path);
      return n;
    });
  };

  const toggleSelectAll = () => {
    if (selected.size === filteredFiles.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(filteredFiles.map(f => f.path)));
    }
  };

  const sortFiles = (list) => {
    return [...list].sort((a, b) => {
      if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
      let va, vb;
      if (sortBy === 'name') { va = a.name.toLowerCase(); vb = b.name.toLowerCase(); }
      else if (sortBy === 'size') { va = a.size; vb = b.size; }
      else if (sortBy === 'date') { va = a.lastModified; vb = b.lastModified; }
      else { va = a.name.toLowerCase(); vb = b.name.toLowerCase(); }
      if (va < vb) return sortAsc ? -1 : 1;
      if (va > vb) return sortAsc ? 1 : -1;
      return 0;
    });
  };

  const filteredFiles = sortFiles(
    files.filter(f => !searchQuery || f.name.toLowerCase().includes(searchQuery.toLowerCase()))
  );

  const breadcrumbs = currentPath.split('/').filter(Boolean);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', fontFamily: 'system-ui,sans-serif', color: '#e2e8f0', background: '#0f172a' }}>

      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '14px 18px', borderBottom: '1px solid #1e293b' }}>
        <span style={{ fontSize: 22 }}>📂</span>
        <div>
          <div style={{ fontWeight: 700, fontSize: 16 }}>File Manager</div>
          <div style={{ fontSize: 12, color: '#64748b' }}>Browse, download, and delete device files</div>
        </div>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          {selected.size > 0 && (
            <button onClick={deleteSelected} style={btnStyle('#dc2626', !isOnline)}>
              🗑 Delete {selected.size} selected
            </button>
          )}
          <button onClick={() => loadPath(currentPath)} disabled={!isOnline || loading} style={btnStyle('#334155', !isOnline || loading)}>
            {loading ? '...' : '↻ Refresh'}
          </button>
        </div>
      </div>

      <div style={{ padding: '7px 18px', background: '#18230f', borderBottom: '1px solid #1e293b', display: 'flex', alignItems: 'center', gap: 10, fontSize: 12 }}>
        <span style={{ color: '#86efac' }}>⚠️ For full access on Android 11+:</span>
        <button onClick={requestAllFilesAccess} disabled={!isOnline} style={{ ...btnStyle('#16a34a', !isOnline), padding: '3px 10px', fontSize: 11 }}>
          Grant All Files Access
        </button>
        <span style={{ color: '#64748b', fontSize: 11 }}>then allow in device Settings → this app → All Files</span>
      </div>

      {status && (
        <div style={{ padding: '7px 18px', background: '#1e293b', fontSize: 12, color: '#94a3b8', borderBottom: '1px solid #1e293b' }}>
          {status}
        </div>
      )}

      <div style={{ padding: '10px 18px', borderBottom: '1px solid #1e293b', display: 'flex', flexDirection: 'column', gap: 8 }}>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {QUICK_PATHS.map(qp => (
            <button key={qp.path} onClick={() => loadPath(qp.path)} disabled={!isOnline}
              style={{ ...btnStyle('#1e3a5f', !isOnline), padding: '4px 10px', fontSize: 11 }}>
              {qp.label}
            </button>
          ))}
        </div>

        <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <button onClick={navigateUp} disabled={!isOnline || currentPath === '/'} style={btnStyle('#334155', !isOnline || currentPath === '/')}>
            ↑ Up
          </button>
          <input
            value={pathInput}
            onChange={e => setPathInput(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && loadPath(pathInput)}
            style={{
              flex: 1, background: '#1e293b', border: '1px solid #334155', borderRadius: 6,
              color: '#e2e8f0', padding: '6px 10px', fontSize: 12, fontFamily: 'monospace',
            }}
            placeholder="/sdcard"
          />
          <button onClick={() => loadPath(pathInput)} disabled={!isOnline} style={btnStyle('#6366f1', !isOnline)}>
            Go
          </button>
        </div>

        <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', fontSize: 11, color: '#64748b', alignItems: 'center' }}>
          <span onClick={() => loadPath('/')} style={{ cursor: 'pointer', color: '#6366f1' }}>/</span>
          {breadcrumbs.map((crumb, i) => {
            const path = '/' + breadcrumbs.slice(0, i + 1).join('/');
            return (
              <React.Fragment key={path}>
                <span style={{ color: '#334155' }}>/</span>
                <span onClick={() => loadPath(path)} style={{ cursor: 'pointer', color: i === breadcrumbs.length - 1 ? '#e2e8f0' : '#6366f1' }}>
                  {crumb}
                </span>
              </React.Fragment>
            );
          })}
        </div>
      </div>

      <div style={{ display: 'flex', gap: 8, padding: '8px 18px', borderBottom: '1px solid #1e293b', alignItems: 'center' }}>
        <input
          value={searchQuery}
          onChange={e => setSearchQuery(e.target.value)}
          placeholder="Search files..."
          style={{
            flex: 1, background: '#1e293b', border: '1px solid #334155', borderRadius: 6,
            color: '#e2e8f0', padding: '5px 10px', fontSize: 12,
          }}
        />
        <span style={{ fontSize: 11, color: '#64748b' }}>Sort:</span>
        {['name', 'size', 'date'].map(s => (
          <button key={s} onClick={() => { if (sortBy === s) setSortAsc(a => !a); else { setSortBy(s); setSortAsc(true); } }}
            style={{ ...btnStyle(sortBy === s ? '#6366f1' : '#334155', false), padding: '4px 10px', fontSize: 11 }}>
            {s} {sortBy === s ? (sortAsc ? '↑' : '↓') : ''}
          </button>
        ))}
        <span style={{ fontSize: 11, color: '#64748b' }}>{filteredFiles.length} items</span>
      </div>

      <div style={{ flex: 1, overflowY: 'auto' }}>
        {!isOnline && (
          <div style={{ padding: 32, textAlign: 'center', color: '#475569' }}>
            <div style={{ fontSize: 32, marginBottom: 8 }}>📴</div>
            Device is offline
          </div>
        )}

        {isOnline && loading && (
          <div style={{ padding: 32, textAlign: 'center', color: '#475569' }}>
            <div style={{ fontSize: 32, marginBottom: 8, animation: 'spin 1s linear infinite' }}>⌛</div>
            Loading...
          </div>
        )}

        {isOnline && !loading && error && (
          <div style={{ padding: 24, textAlign: 'center', color: '#ef4444' }}>
            <div style={{ fontSize: 32, marginBottom: 8 }}>⚠️</div>
            {error}
            <br />
            <button onClick={() => loadPath(currentPath)} style={{ ...btnStyle('#334155', false), marginTop: 10 }}>
              Retry
            </button>
          </div>
        )}

        {isOnline && !loading && !error && filteredFiles.length === 0 && (
          <div style={{ padding: 32, textAlign: 'center', color: '#475569' }}>
            <div style={{ fontSize: 32, marginBottom: 8 }}>📭</div>
            {searchQuery ? 'No files match your search' : 'Empty directory'}
          </div>
        )}

        {isOnline && !loading && filteredFiles.length > 0 && (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ background: '#1e293b', position: 'sticky', top: 0 }}>
                <th style={{ padding: '8px 12px', textAlign: 'left', width: 32, color: '#64748b', fontWeight: 600 }}>
                  <input type="checkbox"
                    checked={selected.size === filteredFiles.length && filteredFiles.length > 0}
                    onChange={toggleSelectAll}
                    style={{ cursor: 'pointer' }}
                  />
                </th>
                <th style={{ padding: '8px 12px', textAlign: 'left', color: '#64748b', fontWeight: 600 }}>Name</th>
                <th style={{ padding: '8px 12px', textAlign: 'right', color: '#64748b', fontWeight: 600, width: 80 }}>Size</th>
                <th style={{ padding: '8px 12px', textAlign: 'right', color: '#64748b', fontWeight: 600, width: 170 }}>Modified</th>
                <th style={{ padding: '8px 12px', textAlign: 'center', color: '#64748b', fontWeight: 600, width: 120 }}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {filteredFiles.map(file => (
                <tr key={file.path}
                  style={{
                    borderBottom: '1px solid #1e293b',
                    background: selected.has(file.path) ? 'rgba(99,102,241,0.08)' : 'transparent',
                    cursor: file.isDirectory ? 'pointer' : 'default',
                  }}
                  onDoubleClick={() => file.isDirectory && loadPath(file.path)}
                >
                  <td style={{ padding: '7px 12px' }}>
                    <input type="checkbox"
                      checked={selected.has(file.path)}
                      onChange={() => toggleSelect(file.path)}
                      onClick={e => e.stopPropagation()}
                      style={{ cursor: 'pointer' }}
                    />
                  </td>
                  <td style={{ padding: '7px 12px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <span>{getFileIcon(file)}</span>
                      <span
                        style={{ color: file.isDirectory ? '#6366f1' : '#e2e8f0', fontWeight: file.isDirectory ? 600 : 400 }}
                        onClick={() => file.isDirectory && loadPath(file.path)}
                      >
                        {file.name}
                      </span>
                      {file.isHidden && <span style={{ fontSize: 9, color: '#475569', background: '#1e293b', padding: '1px 4px', borderRadius: 3 }}>hidden</span>}
                      {!file.canRead && <span style={{ fontSize: 9, color: '#dc2626', background: '#1e293b', padding: '1px 4px', borderRadius: 3 }}>no-read</span>}
                    </div>
                  </td>
                  <td style={{ padding: '7px 12px', textAlign: 'right', color: '#64748b', fontFamily: 'monospace', fontSize: 11 }}>
                    {file.isDirectory ? '' : formatSize(file.size)}
                  </td>
                  <td style={{ padding: '7px 12px', textAlign: 'right', color: '#64748b', fontSize: 11 }}>
                    {formatDate(file.lastModified)}
                  </td>
                  <td style={{ padding: '7px 12px', textAlign: 'center' }}>
                    <div style={{ display: 'flex', gap: 4, justifyContent: 'center' }}>
                      {!file.isDirectory && isImage(file.name) && (
                        <button
                          onClick={() => previewFile(file)}
                          style={{ ...btnStyle('#0f766e', false), padding: '3px 8px', fontSize: 11 }}
                          title="Preview image"
                        >
                          👁
                        </button>
                      )}
                      {!file.isDirectory && (
                        <button
                          onClick={() => downloadFile(file)}
                          disabled={!!downloading[file.path]}
                          style={{ ...btnStyle('#1d4ed8', !!downloading[file.path]), padding: '3px 8px', fontSize: 11 }}
                          title="Download file"
                        >
                          {downloading[file.path] ? '...' : '⬇'}
                        </button>
                      )}
                      <button
                        onClick={() => deleteFile(file)}
                        disabled={!!deleting[file.path]}
                        style={{ ...btnStyle('#7f1d1d', !!deleting[file.path]), padding: '3px 8px', fontSize: 11 }}
                        title="Delete"
                      >
                        {deleting[file.path] ? '...' : '🗑'}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <style>{`@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }`}</style>

      {preview && (
        <div
          onClick={() => setPreview(null)}
          style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.85)', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', zIndex: 9999, padding: 20 }}
        >
          <div onClick={e => e.stopPropagation()} style={{ background: '#1e293b', borderRadius: 12, padding: 16, maxWidth: '90vw', maxHeight: '90vh', display: 'flex', flexDirection: 'column', gap: 10, border: '1px solid #334155' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: 12, color: '#94a3b8', fontFamily: 'monospace', wordBreak: 'break-all' }}>{preview.path}</span>
              <button onClick={() => setPreview(null)} style={{ ...btnStyle('#334155', false), padding: '4px 10px', fontSize: 12 }}>✕</button>
            </div>
            <img
              src={preview.src}
              alt={preview.path.split('/').pop()}
              style={{ maxWidth: '80vw', maxHeight: '70vh', objectFit: 'contain', borderRadius: 8, border: '1px solid #334155' }}
            />
            <button
              onClick={() => { const a = document.createElement('a'); a.href = preview.src; a.download = preview.path.split('/').pop(); a.click(); }}
              style={{ ...btnStyle('#1d4ed8', false), padding: '6px 18px' }}
            >
              ⬇ Download
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
