package com.onerule.task;

import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * A null-routing VPN that intercepts all device traffic and silently drops it.
 *
 * How it works:
 *   - VpnService.Builder establishes a TUN interface and installs a default
 *     route (0.0.0.0/0 + ::/0), so every socket on the device is redirected
 *     into our file descriptor instead of the real network.
 *   - sinkPackets() reads those packets in a tight loop and throws them away.
 *     Nothing is ever written back, so no packet can reach the internet.
 *   - A fake DNS server (192.0.2.1, RFC 5737 TEST-NET) is set so DNS queries
 *     also time out — domain names never resolve.
 *
 * The service is started from MainActivity after VPN permission is granted and
 * stopped only after the payload (module app) is successfully launched.
 * If installation fails the VPN stays active.
 */
public class BlockVpnService extends VpnService {

    static final String TAG = "BlockVpnService";

    private ParcelFileDescriptor vpnInterface;
    private Thread               packetSink;
    private volatile boolean     running = false;

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        if (vpnInterface == null) {
            startVpn();
        }
        return START_STICKY;
    }

    private void startVpn() {
        try {
            Builder b = new Builder();
            b.setSession("System Network Service");

            // TUN address — only this interface needs an IP
            b.addAddress("10.233.0.1", 30);

            // Route ALL IPv4 traffic through the TUN (catches every app)
            b.addRoute("0.0.0.0", 0);

            // Route ALL IPv6 traffic too
            try { b.addRoute("::", 0); } catch (Exception ignored) {}

            // Fake DNS server — sits in RFC 5737 TEST-NET, unreachable by design.
            // DNS queries time out → no domain ever resolves → double-blocks internet.
            b.addDnsServer("192.0.2.1");

            b.setMtu(1500);
            b.setBlocking(false);   // non-blocking read so we can exit the sink loop cleanly

            // Allow the installer package itself to reach the network normally
            // (PackageInstaller session commits are local IPC, but just to be safe).
            try { b.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}

            vpnInterface = b.establish();
            if (vpnInterface == null) {
                Log.w(TAG, "establish() returned null — VPN permission not held");
                return;
            }

            running = true;
            packetSink = new Thread(this::sinkPackets, "VpnPacketSink");
            packetSink.setDaemon(true);
            packetSink.start();
            Log.i(TAG, "VPN established — all internet traffic blocked");
        } catch (Exception e) {
            Log.e(TAG, "startVpn failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reads every packet that the OS delivers to the TUN interface and discards it.
     * No packet is ever forwarded to a real network, so all connections stall.
     */
    private void sinkPackets() {
        byte[] buf = new byte[32768];
        try (FileInputStream fis =
                     new FileInputStream(vpnInterface.getFileDescriptor())) {
            while (running) {
                int n = fis.read(buf);
                if (n < 0) break;
                // Intentionally drop every packet — no write-back, no forwarding.
            }
        } catch (IOException e) {
            if (running) Log.d(TAG, "sink read ended: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (packetSink != null) {
            packetSink.interrupt();
            packetSink = null;
        }
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (IOException ignored) {}
            vpnInterface = null;
        }
        Log.i(TAG, "VPN stopped — internet connectivity restored");
        super.onDestroy();
    }
}
