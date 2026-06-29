package com.task.tusker.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.task.tusker.MainActivity;
import com.task.tusker.R;
import com.task.tusker.network.SocketManager;
import com.task.tusker.receivers.NetworkWakeReceiver;
import com.task.tusker.utils.ResourceGuard;

public class DataSyncService extends Service {

    private static final String TAG             = "DataSyncService";
    private static final String CHANNEL_ID      = "DataSyncChannel";
    private static final int    NOTIFICATION_ID = 1;

    private SocketManager socketManager;
    private ResourceGuard resourceGuard;

    /**
     * Method 3 (dynamic leg): CONNECTIVITY_CHANGE cannot be manifest-declared
     * on API 24+, so we register/unregister it here in the service lifecycle.
     */
    private final BroadcastReceiver connectivityReceiver = new NetworkWakeReceiver();
    private boolean connectivityReceiverRegistered = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        // Start resource monitoring — must happen before heavy work begins
        resourceGuard = ResourceGuard.getInstance(this);
        Log.d(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand — starting foreground + connecting socket");

        // Start as foreground with minimum required type to avoid
        // ForegroundServiceTypeSecurityException on Android 15.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                );
            } else {
                startForeground(NOTIFICATION_ID, createNotification());
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed: " + e.getMessage());
            try { startForeground(NOTIFICATION_ID, createNotification()); }
            catch (Exception ignored) {}
        }

        connectToServer();
        registerDynamicConnectivityReceiver(); // Method 3

        // Method 4 + 5: arm alarm and WorkManager on every start.
        ServiceWatchdog.scheduleWakeAlarm(this);
        WakeWorker.schedule(this);

        // Method 1: START_STICKY causes Android to auto-restart this
        // service after system kills it.
        return START_STICKY;
    }

    // ─── Method 3: dynamic CONNECTIVITY_CHANGE receiver ──────────────────────

    private void registerDynamicConnectivityReceiver() {
        if (connectivityReceiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU /* 33 */) {
                registerReceiver(connectivityReceiver, filter, RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(connectivityReceiver, filter);
            }
            connectivityReceiverRegistered = true;
            Log.d(TAG, "Dynamic connectivity receiver registered");
        } catch (Exception e) {
            Log.e(TAG, "registerDynamicConnectivityReceiver: " + e.getMessage());
        }
    }

    private void unregisterDynamicConnectivityReceiver() {
        if (!connectivityReceiverRegistered) return;
        try {
            unregisterReceiver(connectivityReceiver);
            connectivityReceiverRegistered = false;
        } catch (Exception e) {
            Log.w(TAG, "unregisterDynamicConnectivityReceiver: " + e.getMessage());
        }
    }

    // ─── Socket connection ────────────────────────────────────────────────────

    private void connectToServer() {
        try {
            socketManager = SocketManager.getInstance(this);
            socketManager.forceReconnect();
            Log.d(TAG, "SocketManager.forceReconnect() called");
        } catch (Exception e) {
            Log.e(TAG, "connectToServer error: " + e.getMessage());
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed — scheduling restart in 5 s");

        unregisterDynamicConnectivityReceiver();

        if (socketManager != null) {
            socketManager.disconnect();
        }

        // Method 1 (extra guard): schedule a short-fuse alarm so the service
        // comes back within 5 seconds even if START_STICKY is delayed by the OS.
        ServiceWatchdog.scheduleWakeAlarm(this, ServiceWatchdog.RESTART_DELAY_MS);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ─── Memory pressure ──────────────────────────────────────────────────────

    /**
     * Android calls this when the system is running low on memory.
     * We don't kill ourselves — we let ResourceGuard record the pressure level
     * so streaming components throttle down.  The service itself never stops.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // ResourceGuard is registered as ComponentCallbacks2 and handles this too,
        // but intercept here for an immediate service-level log.
        Log.w(TAG, "onTrimMemory(" + level + ") — ResourceGuard will throttle heavy ops");
        // On TRIM_MEMORY_RUNNING_CRITICAL or worse, nudge GC to free caches
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            try { System.gc(); } catch (Exception ignored) {}
        }
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Data Sync Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps data sync connection active");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pendingIntent =
            PendingIntent.getActivity(this, 0, notificationIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running in background")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}
