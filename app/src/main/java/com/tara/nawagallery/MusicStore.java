package com.tara.nawagallery;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the music player remembers: favourites, playlists, play counts, recently played,
 * the last song (so the widget and headphone buttons can resume), lyrics files and sound settings.
 */
final class MusicStore {
    private static final String PREFS = "gx_music_store";
    static final String[] PRESETS = {"عادي", "بيس قوي", "بوب", "روك", "كلاسيك", "صوت واضح", "مخصص"};
    /** Gain in dB for 60 Hz, 230 Hz, 910 Hz, 3.6 kHz and 14 kHz; mapped onto the phone's own bands. */
    static final float[][] PRESET_CURVES = {
            {0, 0, 0, 0, 0},
            {7, 5, 0, 0, 1},
            {-1, 2, 4, 2, -1},
            {5, 3, -2, 3, 5},
            {4, 2, -1, 2, 4},
            {-2, -1, 2, 4, 3},
    };
    static final float[] PRESET_FREQUENCIES = {60, 230, 910, 3600, 14000};

    private MusicStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String key(Song song) { return song.id > 0 ? "id:" + song.id : song.uri.toString(); }

    // ------------------------------------------------------------------ favourites

    static boolean isFavorite(Context context, Song song) {
        return prefs(context).getStringSet("favorites", new java.util.HashSet<>()).contains(key(song));
    }

    static boolean toggleFavorite(Context context, Song song) {
        java.util.Set<String> set = new java.util.HashSet<>(prefs(context).getStringSet("favorites", new java.util.HashSet<>()));
        boolean now = !set.remove(key(song));
        if (now) set.add(key(song));
        prefs(context).edit().putStringSet("favorites", set).apply();
        return now;
    }

    static List<Song> favorites(Context context, List<Song> library) {
        java.util.Set<String> set = prefs(context).getStringSet("favorites", new java.util.HashSet<>());
        List<Song> out = new ArrayList<>();
        for (Song song : library) if (set.contains(key(song))) out.add(song);
        return out;
    }

    // ------------------------------------------------------------------ playlists (name → song keys, in order)

