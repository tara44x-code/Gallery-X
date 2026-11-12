package com.tara.nawagallery;

/**
 * Picks two vivid, distinct colours from album-art pixels (pure Java), brightened into neon
 * tones so the visualizer stays readable on a dark background. Returns null for grey art.
 */
public final class ArtColors {
    private ArtColors() {}

    public static int[] pick(int[] pixels) {
        if (pixels == null || pixels.length == 0) return null;
        float[] weight = new float[36];
        float[] hsv = new float[3];
        for (int p : pixels) {
            int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
            rgbToHsv(r, g, b, hsv);
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue; // greys, blacks and whites carry no colour
            weight[(int) (hsv[0] / 10f) % 36] += hsv[1] * hsv[2];
        }
        int first = maxIndex(weight, -1, 0);
        if (first < 0 || weight[first] < pixels.length * 0.02f) return null;
        int second = maxIndex(weight, first, 6); // at least 60° away from the first hue
        float h1 = first * 10f + 5f;
        float h2 = second < 0 || weight[second] < weight[first] * 0.15f ? (h1 + 150f) % 360f : second * 10f + 5f;
        return new int[]{hsvToRgb(h1, 0.75f, 1f), hsvToRgb(h2, 0.7f, 1f)};
    }

    private static int maxIndex(float[] weight, int avoid, int minDistance) {
        int best = -1;
        for (int i = 0; i < weight.length; i++) {
            if (weight[i] <= 0f) continue;
            if (avoid >= 0) {
                int d = Math.abs(i - avoid);
                if (Math.min(d, 36 - d) < minDistance) continue;
            }
            if (best < 0 || weight[i] > weight[best]) best = i;
        }
        return best;
    }

    static void rgbToHsv(int r, int g, int b, float[] out) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf)), d = max - min;
        float h;
        if (d == 0f) h = 0f;
        else if (max == rf) h = 60f * (((gf - bf) / d) % 6f);
        else if (max == gf) h = 60f * ((bf - rf) / d + 2f);
        else h = 60f * ((rf - gf) / d + 4f);
        if (h < 0f) h += 360f;
        out[0] = h;
        out[1] = max == 0f ? 0f : d / max;
        out[2] = max;
    }

    static int hsvToRgb(float h, float s, float v) {
        float c = v * s, x = c * (1 - Math.abs((h / 60f) % 2 - 1)), m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; } else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; } else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; } else { r = c; g = 0; b = x; }
        return 0xFF000000 | (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }
}
