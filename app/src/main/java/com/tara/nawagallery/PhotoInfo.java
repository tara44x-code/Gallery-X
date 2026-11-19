package com.tara.nawagallery;

import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The text of the ⓘ panels: photo details (size, resolution, camera, place) and media file sizes. */
final class PhotoInfo {
    private PhotoInfo() {}

    static String describe(Context context, MediaItem item) {
        StringBuilder out = new StringBuilder();
        line(out, "الاسم", item.name);
        String type = null;
        try { type = context.getContentResolver().getType(item.uri); } catch (Exception ignored) { }
        if (type == null || type.endsWith("/*")) type = item.mimeType;
        line(out, "النوع", type == null ? null : type.replace("image/", "").toUpperCase(Locale.US));
        long bytes = size(context, item.uri, item.sizeBytes);
        line(out, "الحجم", bytes > 0 ? readableSize(bytes) : null);

        int width = 0, height = 0;
        try (InputStream in = context.getContentResolver().openInputStream(item.uri)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, bounds);
            width = bounds.outWidth;
            height = bounds.outHeight;
        } catch (Exception ignored) { }

        ExifInterface exif = null;
        try (InputStream in = context.getContentResolver().openInputStream(item.uri)) {
            if (in != null) exif = new ExifInterface(in);
        } catch (Exception ignored) { }
        if (exif != null) {
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                int t = width; width = height; height = t;
            }
        }
        if (width > 0 && height > 0) {
            line(out, "الدقة", width + " × " + height + String.format(Locale.US, "  (%.1f ميغابكسل)", width * (double) height / 1_000_000));
        }

        String taken = exif == null ? null : exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
        if (taken == null && exif != null) taken = exif.getAttribute(ExifInterface.TAG_DATETIME);
        if (taken != null) taken = taken.replaceFirst("^(\\d{4}):(\\d{2}):(\\d{2})", "$1/$2/$3");
        else if (item.dateTaken > 0) taken = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(new Date(item.dateTaken));
        line(out, "التاريخ", taken);

        if (exif != null) {
            String make = exif.getAttribute(ExifInterface.TAG_MAKE), model = exif.getAttribute(ExifInterface.TAG_MODEL);
            String camera = model == null ? make : make == null || model.toLowerCase(Locale.ROOT).startsWith(make.toLowerCase(Locale.ROOT)) ? model : make + " " + model;
            line(out, "الكاميرا", camera);
            StringBuilder shot = new StringBuilder();
            String f = exif.getAttribute(ExifInterface.TAG_F_NUMBER);
            if (f != null) shot.append("f/").append(trim(f)).append("   ");
            String exposure = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
            if (exposure != null) {
                try {
                    double e = Double.parseDouble(exposure);
                    shot.append(e >= 1 ? trim(exposure) + "s" : "1/" + Math.round(1 / e) + "s").append("   ");
                } catch (NumberFormatException ignored) { }
            }
            String iso = exif.getAttribute("ISOSpeedRatings");
            if (iso != null) shot.append("ISO ").append(iso).append("   ");
            double focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0);
            if (focal > 0) shot.append(trim(String.valueOf(focal))).append(" مم");
            line(out, "الإعدادات", shot.toString().trim());
            int flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1);
            if (flash >= 0) line(out, "الفلاش", (flash & 1) == 1 ? "اشتغل" : "ما اشتغل");
            float[] latLong = new float[2];
            if (exif.getLatLong(latLong)) line(out, "الموقع", String.format(Locale.US, "%.5f, %.5f", latLong[0], latLong[1]));
        }

        if (VaultStore.isVaultUri(item.uri)) line(out, "المكان", "الخزنة الخاصة (مشفّرة)");
        else if (item.filePath != null) {
            File parent = new File(item.filePath).getParentFile();
            line(out, "المجلد", parent == null ? null : parent.getName());
            line(out, "المسار", item.filePath);
        } else line(out, "المجلد", item.bucketName);
        return out.length() == 0 ? "ما گدرت أقرا معلومات هالملف." : out.toString().trim();
    }

    static long size(Context context, android.net.Uri uri, long known) {
        if (known > 0) return known;
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) { }
        return -1;
    }

    static String readableSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024 * 1024) return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }

    static void line(StringBuilder out, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        out.append(label).append(": ").append(value.trim()).append("\n\n");
    }

    private static String trim(String number) {
        try {
            double v = Double.parseDouble(number);
            return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
        } catch (NumberFormatException e) { return number; }
    }
}
