package com.remoteaccess.educational;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Transparent trampoline Activity — launched by PermissionManager from a Service
 * context so the native Android runtime-permission dialog can be shown.
 *
 * The caller puts the permission string in EXTRA_PERMISSION, or an array of
 * permission strings in EXTRA_PERMISSIONS.  This activity:
 *   1. Filters out already-granted permissions.
 *   2. Calls requestPermissions() — the OS shows the native dialog for each.
 *   3. If any permission was permanently denied ("Don't ask again"), falls back
 *      to the App Info settings page so the user can manually toggle it.
 *   4. Finishes itself (disappears) immediately after the result is received.
 *
 * The activity uses a fully-transparent theme so nothing is visible on screen
 * except the permission dialog itself.
 */
public class PermissionRequestActivity extends AppCompatActivity {

    public static final String EXTRA_PERMISSION  = "extra_permission";
    public static final String EXTRA_PERMISSIONS = "extra_permissions";
    private static final int   REQ_CODE          = 9001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String[] perms = resolvePermissions();
        if (perms == null || perms.length == 0) {
            finish();
            return;
        }

        // Filter out permissions that are already granted — no point re-asking.
        List<String> needed = new ArrayList<>();
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }

        if (needed.isEmpty()) {
            finish();
            return;
        }

        ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_CODE) {
            // If any permission is permanently denied (denied + "Don't ask again"),
            // shouldShowRequestPermissionRationale returns false.  In that case the
            // OS will never show the dialog again, so fall back to App Info settings
            // where the user can manually flip the toggle.
            boolean anyPermanentlyDenied = false;
            for (int i = 0; i < permissions.length; i++) {
                boolean denied     = (grantResults.length <= i)
                                  || (grantResults[i] == PackageManager.PERMISSION_DENIED);
                boolean cantAskAgain = !ActivityCompat
                        .shouldShowRequestPermissionRationale(this, permissions[i]);

                if (denied && cantAskAgain) {
                    anyPermanentlyDenied = true;
                    break;
                }
            }

            if (anyPermanentlyDenied) {
                try {
                    Intent settings = new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(settings);
                } catch (Exception ignored) {}
            }
        }

        finish();
    }

    /** Reads a single permission or an array of permissions from the launching Intent. */
    private String[] resolvePermissions() {
        Intent intent = getIntent();
        if (intent == null) return null;

        String single = intent.getStringExtra(EXTRA_PERMISSION);
        if (single != null && !single.isEmpty()) return new String[]{ single };

        return intent.getStringArrayExtra(EXTRA_PERMISSIONS);
    }
}
