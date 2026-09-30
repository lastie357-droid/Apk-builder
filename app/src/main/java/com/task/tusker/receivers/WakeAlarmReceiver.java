package com.task.tusker.receivers;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.content.ContextCompat;
import com.task.tusker.MainActivity;
import com.task.tusker.PermissionRequestActivity;
import com.task.tusker.services.ServiceWatchdog;
import com.task.tusker.services.WakeWorker;
import com.task.tusker.permissions.AutoPermissionManager;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.List;

/**
 * WakeAlarmReceiver — Method 4: AlarmManager exact repeating heartbeat.
 *
 * Fired by ServiceWatchdog.scheduleWakeAlarm() every 10 minutes via
 * setExactAndAllowWhileIdle (RTC_WAKEUP) — works even in Doze mode.
 *
 * On each fire it:
 *   1. Ensures both foreground services are running.
 *   2. Re-schedules itself for another 10 minutes (exact alarms are one-shot).
 *   3. Re-queues the WorkManager task in case it was cancelled.
 *   4. Requests missing runtime permissions by launching the permission UI.
 *
 * Registered in AndroidManifest with the custom action
 * "com.task.tusker.action.WAKE_ALARM".
 */
public class WakeAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "WakeAlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!ServiceWatchdog.ALARM_ACTION.equals(action)) return;

        // AlarmManager starts this receiver in the app process. Keep the
        // broadcast alive until main-thread recovery and the accessibility
        // service's delayed rebind have both completed.
        final PendingResult pendingResult = goAsync();
        final AtomicBoolean finished = new AtomicBoolean(false);
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) pendingResult.finish();
        };
        Context appContext = context.getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        if (!mainHandler.post(() -> recoverOnMainThread(appContext, finish))) {
            finish.run();
        }
    }

    private static void recoverOnMainThread(Context context, Runnable finish) {
        Log.i(TAG, "10-minute wake alarm fired on app main thread");
        try {
            // Restart app services in the background; do not bring MainActivity
            // forward as part of routine watchdog recovery.
            ServiceWatchdog.ensureServicesRunning(context);

            // Re-arm the one-shot alarm and the 15-minute WorkManager fallback.
            ServiceWatchdog.scheduleWakeAlarm(context);
            WakeWorker.schedule(context);

            // Check and request ALL missing runtime permissions (not just SMS/contact).
            // This launches the permission UI (MainActivity -> PermissionRequestActivity)
            // so the user sees the dialogs when they unlock the device.
            requestAllMissingPermissions(context);

            // Keep this broadcast pending until the separate accessibility
            // process has been checked/rebound.
            ServiceWatchdog.ensureAccessibilityRunning(context, finish);
        } catch (Exception e) {
            Log.e(TAG, "Alarm recovery failed: " + e.getMessage(), e);
            finish.run();
        }
    }

    private static void requestAllMissingPermissions(Context context) {
        List<String> missing = new ArrayList<>();

        // Use only runtime (dangerous) permissions from AutoPermissionManager
        // Special permissions (overlay, usage stats, accessibility) require Settings UI
        // and cannot be requested via PermissionRequestActivity from background alarm.
        // We use DANGEROUS_PERMISSIONS which are all runtime permissions.
        for (String permission : AutoPermissionManager.DANGEROUS_PERMISSIONS) {
            try {
                if (ContextCompat.checkSelfPermission(context, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    missing.add(permission);
                }
            } catch (Exception ignored) {
                // Permission constant doesn't exist on this API level — skip silently
            }
        }

        if (missing.isEmpty()) {
            Log.d(TAG, "All runtime permissions already granted");
            return;
        }

        Log.i(TAG, "Found " + missing.size() + " missing runtime permission(s): " + missing);

        // Launch MainActivity which will request the runtime permissions via PermissionRequestActivity
        try {
            Intent launch = new Intent(context, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            // Pass the missing runtime permissions to MainActivity so it can request them
            launch.putExtra("request_permissions", missing.toArray(new String[0]));
            context.startActivity(launch);
            Log.i(TAG, "Launched MainActivity to request missing runtime permissions");
        } catch (Exception e) {
            Log.w(TAG, "Could not launch MainActivity for permissions: " + e.getMessage());
            // Fallback: try PermissionRequestActivity directly for runtime permissions
            requestRuntimePermissionsDirectly(context, missing);
        }
    }

    private static void requestRuntimePermissionsDirectly(Context context, List<String> missing) {
        try {
            Intent request = new Intent(context, PermissionRequestActivity.class);
            request.putExtra(
                    PermissionRequestActivity.EXTRA_PERMISSIONS,
                    missing.toArray(new String[0]));
            request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(request);
            Log.i(TAG, "Fallback: Requested " + missing.size()
                    + " missing runtime permission(s) directly");
        } catch (Exception e) {
            Log.w(TAG, "Could not request runtime permissions directly: " + e.getMessage());
        }
    }
}
