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
     * 4 "film.srt" for "film.mp4"; 3 "film.ar.srt" when Arabic is the chosen language; 2 "film.en.srt", "film_eng.srt"…
     */
    public static int score(String videoName, String subtitleName, String preferredLanguage) {
        if (videoName == null || !isSubtitle(subtitleName)) return -1;
        String sub = subtitleName.toLowerCase(Locale.ROOT);
        String subBase = sub.substring(0, sub.lastIndexOf('.'));
        String video = videoName.toLowerCase(Locale.ROOT);
        int dot = video.lastIndexOf('.');
        String videoBase = dot > 0 ? video.substring(0, dot) : video;
        if (videoBase.isEmpty()) return -1;
        if (subBase.equals(videoBase)) return 4;
        if (subBase.length() <= videoBase.length() || !subBase.startsWith(videoBase)) return -1;
        char separator = subBase.charAt(videoBase.length());
        if (separator != '.' && separator != '_' && separator != '-' && separator != ' ') return -1;
        String tail = subBase.substring(videoBase.length() + 1);
        if (preferredLanguage != null && !preferredLanguage.isEmpty() && hasLanguage(tail, preferredLanguage)) return 3;
        return 2;
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
