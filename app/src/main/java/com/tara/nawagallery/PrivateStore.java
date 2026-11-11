package com.tara.nawagallery;

import android.content.Context;

import java.util.HashSet;
import java.util.Set;

/** Legacy (3.x) list of gallery items marked private before the encrypted vault existed. */
public final class PrivateStore {
    private static final String PREFS = "nawa_private_album";
    private static final String KEY_URIS = "private_uris";

    private PrivateStore() {}

    public static Set<String> getUris(Context context) {
        Set<String> stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_URIS, new HashSet<>());
        return new HashSet<>(stored);
    }

    public static void addAll(Context context, Set<String> uris) {
        Set<String> values = getUris(context);
        values.addAll(uris);
        saveUris(context, values);
    }

    public static void removeAll(Context context, Set<String> uris) {
        Set<String> values = getUris(context);
        values.removeAll(uris);
        saveUris(context, values);
    }

    private static void saveUris(Context context, Set<String> uris) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_URIS, uris).apply();
    }
}
