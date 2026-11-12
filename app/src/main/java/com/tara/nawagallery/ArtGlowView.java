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

/** The music player's background: two slow-moving glows in the colours of the current song. */
public final class ArtGlowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int base;
    private int first, second;
    private int fromFirst, fromSecond, toFirst, toSecond;
    private float mixT = 1f, phase;
    private ValueAnimator animator;

    public ArtGlowView(Context context, int base, int first, int second) {
        super(context);
        this.base = base;
        this.first = fromFirst = toFirst = first;
        this.second = fromSecond = toSecond = second;
    }

    public void setColors(int a, int b) {
        fromFirst = first;
        fromSecond = second;
        toFirst = a;
        toSecond = b;
        mixT = 0f;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(7000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(v -> {
            phase = (float) v.getAnimatedValue();
            if (mixT < 1f) {
                mixT = Math.min(1f, mixT + 0.02f);
                first = mix(fromFirst, toFirst, mixT);
                second = mix(fromSecond, toSecond, mixT);
            }
            invalidate();
        });
        animator.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(base);
        float radius = Math.max(getWidth(), getHeight()) * 0.75f;
        paint.setShader(new RadialGradient(getWidth() * (0.15f + phase * 0.7f), getHeight() * (0.2f + phase * 0.25f), radius,
                new int[]{alpha(first, 110), alpha(first, 26), Color.TRANSPARENT}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(new RadialGradient(getWidth() * (0.85f - phase * 0.6f), getHeight() * (0.85f - phase * 0.3f), radius * 0.8f,
                new int[]{alpha(second, 90), alpha(second, 20), Color.TRANSPARENT}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);
    }

    private static int alpha(int c, int a) { return Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)); }

    private static int mix(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
