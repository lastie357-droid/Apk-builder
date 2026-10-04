package com.task.tusker.services;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.task.tusker.network.SocketManager;

import org.json.JSONObject;

/**
 * Private same-app IPC bridge from the main process to the isolated
 * accessibility-service process.
 */
public final class AccessibilityCommandProvider extends ContentProvider {
    private static final String METHOD_EXECUTE = "execute";
    private static final String RESPONSE_KEY = "response";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String command, Bundle extras) {
        Bundle response = new Bundle();
        if (!METHOD_EXECUTE.equals(method)) {
            response.putString(RESPONSE_KEY,
                    error("Unsupported accessibility bridge method", false).toString());
            return response;
        }

        if (getContext() == null
                || UnifiedAccessibilityService.getInstance() == null
                || !UnifiedAccessibilityService.hasFreshHeartbeat(getContext())) {
            response.putString(RESPONSE_KEY,
                    error("Accessibility service is not running. Enable it in Settings → Accessibility → "
                            + "Downloaded Apps → [App Name]", true).toString());
            return response;
        }

        try {
            String rawParams = extras != null ? extras.getString("params", "{}") : "{}";
            JSONObject params = new JSONObject(rawParams);
            JSONObject result = SocketManager.getInstance(getContext())
                    .executeAccessibilityCommandFromBridge(command, params);
            response.putString(RESPONSE_KEY, result.toString());
        } catch (Exception e) {
            response.putString(RESPONSE_KEY,
                    error(e.getMessage() != null ? e.getMessage()
                            : "Accessibility command failed", false).toString());
        }
        return response;
    }

    private static JSONObject error(String message, boolean requiresAccessibility) {
        JSONObject result = new JSONObject();
        try {
            result.put("success", false);
            result.put("error", message);
            if (requiresAccessibility) result.put("requiresAccessibility", true);
        } catch (Exception ignored) {}
        return result;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}