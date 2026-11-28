package com.tara.nawagallery;

/**
 * Text decoration ("زخرفة") for Arabic and English, pure Java.
 * Arabic: stretched letters (kashida), decorative diacritics and ornament frames.
 * English: Unicode letter styles (𝓢𝓬𝓻𝓲𝓹𝓽, 𝔉𝔯𝔞𝔨𝔱𝔲𝔯, 𝔻𝕠𝕦𝕓𝕝𝕖, Ⓒⓘⓡⓒⓛⓔⓓ…) and frames.
 */
public final class TextDecor {
    private TextDecor() {}

    public static final String[] STYLES = {
            "بدون زخرفة",
            "مـمـدود",
            "مُزَخْرَفْ بالتشكيل",
            "مـُـمـْـدود ومُزخرف",
            "﴿ بين أقواس ﴾",
            "꧁ إطار ملكي ꧂",
            "✿ ورد ✿",
            "★彡 نجوم 彡★",
            "『 زاوية 』",
            "❝ اقتباس ❞",
            "ـــ۞ زخرفة ۞ـــ",
            "♡ قلوب ♡",
            "𝓢𝓬𝓻𝓲𝓹𝓽 (إنكليزي)",
            "𝕯𝖆𝖗𝖐 𝕲𝖔𝖙𝖍𝖎𝖈 (إنكليزي)",
            "𝔻𝕠𝕦𝕓𝕝𝕖 (إنكليزي)",
            "𝐁𝐨𝐥𝐝 (إنكليزي)",
            "Ⓑⓤⓑⓑⓛⓔ (إنكليزي)",
            "🅂🅀🅄🄰🅁🄴 (إنكليزي)",
            "ꜱᴍᴀʟʟ ᴄᴀᴘꜱ (إنكليزي)",
            "𝙼𝚘𝚗𝚘 (إنكليزي)",
            "Ｗｉｄｅ (إنكليزي)",
    };

    public static String apply(String text, int style) {
        if (text == null) return "";
        switch (style) {
            case 1: return stretch(text);
            case 2: return harakat(text);
            case 3: return harakat(stretch(text));
            case 4: return "﴿ " + text + " ﴾";
            case 5: return "꧁ " + text + " ꧂";
            case 6: return "✿ " + text + " ✿";
            case 7: return "★彡 " + text + " 彡★";
            case 8: return "『 " + text + " 』";
            case 9: return "❝ " + text + " ❞";
            case 10: return "ـــ۞ " + text + " ۞ـــ";
            case 11: return "♡ " + text + " ♡";
            case 12: return math(text, 0x1D4D0, 0x1D4EA, -1);           // bold script
            case 13: return math(text, 0x1D56C, 0x1D586, -1);           // bold fraktur
            case 14: return doubleStruck(text);
            case 15: return math(text, 0x1D400, 0x1D41A, 0x1D7CE);      // bold serif
            case 16: return circled(text);
            case 17: return squared(text);
            case 18: return smallCaps(text);
            case 19: return math(text, 0x1D670, 0x1D68A, 0x1D7F6);      // monospace
            case 20: return fullWidth(text);
            default: return text;
        }
    }

    // ------------------------------------------------------------------ Arabic

    /** Letters that never join to the next letter, so no kashida goes after them. */
    private static final String NON_JOINING = "اأإآٱدذرزوؤةىءـ";

    static boolean isArabicLetter(char c) { return c >= 'ء' && c <= 'ي'; }

    /** "محمد" → "مـحـمـد": a tatweel after every letter that joins the one after it. */
    public static String stretch(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c);
            if (!isArabicLetter(c) || NON_JOINING.indexOf(c) >= 0) continue;
            // the next real letter (skipping diacritics) must also be Arabic
            int j = i + 1;
            while (j < text.length() && isMark(text.charAt(j))) out.append(text.charAt(j++));
            i = j - 1;
            if (j < text.length() && isArabicLetter(text.charAt(j))) out.append('ـ');
        }
        return out.toString();
    }

    private static final char[] MARKS = {'َ', 'ُ', 'ِ', 'ّ', 'ْ', 'ً', 'ٌ', 'ٍ'};

    static boolean isMark(char c) { return c >= 'ً' && c <= 'ْ'; }

    /** Adds a repeating pattern of diacritics on letters that have none yet. */
    public static String harakat(String text) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c);
            boolean hasMark = i + 1 < text.length() && isMark(text.charAt(i + 1));
            if (isArabicLetter(c) && c != 'ء' && !hasMark) out.append(MARKS[n++ % MARKS.length]);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ English

    private static String math(String text, int upper, int lower, int digits) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') out.appendCodePoint(upper + (c - 'A'));
            else if (c >= 'a' && c <= 'z') out.appendCodePoint(lower + (c - 'a'));
            else if (digits > 0 && c >= '0' && c <= '9') out.appendCodePoint(digits + (c - '0'));
            else out.append(c);
        }
        return out.toString();
    }

    private static String doubleStruck(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int cp;
            switch (c) { // letters that live outside the math block
                case 'C': cp = 0x2102; break;
                case 'H': cp = 0x210D; break;
                case 'N': cp = 0x2115; break;
                case 'P': cp = 0x2119; break;
                case 'Q': cp = 0x211A; break;
                case 'R': cp = 0x211D; break;
                case 'Z': cp = 0x2124; break;
                default:
                    if (c >= 'A' && c <= 'Z') cp = 0x1D538 + (c - 'A');
                    else if (c >= 'a' && c <= 'z') cp = 0x1D552 + (c - 'a');
                    else if (c >= '0' && c <= '9') cp = 0x1D7D8 + (c - '0');
                    else cp = c;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    private static String circled(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') out.appendCodePoint(0x24B6 + (c - 'A'));
            else if (c >= 'a' && c <= 'z') out.appendCodePoint(0x24D0 + (c - 'a'));
            else if (c == '0') out.appendCodePoint(0x24EA);
            else if (c >= '1' && c <= '9') out.appendCodePoint(0x2460 + (c - '1'));
            else out.append(c);
        }
        return out.toString();
    }

    private static String squared(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toUpperCase(text.charAt(i));
            if (c >= 'A' && c <= 'Z') out.appendCodePoint(0x1F130 + (c - 'A'));
            else out.append(text.charAt(i));
        }
        return out.toString();
    }

    private static final String SMALL_CAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    private static String smallCaps(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            out.append(c >= 'a' && c <= 'z' ? SMALL_CAPS.charAt(c - 'a') : text.charAt(i));
        }
        return out.toString();
    }

    private static String fullWidth(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '!' && c <= '~') out.append((char) (c - '!' + 0xFF01));
            else if (c == ' ') out.append('　');
            else out.append(c);
        }
        return out.toString();
    }
}
