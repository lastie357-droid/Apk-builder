package com.remoteaccess.educational.utils;

public class Constants {

    // ========== TCP SERVER ==========
    // Point these at your server's host and TCP port.
    public static final String TCP_HOST = "sjc1.clusters.zeabur.com";
    public static final int    TCP_PORT = 21400;

    // Delay before attempting a reconnect after a drop (ms)
    public static final int TCP_RECONNECT_DELAY = 3000;

    // Heartbeat interval — must be shorter than the server's 45 s pong timeout
    public static final int HEARTBEAT_INTERVAL = 20000; // 20 seconds
}
