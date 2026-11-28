package com.tara.nawagallery;

/** One thing placed on a photo or video: a piece of text / sticker, or a frame around the whole picture. */
final class TextLayer {
    static final int TEXT = 0, FRAME = 1;

    int type = TEXT;
    /** What the user typed; {@link #text} is it after decoration. */
    String raw = "";
    String text = "";
    int decor;
    String font = "sys_bold";
    /** Text size as a fraction of the picture's width (before pinch scale). */
    float size = 0.09f;
    /** Centre, as fractions of the picture's width and height. */
    float cx = 0.5f, cy = 0.5f;
    float scale = 1f, rotation;
    int color = 0xFFFFFFFF;
    /** Second colour for a two-colour gradient (0 = single colour). */
    int color2;
    boolean rainbow;
    boolean glow = true;
    int outline;              // 0 none, 1 thin, 2 thick
    int outlineColor = 0xFF000000;
    boolean shadow;
    int boxColor;             // 0 = no background box
    int align;                // 0 centre, 1 start, 2 end
    float spacing;
    int frameStyle;

    void setText(String raw) {
        this.raw = raw == null ? "" : raw;
        this.text = TextDecor.apply(this.raw, decor);
    }

    TextLayer copy() {
        TextLayer l = new TextLayer();
        l.type = type; l.raw = raw; l.text = text; l.decor = decor; l.font = font; l.size = size;
        l.cx = Math.min(0.9f, cx + 0.04f); l.cy = Math.min(0.9f, cy + 0.04f); l.scale = scale; l.rotation = rotation;
        l.color = color; l.color2 = color2; l.rainbow = rainbow; l.glow = glow; l.outline = outline;
        l.outlineColor = outlineColor; l.shadow = shadow; l.boxColor = boxColor; l.align = align; l.spacing = spacing;
        l.frameStyle = frameStyle;
        return l;
    }
}
