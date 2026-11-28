package com.tara.nawagallery;

import android.content.Context;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fonts for writing on photos and videos. Decorative Arabic and English fonts (SIL Open Font License,
 * from Google Fonts) are bundled in assets/fonts by tools/fetch-fonts.sh at build time; only the ones
 * actually present are offered, plus the phone's own fonts.
 */
final class FontStore {
    static final class Font {
        final String id, label;
        final boolean arabic;
        Font(String id, String label, boolean arabic) { this.id = id; this.label = label; this.arabic = arabic; }
    }

    /** id (file name in assets/fonts without .ttf), display name, Arabic? */
    private static final Font[] BUNDLED = {
            new Font("arefruqaa", "رقعة — Aref Ruqaa", true),
            new Font("arefruqaaink", "رقعة ملوّنة — Ruqaa Ink", true),
            new Font("amiri", "نسخ — Amiri", true),
            new Font("reemkufi", "كوفي — Reem Kufi", true),
            new Font("reemkufifun", "كوفي مرح — Reem Kufi Fun", true),
            new Font("kufam", "كوفي حديث — Kufam", true),
            new Font("notonastaliq", "نستعليق — Nastaliq", true),
            new Font("lateef", "لطيف — Lateef", true),
            new Font("alkalami", "قلم — Alkalami", true),
            new Font("vibes", "مزخرف — Vibes", true),
            new Font("blakaink", "حبر — Blaka Ink", true),
            new Font("katibeh", "كاتبة — Katibeh", true),
            new Font("rakkas", "ركاز — Rakkas", true),
            new Font("lalezar", "لاله‌زار — Lalezar", true),
            new Font("jomhuria", "جمهورية — Jomhuria", true),
            new Font("marhey", "مرحي — Marhey", true),
            new Font("elmessiri", "المسيري — El Messiri", true),
            new Font("cairo", "القاهرة — Cairo", true),
            new Font("tajawal", "تجول — Tajawal", true),
            new Font("changa", "شنغا — Changa", true),
            new Font("mirza", "ميرزا — Mirza", true),
            new Font("greatvibes", "Great Vibes", false),
            new Font("alexbrush", "Alex Brush", false),
            new Font("pinyonscript", "Pinyon Script", false),
            new Font("sacramento", "Sacramento", false),
            new Font("dancingscript", "Dancing Script", false),
            new Font("pacifico", "Pacifico", false),
            new Font("satisfy", "Satisfy", false),
            new Font("kaushanscript", "Kaushan Script", false),
            new Font("lobster", "Lobster", false),
            new Font("caveat", "Caveat", false),
            new Font("permanentmarker", "Permanent Marker", false),
            new Font("neonderthaw", "Neonderthaw (نيون)", false),
            new Font("tiltneon", "Tilt Neon (نيون)", false),
            new Font("monoton", "Monoton (نيون)", false),
            new Font("bungeeshade", "Bungee Shade", false),
            new Font("cinzeldecorative", "Cinzel Decorative", false),
            new Font("playfairdisplay", "Playfair Display", false),
            new Font("bebasneue", "Bebas Neue", false),
            new Font("righteous", "Righteous", false),
            new Font("audiowide", "Audiowide", false),
            new Font("orbitron", "Orbitron", false),
    };

    private static final Font[] SYSTEM = {
            new Font("sys_bold", "عريض — خط الهاتف", true),
            new Font("sys", "عادي — خط الهاتف", true),
            new Font("sys_serif", "Serif", false),
            new Font("sys_mono", "Mono", false),
            new Font("sys_cond", "ضيق — Condensed", false),
    };

    private static final Map<String, Typeface> CACHE = new HashMap<>();
    private static Set<String> present;

    private FontStore() {}

    private static synchronized Set<String> present(Context context) {
        if (present == null) {
            present = new HashSet<>();
            try {
                String[] files = context.getAssets().list("fonts");
                if (files != null) for (String f : Arrays.asList(files)) if (f.endsWith(".ttf")) present.add(f.substring(0, f.length() - 4));
            } catch (Exception ignored) { }
        }
        return present;
    }

    /** Arabic fonts first, then English, then the phone's own. */
    static List<Font> list(Context context) {
        List<Font> out = new ArrayList<>();
        Set<String> have = present(context);
        for (Font f : BUNDLED) if (f.arabic && have.contains(f.id)) out.add(f);
        for (Font f : BUNDLED) if (!f.arabic && have.contains(f.id)) out.add(f);
        out.addAll(Arrays.asList(SYSTEM));
        return out;
    }

    /** A good first font for new text: a decorative one matching the script if bundled. */
    static String defaultFor(Context context, boolean arabic) {
        Set<String> have = present(context);
        String[] wanted = arabic ? new String[]{"arefruqaa", "reemkufi", "amiri"} : new String[]{"greatvibes", "pacifico", "lobster"};
        for (String id : wanted) if (have.contains(id)) return id;
        return "sys_bold";
    }

    static synchronized Typeface get(Context context, String id) {
        Typeface cached = CACHE.get(id);
        if (cached != null) return cached;
        Typeface face;
        switch (id == null ? "" : id) {
            case "sys": face = Typeface.create("sans-serif", Typeface.NORMAL); break;
            case "sys_serif": face = Typeface.create(Typeface.SERIF, Typeface.BOLD); break;
            case "sys_mono": face = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); break;
            case "sys_cond": face = Typeface.create("sans-serif-condensed", Typeface.BOLD); break;
            case "sys_bold": face = Typeface.DEFAULT_BOLD; break;
            default:
                try { face = Typeface.createFromAsset(context.getAssets(), "fonts/" + id + ".ttf"); }
                catch (Exception missing) { face = Typeface.DEFAULT_BOLD; }
        }
        CACHE.put(id, face);
        return face;
    }

    static boolean hasArabic(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) >= '؀' && text.charAt(i) <= 'ۿ') return true;
        return false;
    }
}
