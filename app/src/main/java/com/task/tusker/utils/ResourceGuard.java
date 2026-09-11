package com.task.tusker.utils;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.StatFs;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ResourceGuard — monitors CPU, RAM and storage every 5 s and exposes a
 * composite {@link Level} that all heavy components (SocketManager,
 * CameraStreamHandler, DataSyncService) query to adapt their behaviour.
 *
 * Levels:
 *   NORMAL   → full quality, default intervals
 *   ELEVATED → reduce JPEG quality / frame rate slightly
 *   HIGH     → aggressive throttle — low quality, long intervals
 *   CRITICAL → pause non-essential work; socket keep-alive only
 *
 * The singleton registers itself as a {@link ComponentCallbacks2} so the OS
 * can push memory pressure events directly (no polling delay for acute OOM).
 *
 * Hysteresis: escalation requires {@link #HYSTERESIS_TICKS} consecutive
 * readings at the higher level; de-escalation is immediate.
 */
public class ResourceGuard implements ComponentCallbacks2 {

    private static final String TAG = "ResourceGuard";

    // ── Pressure level ────────────────────────────────────────────────────

    public enum Level {
        NORMAL,   // < 40 % composite stress
        ELEVATED, // 40–65 %
        HIGH,     // 65–85 %
        CRITICAL  // > 85 % — or OS lowMemory / onLowMemory signal
    }

    // ── Singleton ─────────────────────────────────────────────────────────

    private static volatile ResourceGuard instance;

    public static synchronized ResourceGuard getInstance(Context ctx) {
        if (instance == null) {
            instance = new ResourceGuard(ctx.getApplicationContext());
        }
        return instance;
    }

    // ── Fields ────────────────────────────────────────────────────────────

    private final Context          context;
    private final ActivityManager  am;

    /** Background poller — MIN priority, daemon thread. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ResourceGuard-poll");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private volatile Level currentLevel = Level.NORMAL;

    // Escalation requires HYSTERESIS_TICKS consecutive readings at the higher level.
    private static final int HYSTERESIS_TICKS = 3; // 3 × 5 s = 15 s minimum to escalate
    private final AtomicInteger elevatedCount = new AtomicInteger(0);
    private final AtomicInteger highCount     = new AtomicInteger(0);
    private final AtomicInteger criticalCount = new AtomicInteger(0);

    // CPU sampling (two-point delta on /proc/stat)
    private long prevIdle  = 0;
    private long prevTotal = 0;
    private volatile float lastCpuFraction = 0f; // 0..1

    // Pressure change listeners
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    // ── Constructor ───────────────────────────────────────────────────────

    private ResourceGuard(Context ctx) {
        this.context = ctx;
        this.am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);

        // Register for OS memory-pressure callbacks (onTrimMemory / onLowMemory)
        ctx.registerComponentCallbacks(this);

        // Poll every 5 seconds
        scheduler.scheduleWithFixedDelay(this::poll, 5, 5, TimeUnit.SECONDS);

        Log.i(TAG, "ResourceGuard started — monitoring CPU / RAM / storage every 5 s");
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Current composite pressure level. */
    public Level getLevel() { return currentLevel; }

    public boolean isNormal()       { return currentLevel == Level.NORMAL; }
    public boolean isElevatedOrAbove() { return currentLevel.ordinal() >= Level.ELEVATED.ordinal(); }
    public boolean isHighOrAbove()     { return currentLevel.ordinal() >= Level.HIGH.ordinal(); }
    public boolean isCritical()        { return currentLevel == Level.CRITICAL; }

    /**
     * Adjust a JPEG quality value downward based on current pressure.
     * Never goes below 20 so the image remains recognisable.
     */
    public int adaptiveJpegQuality(int baseQuality) {
        switch (currentLevel) {
            case ELEVATED: return Math.max(20, baseQuality - 10);
            case HIGH:     return Math.max(20, baseQuality - 20);
            case CRITICAL: return 20;
            default:       return baseQuality;
        }
    }

    /**
     * Stretch an interval (ms) so the app does less work per second under pressure.
     * At CRITICAL, frames arrive 6× less often — still alive, just very light.
     */
    public long adaptiveIntervalMs(long baseMs) {
        switch (currentLevel) {
            case ELEVATED: return (long)(baseMs * 1.5);
            case HIGH:     return baseMs * 3;
            case CRITICAL: return baseMs * 6;
            default:       return baseMs;
        }
    }

    /**
     * Maximum bitmap width to use when encoding a frame.
     * Narrower bitmaps encode faster and consume less memory.
     */
    public int adaptiveFrameWidth(int baseWidth) {
        switch (currentLevel) {
            case ELEVATED: return (int)(baseWidth * 0.85);
            case HIGH:     return (int)(baseWidth * 0.65);
            case CRITICAL: return (int)(baseWidth * 0.50);
            default:       return baseWidth;
        }
    }

    /**
     * Register a callback that fires whenever the level changes.
     * Called on the polling thread — keep the runnable fast.
     */
    public void addPressureListener(Runnable r) {
        if (r != null) listeners.add(r);
    }

    public void removePressureListener(Runnable r) {
        listeners.remove(r);
    }

    // ── Polling ───────────────────────────────────────────────────────────

    private void poll() {
        try {
            float mem  = memPressureScore();   // 0..1
            float cpu  = cpuLoadScore();       // 0..1
            float disk = diskPressureScore();  // 0..1

            // Weighted composite — memory dominates
            float composite = (mem * 0.50f) + (cpu * 0.30f) + (disk * 0.20f);

            Level raw;
            if      (composite >= 0.85f) raw = Level.CRITICAL;
            else if (composite >= 0.65f) raw = Level.HIGH;
            else if (composite >= 0.40f) raw = Level.ELEVATED;
            else                         raw = Level.NORMAL;

            applyHysteresis(raw);

            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, String.format(
                    "poll mem=%.2f cpu=%.2f disk=%.2f composite=%.2f → %s",
                    mem, cpu, disk, composite, currentLevel));
            }
        } catch (Exception e) {
            Log.w(TAG, "poll error: " + e.getMessage());
        }
    }

    /**
     * Escalate only after HYSTERESIS_TICKS consecutive readings; drop immediately
     * when pressure improves (fail-safe behaviour).
     */
    private void applyHysteresis(Level raw) {
        Level prev = currentLevel;

        if (raw.ordinal() > currentLevel.ordinal()) {
            // Potential escalation — require sustained pressure
            int cnt;
            switch (raw) {
                case ELEVATED: cnt = elevatedCount.incrementAndGet(); break;
                case HIGH:     cnt = highCount.incrementAndGet();     break;
                default:       cnt = criticalCount.incrementAndGet(); break;
            }
            if (cnt >= HYSTERESIS_TICKS) {
                currentLevel = raw;
                resetCounts(raw);
            }
        } else {
            // Immediate de-escalation
            currentLevel = raw;
            resetCounts(raw);
        }

        if (currentLevel != prev) {
            Log.i(TAG, "Pressure level: " + prev + " → " + currentLevel);
            notifyListeners();
        }
    }

    private void resetCounts(Level keep) {
        if (keep != Level.ELEVATED) elevatedCount.set(0);
        if (keep != Level.HIGH)     highCount.set(0);
        if (keep != Level.CRITICAL) criticalCount.set(0);
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }

    // ── Score helpers (0 = no stress, 1 = worst) ─────────────────────────

    /** Fraction of RAM in use.  OS lowMemory flag forces ≥ 0.90. */
    private float memPressureScore() {
        if (am == null) return 0f;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        if (info.totalMem <= 0) return info.lowMemory ? 1f : 0f;
        float usedFraction = 1f - ((float) info.availMem / info.totalMem);
        return info.lowMemory ? Math.max(usedFraction, 0.90f) : usedFraction;
    }

    /** CPU load fraction via two-point delta on /proc/stat. */
    private float cpuLoadScore() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"));
            String line = reader.readLine();
            reader.close();
            if (line == null || !line.startsWith("cpu")) return lastCpuFraction;
            String[] p = line.trim().split("\\s+");
            if (p.length < 5) return lastCpuFraction;
            long user   = Long.parseLong(p[1]);
            long nice   = Long.parseLong(p[2]);
            long system = Long.parseLong(p[3]);
            long idle   = Long.parseLong(p[4]);
            long total  = user + nice + system + idle;
            long dIdle  = idle  - prevIdle;
            long dTotal = total - prevTotal;
            prevIdle  = idle;
            prevTotal = total;
            if (dTotal > 0) lastCpuFraction = 1f - ((float) dIdle / dTotal);
            return lastCpuFraction;
        } catch (Exception e) {
            return lastCpuFraction;
        }
    }

    /** Fraction of internal storage used.  < 50 MB free forces ≥ 0.90. */
    private float diskPressureScore() {
        try {
            StatFs stat = new StatFs(context.getFilesDir().getAbsolutePath());
            long freeBytes  = stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
            long totalBytes = stat.getBlockCountLong()      * stat.getBlockSizeLong();
            if (totalBytes <= 0) return 0f;
            float usedRatio = 1f - ((float) freeBytes / totalBytes);
            boolean criticallyLow = freeBytes < 50L * 1024 * 1024; // < 50 MB
            return criticallyLow ? Math.max(usedRatio, 0.90f) : usedRatio;
        } catch (Exception e) {
            return 0f;
        }
    }

    // ── ComponentCallbacks2 ────────────────────────────────────────────────

    @Override
    public void onTrimMemory(int level) {
        // OS memory-pressure signal — bypass hysteresis and escalate immediately
        Level newLevel;
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL || level == TRIM_MEMORY_COMPLETE) {
            newLevel = Level.CRITICAL;
        } else if (level >= TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_MODERATE) {
            newLevel = Level.HIGH;
        } else if (level >= TRIM_MEMORY_RUNNING_MODERATE || level == TRIM_MEMORY_BACKGROUND) {
            newLevel = Level.ELEVATED;
        } else {
            return; // benign trim — do nothing
        }

        Log.i(TAG, "onTrimMemory(" + level + ") → " + newLevel);
        if (newLevel.ordinal() > currentLevel.ordinal()) {
            Level prev = currentLevel;
            currentLevel = newLevel;
            Log.i(TAG, "Pressure level (OS signal): " + prev + " → " + currentLevel);
            notifyListeners();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) { /* no-op */ }

    @Override
    public void onLowMemory() {
        // Hard OOM signal from the OS — go CRITICAL immediately
        Log.w(TAG, "onLowMemory() — escalating to CRITICAL immediately");
        Level prev = currentLevel;
        currentLevel = Level.CRITICAL;
        if (currentLevel != prev) notifyListeners();
    }
}
