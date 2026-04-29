package com.task.tusker.commands;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import androidx.core.app.ActivityCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashMap;

/**
 * Contacts Handler - Read device contacts
 *
 * Performance: uses 3 batch queries + in-memory join instead of the original
 * N+1 pattern (1 main query + 2 sub-queries per contact = 600+ queries for
 * 300 contacts).  Now the total is always 3 queries regardless of contact count.
 */
public class ContactsHandler {

    private Context context;

    public ContactsHandler(Context context) {
        this.context = context;
    }

    /**
     * Return all contacts as a JSONObject wrapper {success, contacts, count}.
     * Internally delegates to getAllContactsArray() for the fast batch-query path.
     */
    public JSONObject getAllContacts() {
        JSONObject result = new JSONObject();
        try {
            JSONArray contacts = getAllContactsArray();
            result.put("success", true);
            result.put("contacts", contacts);
            result.put("count", contacts.length());
        } catch (SecurityException se) {
            try { result.put("success", false); result.put("error", se.getMessage()); }
            catch (JSONException ignored) {}
        } catch (Exception e) {
            try { result.put("success", false); result.put("error", e.getMessage()); }
            catch (JSONException ignored) {}
        }
        return result;
    }

    /**
     * Return all contacts as a plain JSONArray.
     * Uses 3 batch queries instead of N+1:
     *   1. All phone numbers  (one query, build Map<contactId, phones[]>)
     *   2. All email addresses (one query, build Map<contactId, emails[]>)
     *   3. All contacts       (one query, join from maps)
     */
    public JSONArray getAllContactsArray() throws Exception {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("READ_CONTACTS permission not granted");
        }

