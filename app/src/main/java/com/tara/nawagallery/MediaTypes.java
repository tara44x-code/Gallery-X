package com.tara.nawagallery;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Works out a file's real MIME type (pure Java). Used so a video is never shared or stored as
 * "image/*": that is what made apps such as Telegram receive videos as .jpg photos.
 */
public final class MediaTypes {
    public static final String UNKNOWN = "application/octet-stream";
    private static final Map<String, String> BY_EXTENSION = new HashMap<>();

    static {
        String[][] pairs = {
                {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"}, {"png", "image/png"}, {"gif", "image/gif"},
                {"webp", "image/webp"}, {"bmp", "image/bmp"}, {"heic", "image/heic"}, {"heif", "image/heif"},
                {"avif", "image/avif"}, {"dng", "image/x-adobe-dng"}, {"cr2", "image/x-canon-cr2"},
                {"nef", "image/x-nikon-nef"}, {"arw", "image/x-sony-arw"},
                {"mp4", "video/mp4"}, {"m4v", "video/mp4"}, {"mov", "video/quicktime"}, {"3gp", "video/3gpp"},
                {"3g2", "video/3gpp2"}, {"mkv", "video/x-matroska"}, {"webm", "video/webm"},
                {"avi", "video/x-msvideo"}, {"ts", "video/mp2t"}, {"m2ts", "video/mp2t"}, {"mts", "video/mp2t"},
                {"flv", "video/x-flv"}, {"wmv", "video/x-ms-wmv"}, {"mpg", "video/mpeg"}, {"mpeg", "video/mpeg"},
        };
        for (String[] pair : pairs) BY_EXTENSION.put(pair[0], pair[1]);
    }

    private MediaTypes() {}

    public static boolean isSpecific(String mime) {
        return mime != null && mime.contains("/") && !mime.endsWith("/*") && !UNKNOWN.equals(mime);
    }

    public static boolean isVideo(String mime) { return mime != null && mime.startsWith("video/"); }
    public static boolean isImage(String mime) { return mime != null && mime.startsWith("image/"); }

    /** From the file name only (case-insensitive); {@link #UNKNOWN} when the extension is not known. */
    public static String fromName(String name) {
        if (name == null) return UNKNOWN;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return UNKNOWN;
        String type = BY_EXTENSION.get(name.substring(dot + 1).toLowerCase(Locale.US));
        return type == null ? UNKNOWN : type;
    }

    /** From the first bytes of the file (at least 16 are useful); {@link #UNKNOWN} when not recognised. */
    public static String sniff(byte[] head, int length) {
        if (head == null || length < 4) return UNKNOWN;
        if (u(head, 0) == 0xFF && u(head, 1) == 0xD8 && u(head, 2) == 0xFF) return "image/jpeg";
        if (u(head, 0) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return "image/png";
        if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8') return "image/gif";
        if (head[0] == 'B' && head[1] == 'M') return "image/bmp";
        if (u(head, 0) == 0x1A && u(head, 1) == 0x45 && u(head, 2) == 0xDF && u(head, 3) == 0xA3) {
            // Matroska and WebM share the EBML header; the doc type says which.
            String text = new String(head, 0, length, java.nio.charset.StandardCharsets.ISO_8859_1);
            return text.contains("webm") ? "video/webm" : "video/x-matroska";
        }
        if (length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') {
            if (head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return "image/webp";
            if (head[8] == 'A' && head[9] == 'V' && head[10] == 'I') return "video/x-msvideo";
        }
        if (length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            String brand = new String(head, 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(Locale.US);
            if (brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("mif1") || brand.startsWith("msf1")) return "image/heic";
            if (brand.startsWith("avif")) return "image/avif";
            if (brand.startsWith("qt")) return "video/quicktime";
            if (brand.startsWith("3g2")) return "video/3gpp2";
            if (brand.startsWith("3gp")) return "video/3gpp";
            return "video/mp4";
        }
        if (u(head, 0) == 0x47 && length >= 189 && u(head, 188) == 0x47) return "video/mp2t";
        if (u(head, 0) == 0x00 && u(head, 1) == 0x00 && u(head, 2) == 0x01 && u(head, 3) == 0xBA) return "video/mpeg";
        if (head[0] == 'F' && head[1] == 'L' && head[2] == 'V') return "video/x-flv";
        return UNKNOWN;
    }

    /**
     * Best type from everything known about a file: a specific type reported by the system wins,
     * then the content, then the name, then a family hint such as "video/*".
     */
    public static String best(String reported, byte[] head, int headLength, String name) {
        if (isSpecific(reported)) return reported;
        String sniffed = sniff(head, headLength);
        if (!UNKNOWN.equals(sniffed)) return sniffed;
        String byName = fromName(name);
        if (!UNKNOWN.equals(byName)) return byName;
        return reported != null && reported.endsWith("/*") ? reported : UNKNOWN;
    }

    private static int u(byte[] data, int index) { return data[index] & 0xff; }
}
