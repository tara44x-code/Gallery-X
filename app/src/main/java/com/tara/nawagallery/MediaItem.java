package com.tara.nawagallery;

import android.net.Uri;

public final class MediaItem {
    public final long id;
    public final Uri uri;
    public final String name;
    public final String bucketId;
    public final String bucketName;
    public final long dateTaken;
    public final String mimeType;
    public final String filePath;
    public final long sizeBytes;
    public final long durationMs;

    public MediaItem(long id, Uri uri, String name, String bucketId,
                     String bucketName, long dateTaken, String mimeType) {
        this(id, uri, name, bucketId, bucketName, dateTaken, mimeType, null, -1L, 0L);
    }

    public MediaItem(long id, Uri uri, String name, String bucketId,
                     String bucketName, long dateTaken, String mimeType,
                     String filePath, long sizeBytes) {
        this(id, uri, name, bucketId, bucketName, dateTaken, mimeType, filePath, sizeBytes, 0L);
    }

    public MediaItem(long id, Uri uri, String name, String bucketId,
                     String bucketName, long dateTaken, String mimeType,
                     String filePath, long sizeBytes, long durationMs) {
        this.id = id;
        this.uri = uri;
        this.name = name;
        this.bucketId = bucketId;
        this.bucketName = bucketName;
        this.dateTaken = dateTaken;
        this.mimeType = mimeType;
        this.filePath = filePath;
        this.sizeBytes = sizeBytes;
        this.durationMs = durationMs;
    }

    public boolean isVideo() {
        return mimeType != null && mimeType.startsWith("video/");
    }
}