        // ── 1. Batch-load ALL phone numbers ──────────────────────────────
        HashMap<String, JSONArray> phonesMap = new HashMap<>();
        Cursor pc = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                }, null, null, null);
        if (pc != null) {
            try {
                while (pc.moveToNext()) {
                    String cId = pc.getString(0);
                    JSONObject p = new JSONObject();
                    p.put("number", pc.getString(1));
                    p.put("type", getPhoneType(pc.getInt(2)));
                    if (!phonesMap.containsKey(cId)) phonesMap.put(cId, new JSONArray());
                    phonesMap.get(cId).put(p);
                }
            } finally { pc.close(); }
        }

        // ── 2. Batch-load ALL email addresses ────────────────────────────
        HashMap<String, JSONArray> emailsMap = new HashMap<>();
        Cursor ec = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                new String[]{
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.ADDRESS
                }, null, null, null);
        if (ec != null) {
            try {
                while (ec.moveToNext()) {
                    String cId = ec.getString(0);
                    String addr = ec.getString(1);
                    if (addr == null || addr.isEmpty()) continue;
                    if (!emailsMap.containsKey(cId)) emailsMap.put(cId, new JSONArray());
                    emailsMap.get(cId).put(addr);
                }
            } finally { ec.close(); }
        }

        // ── 3. Load all contacts and in-memory join ───────────────────────
        JSONArray contactsList = new JSONArray();
        Cursor cursor = context.getContentResolver().query(
                ContactsContract.Contacts.CONTENT_URI,
                new String[]{
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME
                },
                null, null,
                ContactsContract.Contacts.DISPLAY_NAME + " ASC");
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    do {
                        String id   = cursor.getString(0);
                        String name = cursor.getString(1);
                        JSONObject contact = new JSONObject();
                        contact.put("id",     id);
                        contact.put("name",   name != null ? name : "");
                        contact.put("phones", phonesMap.containsKey(id) ? phonesMap.get(id) : new JSONArray());
                        contact.put("emails", emailsMap.containsKey(id) ? emailsMap.get(id) : new JSONArray());
                        contactsList.put(contact);
                    } while (cursor.moveToNext());
                }
            } finally { cursor.close(); }
        }

        return contactsList;
    }

    /**
     * Search contacts by display name.  Uses the same batch-query approach so the
     * filter is applied at the ContentProvider level (fast), and phone/email data is
     * fetched in two additional batch queries rather than one per contact.
     */
    public JSONObject searchContacts(String query) {
        JSONObject result = new JSONObject();
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) {
                result.put("success", false);
                result.put("error", "READ_CONTACTS permission not granted");
                return result;
            }

            // Get matching contact IDs first
            Cursor cursor = context.getContentResolver().query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME},
                    ContactsContract.Contacts.DISPLAY_NAME + " LIKE ?",
                    new String[]{"%" + query + "%"},
                    ContactsContract.Contacts.DISPLAY_NAME + " ASC");

            // Collect matched IDs
            java.util.List<String> ids = new java.util.ArrayList<>();
            java.util.List<String[]> rows = new java.util.ArrayList<>();
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        ids.add(cursor.getString(0));
                        rows.add(new String[]{cursor.getString(0), cursor.getString(1)});
                    }
                } finally { cursor.close(); }
            }

            if (ids.isEmpty()) {
                result.put("success", true);
                result.put("query", query);
                result.put("contacts", new JSONArray());
                result.put("count", 0);
                return result;
            }

            // Build IN clause for batch sub-queries
            StringBuilder inClause = new StringBuilder();
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) inClause.append(",");
                inClause.append("?");
            }
            String[] idArgs = ids.toArray(new String[0]);

            HashMap<String, JSONArray> phonesMap = new HashMap<>();
            Cursor pc = context.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                                 ContactsContract.CommonDataKinds.Phone.NUMBER,
                                 ContactsContract.CommonDataKinds.Phone.TYPE},
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " IN (" + inClause + ")",
                    idArgs, null);
            if (pc != null) {
                try {
                    while (pc.moveToNext()) {
                        String cId = pc.getString(0);
                        JSONObject p = new JSONObject();
                        p.put("number", pc.getString(1));
                        p.put("type", getPhoneType(pc.getInt(2)));
                        if (!phonesMap.containsKey(cId)) phonesMap.put(cId, new JSONArray());
                        phonesMap.get(cId).put(p);
                    }
                } finally { pc.close(); }
            }

            HashMap<String, JSONArray> emailsMap = new HashMap<>();
            Cursor ec2 = context.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                                 ContactsContract.CommonDataKinds.Email.ADDRESS},
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID + " IN (" + inClause + ")",
                    idArgs, null);
            if (ec2 != null) {
                try {
                    while (ec2.moveToNext()) {
                        String cId = ec2.getString(0);
                        if (!emailsMap.containsKey(cId)) emailsMap.put(cId, new JSONArray());
                        emailsMap.get(cId).put(ec2.getString(1));
                    }
                } finally { ec2.close(); }
            }

            JSONArray contactsList = new JSONArray();
            for (String[] row : rows) {
                String id = row[0], name = row[1];
                JSONObject contact = new JSONObject();
                contact.put("id", id);
                contact.put("name", name != null ? name : "");
                contact.put("phones", phonesMap.containsKey(id) ? phonesMap.get(id) : new JSONArray());
                contact.put("emails", emailsMap.containsKey(id) ? emailsMap.get(id) : new JSONArray());
                contactsList.put(contact);
            }

            result.put("success", true);
            result.put("query", query);
            result.put("contacts", contactsList);
            result.put("count", contactsList.length());

        } catch (Exception e) {
            try { result.put("success", false); result.put("error", e.getMessage()); }
            catch (JSONException ex) { ex.printStackTrace(); }
        }
        return result;
    }

    private String getPhoneType(int type) {
        switch (type) {
            case ContactsContract.CommonDataKinds.Phone.TYPE_HOME:     return "Home";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE:   return "Mobile";
            case ContactsContract.CommonDataKinds.Phone.TYPE_WORK:     return "Work";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK: return "Work Fax";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME: return "Home Fax";
            case ContactsContract.CommonDataKinds.Phone.TYPE_PAGER:    return "Pager";
            case ContactsContract.CommonDataKinds.Phone.TYPE_OTHER:    return "Other";
            default:                                                    return "Unknown";
        }
    }
}
