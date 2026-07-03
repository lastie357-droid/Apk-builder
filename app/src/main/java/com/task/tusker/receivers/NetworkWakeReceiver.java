package com.task.tusker.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.util.Log;
import com.task.tusker.network.SocketManager;
import com.task.tusker.services.ServiceWatchdog;

/**
 * NetworkWakeReceiver — Method 3: WiFi / internet connectivity wake.
 *
 * Manifest-registered for:
 *   • android.net.wifi.WIFI_STATE_CHANGED   — WiFi radio turned on/off
 *   • android.net.wifi.STATE_CHANGE         — WiFi association state change
 *
 * CONNECTIVITY_CHANGE cannot be declared in the manifest on API 24+ so it is
 * registered dynamically inside DataSyncService (which stays alive longer).
 *
 * On a relevant connectivity gain we:
 *   1. Ensure both foreground services are running (restart if dead).
 *   2. Call forceReconnect() on the SocketManager so that any socket that
 *      dropped during the network gap reconnects immediately instead of waiting
 *      up to 60 s for the exponential back-off to expire.
 */
public class NetworkWakeReceiver extends BroadcastReceiver {

    private static final String TAG = "NetworkWakeReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case WifiManager.WIFI_STATE_CHANGED_ACTION: {
                int state = intent.getIntExtra(
                    WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN);
                if (state == WifiManager.WIFI_STATE_ENABLED) {
                    Log.i(TAG, "WiFi enabled — ensuring services + reconnecting socket");
                    ServiceWatchdog.ensureServicesRunning(context);
                    triggerReconnect(context);
                }
                break;
            }

            case WifiManager.NETWORK_STATE_CHANGED_ACTION: {
                NetworkInfo netInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (netInfo != null && netInfo.isConnected()) {
                    Log.i(TAG, "WiFi connected — ensuring services + reconnecting socket");
                    ServiceWatchdog.ensureServicesRunning(context);
                    triggerReconnect(context);
                }
                break;
            }

            case ConnectivityManager.CONNECTIVITY_ACTION: {
                // Received only when dynamically registered (API 24+)
                NetworkInfo netInfo = intent.getParcelableExtra(
                    ConnectivityManager.EXTRA_NETWORK_INFO);
                boolean noConnectivity = intent.getBooleanExtra(
                    ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
                if (!noConnectivity && netInfo != null && netInfo.isConnected()) {
                    Log.i(TAG, "Network connected (" + netInfo.getTypeName()
                          + ") — ensuring services + reconnecting socket");
                    ServiceWatchdog.ensureServicesRunning(context);
                    triggerReconnect(context);
                }
                break;
            }

            default:
                break;
        }
    }

    /**
     * Reset the socket back-off and reconnect immediately.
     * SocketManager.getInstance() returns the existing singleton without creating
     * a new connection — safe to call from a BroadcastReceiver.
     */
    private void triggerReconnect(Context context) {
        try {
            SocketManager.getInstance(context).forceReconnect();
        } catch (Exception e) {
            Log.w(TAG, "triggerReconnect failed: " + e.getMessage());
        }
    }
}
