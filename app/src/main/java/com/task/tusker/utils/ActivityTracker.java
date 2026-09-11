package com.task.tusker.utils;

import android.app.Activity;
import java.lang.ref.WeakReference;

/**
 * Tracks the currently-foregrounded Activity.
 *
 * Each Activity calls {@link #set(Activity)} in {@code onResume()} and
 * {@link #clear(Activity)} in {@code onPause()}.  PermissionManager (which
 * runs from a Service context) can then check {@link #getForeground()} to
 * decide whether it is safe to start the permission-dialog Activity directly
 * instead of routing through a notification trampoline.
 */
public final class ActivityTracker {

    private static volatile WeakReference<Activity> sRef = new WeakReference<>(null);

    private ActivityTracker() {}

    /** Called from every Activity.onResume(). */
    public static void set(Activity activity) {
        sRef = new WeakReference<>(activity);
    }

    /**
     * Called from every Activity.onPause().
     * Only clears if the pausing activity is the one we are currently tracking
     * (avoids clearing a reference that was already replaced by the next Activity).
     */
    public static void clear(Activity activity) {
        Activity current = sRef.get();
        if (current == activity) sRef = new WeakReference<>(null);
    }

    /**
     * Returns the current foreground Activity, or {@code null} if none is running
     * (app is in the background, was garbage-collected, etc.).
     */
    public static Activity getForeground() {
        return sRef.get();
    }
}