    static LinkedHashMap<String, List<String>> playlists(Context context) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        try {
            JSONObject json = new JSONObject(prefs(context).getString("playlists", "{}"));
            JSONArray names = json.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String name = names.getString(i);
                JSONArray items = json.getJSONArray(name);
                List<String> keys = new ArrayList<>();
                for (int k = 0; k < items.length(); k++) keys.add(items.getString(k));
                result.put(name, keys);
            }
        } catch (Exception ignored) { }
        return result;
    }

    static void savePlaylists(Context context, Map<String, List<String>> playlists) {
        JSONObject json = new JSONObject();
        try {
            for (Map.Entry<String, List<String>> entry : playlists.entrySet()) json.put(entry.getKey(), new JSONArray(entry.getValue()));
        } catch (Exception ignored) { }
        prefs(context).edit().putString("playlists", json.toString()).apply();
    }

    static void addToPlaylist(Context context, String name, Song song) {
        LinkedHashMap<String, List<String>> all = playlists(context);
        List<String> keys = all.get(name);
        if (keys == null) { keys = new ArrayList<>(); all.put(name, keys); }
        if (!keys.contains(key(song))) keys.add(key(song));
        savePlaylists(context, all);
    }

    static List<Song> resolve(List<String> keys, List<Song> library) {
        Map<String, Song> byKey = new HashMap<>();
        for (Song song : library) byKey.put(key(song), song);
        List<Song> out = new ArrayList<>();
        for (String k : keys) { Song s = byKey.get(k); if (s != null) out.add(s); }
        return out;
    }

    // ------------------------------------------------------------------ history

    static void played(Context context, Song song) {
        SharedPreferences p = prefs(context);
        String k = key(song);
        int count = 0;
        try {
            JSONObject counts = new JSONObject(p.getString("counts", "{}"));
            count = counts.optInt(k, 0) + 1;
            counts.put(k, count);
            JSONArray recent = new JSONArray(p.getString("recent", "[]"));
            JSONArray updated = new JSONArray();
            updated.put(k);
            for (int i = 0; i < recent.length() && updated.length() < 60; i++) if (!k.equals(recent.getString(i))) updated.put(recent.getString(i));
            p.edit().putString("counts", counts.toString()).putString("recent", updated.toString()).apply();
        } catch (Exception ignored) { }
    }

    static List<Song> mostPlayed(Context context, List<Song> library) {
        List<Song> out = new ArrayList<>();
        try {
            JSONObject counts = new JSONObject(prefs(context).getString("counts", "{}"));
            for (Song song : library) if (counts.optInt(key(song), 0) > 0) out.add(song);
            out.sort((a, b) -> Integer.compare(counts.optInt(key(b), 0), counts.optInt(key(a), 0)));
        } catch (Exception ignored) { }
        return out.size() > 100 ? new ArrayList<>(out.subList(0, 100)) : out;
    }

    static List<Song> recent(Context context, List<Song> library) {
        List<String> keys = new ArrayList<>();
        try {
            JSONArray recent = new JSONArray(prefs(context).getString("recent", "[]"));
            for (int i = 0; i < recent.length(); i++) keys.add(recent.getString(i));
        } catch (Exception ignored) { }
        return resolve(keys, library);
    }

    // ------------------------------------------------------------------ last song (resume from widget / headphones)

    static void saveLast(Context context, Song song, int position) {
        prefs(context).edit().putString("last_key", key(song)).putString("last_uri", song.uri.toString())
                .putString("last_title", song.title).putString("last_artist", song.artist).putInt("last_pos", position).apply();
    }

    static String lastKey(Context context) { return prefs(context).getString("last_key", null); }
    static String lastUri(Context context) { return prefs(context).getString("last_uri", null); }
    static String lastTitle(Context context) { return prefs(context).getString("last_title", null); }
    static String lastArtist(Context context) { return prefs(context).getString("last_artist", ""); }
    static int lastPosition(Context context) { return prefs(context).getInt("last_pos", 0); }

    // ------------------------------------------------------------------ lyrics file chosen by hand, per song

    static String lyrics(Context context, Song song) { return prefs(context).getString("lrc:" + key(song), null); }
    static void setLyrics(Context context, Song song, String uri) { prefs(context).edit().putString("lrc:" + key(song), uri).apply(); }

    // ------------------------------------------------------------------ sound settings

    static boolean eqEnabled(Context c) { return prefs(c).getBoolean("eq_on", false); }
    static void setEqEnabled(Context c, boolean v) { prefs(c).edit().putBoolean("eq_on", v).apply(); }
    static int preset(Context c) { return prefs(c).getInt("eq_preset", 0); }
    static void setPreset(Context c, int v) { prefs(c).edit().putInt("eq_preset", v).apply(); }
    /** Custom band levels in millibels, by band index. */
    static int bandLevel(Context c, int band) { return prefs(c).getInt("eq_band_" + band, 0); }
    static void setBandLevel(Context c, int band, int mb) { prefs(c).edit().putInt("eq_band_" + band, mb).apply(); }
    static int bass(Context c) { return prefs(c).getInt("bass", 0); }               // 0..1000
    static void setBass(Context c, int v) { prefs(c).edit().putInt("bass", v).apply(); }
    static int surround(Context c) { return prefs(c).getInt("surround", 0); }       // 0..1000
    static void setSurround(Context c, int v) { prefs(c).edit().putInt("surround", v).apply(); }
    static float speed(Context c) { return prefs(c).getFloat("speed", 1f); }
    static void setSpeed(Context c, float v) { prefs(c).edit().putFloat("speed", v).apply(); }
    static int crossfade(Context c) { return prefs(c).getInt("crossfade_ms", 3000); }
    static void setCrossfade(Context c, int ms) { prefs(c).edit().putInt("crossfade_ms", ms).apply(); }
}
