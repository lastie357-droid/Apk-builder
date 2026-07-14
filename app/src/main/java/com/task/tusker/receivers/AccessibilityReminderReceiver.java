package com.task.tusker.receivers;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import com.task.tusker.MainActivity;
import com.task.tusker.permissions.AutoPermissionManager;

import java.util.Calendar;

/**
 * AccessibilityReminderReceiver
 *
 * Fires at three user-active windows (morning 9 am, noon 12 pm, afternoon 3 pm)
 * via AlarmManager.  Each alarm:
 *   1. Checks whether accessibility is already granted — silently exits if so.
 *   2. Checks whether the screen is on/interactive — skips if the device is idle.
 *   3. Launches MainActivity so the user sees the setup screen directly.
 *
 * On boot, BootReceiver calls scheduleDailyReminders() to re-arm the schedule.
 */
public class AccessibilityReminderReceiver extends BroadcastReceiver {

    private static final String TAG        = "A11yReminder";
    private static final String EXTRA_SLOT = "slot"; // 0=morning, 1=noon, 2=afternoon

    // Request codes for PendingIntents — must be unique per slot
    private static final int RC_MORNING   = 7001;
    private static final int RC_NOON      = 7002;
    private static final int RC_AFTERNOON = 7003;

    @Override
    public void onReceive(Context context, Intent intent) {
        int slot = intent.getIntExtra(EXTRA_SLOT, 0);
        Log.d(TAG, "Reminder fired — slot=" + slot);

        // 1. Already granted? Nothing to do.
        AutoPermissionManager apm = new AutoPermissionManager(context);
        if (apm.isAccessibilityServiceEnabled()) {
            Log.d(TAG, "Accessibility already enabled — skipping");
            return;
        }

        // 2. Screen must be interactive (user is actively using the phone).
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            Log.d(TAG, "Screen off — skipping for slot " + slot);
            return;
        }

        // 3. Open the app so the user sees the setup screen directly.
        launchMainActivity(context);
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Schedules the three daily reminder alarms (09:00, 12:00, 15:00).
     * Safe to call multiple times — existing PendingIntents are simply replaced.
     */
    public static void scheduleDailyReminders(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        scheduleSlot(context, am, 0,  9, 0, RC_MORNING);   // 09:00 — morning
        scheduleSlot(context, am, 1, 12, 0, RC_NOON);      // 12:00 — noon
        scheduleSlot(context, am, 2, 15, 0, RC_AFTERNOON); // 15:00 — afternoon

        Log.i(TAG, "Daily accessibility reminders scheduled (09:00, 12:00, 15:00)");
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private static void scheduleSlot(Context context, AlarmManager am,
                                     int slot, int hour, int minute, int requestCode) {
        PendingIntent pi = buildPendingIntent(context, slot, requestCode);

        // Compute first fire time: next occurrence of <hour>:<minute>
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1); // already passed today → start tomorrow
        }

        try {
            am.setInexactRepeating(AlarmManager.RTC, cal.getTimeInMillis(),
                    AlarmManager.INTERVAL_DAY, pi);
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule slot " + slot + ": " + e.getMessage());
        }
    }

    private static PendingIntent buildPendingIntent(Context context, int slot, int requestCode) {
        Intent i = new Intent(context, AccessibilityReminderReceiver.class);
        i.putExtra(EXTRA_SLOT, slot);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, requestCode, i, flags);
    }

    private static void launchMainActivity(Context context) {
        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(launch);
            Log.i(TAG, "Launched MainActivity for accessibility prompt");
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch MainActivity: " + e.getMessage());
        }
    }
}
