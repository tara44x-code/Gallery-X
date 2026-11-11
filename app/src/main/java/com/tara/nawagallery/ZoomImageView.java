package com.tara.nawagallery;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

public final class ZoomImageView extends ImageView {
    public interface SwipeListener {
        void onSwipeLeft();
        void onSwipeRight();
    }
    private final Matrix matrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float baseScale = 1f;
    private float currentScale = 1f;
    private float touchDownX;
    private float touchDownY;
    private SwipeListener swipeListener;

    public ZoomImageView(Context context) { this(context, null); }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (currentScale > baseScale) {
                    matrix.postTranslate(-distanceX, -distanceY);
                    constrain();
                    setImageMatrix(matrix);
                }
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                float target = currentScale > baseScale * 1.2f ? baseScale : Math.min(baseScale * 2.5f, baseScale * 5f);
                float factor = target / currentScale;
                matrix.postScale(factor, factor, e.getX(), e.getY());
                currentScale = target;
                constrain();
                setImageMatrix(matrix);
                return true;
            }
        });
    }

    @Override public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::fitToScreen);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::fitToScreen);
    }

    private void fitToScreen() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0 || getHeight() == 0) return;
        int dw = Math.max(1, drawable.getIntrinsicWidth());
        int dh = Math.max(1, drawable.getIntrinsicHeight());
        baseScale = Math.min((float) getWidth() / dw, (float) getHeight() / dh);
        currentScale = baseScale;
        float dx = (getWidth() - dw * baseScale) / 2f;
        float dy = (getHeight() - dh * baseScale) / 2f;
        matrix.reset();
        matrix.postScale(baseScale, baseScale);
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }

    private void constrain() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        RectF bounds = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        matrix.mapRect(bounds);
        float dx = 0, dy = 0;
        if (bounds.width() <= getWidth()) dx = getWidth() / 2f - bounds.centerX();
        else if (bounds.left > 0) dx = -bounds.left;
        else if (bounds.right < getWidth()) dx = getWidth() - bounds.right;
        if (bounds.height() <= getHeight()) dy = getHeight() / 2f - bounds.centerY();
        else if (bounds.top > 0) dy = -bounds.top;
        else if (bounds.bottom < getHeight()) dy = getHeight() - bounds.bottom;
        matrix.postTranslate(dx, dy);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchDownX = event.getX();
            touchDownY = event.getY();
        }
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP && swipeListener != null
                && currentScale <= baseScale * 1.05f) {
            float dx = event.getX() - touchDownX;
            float dy = event.getY() - touchDownY;
            if (Math.abs(dx) > 110 && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                if (dx < 0) swipeListener.onSwipeLeft(); else swipeListener.onSwipeRight();
            }
        }
        return true;
    }

    public void setSwipeListener(SwipeListener listener) {
        swipeListener = listener;
    }

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScale(ScaleGestureDetector detector) {
            float desired = currentScale * detector.getScaleFactor();
            float min = baseScale;
            float max = baseScale * 5f;
            float factor = detector.getScaleFactor();
            if (desired < min) factor = min / currentScale;
            if (desired > max) factor = max / currentScale;
            matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
            currentScale *= factor;
            constrain();
            setImageMatrix(matrix);
            return true;
        }
    }
}
