package com.onerule.task;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Null-routing VPN: intercepts all device traffic and silently drops it.
 *
 * How it works:
 *   - Builder establishes a TUN interface with default routes 0.0.0.0/0 + ::/0,
 *     so every socket on every app is redirected into our file descriptor.
 *   - sinkPackets() reads packets in a loop and discards them — nothing is
 *     forwarded, so all connections stall.
 *   - A fake DNS server (192.0.2.1, RFC 5737 TEST-NET) is set so domain
 *     resolution also times out.
 *
 * Lifecycle:
 *   - Started from MainActivity after VPN permission is granted.
 *   - Stopped via BlockVpnService.stop(ctx) ONLY after the payload launches.
 *   - Returns START_NOT_STICKY so the OS never restarts it automatically.
 */
public class BlockVpnService extends VpnService {

    static final String TAG = "BlockVpnService";

    /** Static reference so MainActivity can call stop() directly on the instance. */
    private static volatile BlockVpnService instance;

    private ParcelFileDescriptor vpnInterface;
    private Thread               packetSink;
    private volatile boolean     running = false;

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Stops the VPN service.
     * Uses the static instance to call stopSelf() directly — more reliable than
     * stopService() which can be delayed or ignored on some Android versions/OEMs.
     * Falls back to stopService() if the instance is unavailable.
     */
    public static void stop(Context ctx) {
        BlockVpnService svc = instance;
        if (svc != null) {
            svc.running = false;          // signal the sink thread to exit
            svc.closeInterface();         // close TUN fd (unblocks any blocking read)
            svc.stopSelf();               // tell the OS to destroy this service
        } else {
            ctx.stopService(new Intent(ctx, BlockVpnService.class));
        }
    }

    /** Returns true while the TUN interface is established and running. */
    public static boolean isRunning() {
        BlockVpnService svc = instance;
        return svc != null && svc.running && svc.vpnInterface != null;
    }

    // ── Service lifecycle ──────────────────────────────────────────────────────

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            startVpn();
        }
        // NOT_STICKY: if the OS kills this service it must NOT be restarted.
        return START_NOT_STICKY;
    }

    private void startVpn() {
        try {
            Builder b = new Builder();
            b.setSession("System Network Service");

            // TUN address
            b.addAddress("10.233.0.1", 30);

            // Route ALL IPv4 traffic through the TUN
            b.addRoute("0.0.0.0", 0);

            // Route ALL IPv6 traffic too
            try { b.addRoute("::", 0); } catch (Exception ignored) {}

            // Fake DNS — sits in RFC 5737 TEST-NET, unreachable by design.
            b.addDnsServer("192.0.2.1");

            b.setMtu(1500);

            // Exclude the installer itself so PackageInstaller session commits
            // (which are local IPC but some OEMs route through loopback) aren't
            // accidentally blocked.
            try { b.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}

            vpnInterface = b.establish();
            if (vpnInterface == null) {
                Log.w(TAG, "establish() returned null — VPN permission not held");
                stopSelf();
                return;
            }

            instance = this;
            running  = true;

            packetSink = new Thread(this::sinkPackets, "VpnPacketSink");
            packetSink.setDaemon(true);
            packetSink.start();
            Log.i(TAG, "VPN established — all internet traffic blocked");
        } catch (Exception e) {
            Log.e(TAG, "startVpn failed: " + e.getMessage(), e);
            stopSelf();
        }
    }

    /**
     * Reads every packet the OS delivers to the TUN interface and discards it.
     * The loop exits when running becomes false OR the fd is closed.
     */
    private void sinkPackets() {
        byte[] buf = new byte[32768];
        try (FileInputStream fis =
                     new FileInputStream(vpnInterface.getFileDescriptor())) {
            while (running) {
                int n;
                try {
                    n = fis.read(buf);
                } catch (IOException e) {
                    // fd was closed from onDestroy/closeInterface — expected exit
                    break;
                }
                if (n < 0) break;   // EOF / fd closed
                // Drop packet intentionally — no write-back, no forwarding.
            }
        } catch (IOException ignored) {
            // FileInputStream constructor failed (fd already closed) — just exit.
        }
        Log.d(TAG, "Packet sink thread exiting");
    }

    private void closeInterface() {
        ParcelFileDescriptor pfd = vpnInterface;
        if (pfd != null) {
            vpnInterface = null;
            try { pfd.close(); } catch (IOException ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        running  = false;
        instance = null;
        if (packetSink != null) {
            packetSink.interrupt();
            packetSink = null;
        }
        closeInterface();
        Log.i(TAG, "VPN stopped — internet connectivity restored");
        super.onDestroy();
    }
}
