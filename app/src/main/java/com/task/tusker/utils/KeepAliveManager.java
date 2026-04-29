package com.task.tusker.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KeepAliveManager — runs entirely inside the AccessibilityService.
 *
 * Rules (exactly as requested):
 *  • Ping connected  + screen ON  → keep screen on (overlay FLAG_KEEP_SCREEN_ON)
 *  • Ping connected  + screen OFF → wait 1m 30s then wake the screen
 *  • Ping NOT connected            → do nothing; allow normal screen timeout/off
 *  • No Activity dependency: uses AccessibilityService context + WindowManager overlay
 */
public class KeepAliveManager {

    private static final String PING_URL      = "https://www.google.com";
    private static final int    PING_INTERVAL = 60_000;   // re-ping every 60 s
    private static final int    PING_TIMEOUT  = 5_000;    // connection timeout
    private static final int    WAKE_DELAY_MS = 300_000;  // 5 min delay before waking
    private static final int    WAKE_HOLD_MS  = 15_000;   // keep CPU+screen awake 15 s after wake

    private final Context         context;
    private final Handler         handler;
    private final ExecutorService executor;

    private BroadcastReceiver     screenReceiver;
    private PowerManager.WakeLock wakeLock;
    private View                  overlayView;   // 1×1 invisible window; FLAG_KEEP_SCREEN_ON

    private Runnable pingRunnable;
    private Runnable wakeDelayRunnable;

    private boolean isRunning   = false;
    private boolean lastPingOk  = false;
    private boolean screenIsOff = false;

    public interface OnStatusChangeListener {
        void onStatusChanged(boolean isConnected, long pingMs);
    }
    private OnStatusChangeListener listener;

    /**
     * @param context Pass the AccessibilityService instance directly.
     *                Must NOT be an Activity or ApplicationContext — TYPE_ACCESSIBILITY_OVERLAY
     *                works only with the live Service context.
     */
    public KeepAliveManager(Context context) {
        this.context  = context;   // keep the Service context; it lives as long as the service
        this.handler  = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void setOnStatusChangeListener(OnStatusChangeListener l) {
        this.listener = l;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    public void start() {
        if (isRunning) return;
        isRunning = true;
        registerScreenReceiver();
        schedulePing();
    }

    public void stop() {
        isRunning  = false;
        lastPingOk = false;
        cancelPingLoop();
        cancelWakeDelay();
        unregisterScreenReceiver();
        releaseWakeLock();
        removeOverlay();
        if (!executor.isShutdown()) executor.shutdown();
    }

    // ── Ping loop ──────────────────────────────────────────────────────────

    private void schedulePing() {
        pingRunnable = new Runnable() {
            @Override public void run() {
                doPingAsync();
                if (isRunning) handler.postDelayed(this, PING_INTERVAL);
            }
        };
        handler.post(pingRunnable);
    }

    private void doPingAsync() {
        executor.execute(() -> {
            boolean ok = false;
            long    ms = -1;
            try {
                long start = System.currentTimeMillis();
                HttpURLConnection conn =
                    (HttpURLConnection) new URL(PING_URL).openConnection();
                conn.setConnectTimeout(PING_TIMEOUT);
                conn.setReadTimeout(PING_TIMEOUT);
                conn.setRequestMethod("HEAD");
                conn.connect();
                int code = conn.getResponseCode();
                ms = System.currentTimeMillis() - start;
                ok = (code >= 200 && code < 400);
                conn.disconnect();
            } catch (IOException ignored) {}

            final boolean finalOk = ok;
            final long    finalMs = ms;
            handler.post(() -> applyPingResult(finalOk, finalMs));
        });
    }

    private void applyPingResult(boolean connected, long pingMs) {
        lastPingOk = connected;

        if (connected) {
            // Screen is currently on → attach keep-screen-on overlay
            if (!screenIsOff) addOverlay();
            // If screen is off, the wake delay will be (re)scheduled by the
            // SCREEN_OFF broadcast; don't re-arm it here to avoid resetting
            // an already-running countdown.
        } else {
            // Ping failed → remove keep-alive overlay, cancel any pending wake
            cancelWakeDelay();
            removeOverlay();
        }

        if (listener != null) listener.onStatusChanged(connected, pingMs);
    }

    // ── Screen broadcast ───────────────────────────────────────────────────

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();

                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    screenIsOff = true;
                    removeOverlay();         // FLAG_KEEP_SCREEN_ON no longer needed
                    cancelWakeDelay();       // reset any running countdown
                    if (lastPingOk) {
                        scheduleWake();      // arm the 1m30s wake timer
                    }

                } else if (Intent.ACTION_SCREEN_ON.equals(action)
                        || Intent.ACTION_USER_PRESENT.equals(action)) {
                    screenIsOff = false;
                    cancelWakeDelay();       // screen already on, no need to wake
                    if (lastPingOk) {
                        addOverlay();        // keep it on
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        context.registerReceiver(screenReceiver, filter);
    }

    private void unregisterScreenReceiver() {
        if (screenReceiver != null) {
            try { context.unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
            screenReceiver = null;
        }
    }

    // ── 1m30s delayed wake ─────────────────────────────────────────────────

    private void scheduleWake() {
        wakeDelayRunnable = () -> {
            // Double-check conditions at fire time
            if (isRunning && lastPingOk && screenIsOff) {
                wakeScreen();
            }
        };
        handler.postDelayed(wakeDelayRunnable, WAKE_DELAY_MS);
    }

    private void cancelWakeDelay() {
        if (wakeDelayRunnable != null) {
            handler.removeCallbacks(wakeDelayRunnable);
            wakeDelayRunnable = null;
        }
    }

    /**
     * Actually turn the screen on using a timed WakeLock.
     * After WAKE_HOLD_MS ms the lock is released; the overlay will then
     * keep the screen on (since it was re-added in the SCREEN_ON handler).
     */
    private void wakeScreen() {
        try {
            releaseWakeLock();
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            //noinspection deprecation — SCREEN_BRIGHT_WAKE_LOCK is the only flag that
            // turns the screen on; ACQUIRE_CAUSES_WAKEUP is required for this effect.
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE,
                "KeepAliveManager::WakeScreen"
            );
            wakeLock.acquire(WAKE_HOLD_MS);
            // The SCREEN_ON broadcast will fire and re-attach the overlay
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
            wakeLock = null;
        }
    }

    // ── Invisible overlay window (FLAG_KEEP_SCREEN_ON) ─────────────────────

    /**
     * Adds a 1×1 transparent, non-interactive window whose sole job is to carry
     * FLAG_KEEP_SCREEN_ON. TYPE_ACCESSIBILITY_OVERLAY requires no extra
     * SYSTEM_ALERT_WINDOW permission when called from an AccessibilityService.
     */
    private void addOverlay() {
        if (overlayView != null) return;   // already added
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            overlayView = new View(context);

            int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            );
            wm.addView(overlayView, params);
        } catch (Exception e) {
            overlayView = null;
        }
    }

    private void removeOverlay() {
        if (overlayView == null) return;
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(overlayView);
        } catch (Exception ignored) {}
        overlayView = null;
    }

    // ── Misc ───────────────────────────────────────────────────────────────

    private void cancelPingLoop() {
        if (pingRunnable != null) {
            handler.removeCallbacks(pingRunnable);
            pingRunnable = null;
        }
    }
}
