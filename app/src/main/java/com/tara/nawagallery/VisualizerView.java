package com.tara.nawagallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Music visualizer with five looks that react to the song: a spinning vinyl, spectrum bars,
 * a radial neon ring, flowing waves and bass-driven particles. Colours follow the app theme.
 */
public final class VisualizerView extends View {
    public static final String[] MODES = {"أسطوانة", "أعمدة الطيف", "دائرة نيون", "موجة", "جزيئات"};

    public interface Source {
        SpectrumAnalyzer.Frames frames();
        long position();
        boolean playing();
    }

    private static final int B = SpectrumAnalyzer.BANDS;
    private final float density;
    private final int primary, secondary;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Matrix matrix = new Matrix();
    private final Random random = new Random();
    private final float[] target = new float[B];
    private final float[] smooth = new float[B];
    private final float[] peaks = new float[B];
    private Source source;
    private int mode;
    private Bitmap art;
    private BitmapShader artShader;
    private float level, bass, bassAverage, angle, spin, time, beat;
    private long lastFrame;

    // particles
    private static final int MAX = 220;
    private final float[] px = new float[MAX], py = new float[MAX], vx = new float[MAX], vy = new float[MAX],
            life = new float[MAX], size = new float[MAX];
    private final boolean[] alt = new boolean[MAX];
    private int next;

    public VisualizerView(Context context, int primary, int secondary) {
        super(context);
        this.primary = primary;
        this.secondary = secondary;
        density = context.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setSource(Source source) { this.source = source; invalidate(); }

    public int mode() { return mode; }

    public void setMode(int mode) {
        this.mode = ((mode % MODES.length) + MODES.length) % MODES.length;
        invalidate();
    }

    public void setArt(Bitmap bitmap) {
        art = bitmap;
        artShader = bitmap == null ? null : new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        invalidate();
    }

    // ------------------------------------------------------------------ data

    private void update() {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        time += dt;
        boolean playing = source != null && source.playing();
        SpectrumAnalyzer.Frames frames = source == null ? null : source.frames();
        int index = frames == null ? -1 : frames.indexAt(source.position());
        float loud = 0f;
        if (index >= 0) loud = frames.read(index, target);
        else for (int i = 0; i < B; i++) target[i] = playing ? 0.12f + 0.08f * (float) Math.sin(time * 2.2 + i * 0.45) : 0f;
        if (!playing) for (int i = 0; i < B; i++) target[i] *= 0.2f;
        float sumBass = 0f;
        for (int i = 0; i < B; i++) {
            float k = target[i] > smooth[i] ? 0.55f : 0.16f;
            smooth[i] += (target[i] - smooth[i]) * k;
            peaks[i] = Math.max(smooth[i], peaks[i] - dt * 0.45f);
            if (i < 6) sumBass += smooth[i];
        }
        level += ((playing ? loud : 0f) - level) * 0.3f;
        bass = sumBass / 6f;
        beat = Math.max(0f, beat - dt * 3.5f);
        if (bass - bassAverage > 0.10f && bass > 0.35f && playing) beat = 1f;
        bassAverage = bassAverage * 0.94f + bass * 0.06f;
        spin += ((playing ? 1f : 0f) - spin) * Math.min(1f, dt * 2.5f); // vinyl eases in and out
        angle = (angle + spin * dt * 200f) % 360f; // 33⅓ rpm
    }

    @Override protected void onDraw(Canvas canvas) {
        update();
        switch (mode) {
            case 0: drawVinyl(canvas); break;
            case 1: drawBars(canvas); break;
            case 2: drawRadial(canvas); break;
            case 3: drawWaves(canvas); break;
            default: drawParticles(canvas); break;
        }
        boolean moving = (source != null && source.playing()) || spin > 0.01f || level > 0.01f || bass > 0.01f || anyParticle();
        if (moving) postInvalidateOnAnimation();
    }

    private float dp(float v) { return v * density; }

    private int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static int alpha(int color, float a) {
        return Color.argb((int) (255 * Math.max(0f, Math.min(1f, a))), Color.red(color), Color.green(color), Color.blue(color));
    }

    /** Album art (or a neon gradient with a note) clipped to a circle. */
    private void drawCover(Canvas canvas, float cx, float cy, float r, float rotate) {
        paint.setStyle(Paint.Style.FILL);
        if (artShader != null) {
            float scale = 2 * r / Math.min(art.getWidth(), art.getHeight());
            matrix.reset();
            matrix.postTranslate(-art.getWidth() / 2f, -art.getHeight() / 2f);
            matrix.postScale(scale, scale);
            matrix.postRotate(rotate);
            matrix.postTranslate(cx, cy);
            artShader.setLocalMatrix(matrix);
            paint.setShader(artShader);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setShader(null);
        } else {
            paint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, primary, secondary, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, paint);
            paint.setShader(null);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(r * 0.9f);
            canvas.save();
            canvas.rotate(rotate, cx, cy);
            canvas.drawText("♫", cx, cy + r * 0.32f, paint);
            canvas.restore();
        }
    }

