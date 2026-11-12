package com.tara.nawagallery;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKey;

/**
 * Private vault: every file is stored encrypted (see {@link VaultCrypto}) under a random name.
 * Names, types and contents can only be read while {@link VaultSession} holds the key.
 */
public final class VaultStore {
    public static final String AUTHORITY = "com.tara.nawagallery.vault";
    public static final String RESTORE_FOLDER = "Gallery X Restored";
    private static final String FOLDER = "private_vault";
    private static final String EXTENSION = ".gxv";
    private static final Map<String, VaultCrypto.Meta> META = new ConcurrentHashMap<>();

    private VaultStore() {}

    public static File directory(Context context) {
        File folder = new File(context.getFilesDir(), FOLDER);
        if (!folder.exists()) folder.mkdirs();
        return folder;
    }

    /** Encrypts a gallery item into the vault. Returns the stored file name, or null on failure. */
    public static String copyToVault(Context context, MediaItem item) {
        SecretKey key = VaultSession.key();
        if (key == null) return null;
        String name = item.name == null || item.name.trim().isEmpty() ? "صورة.jpg" : item.name;
        String mime = MediaTypes.isSpecific(item.mimeType) ? item.mimeType : sniffSource(context, item, name);
        String fileName = randomName();
        File destination = new File(directory(context), fileName);
        File partial = new File(directory(context), fileName + ".part");
        try (InputStream input = context.getContentResolver().openInputStream(item.uri)) {
            if (input == null) return null;
            VaultCrypto.Meta meta = new VaultCrypto.Meta(name, mime, System.currentTimeMillis());
            VaultCrypto.encrypt(key, input, partial, meta);
            if (partial.length() <= 0L || !partial.renameTo(destination)) throw new Exception("copy failed");
            META.put(fileName, meta);
            return fileName;
        } catch (Exception ignored) {
            partial.delete();
            destination.delete();
            return null;
        }
    }

    public interface Progress { void step(int done, int total); }

    /** Adds a file picked from a file manager (any app's document) to the vault. */
    public static String importDocument(Context context, Uri uri, String name, String mime) {
        return copyToVault(context, new MediaItem(0, uri, name, "", "", 0, mime));
    }

    /** Writes every vault file into a backup protected by {@code password}. Returns how many. */
    public static int exportBackup(Context context, OutputStream output, String password, Progress progress) throws Exception {
        SecretKey key = VaultSession.key();
        if (key == null) throw new IllegalStateException("vault locked");
        File[] files = directory(context).listFiles(file -> file.isFile() && file.getName().endsWith(EXTENSION));
        if (files == null) files = new File[0];
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        VaultBackup.Writer writer = new VaultBackup.Writer(output, password, context.getCacheDir());
        int done = 0;
        for (File file : files) {
            try (VaultCrypto.Reader reader = new VaultCrypto.Reader(key, file)) {
                writer.add(reader.stream(), checked(reader));
            }
            done++;
            if (progress != null) progress.step(done, files.length);
        }
        writer.finish();
        writer.close();
        return writer.count();
    }

    /** Restores a backup into the vault (added next to what is already there). Returns how many. */
    public static int importBackup(Context context, InputStream input, String password, Progress progress) throws Exception {
        SecretKey key = VaultSession.key();
        if (key == null) throw new IllegalStateException("vault locked");
        int[] done = {0};
        try {
            return VaultBackup.read(input, password, context.getCacheDir(), reader -> {
                String fileName = randomName();
                File destination = new File(directory(context), fileName);
                File partial = new File(directory(context), fileName + ".part");
                try {
                    VaultCrypto.encrypt(key, reader.stream(), partial, reader.meta);
                    if (!partial.renameTo(destination)) throw new java.io.IOException("rename failed");
                    if (reader.meta.dateTaken > 0) destination.setLastModified(reader.meta.dateTaken);
                } catch (java.io.IOException error) {
                    partial.delete();
                    destination.delete();
                    throw error;
                }
                done[0]++;
                if (progress != null) progress.step(done[0], -1);
            });
        } finally {
            cleanPartials(context);
        }
    }

    /**
     * Encrypts files left in clear text by Gallery X 3.4.x and earlier, then deletes the clear copy.
     * Run off the main thread right after unlocking. Returns how many files were secured.
     */
    public static int migrateLegacyFiles(Context context) {
        SecretKey key = VaultSession.key();
        File[] files = directory(context).listFiles(file -> file.isFile()
                && !file.getName().endsWith(EXTENSION) && !file.getName().endsWith(".part"));
        if (key == null || files == null) return 0;
        int secured = 0;
        for (File plain : files) {
            String name = legacyDisplayName(plain.getName());
            String fileName = randomName();
            File destination = new File(directory(context), fileName);
            File partial = new File(directory(context), fileName + ".part");
            try (InputStream input = new FileInputStream(plain)) {
                VaultCrypto.encrypt(key, input, partial, new VaultCrypto.Meta(name, sniffFile(plain, name), plain.lastModified()));
                if (!partial.renameTo(destination)) throw new Exception("rename failed");
                destination.setLastModified(plain.lastModified());
                if (plain.delete()) secured++;
            } catch (Exception ignored) {
                partial.delete();
                destination.delete();
            }
        }
        cleanPartials(context);
        return secured;
    }

