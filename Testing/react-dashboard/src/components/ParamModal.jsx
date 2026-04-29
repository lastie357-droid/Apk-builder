import React, { useState } from 'react';

export default function ParamModal({ cmd, onSend, onClose }) {
  const initial = {};
  (cmd.params || []).forEach(p => { initial[p.key] = p.default || ''; });
  const [values, setValues] = useState(initial);

  const set = (key, val) => setValues(prev => ({ ...prev, [key]: val }));

  return (
    <div className="modal-overlay" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal-box">
        <div className="modal-title">
          {cmd.icon} {cmd.label}
        </div>
        {cmd.params.map(p => (
          <div key={p.key} className="modal-field">
            <label>{p.label}</label>
            <input
              value={values[p.key]}
              onChange={e => set(p.key, e.target.value)}
              placeholder={p.default || ''}
            />
          </div>
        ))}
        <div className="modal-actions">
          <button className="btn-primary" onClick={() => onSend(values)}>
            Send Command
          </button>
          <button className="btn-secondary" onClick={onClose}>Cancel</button>
        </div>
      </div>
    </div>
  );
}
