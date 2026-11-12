package com.tara.nawagallery;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers per video: playback speed and the subtitle file that was opened for it. */
final class VideoMemory {
    private static final String PREFS = "gx_video_memory";

    private VideoMemory() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static float speed(Context context, String video) {
        return prefs(context).getFloat("speed:" + video, 1f);
    }

    static void setSpeed(Context context, String video, float speed) {
        if (speed == 1f) prefs(context).edit().remove("speed:" + video).apply();
        else prefs(context).edit().putFloat("speed:" + video, speed).apply();
    }

    static String subtitle(Context context, String video) {
        return prefs(context).getString("sub:" + video, null);
    }

    static void setSubtitle(Context context, String video, String subtitle) {
        if (subtitle == null) prefs(context).edit().remove("sub:" + video).apply();
        else prefs(context).edit().putString("sub:" + video, subtitle).apply();
    }
}
