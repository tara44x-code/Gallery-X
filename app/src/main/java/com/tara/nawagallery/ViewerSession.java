package com.tara.nawagallery;

import java.util.ArrayList;
import java.util.List;

public final class ViewerSession {
    private static final List<MediaItem> ITEMS = new ArrayList<>();
    private static int index;

    private ViewerSession() {}

    public static synchronized void open(List<MediaItem> items, int selectedIndex) {
        ITEMS.clear();
        ITEMS.addAll(items);
        index = Math.max(0, Math.min(selectedIndex, ITEMS.size() - 1));
    }

    public static synchronized boolean isEmpty() { return ITEMS.isEmpty(); }
    public static synchronized int size() { return ITEMS.size(); }
    public static synchronized int index() { return index; }
    public static synchronized MediaItem current() {
        return ITEMS.isEmpty() ? null : ITEMS.get(index);
    }
    public static synchronized MediaItem next() {
        if (index + 1 >= ITEMS.size()) return null;
        return ITEMS.get(++index);
    }
    public static synchronized MediaItem previous() {
        if (index <= 0 || ITEMS.isEmpty()) return null;
        return ITEMS.get(--index);
    }
    public static synchronized void removeCurrent() {
        if (ITEMS.isEmpty()) return;
        ITEMS.remove(index);
        if (index >= ITEMS.size()) index = Math.max(0, ITEMS.size() - 1);
    }

    public static synchronized void clear() {
        ITEMS.clear();
        index = 0;
    }
}
