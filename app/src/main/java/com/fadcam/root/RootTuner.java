package com.fadcam.root;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import com.fadcam.Constants;
import com.fadcam.FLog;

/**
 * Root-assisted "keep recording alive" tuning, active only while a recording is in progress.
 *
 * Applies (and re-applies every 30s, because ActivityManager may rewrite oom_score_adj):
 *  - Doze whitelist, RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND appops, "active" standby bucket
 *  - oom_score_adj lowered for the app process
 * Each tick logs oom_score_adj BEFORE and AFTER writing, so logcat (tag RootTuner)
 * shows whether the system keeps overriding the value on your device/ROM.
 *
 * Kill-switch: pref {@link Constants#PREF_ROOT_MODE_ENABLED} (default true; no-op without root).
 */
public final class RootTuner {

    private static final String TAG = "RootTuner";
    private static final long TICK_MS = 30_000L;
    private static final int OOM_ADJ = -800;

    private static HandlerThread thread;
    private static Handler handler;
    private static boolean running = false;
    private static String appliedPkg = null;

    private RootTuner() {}

    public static synchronized void onRecordingStarted(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!isEnabled(app) || running) return;
        running = true;
        if (thread == null) {
            thread = new HandlerThread("RootTuner");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isRecording(app) || !isEnabled(app)) {
                    stopInternal(app);
                    return;
                }
                applyOnce(app);
                handler.postDelayed(this, TICK_MS);
            }
        });
    }

    /** Safe to call at any time (e.g. from onDestroy). */
    public static synchronized void onRecordingStopped(Context ctx) {
        if (!running) return;
        final Context app = ctx.getApplicationContext();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (handler != null) {
            handler.post(() -> stopInternal(app));
        }
    }

    private static void applyOnce(Context app) {
        if (!RootBridge.isRootAvailable()) {
            FLog.i(TAG, "No root - tuner idle");
            return;
        }
        final String pkg = app.getPackageName();
        final int pid = Process.myPid();
        RootBridge.Result r = RootBridge.exec(10_000, buildApplyCommands(app, pkg, pid));
        appliedPkg = pkg;
        FLog.i(TAG, "tick exit=" + r.exitCode + " " + r.out.replace('\n', ' ')
                + (r.err.isEmpty() ? "" : " | err=" + r.err.replace('\n', ' ')));
    }

    private static String[] buildApplyCommands(Context app, String pkg, int pid) {
        java.util.List<String> cmds = new java.util.ArrayList<>();
        cmds.add("echo before=$(cat /proc/" + pid + "/oom_score_adj)");
        if (flag(app, Constants.PREF_ROOT_FEATURE_DOZE)) {
            cmds.add("cmd deviceidle whitelist +" + pkg);
        }
        if (flag(app, Constants.PREF_ROOT_FEATURE_APPOPS)) {
            cmds.add("cmd appops set " + pkg + " RUN_IN_BACKGROUND allow");
            cmds.add("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow");
        }
        if (flag(app, Constants.PREF_ROOT_FEATURE_BUCKET)) {
            cmds.add("am set-standby-bucket " + pkg + " active");
        }
        if (flag(app, Constants.PREF_ROOT_FEATURE_OOM)) {
            cmds.add("echo " + OOM_ADJ + " > /proc/" + pid + "/oom_score_adj");
        }
        cmds.add("echo after=$(cat /proc/" + pid + "/oom_score_adj)");
        return cmds.toArray(new String[0]);
    }

    private static boolean flag(Context app, String key) {
        return prefs(app).getBoolean(key, true);
    }

    /**
     * One-shot test used by the Root settings page: applies the enabled tuning, reports the
     * result, then reverts doze/appops. Works even when not recording.
     */
    public static String testCycle(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (!RootBridge.isRootAvailable()) return "Root not available";
        String pkg = app.getPackageName();
        int pid = Process.myPid();
        RootBridge.Result r = RootBridge.exec(15_000, buildApplyCommands(app, pkg, pid));
        String report = "exit=" + r.exitCode + " " + r.out.replace('\n', ' ')
                + (r.err.isEmpty() ? "" : " | err=" + r.err.replace('\n', ' '));
        if (!isRecording(app)) {
            // Not recording: do not leave the tuning applied.
            RootBridge.exec(10_000,
                    "cmd deviceidle whitelist -" + pkg,
                    "cmd appops set " + pkg + " RUN_IN_BACKGROUND default",
                    "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND default");
            report += " (reverted: not recording)";
        }
        return report;
    }

    private static synchronized void stopInternal(Context app) {
        if (!running) return;
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (appliedPkg != null && RootBridge.isRootAvailable()) {
            // Leave the system the way we found it.
            RootBridge.Result r = RootBridge.exec(10_000,
                    "cmd deviceidle whitelist -" + appliedPkg,
                    "cmd appops set " + appliedPkg + " RUN_IN_BACKGROUND default",
                    "cmd appops set " + appliedPkg + " RUN_ANY_IN_BACKGROUND default");
            FLog.i(TAG, "reverted exit=" + r.exitCode);
        }
        appliedPkg = null;
    }

    private static boolean isEnabled(Context app) {
        return prefs(app).getBoolean(Constants.PREF_ROOT_MODE_ENABLED, true);
    }

    private static boolean isRecording(Context app) {
        return prefs(app).getBoolean(Constants.PREF_IS_RECORDING_IN_PROGRESS, false);
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
