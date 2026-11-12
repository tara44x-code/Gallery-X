package com.tara.nawagallery;

import android.net.Uri;

/** One audio file for the music player. */
final class Song {
    final long id;
    final Uri uri;
    final String title;
    final String artist;
    final String album;
    final long albumId;
    final long durationMs;
    final String folder;

    Song(long id, Uri uri, String title, String artist, String album, long albumId, long durationMs, String folder) {
        this.id = id;
        this.uri = uri;
        this.title = title == null || title.trim().isEmpty() ? "بدون اسم" : title;
        this.artist = artist == null || artist.trim().isEmpty() || "<unknown>".equals(artist) ? "فنان غير معروف" : artist;
        this.album = album == null || album.trim().isEmpty() || "<unknown>".equals(album) ? "ألبوم غير معروف" : album;
        this.albumId = albumId;
        this.durationMs = durationMs;
        this.folder = folder == null || folder.trim().isEmpty() ? "أخرى" : folder;
    }
}
