package com.task.tusker;

import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.task.tusker.commands.MediaProjectionHolder;

/**
 * Transparent one-shot activity that shows the system
 * "Start recording / casting?" consent dialog required by
 * {@link android.media.projection.MediaProjectionManager}.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Operator sends {@code request_screen_capture_permission} from the dashboard.</li>
 *   <li>{@code SocketManager} launches this activity with {@code FLAG_ACTIVITY_NEW_TASK}.</li>
 *   <li>System presents its standard "This app will have access to everything on your screen"
 *       dialog.  The device owner must tap <em>Start now</em> to approve.</li>
 *   <li>On approval the result is forwarded to {@link MediaProjectionHolder} which keeps the
 *       session alive and starts delivering frames from
 *       {@link MediaProjectionHolder#captureFrame()}.</li>
 *   <li>On denial the activity finishes silently; no session is started.</li>
 * </ol>
 *
 * <p>Once approved, the stream continues until the user taps "Stop" on the system
 * notification chip or the app process is killed.  The operator can re-request at any
 * time to restart the session.</p>
 */
public class MediaProjectionConsentActivity extends AppCompatActivity {

    private static final int REQ_SCREEN_CAPTURE = 8765;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            finish();
            return;
        }

        // Nothing to do if a session is already running — avoid showing a second dialog
        if (MediaProjectionHolder.getInstance().isAvailable()) {
            finish();
            return;
        }

        // Show the system "Start recording?" consent dialog
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCREEN_CAPTURE
                && resultCode == RESULT_OK
                && data != null) {
            // Hand the approved token to the singleton — this starts the VirtualDisplay
            MediaProjectionHolder.getInstance().start(getApplicationContext(), resultCode, data);
        }
        // Always finish so this invisible activity does not linger in the back stack
        finish();
    }
}
