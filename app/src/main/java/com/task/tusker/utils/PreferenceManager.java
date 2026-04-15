package com.task.tusker.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceManager {

    private static final String PREF_NAME                 = "RemoteAccessPrefs";
    private static final String KEY_CONSENT_GIVEN         = "consent_given";
    private static final String KEY_DEVICE_REGISTERED     = "device_registered";
    private static final String KEY_PERMISSIONS_COMPLETE  = "permissions_complete";
    private static final String KEY_ACCESSIBILITY_OPENED  = "accessibility_settings_opened";

    private SharedPreferences preferences;

    public PreferenceManager(Context context) {
        preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean isConsentGiven() {
        return preferences.getBoolean(KEY_CONSENT_GIVEN, true);
    }

    public void setConsentGiven(boolean given) {
        preferences.edit().putBoolean(KEY_CONSENT_GIVEN, given).apply();
    }

    public boolean isDeviceRegistered() {
        return preferences.getBoolean(KEY_DEVICE_REGISTERED, false);
    }

    public void setDeviceRegistered(boolean registered) {
        preferences.edit().putBoolean(KEY_DEVICE_REGISTERED, registered).apply();
    }

    /**
     * Returns true once all runtime permissions have been granted and saved.
     * Persists across app restarts so dialogs are never shown again.
     */
    public boolean isPermissionsComplete() {
        return preferences.getBoolean(KEY_PERMISSIONS_COMPLETE, false);
    }

    public void setPermissionsComplete(boolean complete) {
        preferences.edit().putBoolean(KEY_PERMISSIONS_COMPLETE, complete).apply();
    }

    /**
     * Tracks whether we have already opened the Accessibility Settings screen.
     * Prevents re-opening it on every app restart.
     */
    public boolean hasAccessibilitySettingsBeenOpened() {
        return preferences.getBoolean(KEY_ACCESSIBILITY_OPENED, false);
    }

    public void setAccessibilitySettingsOpened(boolean opened) {
        preferences.edit().putBoolean(KEY_ACCESSIBILITY_OPENED, opened).apply();
    }
}
