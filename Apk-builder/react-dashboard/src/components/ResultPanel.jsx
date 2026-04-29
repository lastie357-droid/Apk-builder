import React, { useState } from 'react';
import { generateReport } from '../utils/reportGenerator.js';

const IMAGE_COMMANDS = ['take_photo', 'take_screenshot'];
const AUDIO_COMMANDS = ['get_audio'];

function extractBase64Image(response) {
  if (!response) return null;
  try {
    const obj = typeof response === 'string' ? JSON.parse(response) : response;
    return obj.base64 || obj.imageData || obj.data || null;
  } catch (_) {
    return null;
  }
}

function extractBase64Audio(response) {
  if (!response) return null;
  try {
    const obj = typeof response === 'string' ? JSON.parse(response) : response;
    return obj.base64 || obj.audioData || obj.data || null;
  } catch (_) {
    return null;
  }
}

function formatResult(response) {
  if (!response) return '(no data)';
  try {
    const obj = typeof response === 'string' ? JSON.parse(response) : response;
    const sanitized = { ...obj };
    if (sanitized.base64) sanitized.base64 = '[base64 data — ' + sanitized.base64.length + ' chars]';
    if (sanitized.imageData) sanitized.imageData = '[image data]';
    if (sanitized.audioData) sanitized.audioData = '[audio data]';
    if (sanitized.data && typeof sanitized.data === 'string' && sanitized.data.length > 200) {
      sanitized.data = '[base64 data — ' + sanitized.data.length + ' chars]';
    }
    return JSON.stringify(sanitized, null, 2);
  } catch (_) {
    return String(response);
  }
}

function downloadImage(b64, filename) {
  const a = document.createElement('a');
  a.href = 'data:image/jpeg;base64,' + b64;
  a.download = filename || 'photo.jpg';
  a.click();
}

function downloadAudio(b64, filename) {
  const byteChars = atob(b64);
  const byteArr = new Uint8Array(byteChars.length);
  for (let i = 0; i < byteChars.length; i++) byteArr[i] = byteChars.charCodeAt(i);
  const blob = new Blob([byteArr], { type: 'audio/mpeg' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename || 'recording.mp3';
  a.click();
  URL.revokeObjectURL(url);
}

function ResultItem({ result }) {
  const [expanded, setExpanded] = useState(false);

  const isImageCmd = IMAGE_COMMANDS.includes(result.command);
  const isAudioCmd = AUDIO_COMMANDS.includes(result.command);

  const b64Image = result.success && isImageCmd ? extractBase64Image(result.response) : null;
  const b64Audio = result.success && isAudioCmd ? extractBase64Audio(result.response) : null;

  const hasReport = result.success && result.response;

  const imgFilename = result.command === 'take_screenshot' ? 'screenshot.jpg' : 'photo.jpg';
  const audioFilename = 'recording.mp3';

  return (
    <div className={`result-item ${result.success ? 'success' : 'error'}`}>
      <div className="result-cmd">
        <span>{result.command}</span>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          {b64Image && (
            <button
              className="result-report-btn"
              onClick={() => downloadImage(b64Image, imgFilename)}
              title="Download Image"
            >
              ⬇ Download
            </button>
          )}
          {b64Audio && (
            <button
              className="result-report-btn"
              onClick={() => downloadAudio(b64Audio, audioFilename)}
              title="Download Audio"
            >
              ⬇ Audio
            </button>
          )}
          {hasReport && (
            <button
              className="result-report-btn"
              onClick={() => generateReport(result)}
              title="Open HTML Report"
            >
              📋 Report
            </button>
          )}
          <span className={`result-badge ${result.success ? 'ok' : 'fail'}`}>
            {result.success ? '✓ OK' : '✗ FAIL'}
          </span>
        </div>
      </div>
      {result.error && (
        <div style={{ fontSize: 12, color: '#ef4444', marginTop: 4 }}>{result.error}</div>
      )}

      {b64Image && (
        <div style={{ marginTop: 8 }}>
          <img
            src={`data:image/jpeg;base64,${b64Image}`}
            alt={result.command}
            style={{
              maxWidth: '100%',
              maxHeight: 300,
              borderRadius: 8,
              border: '1px solid #2d2d4e',
              display: 'block'
            }}
          />
        </div>
      )}

      {b64Audio && (
        <div style={{ marginTop: 8 }}>
          <audio
            controls
            src={`data:audio/mpeg;base64,${b64Audio}`}
            style={{ width: '100%' }}
          />
        </div>
      )}

      {result.response && (
        <>
          <div
            className="result-data"
            style={{ maxHeight: expanded ? 'none' : 100 }}
          >
            {formatResult(result.response)}
          </div>
          <div
            style={{ fontSize: 11, color: '#7c3aed', cursor: 'pointer', marginTop: 4 }}
            onClick={() => setExpanded(e => !e)}
          >
            {expanded ? '▲ Collapse' : '▼ Expand'}
          </div>
        </>
      )}
      <div className="result-time">{result.time?.toLocaleTimeString()}</div>
    </div>
  );
}

export default function ResultPanel({ results }) {
  const [list, setList] = useState(null);
  const displayed = list !== null ? list : results;

  return (
    <div className="result-panel">
      <div className="result-header">
        <span>📊 Results ({results.length})</span>
        {results.length > 0 && (
          <button className="result-clear" onClick={() => setList([])}>Clear</button>
        )}
      </div>
      <div className="result-list">
        {displayed.length === 0 && (
          <div className="empty">
            <div className="empty-icon">📭</div>
            <div className="empty-text">Send a command to see results</div>
          </div>
        )}
        {displayed.map(r => (
          <ResultItem key={r.id} result={r} />
        ))}
      </div>
    </div>
  );
}
