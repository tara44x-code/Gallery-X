package com.tara.nawagallery;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

import javax.crypto.SecretKey;

/**
 * Serves vault files decrypted on the fly through a seekable proxy descriptor, so images open
 * and videos play and seek without ever writing a clear copy to storage. Nothing is served while
 * the vault is locked.
 */
public class VaultContentProvider extends ContentProvider {
    private static HandlerThread ioThread;

    @Override public boolean onCreate() { return true; }

    private static synchronized Handler ioHandler() {
        if (ioThread == null) {
            ioThread = new HandlerThread("vault-io");
            ioThread.start();
        }
        return new Handler(ioThread.getLooper());
    }

    @Override public String getType(Uri uri) {
        VaultCrypto.Meta meta = VaultStore.meta(getContext(), uri);
        return meta == null || meta.mime.isEmpty() ? "application/octet-stream" : meta.mime;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        SecretKey key = VaultSession.key();
        if (key == null) throw new FileNotFoundException("vault locked");
        File file = VaultStore.fileForUri(getContext(), uri);
        if (file == null || !file.isFile()) throw new FileNotFoundException("missing");
        VaultCrypto.Reader reader;
        try {
            reader = new VaultCrypto.Reader(key, file);
        } catch (IOException error) {
            throw new FileNotFoundException("cannot decrypt");
        }
        return proxy(new ReaderCallback(reader));
    }

    /** Video thumbnails for the grid: a frame is extracted in memory and served as JPEG. */
    @Override public AssetFileDescriptor openTypedAssetFile(Uri uri, String filter, Bundle opts)
            throws FileNotFoundException {
        VaultCrypto.Meta meta = VaultStore.meta(getContext(), uri);
        boolean wantsImage = filter != null && filter.startsWith("image/");
        if (meta != null && meta.mime.startsWith("video/") && wantsImage) {
            byte[] jpeg = videoFrame(uri);
            if (jpeg == null) throw new FileNotFoundException("no frame");
            return new AssetFileDescriptor(proxy(new BytesCallback(jpeg)), 0, jpeg.length);
        }
        return super.openTypedAssetFile(uri, filter, opts);
    }

    private byte[] videoFrame(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try (ParcelFileDescriptor descriptor = openFile(uri, "r")) {
            retriever.setDataSource(descriptor.getFileDescriptor());
            Bitmap frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) frame = retriever.getFrameAtTime();
            if (frame == null) return null;
            int max = Math.max(frame.getWidth(), frame.getHeight());
            if (max > 512) {
                float scale = 512f / max;
                Bitmap scaled = Bitmap.createScaledBitmap(frame, Math.round(frame.getWidth() * scale),
                        Math.round(frame.getHeight() * scale), true);
                if (scaled != frame) frame.recycle();
                frame = scaled;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            frame.compress(Bitmap.CompressFormat.JPEG, 85, out);
            frame.recycle();
            return out.toByteArray();
        } catch (Exception ignored) {
            return null;
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
    }

    private ParcelFileDescriptor proxy(ProxyFileDescriptorCallback callback) throws FileNotFoundException {
        Context context = getContext();
        StorageManager storage = context == null ? null : context.getSystemService(StorageManager.class);
        if (storage == null) throw new FileNotFoundException("storage unavailable");
        try {
            return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, ioHandler());
        } catch (IOException error) {
            throw new FileNotFoundException("proxy failed");
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        VaultCrypto.Meta meta = VaultStore.meta(getContext(), uri);
        File file = VaultStore.fileForUri(getContext(), uri);
        SecretKey key = VaultSession.key();
        if (meta == null || file == null || key == null) return cursor;
        long size = -1L;
        try (VaultCrypto.Reader reader = new VaultCrypto.Reader(key, file)) { size = reader.length(); }
        catch (IOException ignored) { }
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = meta.name;
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = size;
            else row[i] = null;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    private static final class ReaderCallback extends ProxyFileDescriptorCallback {
        private final VaultCrypto.Reader reader;
        ReaderCallback(VaultCrypto.Reader reader) { this.reader = reader; }

        @Override public long onGetSize() { return reader.length(); }

        @Override public int onRead(long offset, int size, byte[] data) throws ErrnoException {
            try {
                return reader.read(offset, size, data, 0);
            } catch (IOException error) {
                throw new ErrnoException("onRead", OsConstants.EIO);
            }
        }

        @Override public void onRelease() {
            try { reader.close(); } catch (IOException ignored) { }
        }
    }

    private static final class BytesCallback extends ProxyFileDescriptorCallback {
        private final byte[] bytes;
        BytesCallback(byte[] bytes) { this.bytes = bytes; }

        @Override public long onGetSize() { return bytes.length; }

        @Override public int onRead(long offset, int size, byte[] data) {
            if (offset >= bytes.length) return 0;
            int count = (int) Math.min(size, bytes.length - offset);
            System.arraycopy(bytes, (int) offset, data, 0, count);
            return count;
        }

        @Override public void onRelease() { }
    }
}
