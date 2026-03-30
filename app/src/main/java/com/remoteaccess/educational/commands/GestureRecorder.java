package com.remoteaccess.educational.commands;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
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
        private final List<GesturePoint> points = new ArrayList<>();
        private long startTime;
        private boolean stopped = false;

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

        void show() {
            startTime = System.currentTimeMillis();
            view = new View(context) {
                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    if (!stopped) handleTouch(event);
                    return true;
                }

                @Override
                protected void onDraw(Canvas canvas) {
                    canvas.drawRect(0, 0, getWidth(), getHeight(), paintBg);
                    for (Path p : finishedPaths) canvas.drawPath(p, paintPath);
                    for (Path p : activePaths.values()) canvas.drawPath(p, paintPath);
                    canvas.drawText("● REC  " + label, getWidth() / 2f, 120, paintHint);
                    canvas.drawText(packageId, getWidth() / 2f, 175, paintHint);
                }
            };
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );
            wm.addView(view, lp);
        }

        private void handleTouch(MotionEvent event) {
            if (points.size() >= MAX_PTS) return;
            long relT = System.currentTimeMillis() - startTime;

            int action    = event.getActionMasked();
            int pidIndex  = event.getActionIndex();
            int pointerId = event.getPointerId(pidIndex);

            int count = event.getPointerCount();
            for (int i = 0; i < count; i++) {
                int pid = event.getPointerId(i);
                float rx = event.getX(i) / screenW;
                float ry = event.getY(i) / screenH;
                GesturePoint gp = new GesturePoint();
                gp.pointerId = pid;
                gp.action    = (i == pidIndex) ? action : MotionEvent.ACTION_MOVE;
                gp.nx = rx; gp.ny = ry;
                gp.t = relT;
                points.add(gp);
            }

            // Update visual paths
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
            try { if (view != null) wm.removeView(view); view = null; } catch (Exception ignored) {}
        }
    }
}
