package com.tara.nawagallery;

import android.content.Context;

import java.util.HashSet;
import java.util.Set;

public final class FavoritesStore {
    private static final String PREFS = "gallery_favorites";
    private static final String KEY_URIS = "favorite_uris";

    private FavoritesStore() {}

    public static boolean contains(Context context, String uri) {
        return getAll(context).contains(uri);
    }

    public static boolean toggle(Context context, String uri) {
        Set<String> values = getAll(context);
        boolean favorite;
        if (values.contains(uri)) {
            values.remove(uri);
            favorite = false;
        } else {
            values.add(uri);
            favorite = true;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_URIS, values).apply();
        return favorite;
    }

    public static void toggleIfPresent(Context context, String uri) {
        Set<String> values = getAll(context);
        if (values.remove(uri)) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putStringSet(KEY_URIS, values).apply();
        }
    }

    public static void addAll(Context context, Set<String> uris) {
        Set<String> values = getAll(context);
        values.addAll(uris);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_URIS, values).apply();
    }

    public static Set<String> getAll(Context context) {
        Set<String> stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_URIS, new HashSet<>());
        return new HashSet<>(stored);
    }
}
