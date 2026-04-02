package com.remoteaccess.educational.accessibility;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight socket client for the accessibility APK.
 * Maintains a persistent TCP connection to the Node.js server, independent
 * of the main app. If the main app is killed, this keeps running.
 */
public class SocketClient {

    private static final String TAG = "AccessSocketClient";
    private static final int    RECONNECT_DELAY_MS = 5000;
    private static final int    KEEPALIVE_INTERVAL = 30000;

    private final Context context;
    private final AtomicBoolean running  = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private Socket       socket;
    private PrintWriter  writer;
    private ExecutorService executor = Executors.newCachedThreadPool();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private CommandHandler commandHandler;

    public interface CommandHandler {
        void onCommand(String command, JSONObject params, String commandId);
    }

    public SocketClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setCommandHandler(CommandHandler handler) {
        this.commandHandler = handler;
    }

    public void start() {
        if (running.getAndSet(true)) return;
        stopping.set(false);
        executor.execute(this::connectLoop);
    }

    public void stop() {
        stopping.set(true);
        running.set(false);
        closeSocket();
    }

    private void connectLoop() {
        while (!stopping.get()) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(
                        ConfigActivity.PREFS, Context.MODE_PRIVATE);
                String host = prefs.getString(ConfigActivity.KEY_SERVER, "");
                int    port = prefs.getInt(ConfigActivity.KEY_PORT, 6000);
                String deviceId = prefs.getString(ConfigActivity.KEY_DEVICE, android.provider.Settings.Secure.getString(
                        context.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID));

                if (host.isEmpty()) {
                    Log.w(TAG, "No server host configured — retrying in 10s");
                    Thread.sleep(10000);
                    continue;
                }

                Log.i(TAG, "Connecting to " + host + ":" + port);
                socket = new Socket(host, port);
                socket.setKeepAlive(true);
                socket.setSoTimeout(60000);
                writer = new PrintWriter(socket.getOutputStream(), true);

                // Register this accessibility APK as a device
                JSONObject reg = new JSONObject();
                reg.put("event", "device:register");
                JSONObject payload = new JSONObject();
                payload.put("deviceId", deviceId + "_accessibility");
                payload.put("type", "accessibility_apk");
                reg.put("data", payload);
                writer.println(reg.toString());

                Log.i(TAG, "Connected to server");

                // Read loop
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));
                String line;
                while (!stopping.get() && (line = reader.readLine()) != null) {
                    final String msg = line.trim();
                    if (!msg.isEmpty()) {
                        executor.execute(() -> handleMessage(msg));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.e(TAG, "Socket error: " + e.getMessage());
            }

            if (!stopping.get()) {
                try { Thread.sleep(RECONNECT_DELAY_MS); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void handleMessage(String raw) {
        try {
            JSONObject msg  = new JSONObject(raw);
            String event    = msg.optString("event", "");
            JSONObject data = msg.optJSONObject("data");
            if (data == null) data = new JSONObject();

            if ("command:send".equals(event)) {
                String command   = data.optString("command", "");
                String commandId = data.optString("commandId", "");
                JSONObject params = data.optJSONObject("params");
                if (params == null) params = new JSONObject();
                if (commandHandler != null) {
                    commandHandler.onCommand(command, params, commandId);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "handleMessage error: " + e.getMessage());
        }
    }

    public void sendResult(String commandId, String command, boolean success, JSONObject response) {
        executor.execute(() -> {
            try {
                if (writer == null) return;
                JSONObject msg  = new JSONObject();
                msg.put("event", "command:result");
                JSONObject data = new JSONObject();
                data.put("commandId", commandId);
                data.put("command", command);
                data.put("success", success);
                if (response != null) data.put("response", response);
                msg.put("data", data);
                writer.println(msg.toString());
            } catch (Exception e) {
                Log.e(TAG, "sendResult error: " + e.getMessage());
            }
        });
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        writer = null;
    }
}
