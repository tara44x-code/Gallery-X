package com.tara.nawagallery;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Size;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Reads the phone's music from MediaStore, and album art from MediaStore or the file itself. */
final class MusicRepository {
    private MusicRepository() {}

    static List<Song> load(Context context) {
        List<Song> songs = new ArrayList<>();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        List<String> columns = new ArrayList<>();
        columns.add(MediaStore.Audio.Media._ID);
        columns.add(MediaStore.Audio.Media.TITLE);
        columns.add(MediaStore.Audio.Media.ARTIST);
        columns.add(MediaStore.Audio.Media.ALBUM);
        columns.add(MediaStore.Audio.Media.ALBUM_ID);
        columns.add(MediaStore.Audio.Media.DURATION);
        columns.add(MediaStore.Audio.Media.DATA);
        if (Build.VERSION.SDK_INT >= 29) columns.add(MediaStore.Audio.Media.RELATIVE_PATH);
        try (Cursor c = context.getContentResolver().query(collection, columns.toArray(new String[0]),
                MediaStore.Audio.Media.IS_MUSIC + " != 0", null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (c == null) return songs;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String folder = null;
                if (Build.VERSION.SDK_INT >= 29) folder = lastSegment(c.getString(7));
                if (folder == null && c.getString(6) != null) {
                    File parent = new File(c.getString(6)).getParentFile();
                    folder = parent == null ? null : parent.getName();
                }
                songs.add(new Song(id, ContentUris.withAppendedId(collection, id), c.getString(1), c.getString(2),
                        c.getString(3), c.getLong(4), c.getLong(5), folder, c.getString(6)));
            }
        } catch (Exception ignored) { }
        return songs;
    }

    /** A song opened from another app ("Open with"): name from the file, details from its tags. */
    static Song fromUri(Context context, Uri uri) {
        String name = null, title = null, artist = null, album = null;
        long duration = 0;
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) { }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) duration = Long.parseLong(d);
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
        if (title == null || title.trim().isEmpty()) {
            if (name == null) name = uri.getLastPathSegment();
            title = name == null ? null : name.replaceFirst("\\.[^.]+$", "");
        }
        return new Song(0, uri, title, artist, album, -1, duration, null, "file".equals(uri.getScheme()) ? uri.getPath() : null);
    }

    /** Album art as a normal (software) bitmap, or null. Slow: call off the main thread. */
    static Bitmap art(Context context, Song song, int size) {
        Bitmap bitmap = artRaw(context, song, size);
        // Hardware bitmaps cannot be read, scaled for the widget or sent in a notification.
        if (bitmap != null && Build.VERSION.SDK_INT >= 26 && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (copy != null) bitmap = copy;
        }
        return bitmap;
    }

    private static Bitmap artRaw(Context context, Song song, int size) {
        if (song == null) return null;
        if (Build.VERSION.SDK_INT >= 29) {
            try { return context.getContentResolver().loadThumbnail(song.uri, new Size(size, size), null); }
            catch (Exception ignored) { }
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, song.uri);
            byte[] picture = retriever.getEmbeddedPicture();
            if (picture == null) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(picture, 0, picture.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2;
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
        } catch (Exception ignored) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    static String time(long ms) {
        long t = Math.max(0, ms / 1000);
        return t >= 3600 ? String.format(java.util.Locale.US, "%d:%02d:%02d", t / 3600, t % 3600 / 60, t % 60)
                : String.format(java.util.Locale.US, "%d:%02d", t / 60, t % 60);
    }

    private static String lastSegment(String relativePath) {
        if (relativePath == null) return null;
        String[] parts = relativePath.split("/");
        for (int i = parts.length - 1; i >= 0; i--) if (!parts[i].trim().isEmpty()) return parts[i];
        return null;
    }
}
