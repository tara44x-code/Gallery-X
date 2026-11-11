package com.tara.nawagallery;

import android.content.Context;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DuplicateFinder {
    private DuplicateFinder() {}

    public static List<MediaItem> findExact(Context context, List<MediaItem> items) {
        Map<Long, List<MediaItem>> bySize = new HashMap<>();
        for (MediaItem item : items) {
            if (item.sizeBytes <= 0) continue;
            bySize.computeIfAbsent(item.sizeBytes, key -> new ArrayList<>()).add(item);
        }

        List<MediaItem> duplicates = new ArrayList<>();
        for (List<MediaItem> candidates : bySize.values()) {
            if (candidates.size() < 2) continue;
            Map<String, List<MediaItem>> byHash = new LinkedHashMap<>();
            for (MediaItem item : candidates) {
                String hash = hash(context, item);
                if (hash != null) byHash.computeIfAbsent(hash, key -> new ArrayList<>()).add(item);
            }
            for (List<MediaItem> group : byHash.values()) {
                if (group.size() > 1) duplicates.addAll(group);
            }
        }
        return duplicates;
    }

    private static String hash(Context context, MediaItem item) {
        try (InputStream stream = context.getContentResolver().openInputStream(item.uri)) {
            if (stream == null) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
