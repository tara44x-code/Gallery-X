package com.tara.nawagallery;

import android.content.ContentUris;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GalleryRepository {
    private GalleryRepository() {}

    public static final class ImageSession {
        public final List<MediaItem> items;
        public final int selectedIndex;
        ImageSession(List<MediaItem> items, int selectedIndex) {
            this.items = items;
            this.selectedIndex = selectedIndex;
        }
    }

    /** Finds the MediaStore folder of an image opened by an external file manager. */
    public static ImageSession loadImageSession(Context context, Uri incoming) {
        Uri resolved = incoming;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Uri mediaUri = MediaStore.getMediaUri(context, incoming);
                if (mediaUri != null) resolved = mediaUri;
            } catch (Exception ignored) { }
        }

        String targetName = null;
        String targetBucket = null;
        String targetPath = null;
        String targetRelative = relativeFolderFromDocument(context, incoming);
        String[] targetProjection = {
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.DATA,
                Build.VERSION.SDK_INT >= 29 ? MediaStore.Images.Media.RELATIVE_PATH : MediaStore.Images.Media.DATA
        };
        try (Cursor cursor = context.getContentResolver().query(resolved, targetProjection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                targetName = cursor.getString(0);
                targetBucket = cursor.getString(1);
                targetPath = cursor.getString(2);
                if (Build.VERSION.SDK_INT >= 29 && targetRelative == null) targetRelative = cursor.getString(3);
            }
        } catch (Exception ignored) { }
        if (targetName == null) {
            try (Cursor cursor = context.getContentResolver().query(incoming,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) targetName = cursor.getString(0);
            } catch (Exception ignored) { }
        }
        if (targetName == null) targetName = incoming.getLastPathSegment();

        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATA, MediaStore.Images.Media.SIZE,
                Build.VERSION.SDK_INT >= 29 ? MediaStore.Images.Media.RELATIVE_PATH : MediaStore.Images.Media.DATA
        };
        StringBuilder selection = new StringBuilder(MediaStore.Images.Media.SIZE + ">0");
        List<String> arguments = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) selection.append(" AND ").append(MediaStore.Images.Media.IS_PENDING).append("=0");
        if (Build.VERSION.SDK_INT >= 30) selection.append(" AND ").append(MediaStore.Images.Media.IS_TRASHED).append("=0");
        if (targetBucket != null && !targetBucket.isEmpty()) {
            selection.append(" AND ").append(MediaStore.Images.Media.BUCKET_ID).append("=?");
            arguments.add(targetBucket);
        } else if (Build.VERSION.SDK_INT >= 29 && targetRelative != null && !targetRelative.isEmpty()) {
            selection.append(" AND ").append(MediaStore.Images.Media.RELATIVE_PATH).append("=?");
            arguments.add(targetRelative);
        }

        List<MediaItem> items = new ArrayList<>();
        int selected = -1;
        try (Cursor cursor = context.getContentResolver().query(collection, projection,
                selection.toString(), arguments.isEmpty() ? null : arguments.toArray(new String[0]),
                MediaStore.Images.Media.DATE_TAKEN + " DESC, " + MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                Uri itemUri = ContentUris.withAppendedId(collection, id);
                String name = cursor.getString(1);
                String bucket = cursor.getString(2);
                String path = cursor.getString(6);
                String relative = cursor.getString(8);
                MediaItem item = new MediaItem(id, itemUri, name,
                        bucket == null ? "external" : bucket,
                        cursor.getString(3) == null ? "صور" : cursor.getString(3),
                        cursor.getLong(4), imageType(cursor.getString(5), name), path, cursor.getLong(7));
                boolean same = resolved.equals(itemUri)
                        || (targetPath != null && targetPath.equals(path))
                        || (targetName != null && targetName.equals(name)
                        && (targetRelative == null || targetRelative.equals(relative)));
                if (same) selected = items.size();
                items.add(item);
            }
        } catch (Exception ignored) { }

        if (selected < 0 && !items.isEmpty() && targetName != null) {
            for (int i = 0; i < items.size(); i++) {
                if (targetName.equals(items.get(i).name)) { selected = i; break; }
            }
        }
        if (selected < 0) {
            MediaItem only = new MediaItem(0, incoming, targetName == null ? "صورة" : targetName,
                    "external", "صور", 0, "image/*", targetPath, -1);
            items.clear();
            items.add(only);
            selected = 0;
        }
        return new ImageSession(items, selected);
    }

    private static String relativeFolderFromDocument(Context context, Uri uri) {
        if (Build.VERSION.SDK_INT < 19 || !DocumentsContract.isDocumentUri(context, uri)) return null;
        try {
            String id = DocumentsContract.getDocumentId(uri);
            int colon = id.indexOf(':');
            if (colon < 0 || colon + 1 >= id.length()) return null;
            String path = id.substring(colon + 1);
            int slash = path.lastIndexOf('/');
            return slash < 0 ? "" : path.substring(0, slash + 1);
        } catch (Exception ignored) { return null; }
    }

    public static List<Album> loadAlbums(Context context) {
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.SIZE
        };

        Map<String, Album> albums = new LinkedHashMap<>();
        Album all = new Album("__all__", context.getString(R.string.all_photos));
        String selection = MediaStore.Images.Media.SIZE + ">0";
        if (Build.VERSION.SDK_INT >= 29) {
            selection += " AND " + MediaStore.Images.Media.IS_PENDING + "=0";
        }
        if (Build.VERSION.SDK_INT >= 30) {
            selection += " AND " + MediaStore.Images.Media.IS_TRASHED + "=0";
        }
        try (Cursor cursor = context.getContentResolver().query(
                collection, projection, selection, null,
                MediaStore.Images.Media.DATE_TAKEN + " DESC, " + MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) return new ArrayList<>();

            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID);
            int bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);
            int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE);
            int pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String bucketId = cursor.getString(bucketIdCol);
                if (bucketId == null) bucketId = "unknown";
                String bucketName = cursor.getString(bucketNameCol);
                if (bucketName == null || bucketName.trim().isEmpty()) bucketName = "صور أخرى";
                Uri uri = ContentUris.withAppendedId(collection, id);
                MediaItem item = new MediaItem(id, uri, cursor.getString(nameCol), bucketId,
                        bucketName, cursor.getLong(dateCol), imageType(cursor.getString(mimeCol), cursor.getString(nameCol)),
                        cursor.getString(pathCol), cursor.getLong(sizeCol));
                all.items.add(item);
                Album album = albums.get(bucketId);
                if (album == null) {
                    album = new Album(bucketId, bucketName);
                    albums.put(bucketId, album);
                }
                album.items.add(item);
            }
        } catch (SecurityException ignored) {
            return new ArrayList<>();
        }

        loadVideos(context, albums, all);
        all.items.sort(Comparator.comparingLong((MediaItem item) -> item.dateTaken).reversed());
        for (Album album : albums.values()) {
            album.items.sort(Comparator.comparingLong((MediaItem item) -> item.dateTaken).reversed());
        }

        List<Album> result = new ArrayList<>();
        if (!all.items.isEmpty()) result.add(all);
        result.addAll(albums.values());
        return result;
    }

    private static void loadVideos(Context context, Map<String, Album> albums, Album all) {
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.BUCKET_ID,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION
        };
        String selection = MediaStore.Video.Media.SIZE + ">0";
        if (Build.VERSION.SDK_INT >= 29) selection += " AND " + MediaStore.Video.Media.IS_PENDING + "=0";
        if (Build.VERSION.SDK_INT >= 30) selection += " AND " + MediaStore.Video.Media.IS_TRASHED + "=0";
        try (Cursor cursor = context.getContentResolver().query(collection, projection, selection, null,
                MediaStore.Video.Media.DATE_TAKEN + " DESC, " + MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) return;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID);
            int bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN);
            int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE);
            int pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
            int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String bucketId = cursor.getString(bucketIdCol);
                if (bucketId == null) bucketId = "video_unknown";
                String bucketName = cursor.getString(bucketNameCol);
                if (bucketName == null || bucketName.trim().isEmpty()) bucketName = "فيديوهات أخرى";
                MediaItem item = new MediaItem(id, ContentUris.withAppendedId(collection, id),
                        cursor.getString(nameCol), bucketId, bucketName, cursor.getLong(dateCol),
                        videoType(cursor.getString(mimeCol), cursor.getString(nameCol)), cursor.getString(pathCol), cursor.getLong(sizeCol),
                        cursor.getLong(durationCol));
                all.items.add(item);
                Album album = albums.get(bucketId);
                if (album == null) {
                    album = new Album(bucketId, bucketName);
                    albums.put(bucketId, album);
                }
                album.items.add(item);
            }
        } catch (Exception ignored) { }
    }

    public static List<MediaItem> loadTrashedImages(Context context) {
        List<MediaItem> result = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 30) return result;
        Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.SIZE
        };
        Bundle queryArgs = new Bundle();
        queryArgs.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY);
        queryArgs.putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS,
                new String[]{MediaStore.Images.Media.DATE_ADDED});
        queryArgs.putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING);
        try (Cursor cursor = context.getContentResolver().query(
                collection, projection, queryArgs, null)) {
            if (cursor == null) return result;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
            int bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID);
            int bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);
            int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE);
            int pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                String bucketId = cursor.getString(bucketIdCol);
                String bucketName = cursor.getString(bucketNameCol);
                result.add(new MediaItem(id, ContentUris.withAppendedId(collection, id),
                        cursor.getString(nameCol), bucketId == null ? "trash" : bucketId,
                        bucketName == null ? "سلة المحذوفات" : bucketName,
                        cursor.getLong(dateCol), imageType(cursor.getString(mimeCol), cursor.getString(nameCol)),
                        cursor.getString(pathCol), cursor.getLong(sizeCol)));
            }
        } catch (Exception ignored) { }
        loadTrashedVideos(context, result);
        result.sort(Comparator.comparingLong((MediaItem item) -> item.dateTaken).reversed());
        return result;
    }

    private static void loadTrashedVideos(Context context, List<MediaItem> result) {
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.DATE_TAKEN, MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DATA, MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION
        };
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY);
        try (Cursor cursor = context.getContentResolver().query(collection, projection, args, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String bucketId = cursor.getString(2);
                String bucketName = cursor.getString(3);
                result.add(new MediaItem(id, ContentUris.withAppendedId(collection, id),
                        cursor.getString(1), bucketId == null ? "trash_video" : bucketId,
                        bucketName == null ? "سلة المحذوفات" : bucketName,
                        cursor.getLong(4), videoType(cursor.getString(5), cursor.getString(1)), cursor.getString(6),
                        cursor.getLong(7), cursor.getLong(8)));
            }
        } catch (Exception ignored) { }
    }

    /** MediaStore occasionally reports no type; a file from the video table is always a video. */
    static String videoType(String mime, String name) {
        if (MediaTypes.isVideo(mime)) return mime;
        String guess = MediaTypes.fromName(name);
        return MediaTypes.isVideo(guess) ? guess : "video/*";
    }

    static String imageType(String mime, String name) {
        if (MediaTypes.isImage(mime)) return mime;
        String guess = MediaTypes.fromName(name);
        return MediaTypes.isImage(guess) ? guess : "image/*";
    }
}
