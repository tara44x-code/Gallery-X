package com.tara.nawagallery;

import java.util.ArrayList;
import java.util.List;

/** Keeps the current on-screen video list so the player can move next/previous. */
public final class VideoSession {
    private static final List<MediaItem> ITEMS = new ArrayList<>();
    private static int index;

    private VideoSession() {}

    public static synchronized void open(List<MediaItem> items, int selected) {
        ITEMS.clear();
        ITEMS.addAll(items);
        index = Math.max(0, Math.min(selected, Math.max(0, ITEMS.size() - 1)));
    }

    public static synchronized MediaItem next() {
        return index + 1 < ITEMS.size() ? ITEMS.get(++index) : null;
    }

    public static synchronized MediaItem previous() {
        return index > 0 && !ITEMS.isEmpty() ? ITEMS.get(--index) : null;
    }

    public static synchronized boolean hasNext() { return index + 1 < ITEMS.size(); }
    public static synchronized boolean hasPrevious() { return index > 0 && !ITEMS.isEmpty(); }
}
