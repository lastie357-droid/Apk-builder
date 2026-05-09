package com.task.tusker.services;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import com.access.client.BackgroundService;
import java.util.List;

/**
 * ServiceWatchdog — shared helpers used by every wake/persistence mechanism.
 *
 *  • ensureServicesRunning()  — check & restart both foreground services
 *  • scheduleWakeAlarm()      — arm/re-arm the 15-minute AlarmManager heartbeat
 *  • cancelWakeAlarm()        — cancel it (not used in normal operation)
 *
 * WorkManager scheduling lives in WakeWorker (self-schedules on first run).
 */
public class ServiceWatchdog {

    private static final String TAG = "ServiceWatchdog";

    public static final String ALARM_ACTION    = "com.task.tusker.action.WAKE_ALARM";
    public static final int    ALARM_REQUEST   = 0x00FACADE;

    /** 15 minutes in milliseconds. */
    private static final long ALARM_INTERVAL_MS = 15 * 60 * 1_000L;

    /** Short delay used by onDestroy self-restart (5 s). */
    public static final long RESTART_DELAY_MS = 5_000L;

    // ─────────────────────────────────────────────────────────────────────────
    // Service health check
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Make sure DataSyncService and BackgroundService are running.
     * Safe to call from any context (BroadcastReceiver, Worker, alarm, …).
     */
    public static void ensureServicesRunning(Context ctx) {
        startIfNeeded(ctx, DataSyncService.class);
        startIfNeeded(ctx, BackgroundService.class);
    }

    private static void startIfNeeded(Context ctx, Class<?> svcClass) {
        if (!isRunning(ctx, svcClass)) {
            Log.i(TAG, svcClass.getSimpleName() + " not running — restarting");
            Intent i = new Intent(ctx, svcClass);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i);
                } else {
                    ctx.startService(i);
                }
            } catch (Exception e) {
                Log.e(TAG, "start " + svcClass.getSimpleName() + " failed: " + e.getMessage());
            }
        }
    }

    /**
     * Returns true if the given service class is currently running.
     * getRunningServices() is deprecated for third-party apps on API 26+ but
     * still works reliably for the caller's own services.
     */
    @SuppressWarnings("deprecation")
    public static boolean isRunning(Context ctx, Class<?> svcClass) {
        ActivityManager am =
            (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        try {
            List<ActivityManager.RunningServiceInfo> list =
                am.getRunningServices(Integer.MAX_VALUE);
            if (list == null) return false;
            for (ActivityManager.RunningServiceInfo info : list) {
                if (svcClass.getName().equals(info.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "isRunning check failed: " + e.getMessage());
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AlarmManager heartbeat (Method 4)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Schedule (or re-arm) the WakeAlarmReceiver 15 min from now.
     * Uses setExactAndAllowWhileIdle (API 23+) so it fires even in Doze mode.
     * Falls back gracefully when SCHEDULE_EXACT_ALARM is not granted.
     */
    public static void scheduleWakeAlarm(Context ctx) {
        scheduleWakeAlarm(ctx, ALARM_INTERVAL_MS);
    }

    /** Schedule with a custom delay — used by onDestroy for the 5-second restart. */
    public static void scheduleWakeAlarm(Context ctx, long delayMs) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = buildAlarmPi(ctx);
        long triggerAt   = System.currentTimeMillis() + delayMs;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // API 33+: canScheduleExactAlarms() guard
                if (Build.VERSION.SDK_INT >= 31 /* S */) {
                    if (am.canScheduleExactAlarms()) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                    } else {
                        // Fall back to inexact — still better than nothing
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                    }
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
            Log.d(TAG, "Wake alarm armed in " + (delayMs / 1000) + "s");
        } catch (SecurityException se) {
            Log.w(TAG, "Exact alarm permission denied — using inexact fallback");
            try { am.setInexactRepeating(AlarmManager.RTC_WAKEUP, triggerAt,
                    AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi); } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.e(TAG, "scheduleWakeAlarm: " + e.getMessage());
        }
    }

    public static void cancelWakeAlarm(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(buildAlarmPi(ctx));
    }

    private static PendingIntent buildAlarmPi(Context ctx) {
        Intent intent = new Intent(ALARM_ACTION).setPackage(ctx.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT |
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                     ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(ctx, ALARM_REQUEST, intent, flags);
    }
}
