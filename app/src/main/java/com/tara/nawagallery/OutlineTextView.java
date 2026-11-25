package com.tara.nawagallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.widget.TextView;

/** A TextView that can draw a coloured outline (stroke) around every letter, like film subtitles. */
public final class OutlineTextView extends TextView {
    private float outlineWidth;
    private int outlineColor = 0xFF000000;
    private boolean drawingOutline;

    public OutlineTextView(Context context) { super(context); }

    /** Width in pixels; 0 turns the outline off. */
    public void setOutline(float width, int color) {
        outlineWidth = width;
        outlineColor = color;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (outlineWidth > 0f) {
            ColorStateList fill = getTextColors();
            float shadowRadius = getShadowRadius(), shadowDx = getShadowDx(), shadowDy = getShadowDy();
            int shadowColor = getShadowColor();
            drawingOutline = true;
            Paint paint = getPaint();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(outlineWidth);
            setTextColor(outlineColor);
            super.onDraw(canvas);
            paint.setStyle(Paint.Style.FILL);
            setTextColor(fill);
            setShadowLayer(0, 0, 0, 0); // the glow was already drawn with the outline
            super.onDraw(canvas);
            setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor);
            drawingOutline = false;
        } else super.onDraw(canvas);
    }

    // Changing the colour inside onDraw would otherwise schedule another frame forever.
    @Override public void invalidate() { if (!drawingOutline) super.invalidate(); }
    @Override public void requestLayout() { if (!drawingOutline) super.requestLayout(); }
}
