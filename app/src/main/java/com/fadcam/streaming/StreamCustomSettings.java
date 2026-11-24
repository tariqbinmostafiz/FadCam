package com.fadcam.streaming;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Manual ("Custom") stream settings. Stored in FadCamPrefs next to the preset keys, and applied
 * through the same keys the presets use (stream_bitrate / stream_fps_cap), so every existing
 * enforcement path keeps working. Resolution 0x0 means "same as Recording Settings".
 */
public final class StreamCustomSettings {

    public static final String KEY_BITRATE_KBPS = "stream_custom_bitrate_kbps";
    public static final String KEY_FPS = "stream_custom_fps";
    public static final String KEY_WIDTH = "stream_custom_width";
    public static final String KEY_HEIGHT = "stream_custom_height";

    public static final int DEFAULT_BITRATE_KBPS = 4000;
    public static final int DEFAULT_FPS = 30;
    public static final int MIN_BITRATE_KBPS = 200;
    public static final int MAX_BITRATE_KBPS = 50000;
    public static final int MIN_FPS = 1;
    public static final int MAX_FPS = 60;

    private StreamCustomSettings() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("FadCamPrefs", Context.MODE_PRIVATE);
    }

    public static int getBitrateBps(Context c) {
        return prefs(c).getInt(KEY_BITRATE_KBPS, DEFAULT_BITRATE_KBPS) * 1000;
    }

    public static int getFps(Context c) {
        return prefs(c).getInt(KEY_FPS, DEFAULT_FPS);
    }

    public static int getWidth(Context c) {
        return prefs(c).getInt(KEY_WIDTH, 0);
    }

    public static int getHeight(Context c) {
        return prefs(c).getInt(KEY_HEIGHT, 0);
    }

    public static void save(Context c, int bitrateKbps, int fps, int width, int height) {
        bitrateKbps = Math.max(MIN_BITRATE_KBPS, Math.min(MAX_BITRATE_KBPS, bitrateKbps));
        fps = Math.max(MIN_FPS, Math.min(MAX_FPS, fps));
        prefs(c).edit()
                .putInt(KEY_BITRATE_KBPS, bitrateKbps)
                .putInt(KEY_FPS, fps)
                .putInt(KEY_WIDTH, Math.max(0, width))
                .putInt(KEY_HEIGHT, Math.max(0, height))
                .apply();
    }

    /** Human readable summary, e.g. "30fps cap • 4 Mbps • 1280x720". */
    public static String summary(Context c) {
        int kbps = prefs(c).getInt(KEY_BITRATE_KBPS, DEFAULT_BITRATE_KBPS);
        String rate = kbps >= 1000 ? (kbps % 1000 == 0 ? (kbps / 1000) + " Mbps" : (kbps / 1000.0) + " Mbps")
                : kbps + " kbps";
        int w = getWidth(c);
        int h = getHeight(c);
        String res = (w > 0 && h > 0) ? (w + "x" + h) : "recording resolution";
        return getFps(c) + "fps cap \u2022 " + rate + " \u2022 " + res;
    }
}
