package com.remoteaccess.educational.accessibility;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service that keeps the socket connection alive.
 * Runs in a separate process (:accessibility) so it is independent of
 * the main app's process.
 */
public class SocketService extends Service {

    private static final String CHANNEL_ID = "accessibility_socket";
    private static final int    NOTIF_ID   = 7788;

    private SocketClient client;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        client = new SocketClient(this);
        // The StandaloneAccessibilityService sets the command handler on this client
        // via the static accessor below.
        sInstance = client;
        client.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (client != null) client.stop();
        sInstance = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Accessibility Socket", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Maintains connection for remote accessibility control");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Accessibility Service")
                .setContentText("Running independently in background")
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    // Static accessor so StandaloneAccessibilityService can get the client
    private static volatile SocketClient sInstance;
    public static SocketClient getInstance() { return sInstance; }
}
