package com.fadcam.thermal;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.fadcam.Constants;
import com.fadcam.FLog;
import com.fadcam.services.RecordingService;

/**
 * Watches battery temperature and the system thermal status while recording. Warns when the
 * device gets hot and (optionally) stops the recording before the OS shuts the camera down.
 * Opt-in: pref {@link Constants#PREF_THERMAL_GUARD_ENABLED} (default off).
 */
public final class ThermalGuardian {

    private static final String TAG = "ThermalGuardian";
    private static final String CHANNEL_ID = "fadcam_thermal";
    private static final long TICK_MS = 15_000L;
    private static final long WARN_REPEAT_MS = 5 * 60_000L;

    public static final int DEFAULT_WARN_C = 42;
    public static final int DEFAULT_STOP_C = 47;

    private static Handler handler;
    private static boolean running = false;
    private static long lastWarnMs = 0;

    private ThermalGuardian() {}

    public static synchronized void onRecordingStarted(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (running || !isEnabled(app)) return;
        running = true;
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isRecording(app) || !isEnabled(app)) {
                    running = false;
                    return;
                }
                if (!evaluate(app)) {
                    running = false;
                    return;
                }
                handler.postDelayed(this, TICK_MS);
            }
        });
    }

    /** @return false when the guardian stopped the recording (no more ticks needed). */
    private static boolean evaluate(Context app) {
        SharedPreferences p = prefs(app);
        float temp = batteryTempC(app);
        int status = thermalStatus(app);
        int warn = p.getInt(Constants.PREF_THERMAL_WARN_C, DEFAULT_WARN_C);
        int stop = p.getInt(Constants.PREF_THERMAL_STOP_C, DEFAULT_STOP_C);
        boolean autoStop = p.getBoolean(Constants.PREF_THERMAL_AUTO_STOP, true);
        boolean critical = temp >= stop || status >= 4; // THERMAL_STATUS_CRITICAL
        boolean hot = temp >= warn || status >= 3;      // THERMAL_STATUS_SEVERE

        if (critical && autoStop) {
            String msg = "Recording stopped: device too hot (" + String.format("%.1f", temp) + "\u00B0C)";
            FLog.w(TAG, msg + " status=" + status);
            notifyUser(app, msg);
            try {
                app.startService(new Intent(app, RecordingService.class)
                        .setAction(Constants.INTENT_ACTION_STOP_RECORDING));
            } catch (Exception e) {
                FLog.e(TAG, "Could not send stop intent", e);
            }
            return false;
        }
        long now = System.currentTimeMillis();
        if (hot && now - lastWarnMs > WARN_REPEAT_MS) {
            lastWarnMs = now;
            String msg = "Device is hot (" + String.format("%.1f", temp) + "\u00B0C) - consider pausing recording";
            FLog.w(TAG, msg + " status=" + status);
            notifyUser(app, msg);
        }
        return true;
    }

    public static float batteryTempC(Context ctx) {
        Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return -1f;
        int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        return t == Integer.MIN_VALUE ? -1f : t / 10f;
    }

    /** PowerManager thermal status (0 none .. 6 shutdown), or -1 when unavailable (below Android 10). */
    public static int thermalStatus(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        return pm == null ? -1 : pm.getCurrentThermalStatus();
    }

    public static String statusName(int s) {
        switch (s) {
            case 0: return "None";
            case 1: return "Light";
            case 2: return "Moderate";
            case 3: return "Severe";
            case 4: return "Critical";
            case 5: return "Emergency";
            case 6: return "Shutdown";
            default: return "Unavailable";
        }
    }

    private static void notifyUser(Context app, String msg) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Thermal Guardian",
                            NotificationManager.IMPORTANCE_HIGH));
                }
                nm.notify(7311, new NotificationCompat.Builder(app, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_warning)
                        .setContentTitle("Thermal Guardian")
                        .setContentText(msg)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build());
            }
        } catch (Exception e) {
            FLog.w(TAG, "notification failed", e);
        }
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
    }

    private static boolean isEnabled(Context app) {
        return prefs(app).getBoolean(Constants.PREF_THERMAL_GUARD_ENABLED, false);
    }

    private static boolean isRecording(Context app) {
        return prefs(app).getBoolean(Constants.PREF_IS_RECORDING_IN_PROGRESS, false);
    }

    private static SharedPreferences prefs(Context app) {
        return app.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
