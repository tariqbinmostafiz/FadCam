package com.fadcam.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.fadcam.Constants;
import com.fadcam.root.RootBridge;
import com.fadcam.root.RootDiagnostics;
import com.fadcam.root.RootTuner;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

/** Root & Diagnostics: switches for every root-related behaviour plus a self-test with a copyable report. */
public class RootSettingsFragment extends SimplePageFragment {

    private static final String GUARDIAN_PAUSE_FILE = "/data/local/tmp/fadcam_guardian.disable";

    private SharedPreferences prefs;
    private LinearLayout resultsCard;
    private String lastReport = "";
    private final List<MaterialSwitch> dependentSwitches = new ArrayList<>();

    @Override
    protected String pageTitle() {
        return "Root & Diagnostics";
    }

    @Override
    protected void buildContent(@NonNull Context ctx, @NonNull LinearLayout root) {
        prefs = ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);

        root.addView(SettingsPageUi.note(ctx, "Everything in FadCam works without root. These options only add "
                + "protection against Android killing a long recording. Grant FadCam superuser access in "
                + "your root manager (Magisk / KernelSU / APatch) first."));

        // ---- Switches ----
        root.addView(SettingsPageUi.header(ctx, "Root features"));
        LinearLayout sw = SettingsPageUi.card(ctx);
        MaterialSwitch master = SettingsPageUi.switchRow(ctx, sw, "Root mode",
                "Master switch for everything below", prefs.getBoolean(Constants.PREF_ROOT_MODE_ENABLED, true),
                (b, on) -> {
                    prefs.edit().putBoolean(Constants.PREF_ROOT_MODE_ENABLED, on).apply();
                    applyDependentState(on);
                });
        dependentSwitches.add(featureSwitch(ctx, sw, Constants.PREF_ROOT_FEATURE_OOM, "Lower kill priority",
                "Write a low oom_score_adj for the app process while recording"));
        dependentSwitches.add(featureSwitch(ctx, sw, Constants.PREF_ROOT_FEATURE_DOZE, "Doze whitelist",
                "Exempt FadCam from Doze while recording"));
        dependentSwitches.add(featureSwitch(ctx, sw, Constants.PREF_ROOT_FEATURE_APPOPS, "Background app-ops",
                "Allow RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND while recording"));
        dependentSwitches.add(featureSwitch(ctx, sw, Constants.PREF_ROOT_FEATURE_BUCKET, "Active standby bucket",
                "Keep FadCam in the 'active' standby bucket while recording"));
        applyDependentState(master.isChecked());
        root.addView(sw);

        root.addView(SettingsPageUi.header(ctx, "Reliability"));
        LinearLayout rel = SettingsPageUi.card(ctx);
        SettingsPageUi.switchRow(ctx, rel, "Resume after kill",
                "If Android kills FadCam mid-recording, start a new segment automatically (no root needed)",
                prefs.getBoolean(Constants.PREF_ROOT_AUTO_RESUME, true),
                (b, on) -> prefs.edit().putBoolean(Constants.PREF_ROOT_AUTO_RESUME, on).apply());
        final MaterialSwitch guardian = SettingsPageUi.switchRow(ctx, rel, "Guardian watchdog",
                "Pause / resume the Magisk/KernelSU Guardian module (needs root)", true, null);
        guardian.setEnabled(false);
        loadGuardianState(guardian);
        root.addView(rel);

