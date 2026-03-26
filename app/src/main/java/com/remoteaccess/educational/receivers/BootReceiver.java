package com.remoteaccess.educational.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.remoteaccess.educational.services.RemoteAccessService;
import com.remoteaccess.educational.utils.PreferenceManager;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            PreferenceManager preferenceManager = new PreferenceManager(context);
            
            // Only start service if consent was given
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
