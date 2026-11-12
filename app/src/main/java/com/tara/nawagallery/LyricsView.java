package com.tara.nawagallery;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Synced lyrics: the line being sung glows in neon and stays centred; tap a line to jump to it. */
public final class LyricsView extends View {
    public interface Seek { void to(long ms); }

    private final TextPaint dim = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint lit = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<StaticLayout> layouts = new ArrayList<>();
    private final List<StaticLayout> dimLayouts = new ArrayList<>();
    private final List<Float> tops = new ArrayList<>();
    private List<LrcParser.Line> lines = new ArrayList<>();
    private int current = -1, layoutWidth;
    private float scroll, scrollTarget;
    private Seek seek;
    private final float density;

    public LyricsView(Context context, int glow) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        dim.setColor(0x99FFFFFF);
        dim.setTextSize(19 * density);
        lit.setColor(Color.WHITE);
        lit.setTextSize(23 * density);
        lit.setFakeBoldText(true);
        setGlow(glow);
    }

    public void setGlow(int color) {
        lit.setShadowLayer(14 * density, 0, 0, color);
        invalidate();
    }

    public void setSeek(Seek seek) { this.seek = seek; }

    public void setLines(List<LrcParser.Line> lines) {
        this.lines = lines == null ? new ArrayList<>() : lines;
        current = -1;
        layoutWidth = 0;
        scroll = scrollTarget = 0;
        invalidate();
    }

    public boolean hasLines() { return !lines.isEmpty(); }

    public void setPosition(long ms) {
        int line = LrcParser.current(lines, ms);
        if (line != current) {
            current = line;
            invalidate();
        }
        if (Math.abs(scroll - scrollTarget) > 0.5f) invalidate();
    }

    private void build() {
        layouts.clear();
        dimLayouts.clear();
        tops.clear();
        int width = Math.max(1, getWidth() - (int) (32 * density));
        float y = 0;
        for (LrcParser.Line line : lines) {
            String text = line.text.isEmpty() ? "♪" : line.text;
            StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), lit, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
            layouts.add(layout);
            dimLayouts.add(StaticLayout.Builder.obtain(text, 0, text.length(), dim, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).build());
            tops.add(y);
            y += layout.getHeight() + 18 * density;
        }
        layoutWidth = getWidth();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (lines.isEmpty()) return;
        if (layoutWidth != getWidth()) build();
        int focus = Math.max(0, current);
        scrollTarget = tops.get(focus) + layouts.get(focus).getHeight() / 2f - getHeight() * 0.45f;
        scroll += (scrollTarget - scroll) * 0.15f;
        canvas.save();
        canvas.translate(16 * density, -scroll);
        for (int i = 0; i < layouts.size(); i++) {
            float top = tops.get(i);
            if (top - scroll > getHeight() || top + layouts.get(i).getHeight() - scroll < 0) continue;
            canvas.save();
            canvas.translate(0, top);
            StaticLayout layout = layouts.get(i);
            if (i == current) layout.draw(canvas);
            else {
                // Other lines: smaller, dimmer the further they are from the one being sung.
                StaticLayout other = dimLayouts.get(i);
                int alpha = Math.max(40, 200 - Math.abs(i - focus) * 35);
                canvas.translate(0, (layout.getHeight() - other.getHeight()) / 2f);
                canvas.saveLayerAlpha(0, 0, other.getWidth(), other.getHeight(), alpha);
                other.draw(canvas);
                canvas.restore();
            }
            canvas.restore();
        }
        canvas.restore();
        if (Math.abs(scroll - scrollTarget) > 0.5f) postInvalidateOnAnimation();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (event.getAction() == MotionEvent.ACTION_UP && seek != null && !layouts.isEmpty()) {
            float y = event.getY() + scroll;
            for (int i = 0; i < tops.size(); i++) {
                if (y >= tops.get(i) && y <= tops.get(i) + layouts.get(i).getHeight() + 18 * density) {
                    seek.to(lines.get(i).time);
                    return true;
                }
            }
        }
        return super.onTouchEvent(event);
    }
}
