package com.tara.nawagallery;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;

/**
 * A neon "visualizer" cover for songs without album art: a rainbow spectrum ring around a glowing disc,
 * different for every song. Android draws the notification / lock-screen player itself, so this
 * picture is how the app's look reaches those places.
 */
final class NeonCover {
    private NeonCover() {}

    static Bitmap make(String seed, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        java.util.Random random = new java.util.Random(seed == null ? 7 : seed.hashCode());
        float c = size / 2f;
        float hueStart = random.nextInt(360);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new RadialGradient(c, c, size * 0.75f, new int[]{0xFF2A1048, 0xFF12081F, 0xFF07040C},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, size, size, paint);
        paint.setShader(null);

        int bars = 72;
        float inner = size * 0.24f, maxLen = size * 0.2f;
        float[] heights = new float[bars];
        float previous = 0.5f;
        for (int i = 0; i < bars / 2; i++) { // smooth random spectrum, mirrored for symmetry
            previous = Math.max(0.12f, Math.min(1f, previous + (random.nextFloat() - 0.5f) * 0.45f));
            heights[i] = previous;
            heights[bars - 1 - i] = previous;
        }
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int pass = 0; pass < 2; pass++) { // a wide faint pass for glow, then the bright bars
            for (int i = 0; i < bars; i++) {
                double a = Math.toRadians(i * 360.0 / bars - 90);
                float len = size * 0.02f + heights[i] * maxLen;
                int color = Color.HSVToColor(new float[]{(hueStart + i * 360f / bars) % 360f, 0.8f, 1f});
                paint.setColor(pass == 0 ? Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)) : color);
                paint.setStrokeWidth(pass == 0 ? size * 0.03f : size * 0.012f);
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                canvas.drawLine(c + cos * inner, c + sin * inner, c + cos * (inner + len), c + sin * (inner + len), paint);
            }
        }

        int[] ring = new int[13];
        for (int i = 0; i < ring.length; i++) ring[i] = Color.HSVToColor(new float[]{(hueStart + i * 30f) % 360f, 0.8f, 1f});
        paint.setStyle(Paint.Style.STROKE);
        paint.setShader(new SweepGradient(c, c, ring, null));
        paint.setStrokeWidth(size * 0.012f);
        canvas.drawCircle(c, c, inner - size * 0.03f, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(c, c, inner - size * 0.04f, new int[]{0xFF2B1A44, 0xFF0B0712}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(c, c, inner - size * 0.04f, paint);
        paint.setShader(null);
        paint.setColor(Color.WHITE);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(size * 0.16f);
        paint.setShadowLayer(size * 0.03f, 0, 0, ring[0]);
        canvas.drawText("♫", c, c + size * 0.055f, paint);
        return bitmap;
    }
}
