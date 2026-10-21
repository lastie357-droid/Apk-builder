package com.task.tusker.services;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import com.access.client.BackgroundService;
import com.task.tusker.utils.ResourceGuard;
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

    /**
     * Make sure UnifiedAccessibilityService is alive.
     *
     * Uses WRITE_SECURE_SETTINGS (already granted via ADB) to silently remove
     * then re-add our service to ENABLED_ACCESSIBILITY_SERVICES, forcing the
     * accessibility framework to rebind it — no user interaction required.
     */
    public static void ensureAccessibilityRunning(Context ctx) {
        if (UnifiedAccessibilityService.getInstance() != null) return;

        Log.w(TAG, "UnifiedAccessibilityService not running — attempting recovery");

        if (trySecureSettingsRestart(ctx)) {
            Log.i(TAG, "Accessibility service revived via WRITE_SECURE_SETTINGS toggle");
        } else {
            Log.w(TAG, "WRITE_SECURE_SETTINGS toggle failed — service will recover on next alarm");
        }
    }

    /**
     * Toggle the accessibility service entry in Settings.Secure:
     * remove it, wait 300 ms, then re-add it.  This causes the accessibility
     * framework to unbind and immediately rebind the service — effectively a
     * programmatic restart without user interaction.
     *
     * Requires WRITE_SECURE_SETTINGS (signature/privileged; grantable via ADB).
     *
     * @return true  if the Settings.Secure write succeeded (permission granted)
     *         false if SecurityException → permission not held
     */
    private static boolean trySecureSettingsRestart(Context ctx) {
        final String OUR_COMPONENT = ctx.getPackageName()
                + "/com.task.tusker.services.UnifiedAccessibilityService";
        try {
            String current = Settings.Secure.getString(
                    ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            // Build the list without our entry
            String stripped = removeFromServiceList(current, OUR_COMPONENT);

            // Write the stripped list (removes our service → framework unbinds it)
            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    stripped.isEmpty() ? "" : stripped);

            // After a short pause, re-add our service (framework rebinds → onServiceConnected fires)
            final String base = stripped;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    String restored = base.isEmpty() ? OUR_COMPONENT : base + ":" + OUR_COMPONENT;
                    Settings.Secure.putString(ctx.getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, restored);
                    Settings.Secure.putInt(ctx.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                    Log.i(TAG, "Accessibility service re-added to enabled list");
                } catch (Exception e2) {
                    Log.e(TAG, "Re-add accessibility entry failed: " + e2.getMessage());
                }
            }, 300);

            return true; // write succeeded
        } catch (SecurityException e) {
            Log.d(TAG, "WRITE_SECURE_SETTINGS not granted — cannot auto-restart accessibility");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "trySecureSettingsRestart unexpected error: " + e.getMessage());
            return false;
        }
    }

    /** Remove {@code item} from a colon-separated accessibility service list. */
    private static String removeFromServiceList(String list, String item) {
        if (list == null || list.isEmpty()) return "";
        String itemLc = item.toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (String part : list.split(":")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !trimmed.toLowerCase().equals(itemLc)) {
                if (sb.length() > 0) sb.append(":");
                sb.append(trimmed);
            }
        }
        return sb.toString();
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
     *
     * Under HIGH or CRITICAL resource pressure the interval is stretched to 30 min
     * so wakeup-induced work doesn't add to an already-struggling system.  The
     * services are already kept alive by START_STICKY, so a longer check interval
     * is safe — it only matters if something died silently.
     */
    public static void scheduleWakeAlarm(Context ctx) {
        ResourceGuard rg = ResourceGuard.getInstance(ctx);
        long interval = rg.isHighOrAbove()
                ? ALARM_INTERVAL_MS * 2   // 30 min under HIGH/CRITICAL
                : ALARM_INTERVAL_MS;      // 15 min normally
        scheduleWakeAlarm(ctx, interval);
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
