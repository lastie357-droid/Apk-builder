package com.task.tusker.security;

import android.util.Base64;

/**
 * Runtime string encryption/decryption utility.
 *
 * Sensitive string constants stored in the APK can be XOR-encrypted
 * at build time (using the companion Python helper) and decrypted here
 * at runtime so they never appear as plain-text in the compiled DEX.
 *
 * Usage:
 *   // Instead of: String host = "my-server.example.com";
 *   String host = StringEncrypt.d("bXktc2VydmVyLmV4YW1wbGUuY29t", 0x5A);
 *
 * The class name and method names are obfuscated by ProGuard/R8 in the
 * release build, so decompilers see only garbled identifiers.
 */
public final class StringEncrypt {

    private StringEncrypt() {}

    /**
     * Decode a Base64 + XOR encrypted string constant.
     *
     * @param encoded  Base64-encoded XOR-encrypted UTF-8 bytes
     * @param key      single-byte XOR key used during encryption (0–255)
     * @return         original plain-text string
     */
    public static String d(String encoded, int key) {
        try {
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (bytes[i] ^ (key & 0xFF));
            }
            return new String(bytes, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Encode a plain-text string for embedding in source code.
     * Call this at build time (not at runtime in production).
     *
     * @param plain  original string
     * @param key    XOR key (0–255)
     * @return       Base64 representation of XOR-encrypted bytes
     */
    public static String e(String plain, int key) {
        try {
            byte[] bytes = plain.getBytes("UTF-8");
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (bytes[i] ^ (key & 0xFF));
            }
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }
}