        // ---- Diagnostics ----
        root.addView(SettingsPageUi.header(ctx, "Diagnostics"));
        LinearLayout diag = SettingsPageUi.card(ctx);
        SettingsPageUi.actionRow(ctx, diag, "Run all checks",
                "Root access, provider, SELinux, kill-protection, Guardian module, permissions",
                v -> runChecks(ctx));
        SettingsPageUi.actionRow(ctx, diag, "Test tuning now",
                "Applies the enabled root tuning once and shows before/after values",
                v -> runTuningTest(ctx));
        SettingsPageUi.actionRow(ctx, diag, "Copy report", "Copy the last result to the clipboard", v -> {
            if (lastReport.isEmpty()) {
                Toast.makeText(ctx, "Run the checks first", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("FadCam root report", lastReport));
            Toast.makeText(ctx, "Report copied", Toast.LENGTH_SHORT).show();
        });
        root.addView(diag);

        resultsCard = SettingsPageUi.card(ctx);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) resultsCard.getLayoutParams();
        lp.topMargin = SettingsPageUi.dp(ctx, 12);
        resultsCard.setPadding(SettingsPageUi.dp(ctx, 16), SettingsPageUi.dp(ctx, 12),
                SettingsPageUi.dp(ctx, 16), SettingsPageUi.dp(ctx, 12));
        resultsCard.addView(SettingsPageUi.note(ctx, "No results yet. Tap \"Run all checks\"."));
        root.addView(resultsCard);
    }

    private MaterialSwitch featureSwitch(Context ctx, LinearLayout card, String key, String title, String sub) {
        return SettingsPageUi.switchRow(ctx, card, title, sub, prefs.getBoolean(key, true),
                (b, on) -> prefs.edit().putBoolean(key, on).apply());
    }

    private void applyDependentState(boolean masterOn) {
        for (MaterialSwitch s : dependentSwitches) {
            s.setEnabled(masterOn);
            s.setAlpha(masterOn ? 1f : 0.45f);
        }
    }

    private void loadGuardianState(MaterialSwitch guardian) {
        new Thread(() -> {
            boolean rootOk = RootBridge.isRootAvailable();
            boolean paused = false;
            if (rootOk) {
                paused = RootBridge.exec(8000, "[ -f " + GUARDIAN_PAUSE_FILE + " ] && echo yes || echo no")
                        .out.contains("yes");
            }
            final boolean fRoot = rootOk;
            final boolean fPaused = paused;
            runOnUi(() -> {
                guardian.setEnabled(fRoot);
                guardian.setAlpha(fRoot ? 1f : 0.45f);
                guardian.setChecked(!fPaused);
                guardian.setOnCheckedChangeListener((b, on) -> new Thread(() -> {
                    RootBridge.Result r = RootBridge.exec(8000, on ? "rm -f " + GUARDIAN_PAUSE_FILE
                            : "touch " + GUARDIAN_PAUSE_FILE);
                    if (!r.ok()) {
                        runOnUi(() -> {
                            Toast.makeText(requireContext(), "Root command failed", Toast.LENGTH_SHORT).show();
                            guardian.setOnCheckedChangeListener(null);
                            guardian.setChecked(!on);
                        });
                    }
                }).start());
            });
        }).start();
    }

    private void runChecks(Context ctx) {
        showResultsText(ctx, "Running checks...");
        new Thread(() -> {
            List<RootDiagnostics.Check> checks = RootDiagnostics.run(ctx);
            lastReport = RootDiagnostics.toReport(ctx, checks);
            runOnUi(() -> renderChecks(ctx, checks));
        }).start();
    }

    private void runTuningTest(Context ctx) {
        showResultsText(ctx, "Testing...");
        new Thread(() -> {
            String res = RootTuner.testCycle(ctx);
            lastReport = "Tuning test: " + res + "\n";
            runOnUi(() -> showResultsText(ctx, "Tuning test:\n" + res));
        }).start();
    }

    private void showResultsText(Context ctx, String text) {
        if (resultsCard == null) return;
        resultsCard.removeAllViews();
        resultsCard.addView(SettingsPageUi.note(ctx, text));
    }

    private void renderChecks(Context ctx, List<RootDiagnostics.Check> checks) {
        if (resultsCard == null) return;
        resultsCard.removeAllViews();
        for (RootDiagnostics.Check c : checks) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, SettingsPageUi.dp(ctx, 6), 0, SettingsPageUi.dp(ctx, 6));
            TextView t = new TextView(ctx);
            t.setText("[" + c.level + "] " + c.name);
            t.setTextSize(14);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            t.setTextColor(SettingsPageUi.levelColor(c.level.name()));
            row.addView(t);
            TextView d = new TextView(ctx);
            d.setText(c.detail);
            d.setTextSize(12);
            d.setTextColor(0xFFBDBDBD);
            d.setTextIsSelectable(true);
            row.addView(d);
            resultsCard.addView(row);
        }
    }

    private void runOnUi(Runnable r) {
        if (getActivity() != null && isAdded()) getActivity().runOnUiThread(r);
    }
}
