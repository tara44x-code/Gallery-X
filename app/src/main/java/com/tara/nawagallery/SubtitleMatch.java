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
        if (subBase.length() <= videoBase.length() || !subBase.startsWith(videoBase)) return fuzzy(videoBase, subBase, preferredLanguage);
        char separator = subBase.charAt(videoBase.length());
        if (separator != '.' && separator != '_' && separator != '-' && separator != ' ') return -1;
        String tail = subBase.substring(videoBase.length() + 1);
        if (preferredLanguage != null && !preferredLanguage.isEmpty() && hasLanguage(tail, preferredLanguage)) return 30;
        return 20;
    }

    // Words that describe the file, not the film: they may differ between a video and its subtitle.
    private static final java.util.Set<String> NOISE = new java.util.HashSet<>(java.util.Arrays.asList(
            "480p", "576p", "720p", "1080p", "1080i", "1440p", "2160p", "4k", "8k", "uhd", "fhd", "hd", "sd",
            "x264", "x265", "h264", "h265", "hevc", "avc", "av1", "10bit", "8bit", "hdr", "hdr10", "dv", "sdr",
            "bluray", "blu", "ray", "brrip", "bdrip", "bdremux", "remux", "webrip", "web", "dl", "webdl", "hdrip", "dvdrip", "dvd",
            "hdtv", "hdcam", "cam", "ts", "aac", "aac2", "ac3", "eac3", "dd5", "ddp5", "dts", "atmos", "mp3",
            "yify", "yts", "rarbg", "psa", "pahe", "proper", "repack", "extended", "uncut", "internal", "amzn", "nf", "dsnp",
            "hmax", "atvp", "mkv", "mp4", "avi", "sub", "subs", "subtitle", "subtitles", "forced", "sdh", "cc", "hi"));
    private static final java.util.Set<String> LANGUAGES = new java.util.HashSet<>(java.util.Arrays.asList(
            "ar", "ara", "arabic", "arab", "عربي", "عربية", "en", "eng", "english", "fr", "fre", "french", "es", "spa", "spanish",
            "tr", "tur", "turkish", "de", "ger", "german", "it", "ita", "italian", "fa", "per", "persian", "ku", "kurdish",
            "ru", "rus", "russian", "pt", "por", "portuguese", "hi", "hin", "hindi", "ur", "urdu", "ja", "jpn", "japanese",
            "ko", "kor", "korean", "zh", "chi", "chinese"));
    private static final java.util.regex.Pattern EPISODE = java.util.regex.Pattern.compile("s(\\d{1,2})\\s?e(\\d{1,3})|(\\d{1,2})x(\\d{2,3})");

    /**
     * Names that are not exactly the same but describe the same film or episode, for example
     * "Show S01E01 1080p.mp4" and "Show S01E01 arabic.srt": 35/36 when only quality and language words differ,
     * 25/26 when most words agree. Different episode numbers never match.
     */
    static int fuzzy(String videoBase, String subBase, String preferredLanguage) {
        String v = normalize(videoBase), sub = normalize(subBase);
        String ev = episode(v), es = episode(sub);
        if (ev != null && es != null && !ev.equals(es)) return -1;
        if ((ev == null) != (es == null)) return -1;
        java.util.Set<String> vt = words(v), st = words(sub);
        int need = ev != null ? 1 : 2; // an episode number already ties them together
        if (vt.size() < need || st.size() < need) return -1;
        int bonus = 0;
        if (preferredLanguage != null && !preferredLanguage.isEmpty() && hasLanguage(sub, preferredLanguage)) bonus = 1;
        if (vt.equals(st)) return 35 + bonus;
        java.util.Set<String> both = new java.util.HashSet<>(vt);
        both.retainAll(st);
        java.util.Set<String> any = new java.util.HashSet<>(vt);
        any.addAll(st);
        return both.size() >= need && both.size() >= any.size() * 0.75 ? 25 + bonus : -1;
    }

    private static java.util.Set<String> words(String normalized) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String w : normalized.split(" ")) {
            if (w.isEmpty() || NOISE.contains(w) || LANGUAGES.contains(w)) continue;
            if (w.matches("s\\d{1,2}e\\d{1,3}|\\d{1,2}x\\d{2,3}|e\\d{1,3}|s\\d{1,2}")) continue; // compared through episode()
            out.add(w);
        }
        return out;
    }

    private static String episode(String normalized) {
        java.util.regex.Matcher m = EPISODE.matcher(normalized);
        if (!m.find()) return null;
        return m.group(1) != null ? Integer.parseInt(m.group(1)) + "x" + Integer.parseInt(m.group(2))
                : Integer.parseInt(m.group(3)) + "x" + Integer.parseInt(m.group(4));
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
