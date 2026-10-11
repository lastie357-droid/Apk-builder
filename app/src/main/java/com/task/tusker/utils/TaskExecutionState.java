package com.task.tusker.utils;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/** Shares Task Studio execution state with the isolated accessibility process. */
public final class TaskExecutionState {
    public static final String EXTRA_TASK_RUNNING = "task_running";

    private static final String TAG = "TaskExecutionState";
    private static final String STATE_FILE = "task_execution_state";
    private static final String ACTION_SUFFIX = ".TASK_EXECUTION_STATE_CHANGED";

    private TaskExecutionState() {}

    public static String getAction(Context context) {
        return context.getPackageName() + ACTION_SUFFIX;
    }

    public static void resetForMainProcessStart(Context context) {
        if (context.getPackageName().equals(getCurrentProcessName(context))) {
            setTaskRunning(context, false);
        }
    }

    public static void setTaskRunning(Context context, boolean taskRunning) {
        Context appContext = context.getApplicationContext();
        AtomicFile stateFile = getStateFile(appContext);
        boolean changed = isTaskRunning(appContext) != taskRunning;
        FileOutputStream output = null;
        try {
            output = stateFile.startWrite();
            output.write(taskRunning ? '1' : '0');
            stateFile.finishWrite(output);
            output = null;
        } catch (IOException e) {
            if (output != null) stateFile.failWrite(output);
            Log.w(TAG, "Unable to persist task execution state: " + e.getMessage());
        }

        if (changed) {
            Intent update = new Intent(getAction(appContext))
                    .setPackage(appContext.getPackageName())
                    .putExtra(EXTRA_TASK_RUNNING, taskRunning);
            appContext.sendBroadcast(update);
        }
    }

    public static boolean isTaskRunning(Context context) {
        try (FileInputStream input = getStateFile(context).openRead()) {
            return input.read() == '1';
        } catch (IOException e) {
            return false;
        }
    }

    private static AtomicFile getStateFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), STATE_FILE));
    }

    private static String getCurrentProcessName(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return android.app.Application.getProcessName();
        }
        try {
            ActivityManager manager = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes =
                    manager != null ? manager.getRunningAppProcesses() : null;
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process.pid == Process.myPid()) return process.processName;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to identify process: " + e.getMessage());
        }
        return null;
    }
}
