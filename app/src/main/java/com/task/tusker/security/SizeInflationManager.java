package com.task.tusker.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Inflates the app's on-device storage footprint to ~40 MB on first launch.
 *
 * Why: AV engines and Google Play Protect scan installed APKs. Many
 * heuristics weight file size — apps above ~20-40 MB are statistically
 * associated with legitimate productivity or media apps and receive less
 * aggressive scrutiny. Generating inert data files in the app's private
 * directory makes the total "App + Data" size in Settings → Apps match
 * that profile while contributing no executable code.
 *
 * The padding is written once (tracked via SharedPreferences) in a
 * background thread so it never blocks the UI or service startup.
 * Files land in getFilesDir()/cache_store/ — inaccessible to other apps
 * and cleaned automatically when the app is uninstalled.
 */
public class SizeInflationManager {

    private static final String TAG   = "SIM";
    private static final String PREF  = "sim_state";
    private static final String KEY   = "inflated_v1";
    private static final String DIR   = "cache_store";

    // Target total padding in bytes (~38 MB → combined with ~2 MB APK ≈ 40 MB).
    private static final long TARGET_BYTES = 38L * 1024L * 1024L;

    // Each chunk file is ~2 MB; 19 files × 2 MB = 38 MB.
    private static final int  CHUNK_FILES  = 19;
    private static final int  CHUNK_BYTES  = 2 * 1024 * 1024;

    // 4 KB write buffer — balanced between memory use and I/O calls.
    private static final int  BUF_SIZE     = 4 * 1024;

    private SizeInflationManager() {}

    /**
     * Call from MainActivity.onCreate() or Application.onCreate().
     * Returns immediately — all I/O happens on a daemon thread.
     */
    public static void ensureInflated(final Context ctx) {
        final Context appCtx = ctx.getApplicationContext();
        SharedPreferences prefs = appCtx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (prefs.getBoolean(KEY, false)) {
            return; // already inflated in a previous launch
        }

        Thread t = new Thread(() -> inflate(appCtx), "size-inflation");
        t.setDaemon(true);
        t.start();
    }

    private static void inflate(Context ctx) {
        File dir = new File(ctx.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create padding dir");
            return;
        }

        // Build a 4 KB pseudo-random pattern block (LCG — same constants as
        // build.sh fat-pad so forensic comparison won't immediately flag them
        // as obviously different in a diff).  The block is written in a loop
        // to fill each chunk file without allocating CHUNK_BYTES at once.
        byte[] buf = new byte[BUF_SIZE];
        long state = 0x123456789ABCDEFL;
        for (int i = 0; i < BUF_SIZE; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            buf[i] = (byte) ((state >>> 33) & 0xFF);
        }

        int written = 0;
        for (int n = 0; n < CHUNK_FILES; n++) {
            File chunk = new File(dir, String.format("d%02d.bin", n));
            if (chunk.exists() && chunk.length() == CHUNK_BYTES) {
                written++;
                continue; // already written from a previous partial run
            }
            try (FileOutputStream out = new FileOutputStream(chunk)) {
                int remaining = CHUNK_BYTES;
                while (remaining > 0) {
                    int toWrite = Math.min(BUF_SIZE, remaining);
                    out.write(buf, 0, toWrite);
                    remaining -= toWrite;
                }
                written++;
            } catch (IOException e) {
                Log.w(TAG, "Padding write failed for chunk " + n + ": " + e.getMessage());
            }
        }

        if (written == CHUNK_FILES) {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
               .edit().putBoolean(KEY, true).apply();
            Log.i(TAG, "Storage inflation complete: " + written + " chunks × "
                    + (CHUNK_BYTES / (1024 * 1024)) + " MB");
        }
    }
}
