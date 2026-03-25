package com.remoteaccess.educational.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import com.remoteaccess.educational.MainActivity;
import com.remoteaccess.educational.R;
import com.remoteaccess.educational.network.SocketManager;
import com.remoteaccess.educational.utils.DeviceInfo;
import com.remoteaccess.educational.utils.PreferenceManager;

public class RemoteAccessService extends Service {

    private static final String CHANNEL_ID = "RemoteAccessChannel";
    private static final int NOTIFICATION_ID = 1;

    private SocketManager socketManager;
    private PreferenceManager preferenceManager;

    @Override
    public void onCreate() {
        super.onCreate();
        preferenceManager = new PreferenceManager(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start foreground service
        startForeground(NOTIFICATION_ID, createNotification());

        // Initialize socket connection
        if (preferenceManager.isConsentGiven()) {
            connectToServer();
        }

        return START_STICKY;
    }

    private void connectToServer() {
        socketManager = SocketManager.getInstance(this);
        socketManager.connect();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Remote Access Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps remote access connection active");

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, 
            PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Access Active")
            .setContentText("Your device is connected and can be managed remotely")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
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

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
