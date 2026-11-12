package com.tara.nawagallery;

import android.content.Context;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;

/**
 * Equalizer, bass boost and surround on the music player's own audio session (no permission needed,
 * nothing else on the phone is affected). Some phones lack an effect: each one is optional.
 */
final class AudioEffects {
    private final Context context;
    private Equalizer equalizer;
    private BassBoost bassBoost;
    private Virtualizer virtualizer;

    AudioEffects(Context context, int sessionId) {
        this.context = context.getApplicationContext();
        try { equalizer = new Equalizer(0, sessionId); } catch (Throwable ignored) { equalizer = null; }
        try { bassBoost = new BassBoost(0, sessionId); } catch (Throwable ignored) { bassBoost = null; }
        try { virtualizer = new Virtualizer(0, sessionId); } catch (Throwable ignored) { virtualizer = null; }
        apply();
    }

    boolean hasEqualizer() { return equalizer != null; }
    boolean hasBass() { return bassBoost != null && bassBoost.getStrengthSupported(); }
    boolean hasSurround() { return virtualizer != null && virtualizer.getStrengthSupported(); }

    int bands() { return equalizer == null ? 0 : equalizer.getNumberOfBands(); }

    /** Centre frequency in Hz. */
    int bandFrequency(int band) { return equalizer == null ? 0 : equalizer.getCenterFreq((short) band) / 1000; }

    int minLevel() { return equalizer == null ? -1500 : equalizer.getBandLevelRange()[0]; }
    int maxLevel() { return equalizer == null ? 1500 : equalizer.getBandLevelRange()[1]; }

    /** Band level (millibels) a preset gives a band, interpolated from the five-point curve. */
    int presetLevel(int preset, int band) {
        if (preset >= MusicStore.PRESET_CURVES.length) return MusicStore.bandLevel(context, band);
        float[] curve = MusicStore.PRESET_CURVES[preset];
        float[] freqs = MusicStore.PRESET_FREQUENCIES;
        double f = Math.log(Math.max(20, bandFrequency(band)));
        float db;
        if (f <= Math.log(freqs[0])) db = curve[0];
        else if (f >= Math.log(freqs[freqs.length - 1])) db = curve[curve.length - 1];
        else {
            int i = 0;
            while (i < freqs.length - 2 && f > Math.log(freqs[i + 1])) i++;
            double t = (f - Math.log(freqs[i])) / (Math.log(freqs[i + 1]) - Math.log(freqs[i]));
            db = (float) (curve[i] + (curve[i + 1] - curve[i]) * t);
        }
        return Math.max(minLevel(), Math.min(maxLevel(), Math.round(db * 100)));
    }

    /** Applies the saved settings. */
    void apply() {
        boolean on = MusicStore.eqEnabled(context);
        try {
            if (equalizer != null) {
                int preset = MusicStore.preset(context);
                for (int b = 0; b < bands(); b++) equalizer.setBandLevel((short) b, (short) presetLevel(preset, b));
                equalizer.setEnabled(on);
            }
            if (bassBoost != null) {
                int strength = MusicStore.bass(context);
                if (bassBoost.getStrengthSupported()) bassBoost.setStrength((short) strength);
                bassBoost.setEnabled(on && strength > 0);
            }
            if (virtualizer != null) {
                int strength = MusicStore.surround(context);
                if (virtualizer.getStrengthSupported()) virtualizer.setStrength((short) strength);
                virtualizer.setEnabled(on && strength > 0);
            }
        } catch (Throwable ignored) { }
    }

    void release() {
        try { if (equalizer != null) equalizer.release(); } catch (Throwable ignored) { }
        try { if (bassBoost != null) bassBoost.release(); } catch (Throwable ignored) { }
        try { if (virtualizer != null) virtualizer.release(); } catch (Throwable ignored) { }
        equalizer = null;
        bassBoost = null;
        virtualizer = null;
    }
}
