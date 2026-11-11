package com.tara.nawagallery;

import android.content.Context;

import java.util.HashSet;
import java.util.Set;

public final class HiddenFoldersStore {
    private static final String PREFS = "nawa_hidden_folders";
    private static final String KEY_IDS = "bucket_ids";

    private HiddenFoldersStore() {}

    public static Set<String> getAll(Context context) {
        Set<String> stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_IDS, new HashSet<>());
        return new HashSet<>(stored);
    }

    public static void save(Context context, Set<String> ids) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY_IDS, new HashSet<>(ids)).apply();
    }
}
