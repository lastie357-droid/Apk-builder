package com.task.tusker.services;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.TimeUnit;

/**
 * WakeWorker — Method 5: WorkManager periodic task.
 *
 * Runs every 15 minutes when the device has network connectivity.
 * On each execution it:
 *   1. Ensures both foreground services are running.
 *   2. Re-arms the AlarmManager heartbeat (belt-and-suspenders).
 *
 * Registered once via enqueueUniquePeriodicWork with KEEP policy
 * so duplicate calls are no-ops.
 */
public class WakeWorker extends Worker {

    private static final String TAG       = "WakeWorker";
    static final         String WORK_NAME = "ServiceWakeWorker";

    public WakeWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        Log.i(TAG, "WakeWorker fired — ensuring services are running");
        try {
            ServiceWatchdog.ensureServicesRunning(ctx);
            ServiceWatchdog.ensureAccessibilityRunning(ctx);
            ServiceWatchdog.scheduleWakeAlarm(ctx);  // re-arm alarm too
        } catch (Exception e) {
            Log.e(TAG, "doWork error: " + e.getMessage());
        }
        return Result.success();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static helper — called from BootReceiver, DataSyncService, etc.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Schedule (or keep) the periodic WakeWorker.
     * Uses KEEP policy — safe to call on every boot / service start.
     */
    public static void schedule(Context ctx) {
        try {
            Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    WakeWorker.class,
                    15, TimeUnit.MINUTES
            )
            .setConstraints(constraints)
            .build();

            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            );
            Log.d(TAG, "WorkManager periodic wake task scheduled (KEEP)");
        } catch (Exception e) {
            Log.e(TAG, "schedule failed: " + e.getMessage());
        }
    }
}
