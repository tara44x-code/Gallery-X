package com.tara.nawagallery;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers how subtitles are shown: language, size, color, background and neon glow. */
public final class SubtitlePrefs {
    public static final String ORIGINAL = "orig";
    public static final String ARABIC = "ar";
    public static final String ENGLISH = "en";

    public static final String[] SIZE_LABELS = {"صغير", "متوسط", "كبير", "كبير جداً"};
    public static final float[] SIZES = {15f, 18f, 22f, 26f};
    public static final String[] COLOR_LABELS = {"أبيض", "أصفر", "لون الثيم (نيون)"};
    public static final String[] BACKGROUND_LABELS = {"بدون خلفية", "خلفية شفافة", "خلفية داكنة"};

    private static final String PREFS = "gx_subtitle_style";

    private SubtitlePrefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String language(Context context) { return prefs(context).getString("language", ORIGINAL); }
    public static void setLanguage(Context context, String value) { prefs(context).edit().putString("language", value).apply(); }

    public static int size(Context context) { return clamp(prefs(context).getInt("size", 1), SIZES.length); }
    public static void setSize(Context context, int value) { prefs(context).edit().putInt("size", value).apply(); }

    public static int color(Context context) { return clamp(prefs(context).getInt("color", 0), COLOR_LABELS.length); }
    public static void setColor(Context context, int value) { prefs(context).edit().putInt("color", value).apply(); }

    public static int background(Context context) { return clamp(prefs(context).getInt("background", 1), BACKGROUND_LABELS.length); }
    public static void setBackground(Context context, int value) { prefs(context).edit().putInt("background", value).apply(); }

    public static boolean glow(Context context) { return prefs(context).getBoolean("glow", true); }
    public static void setGlow(Context context, boolean value) { prefs(context).edit().putBoolean("glow", value).apply(); }

    public static String languageLabel(String language) {
        if (ARABIC.equals(language)) return "العربية";
        if (ENGLISH.equals(language)) return "English";
        return "اللغة الأصلية";
    }

    private static int clamp(int value, int count) { return Math.max(0, Math.min(count - 1, value)); }
}
