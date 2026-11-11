package com.tara.nawagallery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Interactive crop overlay for an ImageView using FIT_CENTER. */
public class CropOverlayView extends View {
    private static final int NONE = 0;
    private static final int MOVE = 1;
    private static final int TOP_LEFT = 2;
    private static final int TOP_RIGHT = 3;
    private static final int BOTTOM_LEFT = 4;
    private static final int BOTTOM_RIGHT = 5;
    private static final int NEW_SELECTION = 6;

    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF imageRect = new RectF();
    private final RectF cropRect = new RectF();
    private final float handleRadius;
    private final float hitRadius;
    private final float minSize;
    private int bitmapWidth;
    private int bitmapHeight;
    private int action = NONE;
    private float lastX;
    private float lastY;
    private float startX;
    private float startY;

    public CropOverlayView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        handleRadius = Ui.dp(context, 8);
        hitRadius = Ui.dp(context, 30);
        minSize = Ui.dp(context, 56);
        dimPaint.setColor(0xA8000000);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Ui.dp(context, 3));
        borderPaint.setColor(0xFF59E6FF);
        borderPaint.setShadowLayer(Ui.dp(context, 8), 0, 0, 0xFF9B4DFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(Ui.dp(context, 1));
        gridPaint.setColor(0xBFFFFFFF);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setColor(Color.WHITE);
        handlePaint.setShadowLayer(Ui.dp(context, 7), 0, 0, 0xFF59E6FF);
    }

    public void setBitmapSize(int width, int height) {
        bitmapWidth = width;
        bitmapHeight = height;
        updateImageRect();
        resetSelection();
    }

    public void resetSelection() {
        if (imageRect.isEmpty()) return;
        float insetX = imageRect.width() * 0.1f;
        float insetY = imageRect.height() * 0.1f;
        cropRect.set(imageRect.left + insetX, imageRect.top + insetY,
                imageRect.right - insetX, imageRect.bottom - insetY);
        invalidate();
    }

    public Rect getCropBoundsInBitmap() {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || imageRect.isEmpty() || cropRect.isEmpty()) return null;
        float scaleX = bitmapWidth / imageRect.width();
        float scaleY = bitmapHeight / imageRect.height();
        int left = Math.max(0, Math.round((cropRect.left - imageRect.left) * scaleX));
        int top = Math.max(0, Math.round((cropRect.top - imageRect.top) * scaleY));
        int right = Math.min(bitmapWidth, Math.round((cropRect.right - imageRect.left) * scaleX));
        int bottom = Math.min(bitmapHeight, Math.round((cropRect.bottom - imageRect.top) * scaleY));
        return right > left && bottom > top ? new Rect(left, top, right, bottom) : null;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateImageRect();
        resetSelection();
    }

    private void updateImageRect() {
        if (getWidth() <= 0 || getHeight() <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return;
        float scale = Math.min((float) getWidth() / bitmapWidth, (float) getHeight() / bitmapHeight);
        float width = bitmapWidth * scale;
        float height = bitmapHeight * scale;
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        imageRect.set(left, top, left + width, top + height);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (imageRect.isEmpty() || cropRect.isEmpty()) return;
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropRect.top, dimPaint);
        canvas.drawRect(imageRect.left, cropRect.bottom, imageRect.right, imageRect.bottom, dimPaint);
        canvas.drawRect(imageRect.left, cropRect.top, cropRect.left, cropRect.bottom, dimPaint);
        canvas.drawRect(cropRect.right, cropRect.top, imageRect.right, cropRect.bottom, dimPaint);
        canvas.drawRect(cropRect, borderPaint);
        float thirdW = cropRect.width() / 3f;
        float thirdH = cropRect.height() / 3f;
        canvas.drawLine(cropRect.left + thirdW, cropRect.top, cropRect.left + thirdW, cropRect.bottom, gridPaint);
        canvas.drawLine(cropRect.left + thirdW * 2, cropRect.top, cropRect.left + thirdW * 2, cropRect.bottom, gridPaint);
        canvas.drawLine(cropRect.left, cropRect.top + thirdH, cropRect.right, cropRect.top + thirdH, gridPaint);
        canvas.drawLine(cropRect.left, cropRect.top + thirdH * 2, cropRect.right, cropRect.top + thirdH * 2, gridPaint);
        drawHandle(canvas, cropRect.left, cropRect.top);
        drawHandle(canvas, cropRect.right, cropRect.top);
        drawHandle(canvas, cropRect.left, cropRect.bottom);
        drawHandle(canvas, cropRect.right, cropRect.bottom);
    }

    private void drawHandle(Canvas canvas, float x, float y) {
        canvas.drawCircle(x, y, handleRadius, handlePaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (imageRect.isEmpty()) return false;
        float x = clamp(event.getX(), imageRect.left, imageRect.right);
        float y = clamp(event.getY(), imageRect.top, imageRect.bottom);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            action = hitTest(x, y);
            lastX = startX = x;
            lastY = startY = y;
            if (action == NEW_SELECTION) cropRect.set(x, y, x, y);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (action == MOVE) moveBy(x - lastX, y - lastY);
            else if (action == TOP_LEFT) resize(x, y, cropRect.right, cropRect.bottom);
            else if (action == TOP_RIGHT) resize(cropRect.left, y, x, cropRect.bottom);
            else if (action == BOTTOM_LEFT) resize(x, cropRect.top, cropRect.right, y);
            else if (action == BOTTOM_RIGHT) resize(cropRect.left, cropRect.top, x, y);
            else if (action == NEW_SELECTION) resize(startX, startY, x, y);
            lastX = x;
            lastY = y;
            invalidate();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            action = NONE;
            getParent().requestDisallowInterceptTouchEvent(false);
            performClick();
            return true;
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private int hitTest(float x, float y) {
        if (near(x, y, cropRect.left, cropRect.top)) return TOP_LEFT;
        if (near(x, y, cropRect.right, cropRect.top)) return TOP_RIGHT;
        if (near(x, y, cropRect.left, cropRect.bottom)) return BOTTOM_LEFT;
        if (near(x, y, cropRect.right, cropRect.bottom)) return BOTTOM_RIGHT;
        if (cropRect.contains(x, y)) return MOVE;
        return NEW_SELECTION;
    }

    private boolean near(float x, float y, float targetX, float targetY) {
        float dx = x - targetX;
        float dy = y - targetY;
        return dx * dx + dy * dy <= hitRadius * hitRadius;
    }

    private void moveBy(float dx, float dy) {
        if (cropRect.left + dx < imageRect.left) dx = imageRect.left - cropRect.left;
        if (cropRect.right + dx > imageRect.right) dx = imageRect.right - cropRect.right;
        if (cropRect.top + dy < imageRect.top) dy = imageRect.top - cropRect.top;
        if (cropRect.bottom + dy > imageRect.bottom) dy = imageRect.bottom - cropRect.bottom;
        cropRect.offset(dx, dy);
    }

    private void resize(float x1, float y1, float x2, float y2) {
        float left = Math.min(x1, x2);
        float right = Math.max(x1, x2);
        float top = Math.min(y1, y2);
        float bottom = Math.max(y1, y2);
        if (right - left < minSize) {
            if (x2 >= x1) right = Math.min(imageRect.right, left + minSize);
            else left = Math.max(imageRect.left, right - minSize);
        }
        if (bottom - top < minSize) {
            if (y2 >= y1) bottom = Math.min(imageRect.bottom, top + minSize);
            else top = Math.max(imageRect.top, bottom - minSize);
        }
        cropRect.set(clamp(left, imageRect.left, imageRect.right),
                clamp(top, imageRect.top, imageRect.bottom),
                clamp(right, imageRect.left, imageRect.right),
                clamp(bottom, imageRect.top, imageRect.bottom));
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
