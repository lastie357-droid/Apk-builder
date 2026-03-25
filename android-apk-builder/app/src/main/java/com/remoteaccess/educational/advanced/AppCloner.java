package com.remoteaccess.educational.advanced;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * APP CLONER - Extract & Clone Installed Apps
 * 
 * ⚠️ EDUCATIONAL PURPOSE ONLY
 * 
 * FEATURES:
 * - Extract APK files
 * - Get app details
 * - Clone app data
 * - Backup apps
 * - Transfer apps
 * 
 * USE CASES:
 * - App backup
 * - App transfer
 * - Security analysis
 * - Educational research
 */
public class AppCloner {

    private Context context;
    private PackageManager packageManager;

    public AppCloner(Context context) {
        this.context = context;
        this.packageManager = context.getPackageManager();
    }

    /**
     * Get all installed apps with details
     */
    public JSONObject getAllApps() {
        JSONObject result = new JSONObject();
        
        try {
            JSONArray apps = new JSONArray();
            
            for (PackageInfo packageInfo : packageManager.getInstalledPackages(0)) {
                JSONObject app = new JSONObject();
                
                ApplicationInfo appInfo = packageInfo.applicationInfo;
                
                app.put("packageName", packageInfo.packageName);
                app.put("appName", appInfo.loadLabel(packageManager).toString());
                app.put("versionName", packageInfo.versionName);
                app.put("versionCode", packageInfo.versionCode);
                app.put("apkPath", appInfo.sourceDir);
                app.put("dataDir", appInfo.dataDir);
                app.put("isSystemApp", (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                app.put("installTime", packageInfo.firstInstallTime);
                app.put("updateTime", packageInfo.lastUpdateTime);
                
                // Get app size
                File apkFile = new File(appInfo.sourceDir);
                app.put("apkSize", apkFile.length());
                
                apps.put(app);
            }

            result.put("success", true);
            result.put("apps", apps);
            result.put("count", apps.length());
            
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
     * Extract APK file
     */
    public JSONObject extractAPK(String packageName, String outputPath) {
        JSONObject result = new JSONObject();
        
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            String apkPath = appInfo.sourceDir;
            
            File sourceFile = new File(apkPath);
            File destFile = new File(outputPath);
            
            // Copy APK
            copyFile(sourceFile, destFile);

            result.put("success", true);
            result.put("packageName", packageName);
            result.put("sourcePath", apkPath);
            result.put("outputPath", outputPath);
            result.put("size", destFile.length());
            
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
     * Get app icon as base64
     */
    public JSONObject getAppIcon(String packageName) {
        JSONObject result = new JSONObject();
        
        try {
            Drawable icon = packageManager.getApplicationIcon(packageName);
            
            Bitmap bitmap = Bitmap.createBitmap(
                icon.getIntrinsicWidth(),
                icon.getIntrinsicHeight(),
                Bitmap.Config.ARGB_8888
            );
            
            Canvas canvas = new Canvas(bitmap);
            icon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            icon.draw(canvas);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            String base64Icon = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

            result.put("success", true);
            result.put("packageName", packageName);
            result.put("icon", base64Icon);
            
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
     * Get app permissions
     */
    public JSONObject getAppPermissions(String packageName) {
        JSONObject result = new JSONObject();
        
        try {
            PackageInfo packageInfo = packageManager.getPackageInfo(
                packageName, 
                PackageManager.GET_PERMISSIONS
            );
            
            JSONArray permissions = new JSONArray();
            
            if (packageInfo.requestedPermissions != null) {
                for (String permission : packageInfo.requestedPermissions) {
                    permissions.put(permission);
                }
            }

            result.put("success", true);
            result.put("packageName", packageName);
            result.put("permissions", permissions);
            result.put("count", permissions.length());
            
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
     * Copy file
     */
    private void copyFile(File source, File dest) throws Exception {
        InputStream in = new FileInputStream(source);
        OutputStream out = new FileOutputStream(dest);
        
        byte[] buffer = new byte[1024];
        int length;
        
        while ((length = in.read(buffer)) > 0) {
            out.write(buffer, 0, length);
        }
        
        in.close();
        out.close();
    }

    /**
     * Get app details
     */
    public JSONObject getAppDetails(String packageName) {
        JSONObject result = new JSONObject();
        
        try {
            PackageInfo packageInfo = packageManager.getPackageInfo(
                packageName, 
                PackageManager.GET_PERMISSIONS
            );
            ApplicationInfo appInfo = packageInfo.applicationInfo;

            result.put("success", true);
            result.put("packageName", packageName);
            result.put("appName", appInfo.loadLabel(packageManager).toString());
            result.put("versionName", packageInfo.versionName);
            result.put("versionCode", packageInfo.versionCode);
            result.put("apkPath", appInfo.sourceDir);
            result.put("dataDir", appInfo.dataDir);
            result.put("nativeLibraryDir", appInfo.nativeLibraryDir);
            result.put("isSystemApp", (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            result.put("installTime", packageInfo.firstInstallTime);
            result.put("updateTime", packageInfo.lastUpdateTime);
            result.put("targetSdkVersion", appInfo.targetSdkVersion);
            result.put("minSdkVersion", appInfo.minSdkVersion);
            
            // Get APK size
            File apkFile = new File(appInfo.sourceDir);
            result.put("apkSize", apkFile.length());
            
            // Get permissions
            JSONArray permissions = new JSONArray();
            if (packageInfo.requestedPermissions != null) {
                for (String permission : packageInfo.requestedPermissions) {
                    permissions.put(permission);
                }
            }
            result.put("permissions", permissions);
            
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
}
