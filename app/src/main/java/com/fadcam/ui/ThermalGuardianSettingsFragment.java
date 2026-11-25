package com.fadcam.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fadcam.Constants;
import com.fadcam.thermal.ThermalGuardian;

/** Thermal Guardian settings: warn and optionally stop recording when the device overheats. */
public class ThermalGuardianSettingsFragment extends SimplePageFragment {

    private SharedPreferences prefs;

    @Override
    protected String pageTitle() {
        return "Thermal Guardian";
    }

    @Override
    protected void buildContent(@NonNull Context ctx, @NonNull LinearLayout root) {
        prefs = ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);

        root.addView(SettingsPageUi.note(ctx, "Watches battery temperature and the system thermal status while "
                + "recording. It warns you when the phone gets hot and can stop the recording before Android "
                + "shuts the camera down. Off by default: a dash-cam in a hot car may prefer to keep recording."));

        LinearLayout card = SettingsPageUi.card(ctx);
        SettingsPageUi.switchRow(ctx, card, "Enable Thermal Guardian", "Monitor while recording",
                prefs.getBoolean(Constants.PREF_THERMAL_GUARD_ENABLED, false),
                (b, on) -> prefs.edit().putBoolean(Constants.PREF_THERMAL_GUARD_ENABLED, on).apply());
        SettingsPageUi.switchRow(ctx, card, "Stop recording when critical",
                "Safely stop and save the file at the stop temperature (or critical system status)",
                prefs.getBoolean(Constants.PREF_THERMAL_AUTO_STOP, true),
                (b, on) -> prefs.edit().putBoolean(Constants.PREF_THERMAL_AUTO_STOP, on).apply());
        final TextView warnValue = SettingsPageUi.valueRow(ctx, card, "Warning temperature",
                "Show a notification at or above this battery temperature",
                prefs.getInt(Constants.PREF_THERMAL_WARN_C, ThermalGuardian.DEFAULT_WARN_C) + "\u00B0C", null);
        final TextView stopValue = SettingsPageUi.valueRow(ctx, card, "Stop temperature",
                "Stop recording at or above this battery temperature",
                prefs.getInt(Constants.PREF_THERMAL_STOP_C, ThermalGuardian.DEFAULT_STOP_C) + "\u00B0C", null);
        ((LinearLayout) warnValue.getParent()).setOnClickListener(v ->
                SettingsPageUi.numberDialog(ctx, "Warning temperature (\u00B0C)",
                        prefs.getInt(Constants.PREF_THERMAL_WARN_C, ThermalGuardian.DEFAULT_WARN_C), 30, 60, val -> {
                            prefs.edit().putInt(Constants.PREF_THERMAL_WARN_C, val).apply();
                            warnValue.setText(val + "\u00B0C");
                        }));
        ((LinearLayout) stopValue.getParent()).setOnClickListener(v ->
                SettingsPageUi.numberDialog(ctx, "Stop temperature (\u00B0C)",
                        prefs.getInt(Constants.PREF_THERMAL_STOP_C, ThermalGuardian.DEFAULT_STOP_C), 35, 70, val -> {
                            prefs.edit().putInt(Constants.PREF_THERMAL_STOP_C, val).apply();
                            stopValue.setText(val + "\u00B0C");
                        }));
        root.addView(card);

        root.addView(SettingsPageUi.header(ctx, "Current reading"));
        LinearLayout live = SettingsPageUi.card(ctx);
        final TextView reading = new TextView(ctx);
        reading.setTextSize(14);
        reading.setPadding(SettingsPageUi.dp(ctx, 16), SettingsPageUi.dp(ctx, 12),
                SettingsPageUi.dp(ctx, 16), SettingsPageUi.dp(ctx, 12));
        reading.setTextColor(0xFFBDBDBD);
        live.addView(reading);
        Runnable refresh = () -> reading.setText("Battery: " + String.format("%.1f", ThermalGuardian.batteryTempC(ctx))
                + "\u00B0C\nSystem thermal status: " + ThermalGuardian.statusName(ThermalGuardian.thermalStatus(ctx)));
        refresh.run();
        SettingsPageUi.actionRow(ctx, live, "Refresh", null, v -> refresh.run());
        root.addView(live);
    }
}
