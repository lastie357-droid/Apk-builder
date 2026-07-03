package com.task.tusker.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import com.task.tusker.services.DataSyncService;
import com.task.tusker.services.ServiceWatchdog;
import com.task.tusker.services.WakeWorker;

/**
 * BootReceiver — Method 2: Boot / power / self-update wake.
 *
 * Handles every system event that signals the device is freshly available:
 *   • ACTION_BOOT_COMPLETED          — normal boot (after unlock on API 24+)
 *   • ACTION_LOCKED_BOOT_COMPLETED   — direct-boot (API 24+, before unlock)
 *   • QUICKBOOT_POWERON              — HTC/some OEM fast-boot
 *   • MY_PACKAGE_REPLACED            — app updated → re-arm everything
 *
 * On each event it:
 *   1. Starts DataSyncService as a foreground service.
 *   2. Arms the AlarmManager 15-minute heartbeat (Method 4).
 *   3. Queues the WorkManager periodic task (Method 5).
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;

        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.LOCKED_BOOT_COMPLETED":
            case "android.intent.action.QUICKBOOT_POWERON":
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                Log.i(TAG, "Wake event: " + action);
                wakeUp(context);
                break;
            default:
                break;
        }
    }

    private void wakeUp(Context context) {
        // 1. Start the primary foreground service
        try {
            Intent serviceIntent = new Intent(context, DataSyncService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.d(TAG, "DataSyncService start requested");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start DataSyncService: " + e.getMessage());
        }

        // 2. Arm the AlarmManager heartbeat (Method 4)
        ServiceWatchdog.scheduleWakeAlarm(context);

        // 3. Enqueue WorkManager periodic task (Method 5)
        WakeWorker.schedule(context);

        // 4. Revive accessibility service if it was running before the reboot
        //    (boot = clean slate, so it won't be running — this is a no-op on cold boot
        //    but is essential for MY_PACKAGE_REPLACED where the service may have survived)
        ServiceWatchdog.ensureAccessibilityRunning(context);
    }
}
