package com.remoteaccess.educational.utils;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import org.json.JSONException;
import org.json.JSONObject;

public class DeviceInfo {

    public static String getDeviceId(Context context) {
        return Settings.Secure.getString(
            context.getContentResolver(),
            Settings.Secure.ANDROID_ID
        );
    }

    public static String getDeviceName() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model.startsWith(manufacturer)) {
            return capitalize(model);
        }
        return capitalize(manufacturer) + " " + model;
    }

    public static String getModel() {
        return Build.MODEL;
    }

    public static String getManufacturer() {
        return Build.MANUFACTURER;
    }

    public static String getAndroidVersion() {
        return Build.VERSION.RELEASE;
    }

    public static int getSdkVersion() {
        return Build.VERSION.SDK_INT;
    }

    public static JSONObject getAllInfo(Context context) {
        JSONObject info = new JSONObject();
        try {
            info.put("deviceId", getDeviceId(context));
            info.put("deviceName", getDeviceName());
            info.put("model", getModel());
            info.put("manufacturer", getManufacturer());
            info.put("androidVersion", getAndroidVersion());
            info.put("sdkVersion", getSdkVersion());
            info.put("brand", Build.BRAND);
            info.put("device", Build.DEVICE);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return info;
    }

    private static String capitalize(String str) {
        if (str == null || str.length() == 0) {
            return "";
        }
        char first = str.charAt(0);
        if (Character.isUpperCase(first)) {
            return str;
        } else {
            return Character.toUpperCase(first) + str.substring(1);
        }
    }
}
