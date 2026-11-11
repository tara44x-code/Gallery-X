package com.tara.nawagallery;

import java.util.ArrayList;
import java.util.List;

public final class Album {
    public final String id;
    public final String name;
    public final List<MediaItem> items = new ArrayList<>();

    public Album(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public MediaItem cover() {
        return items.isEmpty() ? null : items.get(0);
    }
}
