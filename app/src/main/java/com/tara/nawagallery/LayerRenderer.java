package com.tara.nawagallery;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/**
 * Draws text layers and frames. The same code paints the editor preview and the saved photo / video
 * overlay, so what you see is exactly what gets saved — sizes are fractions of the picture, not pixels.
 */
final class LayerRenderer {
    private LayerRenderer() {}

    static final int[] RAINBOW = {0xFFFF3B6B, 0xFFFF9F1C, 0xFFFFE45C, 0xFF3DFF8A, 0xFF22E5FF, 0xFF6B7BFF, 0xFFB26BFF, 0xFFFF3BD5};

    static void drawAll(Context context, Canvas canvas, Iterable<TextLayer> layers, float w, float h) {
        for (TextLayer layer : layers) draw(context, canvas, layer, w, h);
    }

    static void draw(Context context, Canvas canvas, TextLayer layer, float w, float h) {
        if (layer.type == TextLayer.FRAME) { drawFrame(canvas, layer, w, h); return; }
        if (layer.text == null || layer.text.isEmpty()) return;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        StaticLayout text = layout(context, layer, w, paint);
        float tw = text.getWidth(), th = text.getHeight(), size = paint.getTextSize();
        float pad = size * 0.28f;

        canvas.save();
        canvas.translate(layer.cx * w, layer.cy * h);
        canvas.rotate(layer.rotation);
        canvas.translate(-tw / 2f, -th / 2f);

        if (layer.boxColor != 0) {
            Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
            box.setColor(layer.boxColor);
            canvas.drawRoundRect(-pad, -pad * 0.55f, tw + pad, th + pad * 0.55f, size * 0.35f, size * 0.35f, box);
        }
        if (layer.shadow) {
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(0xAA000000);
            paint.setMaskFilter(new BlurMaskFilter(Math.max(1f, size * 0.06f), BlurMaskFilter.Blur.NORMAL));
            canvas.save();
            canvas.translate(size * 0.05f, size * 0.07f);
            text.draw(canvas);
            canvas.restore();
            paint.setMaskFilter(null);
        }
        if (layer.glow) {
            int glow = layer.rainbow ? RAINBOW[6] : layer.color;
            paint.setStyle(Paint.Style.FILL_AND_STROKE);
            paint.setStrokeWidth(size * 0.04f);
            paint.setShader(null);
            paint.setColor(withAlpha(glow, 200));
            paint.setMaskFilter(new BlurMaskFilter(Math.max(1f, size * 0.28f), BlurMaskFilter.Blur.NORMAL));
            text.draw(canvas);
            paint.setMaskFilter(new BlurMaskFilter(Math.max(1f, size * 0.1f), BlurMaskFilter.Blur.NORMAL));
            text.draw(canvas);
            paint.setMaskFilter(null);
        }
        if (layer.outline > 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(size * (layer.outline == 1 ? 0.07f : 0.15f));
            paint.setShader(null);
            paint.setColor(layer.outlineColor);
            text.draw(canvas);
        }
        paint.setStyle(Paint.Style.FILL);
        if (layer.rainbow) paint.setShader(new LinearGradient(0, 0, tw, 0, RAINBOW, null, Shader.TileMode.CLAMP));
        else if (layer.color2 != 0) paint.setShader(new LinearGradient(0, 0, tw, th, layer.color, layer.color2, Shader.TileMode.CLAMP));
        else { paint.setShader(null); paint.setColor(layer.color); }
        text.draw(canvas);
        canvas.restore();
    }

    static StaticLayout layout(Context context, TextLayer layer, float w, TextPaint paint) {
        Typeface face = FontStore.get(context, layer.font);
        paint.setTypeface(face);
        paint.setTextSize(Math.max(4f, layer.size * w * layer.scale));
        paint.setLetterSpacing(layer.spacing);
        float widest = 1f;
        for (String line : layer.text.split("\n")) widest = Math.max(widest, StaticLayout.getDesiredWidth(line, paint));
        int width = (int) Math.ceil(widest) + 2;
        Layout.Alignment align = layer.align == 1 ? Layout.Alignment.ALIGN_NORMAL
                : layer.align == 2 ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_CENTER;
        return StaticLayout.Builder.obtain(layer.text, 0, layer.text.length(), paint, width)
                .setAlignment(align).setIncludePad(true).build();
    }

