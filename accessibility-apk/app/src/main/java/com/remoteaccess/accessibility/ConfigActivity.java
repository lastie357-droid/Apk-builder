package com.remoteaccess.accessibility;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;

/**
 * Minimal config activity. When launched, it reads server URL/deviceId from
 * SharedPreferences (written by the main app or by ADB), starts the socket
 * service, and opens the Accessibility Settings screen if needed.
 */
public class ConfigActivity extends Activity {

    static final String PREFS = "accessibility_apk_prefs";
    static final String KEY_SERVER = "server_host";
    static final String KEY_PORT   = "server_port";
    static final String KEY_DEVICE = "device_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Accept config updates from intents (e.g. sent by main app)
        Intent i = getIntent();
        if (i != null) {
            String host     = i.getStringExtra("server_host");
            String deviceId = i.getStringExtra("device_id");
            int    port     = i.getIntExtra("server_port", 0);

            if (host != null || deviceId != null || port != 0) {
                SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                if (host != null)     ed.putString(KEY_SERVER, host);
                if (port != 0)        ed.putInt(KEY_PORT, port);
                if (deviceId != null) ed.putString(KEY_DEVICE, deviceId);
                ed.apply();
            }
        }

        // Start socket service
        try {
            Intent svc = new Intent(this, SocketService.class);
            startForegroundService(svc);
        } catch (Exception ignored) {}

        // Open accessibility settings if service not enabled
        if (!isAccessibilityEnabled()) {
            try {
                Intent s = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(s);
            } catch (Exception ignored) {}
        }

        finish();
    }

    private boolean isAccessibilityEnabled() {
        try {
            String services = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return services != null && services.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }
}
