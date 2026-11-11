package com.tara.nawagallery;

import android.content.Context;
import android.graphics.Color;

public final class ThemePrefs {
    private static final String PREFS = "theme_prefs";
    private static final String KEY_THEME = "theme_index";

    public static final class ThemeStyle {
        public final String name;
        public final int primary;
        public final int secondary;
        public final int background;
        public final int surface;
        public final int surfaceAlt;

        ThemeStyle(String name, String primary, String secondary,
                   String background, String surface, String surfaceAlt) {
            this.name = name;
            this.primary = Color.parseColor(primary);
            this.secondary = Color.parseColor(secondary);
            this.background = Color.parseColor(background);
            this.surface = Color.parseColor(surface);
            this.surfaceAlt = Color.parseColor(surfaceAlt);
        }
    }

    public static final ThemeStyle[] THEMES = {
            new ThemeStyle("نيون بنفسجي", "#A855F7", "#FF5EDB", "#090618", "#1A103A", "#2A1D49"),
            new ThemeStyle("سايبر أزرق", "#00C2FF", "#506CFF", "#030B1A", "#071D35", "#102B4B"),
            new ThemeStyle("زمرد نابض", "#00E6A8", "#00A87A", "#03140F", "#092C23", "#104235"),
            new ThemeStyle("غروب وردي", "#FF4FA3", "#FF7A45", "#190712", "#351329", "#4B1C35"),
            new ThemeStyle("ذهبي AMOLED", "#FFC857", "#FF8A00", "#030303", "#1C160B", "#33250E"),
            new ThemeStyle("جليد فضي", "#B8D8FF", "#7C8CFF", "#090B14", "#171B2D", "#272D46"),
            new ThemeStyle("أورورا سماوية", "#7C3AED", "#22D3EE", "#050818", "#11163A", "#1B2850"),
            new ThemeStyle("روبي كهربائي", "#FF1744", "#00E5FF", "#12030A", "#2D0B1A", "#40152B"),
            new ThemeStyle("لايم سايبر", "#B7FF00", "#00FFB2", "#050B05", "#122615", "#1D3B23"),
            new ThemeStyle("ليل ملكي", "#6D5BFF", "#D946EF", "#070512", "#1B1233", "#2D1C4C")
    };

    private ThemePrefs() {}

    public static int getIndex(Context context) {
        int index = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_THEME, 0);
        return Math.max(0, Math.min(index, THEMES.length - 1));
    }

    public static ThemeStyle getStyle(Context context) {
        return THEMES[getIndex(context)];
    }

    public static int get(Context context) { return getStyle(context).primary; }

    public static void setTheme(Context context, int index) {
        int safe = Math.max(0, Math.min(index, THEMES.length - 1));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_THEME, safe).apply();
    }

    public static void set(Context context, int color) {
        for (int i = 0; i < THEMES.length; i++) {
            if (THEMES[i].primary == color) { setTheme(context, i); return; }
        }
        setTheme(context, 0);
    }
}
