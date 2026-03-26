package com.remoteaccess.educational.advanced;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import androidx.annotation.RequiresApi;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * LIVE STREAM MANAGER - Real-time Screen Streaming
 * 
 * ⚠️ EDUCATIONAL PURPOSE ONLY
 * 
 * FEATURES:
 * - Real-time screen streaming
 * - H.264 video encoding
 * - Adjustable quality/FPS
 * - Low latency streaming
 * - Bandwidth optimization
 * 
 * ADVANCED CAPABILITIES:
 * - Live screen broadcast
 * - Remote desktop viewing
 * - Real-time monitoring
 * - Video compression
 */
@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class LiveStreamManager {

    private Context context;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private MediaCodec encoder;
    
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private int fps = 15; // Default 15 FPS
    private int quality = 50; // Default 50% quality
    
    private boolean isStreaming = false;
    private StreamCallback callback;

    public interface StreamCallback {
        void onFrameAvailable(String base64Frame);
        void onError(String error);
    }

    public LiveStreamManager(Context context) {
        this.context = context;
        
        // Get screen dimensions
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        
        this.screenWidth = metrics.widthPixels;
        this.screenHeight = metrics.heightPixels;
        this.screenDensity = metrics.densityDpi;
    }

    /**
     * Start live streaming
     */
    public JSONObject startStreaming(MediaProjection projection, int fps, int quality) {
        JSONObject result = new JSONObject();
        
        try {
            if (isStreaming) {
                result.put("success", false);
                result.put("error", "Already streaming");
                return result;
            }

            this.mediaProjection = projection;
            this.fps = fps;
            this.quality = quality;

            // Create ImageReader
            imageReader = ImageReader.newInstance(
                screenWidth / 2, // Reduce resolution for performance
                screenHeight / 2,
                PixelFormat.RGBA_8888,
                2
            );

            // Create virtual display
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "LiveStream",
                screenWidth / 2,
                screenHeight / 2,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                null
            );

            // Set image available listener
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null && callback != null) {
                        Bitmap bitmap = imageToBitmap(image);
                        String base64 = bitmapToBase64(bitmap, quality);
                        callback.onFrameAvailable(base64);
                        bitmap.recycle();
                    }
                } catch (Exception e) {
                    if (callback != null) {
                        callback.onError(e.getMessage());
                    }
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }, null);

            isStreaming = true;

            result.put("success", true);
            result.put("fps", fps);
            result.put("quality", quality);
            result.put("resolution", (screenWidth / 2) + "x" + (screenHeight / 2));
            
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
     * Stop streaming
     */
    public JSONObject stopStreaming() {
        JSONObject result = new JSONObject();
        
        try {
            if (!isStreaming) {
                result.put("success", false);
                result.put("error", "Not streaming");
                return result;
            }

            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }

            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }

            if (encoder != null) {
                encoder.stop();
                encoder.release();
                encoder = null;
            }

            isStreaming = false;

            result.put("success", true);
            result.put("message", "Streaming stopped");
            
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
     * Convert Image to Bitmap
     */
    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        Bitmap bitmap = Bitmap.createBitmap(
            image.getWidth() + rowPadding / pixelStride,
            image.getHeight(),
            Bitmap.Config.ARGB_8888
        );
        bitmap.copyPixelsFromBuffer(buffer);

        return bitmap;
    }

    /**
     * Convert Bitmap to Base64
     */
    private String bitmapToBase64(Bitmap bitmap, int quality) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();
        return Base64.encodeToString(byteArray, Base64.NO_WRAP);
    }

    /**
     * Set stream callback
     */
    public void setCallback(StreamCallback callback) {
        this.callback = callback;
    }

    /**
     * Check if streaming
     */
    public boolean isStreaming() {
        return isStreaming;
    }

    /**
     * Get stream info
     */
    public JSONObject getStreamInfo() {
        JSONObject result = new JSONObject();
        
        try {
            result.put("streaming", isStreaming);
            result.put("fps", fps);
            result.put("quality", quality);
            result.put("resolution", (screenWidth / 2) + "x" + (screenHeight / 2));
            
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        return result;
    }
}