    public static List<MediaItem> load(Context context) {
        List<MediaItem> result = new ArrayList<>();
        SecretKey key = VaultSession.key();
        if (key == null) return result;
        File[] files = directory(context).listFiles(file -> file.isFile() && file.getName().endsWith(EXTENSION));
        if (files == null) return result;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File file : files) {
            try (VaultCrypto.Reader reader = new VaultCrypto.Reader(key, file)) {
                VaultCrypto.Meta meta = checked(reader);
                META.put(file.getName(), meta);
                result.add(new MediaItem(file.getName().hashCode(), uriFor(file.getName()), meta.name, "__vault__",
                        "الخزنة الخاصة", file.lastModified(), meta.mime, null, reader.length()));
            } catch (Exception ignored) {
                // Damaged or foreign file: never shown, never deleted automatically.
            }
        }
        return result;
    }

    public static boolean restoreToGallery(Context context, MediaItem item) {
        SecretKey key = VaultSession.key();
        File source = fileForUri(context, item.uri);
        if (key == null || source == null || !source.isFile()) return false;
        Uri destination = null;
        try (VaultCrypto.Reader reader = new VaultCrypto.Reader(key, source)) {
            String name = reader.meta.name;
            String mime = checked(reader).mime;
            boolean video = mime.startsWith("video/");
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/" + RESTORE_FOLDER);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            }
            Uri collection = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            destination = context.getContentResolver().insert(collection, values);
            if (destination == null) return false;
            try (OutputStream output = context.getContentResolver().openOutputStream(destination)) {
                if (output == null) throw new Exception("no output");
                reader.copyTo(output);
                output.flush();
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                context.getContentResolver().update(destination, ready, null, null);
            }
            if (source.delete()) {
                META.remove(source.getName());
                return true;
            }
            try { context.getContentResolver().delete(destination, null, null); } catch (Exception ignoredDelete) { }
            return false;
        } catch (Exception ignored) {
            if (destination != null) try { context.getContentResolver().delete(destination, null, null); } catch (Exception ignoredDelete) { }
            return false;
        }
    }

    public static boolean delete(Context context, Uri uri) {
        File file = fileForUri(context, uri);
        boolean deleted = file != null && file.isFile() && file.delete();
        if (deleted) META.remove(file.getName());
        return deleted;
    }

    public static boolean deleteFileName(Context context, String fileName) {
        File file = safeFile(context, fileName);
        boolean deleted = file != null && file.isFile() && file.delete();
        if (deleted) META.remove(fileName);
        return deleted;
    }

    public static boolean isVaultUri(Uri uri) {
        return uri != null && "content".equals(uri.getScheme()) && AUTHORITY.equals(uri.getAuthority());
    }

    public static Uri uriFor(String fileName) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("v").appendPath(fileName).build();
    }

    public static File fileForUri(Context context, Uri uri) {
        if (!isVaultUri(uri)) return null;
        return safeFile(context, uri.getLastPathSegment());
    }

    /** Cached metadata, available only while unlocked. */
    public static VaultCrypto.Meta meta(Context context, Uri uri) {
        SecretKey key = VaultSession.key();
        File file = fileForUri(context, uri);
        if (key == null || file == null || !file.isFile()) return null;
        VaultCrypto.Meta cached = META.get(file.getName());
        if (cached != null) return cached;
        try (VaultCrypto.Reader reader = new VaultCrypto.Reader(key, file)) {
            VaultCrypto.Meta meta = checked(reader);
            META.put(file.getName(), meta);
            return meta;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Forget decrypted names when the vault locks. */
    public static void forgetNames() { META.clear(); }

    private static File safeFile(Context context, String fileName) {
        if (fileName == null || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) return null;
        try {
            File root = directory(context).getCanonicalFile();
            File file = new File(root, fileName).getCanonicalFile();
            return file.getParentFile() != null && file.getParentFile().equals(root) ? file : null;
        } catch (Exception ignored) { return null; }
    }

    private static void cleanPartials(Context context) {
        File[] partials = directory(context).listFiles(file -> file.getName().endsWith(".part"));
        if (partials != null) for (File file : partials) file.delete();
    }

    private static String randomName() {
        byte[] bytes = VaultKeyWrap.randomBytes(12);
        StringBuilder value = new StringBuilder();
        for (byte b : bytes) value.append(String.format(Locale.US, "%02x", b & 0xff));
        return value + EXTENSION;
    }

    /**
     * Older versions could store a video as "image/*" (unknown extension). Such entries are
     * corrected from the decrypted content, so they play, preview and share as videos again.
     */
    private static VaultCrypto.Meta checked(VaultCrypto.Reader reader) {
        VaultCrypto.Meta meta = reader.meta;
        if (MediaTypes.isSpecific(meta.mime)) return meta;
        byte[] head = new byte[256];
        int length = 0;
        try { length = reader.read(0, head.length, head, 0); } catch (Exception ignored) { }
        String best = MediaTypes.best(meta.mime, head, length, meta.name);
        return new VaultCrypto.Meta(meta.name, MediaTypes.UNKNOWN.equals(best) ? "image/*" : best, meta.dateTaken);
    }

    private static String sniffSource(Context context, MediaItem item, String name) {
        byte[] head = new byte[256];
        int length = 0;
        try (InputStream in = context.getContentResolver().openInputStream(item.uri)) {
            int read;
            while (in != null && length < head.length && (read = in.read(head, length, head.length - length)) > 0) length += read;
        } catch (Exception ignored) { }
        String best = MediaTypes.best(item.mimeType, head, length, name);
        return MediaTypes.UNKNOWN.equals(best) ? (item.isVideo() ? "video/*" : "image/*") : best;
    }

    private static String sniffFile(File file, String name) {
        byte[] head = new byte[256];
        int length = 0;
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while (length < head.length && (read = in.read(head, length, head.length - length)) > 0) length += read;
        } catch (Exception ignored) { }
        String best = MediaTypes.best(null, head, length, name);
        return MediaTypes.UNKNOWN.equals(best) ? "image/*" : best;
    }

    private static String legacyDisplayName(String stored) {
        int separator = stored.indexOf("__");
        return separator >= 0 && separator + 2 < stored.length() ? stored.substring(separator + 2) : stored;
    }
}
