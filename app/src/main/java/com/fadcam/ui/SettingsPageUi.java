package com.fadcam.ui;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fadcam.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

/** Small programmatic row/card builders so simple settings pages need no XML. */
final class SettingsPageUi {

    interface IntCallback {
        void on(int value);
    }

    private SettingsPageUi() {}

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static int attrColor(Context c, int attr, int fallback) {
        TypedValue tv = new TypedValue();
        return c.getTheme().resolveAttribute(attr, tv, true) ? tv.data : fallback;
    }

    static TextView header(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(14);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(attrColor(c, R.attr.colorHeading, 0xFFFFFFFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 8), dp(c, 18), 0, dp(c, 8));
        t.setLayoutParams(lp);
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundResource(R.drawable.settings_group_card_bg);
        l.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return l;
    }

    static TextView note(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(0xFF8E8E93);
        t.setLineSpacing(dp(c, 3), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 8), dp(c, 6), dp(c, 8), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private static void divider(Context c, LinearLayout card) {
        if (card.getChildCount() == 0) return;
        View d = new View(c);
        d.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(c, 16), 0, dp(c, 16), 0);
        card.addView(d, lp);
    }

    private static LinearLayout baseRow(Context c, String title, String subtitle, View trailing) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 16), dp(c, 12), dp(c, 12), dp(c, 12));
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            row.setBackgroundResource(tv.resourceId);
        }
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(15);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(attrColor(c, R.attr.colorHeading, 0xFFFFFFFF));
        texts.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(c);
            s.setText(subtitle);
            s.setTextSize(12);
            s.setTextColor(0xFF9E9E9E);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (trailing != null) row.addView(trailing);
        return row;
    }

    static MaterialSwitch switchRow(Context c, LinearLayout card, String title, String subtitle,
            boolean checked, CompoundButton.OnCheckedChangeListener l) {
        divider(c, card);
        MaterialSwitch sw = new MaterialSwitch(c);
        LinearLayout row = baseRow(c, title, subtitle, sw);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(l);
        row.setOnClickListener(v -> {
            if (sw.isEnabled()) sw.toggle();
        });
        card.addView(row);
        return sw;
    }

    /** Row showing a value on the right; tap to run the action. Returns the value TextView. */
    static TextView valueRow(Context c, LinearLayout card, String title, String subtitle, String value,
            View.OnClickListener l) {
        divider(c, card);
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(14);
        v.setTextColor(attrColor(c, R.attr.colorButton, 0xFFE53935));
        LinearLayout row = baseRow(c, title, subtitle, v);
        row.setOnClickListener(l);
        card.addView(row);
        return v;
    }

    static LinearLayout actionRow(Context c, LinearLayout card, String title, String subtitle,
            View.OnClickListener l) {
        divider(c, card);
        LinearLayout row = baseRow(c, title, subtitle, null);
        row.setOnClickListener(l);
        card.addView(row);
        return row;
    }

    static void numberDialog(Context c, String title, int current, int min, int max, IntCallback cb) {
        EditText input = new EditText(c);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(current));
        input.setSelection(input.getText().length());
        FrameLayoutPad wrap = new FrameLayoutPad(c, input);
        new MaterialAlertDialogBuilder(c)
                .setTitle(title + " (" + min + "-" + max + ")")
                .setView(wrap)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        int v = Integer.parseInt(input.getText().toString().trim());
                        if (v < min || v > max) {
                            android.widget.Toast.makeText(c, "Value out of range", android.widget.Toast.LENGTH_SHORT).show();
                            return;
                        }
                        cb.on(v);
                    } catch (NumberFormatException e) {
                        android.widget.Toast.makeText(c, "Enter a number", android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /** Pads a dialog content view. */
    private static final class FrameLayoutPad extends android.widget.FrameLayout {
        FrameLayoutPad(Context c, View child) {
            super(c);
            int p = dp(c, 20);
            setPadding(p, p / 2, p, 0);
            addView(child);
        }
    }

    static int levelColor(String level) {
        switch (level) {
            case "OK": return 0xFF4CAF50;
            case "WARN": return 0xFFFFA726;
            case "FAIL": return 0xFFEF5350;
            default: return 0xFF9E9E9E;
        }
    }
}
