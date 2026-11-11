package com.tara.nawagallery;

import android.content.Context;

public final class GalleryPrefs {
    private static final String PREFS = "gallery_display";
    private static final String KEY_COLUMNS = "photo_columns";
    private static final String KEY_FONT_SCALE = "font_scale";

    private GalleryPrefs() {}

    public static int getColumns(Context context) {
        int value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_COLUMNS, 3);
        return Math.max(2, Math.min(5, value));
    }

    public static void setColumns(Context context, int columns) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_COLUMNS, Math.max(2, Math.min(5, columns))).apply();
    }

    public static float getFontScale(Context context) {
        float value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_FONT_SCALE, 1f);
        return Math.max(0.9f, Math.min(1.3f, value));
    }

    public static void setFontScale(Context context, float scale) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_FONT_SCALE, Math.max(0.9f, Math.min(1.3f, scale))).apply();
    }
}