    // ------------------------------------------------------------------ 1. vinyl

    private void drawVinyl(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * 0.38f;
        // pulsing neon halo
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(dp(14) + bass * dp(26));
        glow.setColor(alpha(primary, 0.18f + bass * 0.35f + beat * 0.2f));
        canvas.drawCircle(cx, cy, r + dp(10) + bass * dp(10), glow);
        glow.setStrokeWidth(dp(3) + bass * dp(5));
        glow.setColor(alpha(mix(primary, secondary, bass), 0.85f));
        canvas.drawCircle(cx, cy, r + dp(4), glow);
        // record
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, r, new int[]{0xFF1A1A22, 0xFF0B0B10, 0xFF050507}, new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, r, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f));
        for (float g = r * 0.48f; g < r * 0.97f; g += dp(3.2f)) {
            paint.setColor(0x16FFFFFF);
            canvas.drawCircle(cx, cy, g, paint);
        }
        // light reflection that stays still while the record turns
        paint.setStrokeWidth(r * 0.42f);
        paint.setColor(0x10FFFFFF);
        canvas.drawArc(cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.72f, 200, 40, false, paint);
        canvas.drawArc(cx - r * 0.72f, cy - r * 0.72f, cx + r * 0.72f, cy + r * 0.72f, 20, 40, false, paint);
        // label (album art) turning with the record
        drawCover(canvas, cx, cy, r * 0.42f, angle);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF07070C);
        canvas.drawCircle(cx, cy, dp(6), paint);
        // tone arm
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(5));
        paint.setColor(0xFFC9C9D9);
        float armAngle = (float) Math.toRadians(spin > 0.5f ? 118 : 100);
        float ax = cx + r * 0.98f, ay = cy - r * 0.98f;
        canvas.drawLine(ax, ay, ax + (float) Math.cos(armAngle) * r * 0.95f, ay + (float) Math.sin(armAngle) * r * 0.95f, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(ax, ay, dp(9), paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    // ------------------------------------------------------------------ 2. spectrum bars

    private void drawBars(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float base = h * 0.72f, maxH = h * 0.6f;
        float slot = w / B, bar = slot * 0.62f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, base - maxH, 0, base, secondary, primary, Shader.TileMode.CLAMP));
        for (int i = 0; i < B; i++) {
            float x = i * slot + (slot - bar) / 2f;
            float height = Math.max(dp(3), smooth[i] * maxH);
            canvas.drawRoundRect(x, base - height, x + bar, base, bar / 2f, bar / 2f, paint);
        }
        paint.setShader(null);
        // reflection
        for (int i = 0; i < B; i++) {
            float x = i * slot + (slot - bar) / 2f;
            float height = smooth[i] * maxH * 0.35f;
            paint.setColor(alpha(primary, 0.18f));
            canvas.drawRoundRect(x, base + dp(4), x + bar, base + dp(4) + height, bar / 2f, bar / 2f, paint);
        }
        // falling peak caps
        paint.setColor(Color.WHITE);
        for (int i = 0; i < B; i++) {
            float x = i * slot + (slot - bar) / 2f;
            float y = base - Math.max(dp(3), peaks[i] * maxH) - dp(6);
            canvas.drawRoundRect(x, y, x + bar, y + dp(3), dp(1.5f), dp(1.5f), paint);
        }
    }

    // ------------------------------------------------------------------ 3. radial neon ring

    private void drawRadial(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * 0.22f * (1f + bass * 0.08f + beat * 0.04f);
        float maxLen = Math.min(getWidth(), getHeight()) * 0.24f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        int spokes = B * 2;
        float stroke = Math.max(dp(2), (float) (2 * Math.PI * r / spokes) * 0.55f);
        for (int s = 0; s < spokes; s++) {
            int band = s < B ? s : spokes - 1 - s; // mirrored so the ring is symmetric
            float len = dp(4) + smooth[band] * maxLen;
            double a = Math.toRadians(s * 360f / spokes - 90 + angle * 0.08f);
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            float r0 = r + dp(8);
            paint.setStrokeWidth(stroke);
            paint.setColor(mix(primary, secondary, (float) band / B));
            canvas.drawLine(cx + cos * r0, cy + sin * r0, cx + cos * (r0 + len), cy + sin * (r0 + len), paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(dp(3));
        glow.setColor(alpha(primary, 0.9f));
        canvas.drawCircle(cx, cy, r + dp(3), glow);
        glow.setStrokeWidth(dp(16));
        glow.setColor(alpha(primary, 0.15f + level * 0.25f));
        canvas.drawCircle(cx, cy, r + dp(3), glow);
        drawCover(canvas, cx, cy, r, angle * 0.25f);
    }

    // ------------------------------------------------------------------ 4. waves

    private void drawWaves(Canvas canvas) {
        float w = getWidth(), h = getHeight(), mid = h * 0.5f;
        float amp = h * 0.3f;
        int[] colors = {secondary, mix(primary, secondary, 0.5f), primary};
        for (int layer = 0; layer < 3; layer++) {
            path.reset();
            int points = 64;
            float phase = time * (1.2f + layer * 0.5f) + layer * 1.7f;
            for (int p = 0; p <= points; p++) {
                float t = (float) p / points;
                int band = Math.min(B - 1, (int) (Math.abs(t - 0.5f) * 2f * (B - 1))); // bass in the middle
                float envelope = (float) Math.sin(Math.PI * t);
                float y = mid - (smooth[band] * amp * (0.55f + 0.45f * (float) Math.sin(phase + t * 9f)) + dp(2)) * envelope * (1f - layer * 0.22f);
                float x = t * w;
                if (p == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            for (int p = points; p >= 0; p--) {
                float t = (float) p / points;
                int band = Math.min(B - 1, (int) (Math.abs(t - 0.5f) * 2f * (B - 1)));
                float envelope = (float) Math.sin(Math.PI * t);
                float y = mid + (smooth[band] * amp * (0.55f + 0.45f * (float) Math.cos(phase + t * 9f)) + dp(2)) * envelope * (1f - layer * 0.22f);
                path.lineTo(t * w, y);
            }
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(alpha(colors[layer], 0.22f + layer * 0.08f));
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(alpha(colors[layer], 0.9f));
            canvas.drawPath(path, paint);
        }
    }

    // ------------------------------------------------------------------ 5. particles

    private void drawParticles(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float dt = 0.016f;
        float speedBase = Math.min(getWidth(), getHeight()) * 0.45f;
        if (beat > 0.95f) emit(cx, cy, 26, speedBase * (0.6f + bass));
        if (level > 0.15f && random.nextFloat() < level) emit(cx, cy, 2, speedBase * 0.35f);
        // core glow
        float core = Math.min(getWidth(), getHeight()) * (0.08f + level * 0.08f + beat * 0.04f);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, core * 2.6f, new int[]{alpha(Color.WHITE, 0.9f), alpha(primary, 0.55f), Color.TRANSPARENT},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, core * 2.6f, paint);
        paint.setShader(null);
        for (int i = 0; i < MAX; i++) {
            if (life[i] <= 0f) continue;
            px[i] += vx[i] * dt;
            py[i] += vy[i] * dt;
            vx[i] *= 0.985f;
            vy[i] *= 0.985f;
            life[i] -= dt * 0.7f;
            if (life[i] <= 0f) continue;
            paint.setColor(alpha(alt[i] ? secondary : primary, life[i]));
            canvas.drawCircle(px[i], py[i], size[i] * (0.5f + life[i] * 0.5f), paint);
        }
        // faint spectrum ring for context
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        float ring = core * 2.2f;
        for (int s = 0; s < B; s++) {
            double a = Math.toRadians(s * 360f / B - 90);
            float len = smooth[s] * core * 1.6f;
            paint.setColor(alpha(mix(primary, secondary, (float) s / B), 0.5f));
            canvas.drawLine(cx + (float) Math.cos(a) * ring, cy + (float) Math.sin(a) * ring,
                    cx + (float) Math.cos(a) * (ring + len), cy + (float) Math.sin(a) * (ring + len), paint);
        }
    }

    private void emit(float cx, float cy, int count, float speed) {
        for (int n = 0; n < count; n++) {
            int i = next;
            next = (next + 1) % MAX;
            double a = random.nextDouble() * Math.PI * 2;
            float v = speed * (0.4f + random.nextFloat() * 0.8f);
            px[i] = cx;
            py[i] = cy;
            vx[i] = (float) Math.cos(a) * v;
            vy[i] = (float) Math.sin(a) * v;
            life[i] = 0.7f + random.nextFloat() * 0.3f;
            size[i] = dp(2) + random.nextFloat() * dp(4);
            alt[i] = random.nextBoolean();
        }
    }

    private boolean anyParticle() {
        if (mode != 4) return false;
        for (int i = 0; i < MAX; i++) if (life[i] > 0f) return true;
        return false;
    }
}
