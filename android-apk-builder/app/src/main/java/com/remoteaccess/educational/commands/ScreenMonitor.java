package com.remoteaccess.educational.commands;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * EDUCATIONAL SCREEN MONITOR
 * 
 * ⚠️ DISCLAIMER: FOR EDUCATIONAL PURPOSES ONLY
 * - Requires explicit user permission via MediaProjection
 * - User sees "Screen recording" notification
 * - Can be stopped anytime from notification
 * - Visible in Android status bar
 * 
 * USE CASES:
 * - Screen recording for tutorials
 * - Remote desktop support
 * - Accessibility features
 * - Educational demonstrations
 */
public class ScreenMonitor {

    private Context context;
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Handler handler;
    
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    
    private static final int REQUEST_CODE = 1000;

    public ScreenMonitor(Context context) {
        this.context = context;
        this.projectionManager = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        this.handler = new Handler(Looper.getMainLooper());
        
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        this.screenWidth = metrics.widthPixels;
        this.screenHeight = metrics.heightPixels;
        this.screenDensity = metrics.densityDpi;
    }

    /**
     * Request screen capture permission
     * User must approve this in a dialog
     */
    public Intent getPermissionIntent() {
        return projectionManager.createScreenCaptureIntent();
    }

    /**
     * Start screen monitoring after permission granted
     */
    public JSONObject startMonitoring(int resultCode, Intent data) {
        JSONObject result = new JSONObject();
        
        try {
            if (resultCode != Activity.RESULT_OK || data == null) {
                result.put("success", false);
                result.put("error", "Permission denied by user");
                return result;
            }

            mediaProjection = projectionManager.getMediaProjection(resultCode, data);
            
            if (mediaProjection == null) {
                result.put("success", false);
                result.put("error", "Failed to create MediaProjection");
                return result;
            }

            // Create ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth, 
                screenHeight, 
                PixelFormat.RGBA_8888, 
                2
            );

            // Create VirtualDisplay
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenMonitor",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler
            );

            result.put("success", true);
            result.put("message", "Screen monitoring started");
            result.put("width", screenWidth);
            result.put("height", screenHeight);
            result.put("note", "User will see 'Screen recording' notification");
            
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
     * Capture current screen
     */
    public JSONObject captureScreen() {
        JSONObject result = new JSONObject();
        
        try {
            if (imageReader == null) {
                result.put("success", false);
                result.put("error", "Screen monitoring not started");
                return result;
            }

            Image image = imageReader.acquireLatestImage();
            
            if (image == null) {
                result.put("success", false);
                result.put("error", "No image available");
                return result;
            }

            // Convert Image to Bitmap
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            Bitmap bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);
            image.close();

            // Convert to base64
            String base64 = bitmapToBase64(bitmap, 70);
            
            // Save to file
            String filename = "screenshot_" + System.currentTimeMillis() + ".jpg";
            String filePath = saveBitmapToFile(bitmap, filename);

            result.put("success", true);
            result.put("base64", base64);
            result.put("filePath", filePath);
            result.put("width", bitmap.getWidth());
            result.put("height", bitmap.getHeight());
            result.put("timestamp", System.currentTimeMillis());
            
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
     * Stop screen monitoring
     */
    public JSONObject stopMonitoring() {
        JSONObject result = new JSONObject();
        
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
            
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            
            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }

            result.put("success", true);
            result.put("message", "Screen monitoring stopped");
            
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
     * Check if monitoring is active
     */
    public boolean isMonitoring() {
        return mediaProjection != null && virtualDisplay != null;
    }

    /**
     * Convert bitmap to base64
     */
    private String bitmapToBase64(Bitmap bitmap, int quality) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        return Base64.encodeToString(byteArray, Base64.NO_WRAP);
    }

    /**
     * Save bitmap to file
     */
    private String saveBitmapToFile(Bitmap bitmap, String filename) {
        try {
            File file = new File(context.getExternalFilesDir(null), filename);
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.close();
            return file.getAbsolutePath();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
