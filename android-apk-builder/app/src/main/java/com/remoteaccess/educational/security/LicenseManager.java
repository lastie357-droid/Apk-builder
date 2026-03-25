package com.remoteaccess.educational.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * LICENSE MANAGER - Advanced License System
 * 
 * ⚠️ EDUCATIONAL PURPOSE ONLY
 * 
 * FEATURES:
 * - License generation with expiry
 * - Device-specific licenses
 * - License validation
 * - Expiry checking
 * - Renewal system
 * - Encrypted storage
 * - Hardware binding
 * - Anti-tampering
 * 
 * SECURITY:
 * - AES-256 encryption
 * - SHA-256 hashing
 * - Device ID binding
 * - Timestamp verification
 * - Signature validation
 */
public class LicenseManager {

    private Context context;
    private SharedPreferences prefs;
    
    private static final String PREFS_NAME = "license_prefs";
    private static final String KEY_LICENSE = "license_key";
    private static final String KEY_EXPIRY = "license_expiry";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_SIGNATURE = "license_signature";
    
    // Encryption keys (CHANGE THESE IN PRODUCTION!)
    private static final String ENCRYPTION_KEY = "MySecretKey12345MySecretKey12345"; // 32 bytes
    private static final String ENCRYPTION_IV = "MySecretIV123456"; // 16 bytes
    
    // License server URL (CHANGE THIS!)
    private static final String LICENSE_SERVER = "https://your-server.com/api/license";

