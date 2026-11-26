package com.tara.nawagallery;

import java.util.Locale;

/** Decides whether a subtitle file belongs to a video by its name (pure Java). */
public final class SubtitleMatch {
    private SubtitleMatch() {}

    public static boolean isSubtitle(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".ass") || lower.endsWith(".ssa");
    }

    /**
     * -1 = not this video's subtitle. Higher is better:
     * 40 "film.srt" for "film.mp4"; 30 "film.ar.srt" when Arabic is the chosen language; 20 "film.en.srt", "film_eng.srt"…
     * (10–11 are used by {@link #looseScore} for subtitles found inside the film's own folder.)
     */
    public static int score(String videoName, String subtitleName, String preferredLanguage) {
        if (videoName == null || !isSubtitle(subtitleName)) return -1;
        String sub = subtitleName.toLowerCase(Locale.ROOT);
        String subBase = sub.substring(0, sub.lastIndexOf('.'));
        String video = videoName.toLowerCase(Locale.ROOT);
        int dot = video.lastIndexOf('.');
        String videoBase = dot > 0 ? video.substring(0, dot) : video;
        if (videoBase.isEmpty()) return -1;
        if (subBase.equals(videoBase)) return 40;
        if (subBase.length() <= videoBase.length() || !subBase.startsWith(videoBase)) return -1;
        char separator = subBase.charAt(videoBase.length());
        if (separator != '.' && separator != '_' && separator != '-' && separator != ' ') return -1;
        String tail = subBase.substring(videoBase.length() + 1);
        if (preferredLanguage != null && !preferredLanguage.isEmpty() && hasLanguage(tail, preferredLanguage)) return 30;
        return 20;
    }

    /**
     * Any subtitle inside the film's own folder (or its Subs folder), whatever its name:
     * 11 when it is in the chosen language, 10 otherwise, -1 if it is not a subtitle file.
     */
    public static int looseScore(String subtitleName, String preferredLanguage) {
        if (!isSubtitle(subtitleName)) return -1;
        String base = subtitleName.toLowerCase(Locale.ROOT);
        base = base.substring(0, base.lastIndexOf('.'));
        return preferredLanguage != null && !preferredLanguage.isEmpty() && hasLanguage(base, preferredLanguage) ? 11 : 10;
    }

    /** True when a folder is named after the film ("The.Movie.2024.1080p" for "The Movie 2024 1080p.mp4"). */
    public static boolean folderMatches(String folderName, String videoName) {
        if (folderName == null || videoName == null) return false;
        String video = videoName;
        int dot = video.lastIndexOf('.');
        if (dot > 0 && video.length() - dot <= 5) video = video.substring(0, dot);
        String a = normalize(folderName), b = normalize(video);
        if (a.length() < 4 || b.length() < 4) return false;
        if (a.equals(b)) return true;
        String shorter = a.length() < b.length() ? a : b, longer = a.length() < b.length() ? b : a;
        return shorter.length() >= 8 && longer.startsWith(shorter);
    }

    /** "Subs", "Subtitles", "ترجمة"… folders that films usually ship their subtitles in. */
    public static boolean isSubsFolder(String name) {
        if (name == null) return false;
        String n = normalize(name);
        return n.equals("subs") || n.equals("sub") || n.equals("subtitles") || n.equals("subtitle")
                || n.equals("srt") || n.equals("ترجمة") || n.equals("ترجمات") || n.equals("الترجمة");
    }

    static String normalize(String value) {
        StringBuilder out = new StringBuilder();
        boolean space = false;
        for (char c : value.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) { out.append(c); space = false; }
            else if (!space && out.length() > 0) { out.append(' '); space = true; }
        }
        return out.toString().trim();
    }

    private static boolean hasLanguage(String tail, String language) {
        String[] tags = "ar".equals(language) ? new String[]{"ar", "ara", "arabic", "عربي"}
                : "en".equals(language) ? new String[]{"en", "eng", "english"} : new String[]{language};
        for (String part : tail.split("[._\\- ]")) {
            for (String tag : tags) if (part.equals(tag)) return true;
        }
        return false;
    }
}