    /** The layer's box around its own centre, before rotation (for touch and the selection outline). */
    static RectF bounds(Context context, TextLayer layer, float w, float h) {
        if (layer.type == TextLayer.FRAME) return new RectF(-w / 2f, -h / 2f, w / 2f, h / 2f);
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        StaticLayout text = layout(context, layer, w, paint);
        float pad = paint.getTextSize() * 0.3f;
        return new RectF(-text.getWidth() / 2f - pad, -text.getHeight() / 2f - pad, text.getWidth() / 2f + pad, text.getHeight() / 2f + pad);
    }

    // ------------------------------------------------------------------ frames around the whole picture

    static final String[] FRAMES = {"إطار نيون", "إطار مزدوج", "زخرفة ۞", "ورود ✿", "قلوب ♥", "نجوم ★", "إطار بولارويد"};

    static void drawFrame(Canvas canvas, TextLayer layer, float w, float h) {
        float m = Math.min(w, h);
        int color = layer.rainbow ? RAINBOW[0] : layer.color;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        switch (layer.frameStyle) {
            case 0: { // neon
                float inset = m * 0.035f, r = m * 0.05f;
                p.setStyle(Paint.Style.STROKE);
                if (layer.rainbow) p.setShader(new LinearGradient(0, 0, w, h, RAINBOW, null, Shader.TileMode.CLAMP));
                else p.setColor(color);
                p.setStrokeWidth(m * 0.03f);
                p.setMaskFilter(new BlurMaskFilter(m * 0.03f, BlurMaskFilter.Blur.NORMAL));
                canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, p);
                p.setMaskFilter(null);
                p.setStrokeWidth(m * 0.01f);
                canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, p);
                break;
            }
            case 1: { // classic double line with corner stars
                p.setStyle(Paint.Style.STROKE);
                p.setColor(color);
                p.setStrokeWidth(m * 0.008f);
                float a = m * 0.03f, b = m * 0.05f;
                canvas.drawRect(a, a, w - a, h - a, p);
                p.setStrokeWidth(m * 0.004f);
                canvas.drawRect(b, b, w - b, h - b, p);
                cornerGlyphs(canvas, "✦", color, w, h, m * 0.06f, a);
                break;
            }
            case 6: { // polaroid
                p.setColor(Color.WHITE);
                float side = m * 0.05f, bottom = m * 0.18f;
                canvas.drawRect(0, 0, w, side, p);
                canvas.drawRect(0, 0, side, h, p);
                canvas.drawRect(w - side, 0, w, h, p);
                canvas.drawRect(0, h - bottom, w, h, p);
                break;
            }
            default: { // a border of repeated ornaments
                String glyph = layer.frameStyle == 2 ? "۞" : layer.frameStyle == 3 ? "✿" : layer.frameStyle == 4 ? "♥" : "★";
                glyphBorder(canvas, glyph, layer.rainbow, color, w, h, m * 0.055f);
            }
        }
    }

    private static void cornerGlyphs(Canvas canvas, String glyph, int color, float w, float h, float size, float inset) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(color);
        t.setTextSize(size);
        t.setTextAlign(Paint.Align.CENTER);
        float dy = size * 0.35f;
        canvas.drawText(glyph, inset, inset + dy, t);
        canvas.drawText(glyph, w - inset, inset + dy, t);
        canvas.drawText(glyph, inset, h - inset + dy, t);
        canvas.drawText(glyph, w - inset, h - inset + dy, t);
    }

    private static void glyphBorder(Canvas canvas, String glyph, boolean rainbow, int color, float w, float h, float size) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextSize(size);
        t.setTextAlign(Paint.Align.CENTER);
        float step = size * 1.15f, half = size * 0.6f, dy = size * 0.35f;
        int i = 0;
        for (float x = half; x <= w - half + 1; x += step) {
            t.setColor(rainbow ? RAINBOW[i++ % RAINBOW.length] : color);
            canvas.drawText(glyph, x, half + dy, t);
            canvas.drawText(glyph, x, h - half + dy, t);
        }
        for (float y = half + step; y <= h - half - step + 1; y += step) {
            t.setColor(rainbow ? RAINBOW[i++ % RAINBOW.length] : color);
            canvas.drawText(glyph, half, y + dy, t);
            canvas.drawText(glyph, w - half, y + dy, t);
        }
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
