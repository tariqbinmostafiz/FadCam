package com.fadcam.root;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.fadcam.Constants;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Self-test for everything the root layer depends on. Blocking: call from a background thread. */
public final class RootDiagnostics {

    public enum Level { OK, WARN, FAIL, INFO }

    public static final class Check {
        @NonNull public final String name;
        @NonNull public final Level level;
        @NonNull public final String detail;

        Check(String name, Level level, String detail) {
            this.name = name;
            this.level = level;
            this.detail = detail == null ? "" : detail;
        }
    }

    private RootDiagnostics() {}

    public static List<Check> run(Context ctx) {
        Context app = ctx.getApplicationContext();
        String pkg = app.getPackageName();
        int pid = Process.myPid();
        List<Check> out = new ArrayList<>();

        out.add(new Check("Device", Level.INFO, Build.MANUFACTURER + " " + Build.MODEL
                + " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") | " + pkg));

        // ---- Root access ----
        RootBridge.Result id = RootBridge.exec(10_000, "id");
        boolean root = id.ok() && id.out.contains("uid=0");
        if (root) {
            out.add(new Check("Root access", Level.OK, "granted (" + firstLine(id.out) + ")"));
        } else {
            out.add(new Check("Root access", Level.FAIL, "not granted: " + (id.err.isEmpty() ? id.out : id.err)
                    + " | Allow FadCam in your root manager (Magisk / KernelSU / APatch) and re-run."));
        }

        if (root) {
            RootBridge.Result prov = RootBridge.exec(10_000,
                    "echo \"su: $(su -v 2>&1 | head -1)\"",
                    "echo \"ksud: $(ksud -V 2>&1 | head -1)\"",
                    "echo \"magisk: $(magisk -V 2>&1 | head -1)\"");
            out.add(new Check("Root provider", Level.INFO, prov.out.replace('\n', ' ').replace("  ", " ")));

            RootBridge.Result se = RootBridge.exec(8_000, "getenforce");
            out.add(new Check("SELinux", Level.INFO, se.out));

            RootBridge.Result oom = RootBridge.exec(10_000,
                    "orig=$(cat /proc/" + pid + "/oom_score_adj)",
                    "echo -800 > /proc/" + pid + "/oom_score_adj",
                    "now=$(cat /proc/" + pid + "/oom_score_adj)",
                    "echo $orig > /proc/" + pid + "/oom_score_adj",
                    "echo orig=$orig now=$now");
            if (oom.out.contains("now=-800")) {
                out.add(new Check("oom_score_adj write", Level.OK, oom.out
                        + " (writable; Android may rewrite it - watch the RootTuner log while recording)"));
            } else {
                out.add(new Check("oom_score_adj write", Level.WARN, oom.out + " " + oom.err));
            }

            boolean recording = isRecording(app);
            RootBridge.Result tune = RootBridge.exec(10_000,
                    "echo doze=$(cmd deviceidle whitelist 2>/dev/null | grep -c " + pkg + ")",
                    "echo appops=$(cmd appops get " + pkg + " RUN_ANY_IN_BACKGROUND 2>&1 | head -1)",
                    "echo bucket=$(am get-standby-bucket " + pkg + " 2>&1 | head -1)");
            out.add(new Check("Background protection", Level.INFO,
                    tune.out.replace('\n', ' | ')
                            + (recording ? "" : " | (tuning is applied only while recording)")));

            RootBridge.Result mod = RootBridge.exec(10_000,
                    "m=/data/adb/modules/fadcam_guardian",
                    "[ -f $m/module.prop ] && echo installed || echo missing",
                    "[ -f $m/disable ] && echo module_disabled",
                    "[ -f /data/local/tmp/fadcam_guardian.disable ] && echo paused_by_file",
                    "echo running=$(pgrep -f fadcam_guardian 2>/dev/null | head -1)",
                    "tail -n 3 /data/local/tmp/fadcam_guardian.log 2>/dev/null");
            boolean installed = mod.out.contains("installed");
            boolean running = mod.out.matches("(?s).*running=\\d+.*");
            if (!installed) {
                out.add(new Check("Guardian module", Level.INFO,
                        "not installed (optional: only needed for reboot / OEM-kill resume)"));
            } else if (mod.out.contains("module_disabled") || mod.out.contains("paused_by_file")) {
                out.add(new Check("Guardian module", Level.WARN, "installed but paused | " + oneLine(mod.out)));
            } else if (!running) {
                out.add(new Check("Guardian module", Level.WARN,
                        "installed but not running (reboot once after installing) | " + oneLine(mod.out)));
            } else {
                out.add(new Check("Guardian module", Level.OK, oneLine(mod.out)));
            }
        }

        // ---- Non-root checks ----
        boolean cam = permission(app, android.Manifest.permission.CAMERA);
        out.add(new Check("Camera permission", cam ? Level.OK : Level.FAIL, cam ? "granted" : "missing"));
        boolean mic = permission(app, android.Manifest.permission.RECORD_AUDIO);
        out.add(new Check("Microphone permission", mic ? Level.OK : Level.WARN,
                mic ? "granted" : "missing (audio recording will not work)"));
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(pkg);
            out.add(new Check("Battery optimization", ignoring ? Level.OK : Level.INFO,
                    ignoring ? "ignored (good)" : "optimized (root tuning or a manual exemption is recommended)"));
        } catch (Exception ignored) {
        }
        out.add(new Check("Root mode setting", Level.INFO, String.valueOf(app.getSharedPreferences(
                Constants.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(Constants.PREF_ROOT_MODE_ENABLED, true))));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> reasons = am.getHistoricalProcessExitReasons(pkg, 0, 5);
                StringBuilder sb = new StringBuilder();
                SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
                for (ApplicationExitInfo e : reasons) {
                    if (sb.length() > 0) sb.append(" | ");
                    sb.append(f.format(new Date(e.getTimestamp()))).append(' ').append(reasonName(e.getReason()));
                }
                out.add(new Check("Last process exits", Level.INFO, sb.length() == 0 ? "none recorded" : sb.toString()));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public static String toReport(Context ctx, List<Check> checks) {
        StringBuilder sb = new StringBuilder("FadCam root diagnostics\n");
        for (Check c : checks) {
            sb.append('[').append(c.level).append("] ").append(c.name).append(": ").append(c.detail).append('\n');
        }
        return sb.toString();
    }

    private static boolean permission(Context c, String p) {
        return ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isRecording(Context app) {
        return app.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(Constants.PREF_IS_RECORDING_IN_PROGRESS, false);
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').trim();
    }

    private static String reasonName(int r) {
        switch (r) {
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            default: return "UNKNOWN(" + r + ")";
        }
    }
}
