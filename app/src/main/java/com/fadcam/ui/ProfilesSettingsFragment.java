package com.fadcam.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.fadcam.Constants;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Profiles: save the current recording-related settings (video, camera, audio, watermark,
 * screen recording) under a name and load them again later.
 */
public class ProfilesSettingsFragment extends SimplePageFragment {

    private static final String STORE = "fadcam_profiles";
    private static final String[] PREFIXES = {"PREF_VIDEO_", "PREF_CAMERA_", "PREF_AUDIO_",
            "PREF_SCREEN_RECORDING_", "PREF_WATERMARK_", "PREF_BITRATE_"};
    private static final String[] EXTRA_KEYS = {"bitrate_mode_custom", "bitrate_custom_value"};

    private LinearLayout content;

    @Override
    protected String pageTitle() {
        return "Profiles";
    }

    @Override
    protected void buildContent(@NonNull Context ctx, @NonNull LinearLayout root) {
        content = root;
        rebuild(ctx);
    }

    private void rebuild(Context ctx) {
        content.removeAllViews();
        content.addView(SettingsPageUi.note(ctx, "Save your current recording settings (video, camera, audio, "
                + "watermark, screen recording) as a profile and switch between them in one tap. Profiles can "
                + "not be loaded while a recording is running."));

        LinearLayout actions = SettingsPageUi.card(ctx);
        SettingsPageUi.actionRow(ctx, actions, "Save current settings as new profile", null, v -> askName(ctx));
        content.addView(actions);

        content.addView(SettingsPageUi.header(ctx, "Saved profiles"));
        SharedPreferences store = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        Set<String> names = new TreeSet<>(store.getAll().keySet());
        if (names.isEmpty()) {
            content.addView(SettingsPageUi.note(ctx, "No profiles yet."));
            return;
        }
        LinearLayout list = SettingsPageUi.card(ctx);
        for (String name : names) {
            String sub = "";
            try {
                sub = new JSONObject(store.getString(name, "{}")).length() + " settings";
            } catch (JSONException ignored) {
            }
            SettingsPageUi.actionRow(ctx, list, name, sub, v -> showActions(ctx, name));
        }
        content.addView(list);
    }

    private void askName(Context ctx) {
        final EditText input = new EditText(ctx);
        input.setHint("Profile name (e.g. Dashcam 60fps)");
        int p = SettingsPageUi.dp(ctx, 20);
        android.widget.FrameLayout box = new android.widget.FrameLayout(ctx);
        box.setPadding(p, p / 2, p, 0);
        box.addView(input);
        new MaterialAlertDialogBuilder(ctx)
                .setTitle("Save profile")
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(ctx, "Enter a name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        JSONObject snap = snapshot(ctx);
                        ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                                .putString(name, snap.toString()).apply();
                        Toast.makeText(ctx, "Saved \"" + name + "\" (" + snap.length() + " settings)",
                                Toast.LENGTH_SHORT).show();
                        rebuild(ctx);
                    } catch (Exception e) {
                        Toast.makeText(ctx, "Could not save profile", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showActions(Context ctx, String name) {
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(name)
                .setItems(new String[]{"Load this profile", "Overwrite with current settings", "Delete"},
                        (d, which) -> {
                            SharedPreferences store = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE);
                            if (which == 0) {
                                load(ctx, store.getString(name, "{}"), name);
                            } else if (which == 1) {
                                try {
                                    store.edit().putString(name, snapshot(ctx).toString()).apply();
                                    Toast.makeText(ctx, "Profile updated", Toast.LENGTH_SHORT).show();
                                    rebuild(ctx);
                                } catch (Exception e) {
                                    Toast.makeText(ctx, "Could not update", Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                store.edit().remove(name).apply();
                                rebuild(ctx);
                            }
                        })
                .show();
    }

    private void load(Context ctx, String json, String name) {
        SharedPreferences app = ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        if (app.getBoolean(Constants.PREF_IS_RECORDING_IN_PROGRESS, false)) {
            Toast.makeText(ctx, "Stop the recording first", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject o = new JSONObject(json);
            SharedPreferences.Editor ed = app.edit();
            Iterator<String> it = o.keys();
            int n = 0;
            while (it.hasNext()) {
                String k = it.next();
                JSONObject e = o.getJSONObject(k);
                switch (e.getString("t")) {
                    case "b": ed.putBoolean(k, e.getBoolean("v")); break;
                    case "i": ed.putInt(k, e.getInt("v")); break;
                    case "l": ed.putLong(k, e.getLong("v")); break;
                    case "f": ed.putFloat(k, (float) e.getDouble("v")); break;
                    default: ed.putString(k, e.getString("v")); break;
                }
                n++;
            }
            ed.apply();
            Toast.makeText(ctx, "Loaded \"" + name + "\" (" + n + " settings). Reopen the settings pages to see the values.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception ex) {
            Toast.makeText(ctx, "Profile is corrupted", Toast.LENGTH_SHORT).show();
        }
    }

    private static Set<String> profileKeys() {
        Set<String> keys = new TreeSet<>();
        for (Field f : Constants.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
            String n = f.getName();
            boolean match = false;
            for (String p : PREFIXES) {
                if (n.startsWith(p)) {
                    match = true;
                    break;
                }
            }
            if (!match || n.contains("STATE") || n.contains("WARNING_SHOWN") || n.contains("ELAPSED")) continue;
            try {
                keys.add((String) f.get(null));
            } catch (IllegalAccessException ignored) {
            }
        }
        for (String k : EXTRA_KEYS) keys.add(k);
        return keys;
    }

    private static JSONObject snapshot(Context ctx) throws JSONException {
        SharedPreferences app = ctx.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> all = app.getAll();
        JSONObject out = new JSONObject();
        for (String k : profileKeys()) {
            Object v = all.get(k);
            if (v == null) continue;
            JSONObject e = new JSONObject();
            if (v instanceof Boolean) {
                e.put("t", "b");
                e.put("v", ((Boolean) v).booleanValue());
            } else if (v instanceof Integer) {
                e.put("t", "i");
                e.put("v", ((Integer) v).intValue());
            } else if (v instanceof Long) {
                e.put("t", "l");
                e.put("v", ((Long) v).longValue());
            } else if (v instanceof Float) {
                e.put("t", "f");
                e.put("v", ((Float) v).doubleValue());
            } else if (v instanceof String) {
                e.put("t", "s");
                e.put("v", v);
            } else {
                continue;
            }
            out.put(k, e);
        }
        return out;
    }
}
