package com.tara.nawagallery;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads SRT, WebVTT and ASS/SSA subtitles (pure Java).
 * Detects UTF-8/UTF-16 and old Arabic (Windows-1256) or Western (Windows-1252) files,
 * removes styling tags and returns cues sorted by start time.
 */
public final class SubtitleParser {
    private static final Pattern TIME = Pattern.compile("(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})");
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");
    private static final Pattern ASS_TAG = Pattern.compile("\\{[^}]*\\}");

    private SubtitleParser() {}

    public static final class Cue {
        public final long start;
        public final long end;
        public final String text;
        /** Filled in later by the translator; null until then. */
        public volatile String translated;

        public Cue(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    public static List<Cue> parse(byte[] data) {
        String text = decode(data).replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("﻿")) text = text.substring(1);
        List<Cue> cues = looksLikeAss(text) ? parseAss(text) : parseTimed(text);
        Collections.sort(cues, (a, b) -> a.start != b.start ? Long.compare(a.start, b.start) : Long.compare(a.end, b.end));
        return cues;
    }

    // ------------------------------------------------------------------ encodings

    static String decode(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xff) == 0xEF && (data[1] & 0xff) == 0xBB && (data[2] & 0xff) == 0xBF)
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        if (data.length >= 2 && (data[0] & 0xff) == 0xFF && (data[1] & 0xff) == 0xFE)
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        if (data.length >= 2 && (data[0] & 0xff) == 0xFE && (data[1] & 0xff) == 0xFF)
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException notUtf8) {
            String arabic = new String(data, Charset.forName("windows-1256"));
            int high = 0, letters = 0;
            for (int i = 0; i < arabic.length(); i++) {
                char c = arabic.charAt(i);
                if (c < 128) continue;
                high++;
                if (c >= '؀' && c <= 'ۿ') letters++;
            }
            return letters * 2 >= high ? arabic : new String(data, Charset.forName("windows-1252"));
        }
    }

    // ------------------------------------------------------------------ SRT / VTT

    private static List<Cue> parseTimed(String text) {
        List<Cue> cues = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int arrow = line.indexOf("-->");
            if (arrow < 0) continue;
            long start = time(line.substring(0, arrow));
            long end = time(line.substring(arrow + 3));
            StringBuilder body = new StringBuilder();
            int j = i + 1;
            while (j < lines.length && !lines[j].trim().isEmpty() && !lines[j].contains("-->")) {
                if (body.length() > 0) body.append('\n');
                body.append(lines[j].trim());
                j++;
            }
            i = j - 1;
            String clean = clean(body.toString());
            if (start >= 0 && end > start && !clean.isEmpty()) cues.add(new Cue(start, end, clean));
        }
        return cues;
    }

    static long time(String value) {
        Matcher m = TIME.matcher(value);
        if (!m.find()) return -1;
        long hours = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long minutes = Long.parseLong(m.group(2));
        long seconds = Long.parseLong(m.group(3));
        String fraction = m.group(4);
        long millis = Long.parseLong(fraction);
        if (fraction.length() == 1) millis *= 100;
        else if (fraction.length() == 2) millis *= 10;
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1000L + millis;
    }

    // ------------------------------------------------------------------ ASS / SSA

    private static boolean looksLikeAss(String text) {
        String head = text.substring(0, Math.min(text.length(), 4000)).toLowerCase(Locale.US);
        return head.contains("[script info]") || head.contains("[events]");
    }

    private static List<Cue> parseAss(String text) {
        List<Cue> cues = new ArrayList<>();
        int startField = 1, endField = 2, textField = 9, fieldCount = 10;
        boolean events = false;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            String lower = line.toLowerCase(Locale.US);
            if (lower.startsWith("[")) {
                events = lower.startsWith("[events]");
                continue;
            }
            if (events && lower.startsWith("format:")) {
                String[] names = line.substring(7).split(",");
                for (int i = 0; i < names.length; i++) {
                    String name = names[i].trim().toLowerCase(Locale.US);
                    if (name.equals("start")) startField = i;
                    else if (name.equals("end")) endField = i;
                    else if (name.equals("text")) textField = i;
                }
                fieldCount = names.length;
                continue;
            }
            if (!lower.startsWith("dialogue:")) continue;
            String[] parts = line.substring(9).split(",", fieldCount);
            if (parts.length <= Math.max(textField, Math.max(startField, endField))) continue;
            long start = time(parts[startField].trim());
            long end = time(parts[endField].trim());
            String body = parts[textField];
            if (body.contains("\\p1") || body.contains("\\p2")) continue; // vector drawings, not text
            body = ASS_TAG.matcher(body).replaceAll("").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ");
            String clean = clean(body);
            if (start >= 0 && end > start && !clean.isEmpty()) cues.add(new Cue(start, end, clean));
        }
        return cues;
    }

    // ------------------------------------------------------------------ cleanup

    static String clean(String value) {
        String text = HTML_TAG.matcher(value).replaceAll("");
        text = ASS_TAG.matcher(text).replaceAll("");
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'");
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(trimmed);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ lookup

    /** Index of the last cue that has started at {@code position}, or -1. */
    public static int lastStarted(List<Cue> cues, long position) {
        int low = 0, high = cues.size() - 1, found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (cues.get(mid).start <= position) { found = mid; low = mid + 1; }
            else high = mid - 1;
        }
        return found;
    }

    /** Indexes of cues showing at {@code position}, oldest first (overlapping lines are common in ASS). */
    public static List<Integer> active(List<Cue> cues, long position) {
        List<Integer> result = new ArrayList<>(2);
        int last = lastStarted(cues, position);
        for (int i = last; i >= 0 && i > last - 12; i--) {
            if (cues.get(i).end > position) result.add(0, i);
        }
        return result;
    }
}
