package com.tara.nawagallery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads synced lyrics (.lrc): "[01:23.45] line", several stamps per line, and [offset:+/-ms]. Pure Java. */
public final class LrcParser {
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE);

    private LrcParser() {}

    public static final class Line {
        public final long time;
        public final String text;
        Line(long time, String text) { this.time = time; this.text = text; }
    }

    public static List<Line> parse(String content) {
        List<Line> lines = new ArrayList<>();
        if (content == null) return lines;
        long offset = 0;
        Matcher o = OFFSET.matcher(content);
        if (o.find()) try { offset = Long.parseLong(o.group(1)); } catch (NumberFormatException ignored) { }
        for (String raw : content.replace("\r", "").split("\n")) {
            Matcher m = STAMP.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                long minutes = Long.parseLong(m.group(1));
                long seconds = Long.parseLong(m.group(2));
                String f = m.group(3);
                long millis = 0;
                if (f != null) millis = f.length() == 1 ? Long.parseLong(f) * 100 : f.length() == 2 ? Long.parseLong(f) * 10 : Long.parseLong(f);
                times.add(minutes * 60_000 + seconds * 1000 + millis);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = raw.substring(end).trim();
            text = text.replaceAll("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>", "").trim(); // word-level stamps
            for (long t : times) lines.add(new Line(Math.max(0, t - offset), text));
        }
        Collections.sort(lines, (a, b) -> Long.compare(a.time, b.time));
        return lines;
    }

    /** Index of the line being sung at {@code position}, or -1 before the first line. */
    public static int current(List<Line> lines, long position) {
        int low = 0, high = lines.size() - 1, found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).time <= position) { found = mid; low = mid + 1; } else high = mid - 1;
        }
        return found;
    }

    /** True when the name looks like a lyrics file for {@code songFile} ("song.mp3" → "song.lrc"). */
    public static boolean matches(String songFile, String candidate) {
        if (songFile == null || candidate == null) return false;
        String c = candidate.toLowerCase(Locale.ROOT);
        if (!c.endsWith(".lrc")) return false;
        String s = songFile.toLowerCase(Locale.ROOT);
        int dot = s.lastIndexOf('.');
        String base = dot > 0 ? s.substring(0, dot) : s;
        return c.substring(0, c.length() - 4).equals(base);
    }
}