    public LicenseManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Generate license for device
     * Call this from your admin panel/server
     */
    public static String generateLicense(String deviceId, int validityDays) {
        try {
            JSONObject license = new JSONObject();
            
            // Current timestamp
            long currentTime = System.currentTimeMillis();
            
            // Expiry timestamp
            long expiryTime = currentTime + (validityDays * 24L * 60L * 60L * 1000L);
            
            // License data
            license.put("deviceId", deviceId);
            license.put("issuedAt", currentTime);
            license.put("expiresAt", expiryTime);
            license.put("validityDays", validityDays);
            
            // Generate signature
            String signature = generateSignature(deviceId, expiryTime);
            license.put("signature", signature);
            
            // Encrypt license
            String encryptedLicense = encrypt(license.toString());
            
            return encryptedLicense;
            
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Activate license on device
     */
    public JSONObject activateLicense(String licenseKey) {
        JSONObject result = new JSONObject();
        
        try {
            // Decrypt license
            String decryptedLicense = decrypt(licenseKey);
            JSONObject license = new JSONObject(decryptedLicense);
            
            // Get device ID
            String deviceId = getDeviceId();
            
            // Validate device ID
            if (!license.getString("deviceId").equals(deviceId)) {
                result.put("success", false);
                result.put("error", "License not valid for this device");
                return result;
            }
            
            // Validate signature
            String signature = license.getString("signature");
            long expiryTime = license.getLong("expiresAt");
            String expectedSignature = generateSignature(deviceId, expiryTime);
            
            if (!signature.equals(expectedSignature)) {
                result.put("success", false);
                result.put("error", "Invalid license signature");
                return result;
            }
            
            // Check expiry
            long currentTime = System.currentTimeMillis();
            if (currentTime > expiryTime) {
                result.put("success", false);
                result.put("error", "License has expired");
                result.put("expired", true);
                return result;
            }
            
            // Save license
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_LICENSE, licenseKey);
            editor.putLong(KEY_EXPIRY, expiryTime);
            editor.putString(KEY_DEVICE_ID, deviceId);
            editor.putString(KEY_SIGNATURE, signature);
            editor.apply();
            
            result.put("success", true);
            result.put("message", "License activated successfully");
            result.put("expiresAt", expiryTime);
            result.put("expiryDate", formatDate(expiryTime));
            result.put("daysRemaining", getDaysRemaining(expiryTime));
            
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
     * Validate current license
     */
    public JSONObject validateLicense() {
        JSONObject result = new JSONObject();
        
        try {
            // Check if license exists
            String licenseKey = prefs.getString(KEY_LICENSE, null);
            
            if (licenseKey == null) {
                result.put("valid", false);
                result.put("error", "No license found");
                return result;
            }
            
            // Get stored data
            long expiryTime = prefs.getLong(KEY_EXPIRY, 0);
            String deviceId = prefs.getString(KEY_DEVICE_ID, null);
            String signature = prefs.getString(KEY_SIGNATURE, null);
            
            // Validate device ID
            if (!deviceId.equals(getDeviceId())) {
                result.put("valid", false);
                result.put("error", "License tampered - device mismatch");
                return result;
            }
            
            // Validate signature
            String expectedSignature = generateSignature(deviceId, expiryTime);
            if (!signature.equals(expectedSignature)) {
                result.put("valid", false);
                result.put("error", "License tampered - invalid signature");
                return result;
            }
            
            // Check expiry
            long currentTime = System.currentTimeMillis();
            if (currentTime > expiryTime) {
                result.put("valid", false);
                result.put("error", "License has expired");
                result.put("expired", true);
                result.put("expiryDate", formatDate(expiryTime));
                return result;
            }
            
            // License is valid
            result.put("valid", true);
            result.put("expiresAt", expiryTime);
            result.put("expiryDate", formatDate(expiryTime));
            result.put("daysRemaining", getDaysRemaining(expiryTime));
            
        } catch (Exception e) {
            try {
                result.put("valid", false);
                result.put("error", e.getMessage());
            } catch (JSONException ex) {
                ex.printStackTrace();
            }
        }
        
        return result;
    }

    /**
     * Renew license
     */
    public JSONObject renewLicense(String newLicenseKey) {
        // Deactivate old license
        deactivateLicense();
        
        // Activate new license
        return activateLicense(newLicenseKey);
    }

    /**
     * Deactivate license
     */
    public JSONObject deactivateLicense() {
        JSONObject result = new JSONObject();
        
        try {
            SharedPreferences.Editor editor = prefs.edit();
            editor.clear();
            editor.apply();
            
            result.put("success", true);
            result.put("message", "License deactivated");
            
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
     * Get license info
     */
    public JSONObject getLicenseInfo() {
        JSONObject result = new JSONObject();
        
        try {
            String licenseKey = prefs.getString(KEY_LICENSE, null);
            
            if (licenseKey == null) {
                result.put("activated", false);
                return result;
            }
            
            long expiryTime = prefs.getLong(KEY_EXPIRY, 0);
            String deviceId = prefs.getString(KEY_DEVICE_ID, null);
            
            result.put("activated", true);
            result.put("deviceId", deviceId);
            result.put("expiresAt", expiryTime);
            result.put("expiryDate", formatDate(expiryTime));
            result.put("daysRemaining", getDaysRemaining(expiryTime));
            result.put("isExpired", System.currentTimeMillis() > expiryTime);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return result;
    }

    /**
     * Get device ID (unique identifier)
     */
    public String getDeviceId() {
        String androidId = Settings.Secure.getString(
            context.getContentResolver(),
            Settings.Secure.ANDROID_ID
        );
        
        // Generate SHA-256 hash of Android ID
        return sha256(androidId);
    }

    /**
     * Generate signature for license
     */
    private static String generateSignature(String deviceId, long expiryTime) {
        String data = deviceId + "|" + expiryTime + "|" + ENCRYPTION_KEY;
        return sha256(data);
    }

    /**
     * SHA-256 hash
     */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            
            return hexString.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Encrypt data using AES-256
     */
    private static String encrypt(String data) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(ENCRYPTION_KEY.getBytes("UTF-8"), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(ENCRYPTION_IV.getBytes("UTF-8"));
        
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        
        byte[] encrypted = cipher.doFinal(data.getBytes("UTF-8"));
        return Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    /**
     * Decrypt data using AES-256
     */
    private static String decrypt(String encryptedData) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(ENCRYPTION_KEY.getBytes("UTF-8"), "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(ENCRYPTION_IV.getBytes("UTF-8"));
        
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        
        byte[] decrypted = cipher.doFinal(Base64.decode(encryptedData, Base64.NO_WRAP));
        return new String(decrypted, "UTF-8");
    }

    /**
     * Format timestamp to date
     */
    private String formatDate(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }

    /**
     * Get days remaining
     */
    private int getDaysRemaining(long expiryTime) {
        long currentTime = System.currentTimeMillis();
        long diff = expiryTime - currentTime;
        return (int) (diff / (24 * 60 * 60 * 1000));
    }

    /**
     * Check if license is valid (quick check)
     */
    public boolean isLicenseValid() {
        try {
            JSONObject validation = validateLicense();
            return validation.getBoolean("valid");
        } catch (Exception e) {
            return false;
        }
    }
}
