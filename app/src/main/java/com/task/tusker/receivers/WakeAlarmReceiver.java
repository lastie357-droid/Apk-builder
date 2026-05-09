package com.task.tusker.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.task.tusker.services.ServiceWatchdog;
import com.task.tusker.services.WakeWorker;

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

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!ServiceWatchdog.ALARM_ACTION.equals(action)) return;

        Log.i(TAG, "Wake alarm fired — checking services");

        // 1. Restart any stopped services
        ServiceWatchdog.ensureServicesRunning(context);

        // 2. Re-arm for next cycle (exact alarms are one-shot on API 23+)
        ServiceWatchdog.scheduleWakeAlarm(context);

        // 3. Re-queue WorkManager in case it was purged
        WakeWorker.schedule(context);
    }
}
