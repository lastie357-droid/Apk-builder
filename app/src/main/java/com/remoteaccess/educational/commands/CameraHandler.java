package com.remoteaccess.educational.commands;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Base64;
import androidx.core.app.ActivityCompat;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Camera Handler - Take photos
 */
public class CameraHandler {

    private Context context;
    private CameraManager cameraManager;

    public CameraHandler(Context context) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    /**
     * Get available cameras
     */
    public JSONObject getAvailableCameras() {
        JSONObject result = new JSONObject();
        
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
                result.put("success", false);
                result.put("error", "CAMERA permission not granted");
                return result;
            }

            String[] cameraIds = cameraManager.getCameraIdList();
            JSONArray cameras = new JSONArray();

            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                
                JSONObject camera = new JSONObject();
                camera.put("id", cameraId);
                
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null) {
                    if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                        camera.put("facing", "Front");
                    } else if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        camera.put("facing", "Back");
                    } else {
                        camera.put("facing", "External");
                    }
                }
                
                cameras.put(camera);
            }

            result.put("success", true);
            result.put("cameras", cameras);
            result.put("count", cameras.length());
            
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
     * Take photo (simplified - requires CameraX or Camera2 API implementation)
     * This is a placeholder that shows the structure
     */
    public JSONObject takePhoto(String cameraId, String quality) {
        JSONObject result = new JSONObject();
        
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) 
                != PackageManager.PERMISSION_GRANTED) {
                result.put("success", false);
                result.put("error", "CAMERA permission not granted");
                return result;
            }

            // Note: Full implementation requires CameraX or Camera2 API
            // This is a simplified placeholder
            result.put("success", false);
            result.put("error", "Camera capture requires full Camera2 API implementation");
            result.put("message", "Use CameraX library for production implementation");
            
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
        return Base64.encodeToString(byteArray, Base64.DEFAULT);
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
