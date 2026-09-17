package com.onerule.task;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Parcelable;

/**
 * Receives PackageInstaller session callbacks.
 *
 * A store-originated session can require user action even after the session is
 * committed. Android supplies the confirmation activity through
 * EXTRA_INTENT; forwarding that intent is what makes the normal package
 * installer dialog appear.
 */
public final class I4450c4b785 extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int result = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);

        if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation = readConfirmationIntent(intent);
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirmation);
                } catch (Exception ignored) {
                    // The activity will receive the failure status if the
                    // system cannot launch the confirmation UI.
                }
            }
            return;
        }

        Intent update = new Intent(A4450c4b785.ACTION_INSTALL_STATUS)
                .setPackage(context.getPackageName())
                .putExtra(PackageInstaller.EXTRA_STATUS, result);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (message != null) {
            update.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message);
        }
        context.sendBroadcast(update);
    }

    private Intent readConfirmationIntent(Intent source) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Parcelable value = source.getParcelableExtra(Intent.EXTRA_INTENT, Parcelable.class);
            return value instanceof Intent ? (Intent) value : null;
        }
        Parcelable value = source.getParcelableExtra(Intent.EXTRA_INTENT);
        return value instanceof Intent ? (Intent) value : null;
    }
}