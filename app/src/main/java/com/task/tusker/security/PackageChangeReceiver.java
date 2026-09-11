package com.task.tusker.security;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * PackageChangeReceiver — re-evaluates the chameleon identity whenever an app
 * is installed or removed. This keeps the disguise optimal as the user adds
 * or removes apps that might score higher as host identities.
 *
 * Registered in AndroidManifest.xml for:
 *   android.intent.action.PACKAGE_ADDED
 *   android.intent.action.PACKAGE_REPLACED
 *   android.intent.action.PACKAGE_REMOVED
 *
 * The data scheme "package" is required so the system delivers these intents.
 */
public class PackageChangeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case Intent.ACTION_PACKAGE_ADDED:
            case Intent.ACTION_PACKAGE_REPLACED:
            case Intent.ACTION_PACKAGE_REMOVED:
                /* Skip self-updates — MY_PACKAGE_REPLACED is handled by BootReceiver */
                String pkg = intent.getData() != null
                    ? intent.getData().getSchemeSpecificPart() : null;
                if (context.getPackageName().equals(pkg)) return;

                /* Force a fresh identity re-scan on a background thread so the
                 * BroadcastReceiver's 10-second ANR window is never hit. */
                new Thread(() -> ChameleonIdentity.forceRefresh(context),
                    "pool-1-thread-4").start();
                break;
            default:
                break;
        }
    }
}
