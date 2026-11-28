package com.tara.nawagallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The picture with its text layers. One finger drags a layer, two fingers resize and rotate it,
 * double tap edits its text. Positions are stored relative to the picture, so the saved file matches.
 */
final class TextStageView extends View {
    interface Listener {
        void onSelected(TextLayer layer);
        void onEdit(TextLayer layer);
    }

    final List<TextLayer> layers = new ArrayList<>();
    private final RectF picture = new RectF();
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector gestures;
    private Bitmap background;
    private TextLayer selected;
    private Listener listener;

    private float lastX, lastY, startDistance, startAngle, startScale, startRotation, startMidX, startMidY, startCx, startCy;
    private boolean twoFingers;

    TextStageView(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(1.6f * d);
        selectionPaint.setColor(0xFFFFFFFF);
        selectionPaint.setPathEffect(new DashPathEffect(new float[]{8 * d, 6 * d}, 0));
        selectionPaint.setShadowLayer(3 * d, 0, 0, 0xFF000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // blur effects need software drawing to match the saved result
        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                TextLayer hit = hit(e.getX(), e.getY());
                if (hit != null && listener != null) listener.onEdit(hit);
                return true;
            }
        });
    }

    void setListener(Listener listener) { this.listener = listener; }

    void setBackground(Bitmap bitmap) {
        background = bitmap;
        invalidate();
    }

    TextLayer selected() { return selected; }

    void select(TextLayer layer) {
        selected = layer;
        if (listener != null) listener.onSelected(layer);
        invalidate();
    }

    /** Where the picture is drawn inside the view (fit centre). */
    RectF picture() {
        float vw = getWidth(), vh = getHeight();
        if (background == null || vw <= 0 || vh <= 0) { picture.set(0, 0, vw, vh); return picture; }
        float s = Math.min(vw / background.getWidth(), vh / background.getHeight());
        float w = background.getWidth() * s, h = background.getHeight() * s;
        picture.set((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f);
        return picture;
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xFF07070C);
        RectF r = picture();
        if (background != null) canvas.drawBitmap(background, null, r, imagePaint);
        canvas.save();
        canvas.clipRect(r);
        canvas.translate(r.left, r.top);
        LayerRenderer.drawAll(getContext(), canvas, layers, r.width(), r.height());
        if (selected != null && selected.type == TextLayer.TEXT && layers.contains(selected)) {
            RectF b = LayerRenderer.bounds(getContext(), selected, r.width(), r.height());
            canvas.save();
            canvas.translate(selected.cx * r.width(), selected.cy * r.height());
            canvas.rotate(selected.rotation);
            canvas.drawRoundRect(b, 12, 12, selectionPaint);
            canvas.restore();
        }
        canvas.restore();
    }

    /** Topmost text layer under a touch point. */
    private TextLayer hit(float x, float y) {
        RectF r = picture();
        for (int i = layers.size() - 1; i >= 0; i--) {
            TextLayer l = layers.get(i);
            if (l.type != TextLayer.TEXT) continue;
            float lx = x - r.left - l.cx * r.width(), ly = y - r.top - l.cy * r.height();
            double a = Math.toRadians(-l.rotation);
            float rx = (float) (lx * Math.cos(a) - ly * Math.sin(a)), ry = (float) (lx * Math.sin(a) + ly * Math.cos(a));
            RectF b = LayerRenderer.bounds(getContext(), l, r.width(), r.height());
            b.inset(-24, -24);
            if (b.contains(rx, ry)) return l;
        }
        return null;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        gestures.onTouchEvent(e);
        RectF r = picture();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                TextLayer hit = hit(e.getX(), e.getY());
                if (hit != selected) select(hit);
                lastX = e.getX();
                lastY = e.getY();
                twoFingers = false;
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (selected != null && e.getPointerCount() >= 2) {
                    twoFingers = true;
                    startDistance = distance(e);
                    startAngle = angle(e);
                    startScale = selected.scale;
                    startRotation = selected.rotation;
                    startMidX = (e.getX(0) + e.getX(1)) / 2f;
                    startMidY = (e.getY(0) + e.getY(1)) / 2f;
                    startCx = selected.cx;
                    startCy = selected.cy;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (selected == null || r.width() <= 0) return true;
                if (twoFingers && e.getPointerCount() >= 2) {
                    float d = distance(e);
                    if (startDistance > 10) selected.scale = Math.max(0.15f, Math.min(10f, startScale * d / startDistance));
                    float turn = angle(e) - startAngle;
                    float rot = startRotation + turn;
                    float snapped = Math.round(rot / 90f) * 90f; // snaps straight when close
                    selected.rotation = Math.abs(rot - snapped) < 4f ? snapped : rot;
                    float midX = (e.getX(0) + e.getX(1)) / 2f, midY = (e.getY(0) + e.getY(1)) / 2f;
                    selected.cx = clamp(startCx + (midX - startMidX) / r.width());
                    selected.cy = clamp(startCy + (midY - startMidY) / r.height());
                } else if (!twoFingers) {
                    selected.cx = clamp(selected.cx + (e.getX() - lastX) / r.width());
                    selected.cy = clamp(selected.cy + (e.getY() - lastY) / r.height());
                    lastX = e.getX();
                    lastY = e.getY();
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                twoFingers = false;
                return true;
        }
        return true;
    }

    private static float clamp(float v) { return Math.max(-0.1f, Math.min(1.1f, v)); }

    private static float distance(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private static float angle(MotionEvent e) {
        return (float) Math.toDegrees(Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)));
    }
}
