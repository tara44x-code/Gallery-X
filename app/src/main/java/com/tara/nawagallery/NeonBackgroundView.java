package com.tara.nawagallery;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

public final class NeonBackgroundView extends View {
    private final ThemePrefs.ThemeStyle theme;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator animator;
    private float phase;

    public NeonBackgroundView(Context context, ThemePrefs.ThemeStyle theme) {
        super(context);
        this.theme = theme;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(6500);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            phase = (float) value.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(theme.background);
        float radius = Math.max(getWidth(), getHeight()) * 0.72f;
        float x1 = getWidth() * (0.12f + phase * 0.72f);
        float y1 = getHeight() * (0.18f + phase * 0.35f);
        paint.setShader(new RadialGradient(x1, y1, radius,
                new int[]{withAlpha(theme.primary, 105), withAlpha(theme.primary, 24), Color.TRANSPARENT},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        float x2 = getWidth() * (0.88f - phase * 0.68f);
        float y2 = getHeight() * (0.82f - phase * 0.28f);
        paint.setShader(new RadialGradient(x2, y2, radius * 0.76f,
                new int[]{withAlpha(theme.secondary, 82), withAlpha(theme.secondary, 18), Color.TRANSPARENT},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
