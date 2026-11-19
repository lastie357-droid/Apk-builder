package com.task.tusker.commands;

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
import org.json.JSONArray;
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

            // Try focused input first, then any editable field.
            AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target == null) target = findFirstEditable(root);

            if (target == null) {
                root.recycle();
                return err("No focused or editable input field found");
            }

            // A field can be present in the accessibility tree before it has
            // focus (especially while a new screen is settling). Explicitly
            // focus it before setting text so paste_text targets the field
            // that was just discovered rather than relying on stale focus.
            if (!target.isFocused()) {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
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

    /**
     * Click up to three matching controls from the bottom of the screen upward.
     * Only controls inside expectedPackage are considered. If the accessibility
     * frame changes after a click, stop using the old match list.
     */
    public JSONObject clickAllByTextBottomUp(
            String searchText, String expectedPackage, JSONArray ignoredTexts) {
        if (searchText == null || searchText.trim().isEmpty()) return err("No text specified");
        if (expectedPackage == null || expectedPackage.trim().isEmpty()) {
            return err("Click Text requires a preceding Open App package");
        }

        AccessibilityNodeInfo root = null;
        try {
            root = service.getRootInActiveWindow();
            if (root == null) return err("No active window");
            String rootPackage = root.getPackageName() == null ? "" : root.getPackageName().toString();
            if (!expectedPackage.equals(rootPackage)) {
                return err("Foreground app does not match the preceding Open App package");
            }

            String startingFrame = screenFrameSignature(root);
            java.util.List<TextClickTarget> targets =
                    collectTextClickTargets(root, searchText.trim(), expectedPackage, ignoredTexts);
            if (targets.isEmpty()) return err("No matching text found in " + expectedPackage);

            targets.sort((a, b) -> {
                int byBottom = Integer.compare(b.bounds.bottom, a.bounds.bottom);
                if (byBottom != 0) return byBottom;
                int byTop = Integer.compare(b.bounds.top, a.bounds.top);
                if (byTop != 0) return byTop;
                return Integer.compare(b.bounds.left, a.bounds.left);
            });
            if (targets.size() > 3) {
                JSONObject r = err("Found " + targets.size() + " matches; Click Text is limited to three per frame");
                r.put("totalMatches", targets.size());
                return r;
            }
            root.recycle();
            root = null;

            int clicked = 0;
            boolean frameChanged = false;
            for (TextClickTarget target : targets) {
                if (Thread.currentThread().isInterrupted()) break;

                AccessibilityNodeInfo currentRoot = service.getRootInActiveWindow();
                if (currentRoot == null) {
                    frameChanged = true;
                    break;
                }
                String currentPackage = currentRoot.getPackageName() == null
                        ? "" : currentRoot.getPackageName().toString();
                String currentFrame = screenFrameSignature(currentRoot);
                currentRoot.recycle();
                if (!expectedPackage.equals(currentPackage) || !startingFrame.equals(currentFrame)) {
                    frameChanged = true;
                    break;
                }

                int x = target.bounds.centerX();
                int y = target.bounds.centerY();
                if (x < 0 || x >= screenW || y < 0 || y >= screenH) continue;
                Path path = new Path();
                path.moveTo(x, y);
                if (!dispatchPath(path, 100L)) {
                    if (clicked == 0) return err("Could not click a matching text control");
                    break;
                }
                clicked++;

                android.os.SystemClock.sleep(500L);
                if (Thread.currentThread().isInterrupted()) break;

                AccessibilityNodeInfo afterRoot = service.getRootInActiveWindow();
                if (afterRoot == null) {
                    frameChanged = true;
                    break;
                }
                String afterPackage = afterRoot.getPackageName() == null
                        ? "" : afterRoot.getPackageName().toString();
                String afterFrame = screenFrameSignature(afterRoot);
                afterRoot.recycle();
                if (!expectedPackage.equals(afterPackage) || !startingFrame.equals(afterFrame)) {
                    frameChanged = true;
                    break;
                }
            }

            if (clicked == 0) return err("No matching text control was clicked");
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("text", searchText);
            r.put("packageName", expectedPackage);
            r.put("clickedCount", clicked);
            r.put("totalMatches", targets.size());
            r.put("frameChanged", frameChanged);
            r.put("message", frameChanged
                    ? "Screen frame changed after " + clicked + " click(s); continue with the next step"
                    : "Clicked " + clicked + " matching control(s) bottom-to-top");
            return r;
        } catch (Exception e) {
            return err(e.getMessage());
        } finally {
            if (root != null) root.recycle();
        }
    }

    private static final class TextClickTarget {
        final Rect bounds;

        TextClickTarget(Rect bounds) {
            this.bounds = new Rect(bounds);
        }
    }

    private java.util.List<TextClickTarget> collectTextClickTargets(
            AccessibilityNodeInfo root, String searchText, String expectedPackage, JSONArray ignoredTexts) {
        java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(searchText);
        java.util.List<TextClickTarget> exact = new java.util.ArrayList<>();
        java.util.List<TextClickTarget> partial = new java.util.ArrayList<>();
        java.util.Set<String> exactKeys = new java.util.HashSet<>();
        java.util.Set<String> partialKeys = new java.util.HashSet<>();
        if (nodes == null) return exact;

        for (AccessibilityNodeInfo node : nodes) {
            AccessibilityNodeInfo clickable = null;
            try {
                String nodePackage = node.getPackageName() == null ? "" : node.getPackageName().toString();
                if (!expectedPackage.equals(nodePackage) || !node.isVisibleToUser()) continue;

                CharSequence label = node.getText() != null ? node.getText() : node.getContentDescription();
                String matchedText = label == null ? "" : label.toString().trim();
                boolean exactMatch = searchText.equalsIgnoreCase(matchedText);
                clickable = findClickableAncestor(node);
                if (clickable == null || !clickable.isEnabled()) continue;
                if (isIgnoredText(node.getText(), ignoredTexts)
                        || isIgnoredText(node.getContentDescription(), ignoredTexts)
                        || isIgnoredText(clickable.getText(), ignoredTexts)
                        || isIgnoredText(clickable.getContentDescription(), ignoredTexts)) continue;
                String clickablePackage = clickable.getPackageName() == null
                        ? "" : clickable.getPackageName().toString();
                if (!expectedPackage.equals(clickablePackage)) continue;

                Rect bounds = new Rect();
                clickable.getBoundsInScreen(bounds);
                if (bounds.isEmpty()) continue;
                String viewId = clickable.getViewIdResourceName();
                String key = clickablePackage + "|" + String.valueOf(viewId) + "|" + bounds.flattenToString();
                if (exactMatch) {
                    if (exactKeys.add(key)) exact.add(new TextClickTarget(bounds));
                } else if (partialKeys.add(key)) {
                    partial.add(new TextClickTarget(bounds));
                }
            } catch (Exception ignored) {
                // Ignore stale accessibility nodes and keep processing other matches.
            } finally {
                if (clickable != null && clickable != node) clickable.recycle();
                try { node.recycle(); } catch (Exception ignored) {}
            }
        }
        return exact.isEmpty() ? partial : exact;
    }

    private boolean isIgnoredText(CharSequence label, JSONArray ignoredTexts) {
        if (label == null || ignoredTexts == null || ignoredTexts.length() == 0) return false;
        String normalizedLabel = label.toString().trim().toLowerCase(java.util.Locale.ROOT);
        if (normalizedLabel.isEmpty()) return false;
        for (int index = 0; index < ignoredTexts.length(); index++) {
            String ignored = ignoredTexts.optString(index, "").trim()
                    .toLowerCase(java.util.Locale.ROOT);
            if (!ignored.isEmpty() && normalizedLabel.contains(ignored)) return true;
        }
        return false;
    }

    private String screenFrameSignature(AccessibilityNodeInfo root) {
        StringBuilder signature = new StringBuilder(1024);
        appendFrameSignature(root, signature, 0);
        return signature.toString();
    }

    private void appendFrameSignature(AccessibilityNodeInfo node, StringBuilder signature, int depth) {
        if (node == null || depth > 40) return;
        if (node.isVisibleToUser()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            signature.append(node.getPackageName()).append('|')
                    .append(node.getClassName()).append('|')
                    .append(node.getText()).append('|')
                    .append(node.getContentDescription()).append('|')
                    .append(bounds.flattenToString()).append(';');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                appendFrameSignature(child, signature, depth + 1);
            } finally {
                child.recycle();
            }
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

    // ── Enter key / IME action ────────────────────────────────────────────

    /**
     * Press the Enter / IME action key globally.
     * Strategy:
     *   1. IME action on input-focused node
     *   2. ACTION_CLICK on accessibility-focused node
     *   3. Find and click visible submit/go/search/done buttons by text
     *   4. Fallback: swipe-gesture on the Enter key region of the soft keyboard
     */
    public JSONObject pressEnter() {
        try {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return err("No active window");

            // ── 1. IME action on input-focused node ───────────────────────
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null) {
                boolean ok = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ok = focused.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                }
                focused.recycle();
                if (ok) {
                    root.recycle();
                    JSONObject r = new JSONObject();
                    r.put("success", true);
                    r.put("action", "press_enter_ime");
                    return r;
                }
            }

            // ── 2. Click on accessibility-focused node ────────────────────
            AccessibilityNodeInfo accFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (accFocused != null) {
                boolean ok = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ok = accFocused.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
                }
                if (!ok) ok = accFocused.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                accFocused.recycle();
                if (ok) {
                    root.recycle();
                    JSONObject r = new JSONObject();
                    r.put("success", true);
                    r.put("action", "press_enter_acc_focus");
                    return r;
                }
            }

            // ── 3. Find and click common submit/go/search buttons ─────────
            String[] submitLabels = {
                "Search", "search", "Go", "go", "Done", "done",
                "Send", "send", "Submit", "submit", "Next", "next",
                "OK", "Ok", "Confirm", "confirm", "Login", "Sign in",
                "Enter", "Return", "Find", "Proceed"
            };
            for (String label : submitLabels) {
                java.util.List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(label);
                if (nodes != null) {
                    for (AccessibilityNodeInfo n : nodes) {
                        CharSequence cls = n.getClassName();
                        boolean isButton = cls != null && (
                            cls.toString().contains("Button") ||
                            cls.toString().contains("ImageView") ||
                            cls.toString().contains("TextView"));
                        if ((n.isClickable() || isButton) && n.isEnabled()) {
                            boolean clicked = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            n.recycle();
                            if (clicked) {
                                for (AccessibilityNodeInfo rem : nodes) {
                                    try { rem.recycle(); } catch (Exception ignored) {}
                                }
                                root.recycle();
                                JSONObject r = new JSONObject();
                                r.put("success", true);
                                r.put("action", "press_enter_button");
                                r.put("matched", label);
                                return r;
                            }
                        }
                        n.recycle();
                    }
                }
            }

            // ── 4. Tap the Enter key area of the soft keyboard ────────────
            // The keyboard Enter key is typically in the bottom-right area of the screen
            int kbEnterX = (int)(screenW * 0.92);
            int kbEnterY = (int)(screenH * 0.94);
            Path path = new Path();
            path.moveTo(kbEnterX, kbEnterY);
            boolean gestureOk = dispatchPath(path, 80);

            root.recycle();
            JSONObject r = new JSONObject();
            r.put("success", gestureOk);
            r.put("action", "press_enter_gesture");
            if (!gestureOk) r.put("error", "Could not find focused input or submit button");
            return r;
        } catch (Exception e) {
            return err(e.getMessage());
        }
    }

    // ── Wake / Lock screen ────────────────────────────────────────────────

    /**
     * Wake the screen (turn it on) using ACQUIRE_CAUSES_WAKEUP WakeLock.
     * Requires WAKE_LOCK permission (already declared in manifest).
     */
    @SuppressWarnings("deprecation")
    public JSONObject wakeScreen() {
        try {
            android.os.PowerManager pm =
                (android.os.PowerManager) service.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                // FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP wakes the screen
                android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                    android.os.PowerManager.FULL_WAKE_LOCK
                        | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | android.os.PowerManager.ON_AFTER_RELEASE,
                    "com.task.tusker:WakeScreen");
                wl.acquire(3000L);
                wl.release();
            }
            JSONObject r = new JSONObject();
            r.put("success", true);
            r.put("action", "wake_screen");
            return r;
        } catch (Exception e) {
            return err("wake_screen: " + e.getMessage());
        }
    }

    /**
     * Lock / turn off the screen.
     * API 28+: uses GLOBAL_ACTION_LOCK_SCREEN (no device-admin needed).
     * API 21-27: requires DevicePolicyManager.lockNow() with admin — best effort.
     */
    public JSONObject lockScreen() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                boolean ok = service.performGlobalAction(
                        AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                JSONObject r = new JSONObject();
                r.put("success", ok);
                r.put("action", "screen_off");
                return r;
            } else {
                // Fallback: try DevicePolicyManager (only works if device admin granted)
                android.app.admin.DevicePolicyManager dpm =
                    (android.app.admin.DevicePolicyManager)
                        service.getSystemService(Context.DEVICE_POLICY_SERVICE);
                if (dpm != null) {
                    dpm.lockNow();
                    JSONObject r = new JSONObject();
                    r.put("success", true);
                    r.put("action", "screen_off");
                    return r;
                }
                return err("screen_off requires Android 9+ or Device Admin permission");
            }
        } catch (Exception e) {
            return err("screen_off: " + e.getMessage());
        }
    }

    // ── Screen dimensions ─────────────────────────────────────────────────

    public JSONObject getScreenInfo() {
        JSONObject r = new JSONObject();
        try { r.put("success", true); r.put("width", screenW); r.put("height", screenH); }
        catch (JSONException ignored) {}
        return r;
    }
}
