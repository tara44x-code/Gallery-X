package com.tara.nawagallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Write on photos and videos: decorative Arabic and English fonts, colours, neon effects, ornaments, stickers and frames. */
public class TextEditorActivity extends Activity implements TextStageView.Listener {
    public static final String EXTRA_URI = "uri", EXTRA_VIDEO = "video", EXTRA_NAME = "name";

    private static final int[] PALETTE = {
            0xFFFFFFFF, 0xFF000000, 0xFFFFE45C, 0xFFFFC23D, 0xFFFF9F1C, 0xFFFF6B3D, 0xFFFF3B3B, 0xFFFF3B6B,
            0xFFFF3BD5, 0xFFE46BFF, 0xFFB26BFF, 0xFF8A5CFF, 0xFF6B7BFF, 0xFF3D8BFF, 0xFF22B5FF, 0xFF22E5FF,
            0xFF1DE9B6, 0xFF3DFF8A, 0xFF9CFF3D, 0xFFE6FF3D, 0xFFD4AF37, 0xFFC0C0C0, 0xFF8D6E63, 0xFF7A0019,
            0xFF0B3D91, 0xFF0F5132, 0xFFFFD1DC, 0xFFFFF5D6,
    };
    private static final String[] STICKERS = {
            "﷽", "۞", "۩", "☪", "☾", "✿", "❀", "❁", "✾", "❦", "❧", "✦", "✧", "★", "☆", "✪", "✯", "❂",
            "♥", "♡", "❤", "❥", "❣", "♛", "♚", "♔", "꧁", "꧂", "『", "』", "﴾", "﴿", "ـــ", "〰",
            "🌙", "⭐", "✨", "🌹", "🌸", "🌺", "💐", "🦋", "🕊", "❤️", "💜", "💙", "💚", "🧡", "💛", "🖤",
            "🔥", "💎", "👑", "🎉", "🎂", "🎁", "🎈", "🎵", "🎶", "📸", "😍", "😂", "🥰", "😎", "🤍", "💯",
            "🇮🇶", "🕋", "🤲", "📿", "🌴", "🌊", "☀️", "🌈", "⚡", "🍀",
    };

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ThemePrefs.ThemeStyle theme;
    private TextStageView stage;
    private Uri uri;
    private boolean video;
    private String name;
    private TextView hint;
    private AlertDialog progressDialog;
    private VideoTextExporter exporter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!AppIntegrity.verifyOrClose(this)) return;
        String value = getIntent().getStringExtra(EXTRA_URI);
        if (value == null) { finish(); return; }
        uri = Uri.parse(value);
        video = getIntent().getBooleanExtra(EXTRA_VIDEO, false);
        name = getIntent().getStringExtra(EXTRA_NAME);
        theme = ThemePrefs.getStyle(this);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
        loadBackground();
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF07070C);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        TextView back = label("‹", 36, true);
        back.setContentDescription("رجوع");
        back.setOnClickListener(v -> confirmLeave());
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        TextView title = label(video ? "كتابة على الفيديو" : "كتابة وزخرفة", 18, true);
        title.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        top.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        TextView save = label("حفظ ✓", 16, true);
        save.setBackground(Ui.rounded(theme.primary, 20, this));
        save.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        save.setOnClickListener(v -> save());
        top.addView(save, new LinearLayout.LayoutParams(-2, Ui.dp(this, 42)));
        root.addView(top);

        FrameLayout middle = new FrameLayout(this);
        stage = new TextStageView(this);
        stage.setListener(this);
        middle.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        hint = label("اضغط «＋ نص» حتى تكتب\nإصبع للتحريك • إصبعين للتكبير والتدوير • ضغطتين للتعديل", 14, false);
        hint.setTextColor(0xCCFFFFFF);
        hint.setShadowLayer(6, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        hp.bottomMargin = Ui.dp(this, 14);
        middle.addView(hint, hp);
        root.addView(middle, new LinearLayout.LayoutParams(-1, 0, 1));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setBackgroundColor(0xFF120C1C);
        LinearLayout tools = new LinearLayout(this);
        tools.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 10));
        tool(tools, "＋", "نص", v -> addText());
        tool(tools, "Aa", "الخط", v -> chooseFont());
        tool(tools, "🎨", "اللون", v -> chooseColor());
        tool(tools, "✦", "التأثير", v -> chooseEffect());
        tool(tools, "ﮩ", "زخرفة", v -> chooseDecor());
        tool(tools, "★", "ملصق", v -> chooseSticker());
        tool(tools, "▢", "إطار", v -> chooseFrame());
        tool(tools, "⧉", "نسخ", v -> duplicate());
        tool(tools, "⇡", "للأمام", v -> bringFront());
        tool(tools, "🗑", "حذف", v -> deleteSelected());
        scroll.addView(tools);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private TextView label(String text, float size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(Ui.textSize(this, size));
        v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private void tool(LinearLayout row, String icon, String text, View.OnClickListener click) {
        TextView v = label(icon + "\n" + text, 14, true);
        v.setLineSpacing(Ui.dp(this, 2), 1f);
        v.setBackground(Ui.rounded(Ui.withAlpha(theme.primary, 55), 16, this));
        v.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(this, 70), Ui.dp(this, 64));
        p.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        row.addView(v, p);
    }

    // ------------------------------------------------------------------ background picture

    private void loadBackground() {
        worker.execute(() -> {
            Bitmap bitmap = video ? videoFrame() : decode(2048);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (bitmap == null) { Toast.makeText(this, "تعذر فتح الملف", Toast.LENGTH_LONG).show(); finish(); return; }
                stage.setBackground(bitmap);
            });
        });
    }

    private Bitmap videoFrame() {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            Bitmap frame = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            return frame != null ? frame : r.getFrameAtTime();
        } catch (Exception e) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    /** The photo with its long side at most {@code max} pixels, upright, as a mutable software bitmap. */
    private Bitmap decode(int max) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri), (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setMutableRequired(true);
                    int longest = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    if (longest > max) {
                        float s = (float) max / longest;
                        decoder.setTargetSize(Math.round(info.getSize().getWidth() * s), Math.round(info.getSize().getHeight() * s));
                    }
                });
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inMutable = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ layers

    @Override public void onSelected(TextLayer layer) {
        if (layer != null) hint.setVisibility(View.GONE);
    }

    @Override public void onEdit(TextLayer layer) { askText(layer); }

    private TextLayer needSelected() {
        TextLayer l = stage.selected();
        if (l == null) Toast.makeText(this, "اضغط على النص أول حتى تختاره", Toast.LENGTH_SHORT).show();
        return l;
    }

    private void addText() { askText(null); }

    private void askText(TextLayer existing) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(2);
        input.setHint("اكتب هنا…");
        if (existing != null) input.setText(existing.raw);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(input);
        new AlertDialog.Builder(this).setTitle(existing == null ? "نص جديد" : "تعديل النص").setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تم", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) return;
                    if (existing != null) { existing.setText(text); stage.invalidate(); return; }
                    TextLayer layer = new TextLayer();
                    layer.font = FontStore.defaultFor(this, FontStore.hasArabic(text));
                    layer.color = 0xFFFFFFFF;
                    layer.glow = true;
                    layer.setText(text);
                    layer.cy = 0.5f + (stage.layers.size() % 4) * 0.08f;
                    stage.layers.add(layer);
                    stage.select(layer);
                }).show();
        input.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    private void duplicate() {
        TextLayer l = needSelected();
        if (l == null || l.type != TextLayer.TEXT) return;
        TextLayer copy = l.copy();
        stage.layers.add(copy);
        stage.select(copy);
    }

    private void bringFront() {
        TextLayer l = needSelected();
        if (l == null) return;
        stage.layers.remove(l);
        stage.layers.add(l);
        stage.invalidate();
    }

    private void deleteSelected() {
        TextLayer l = stage.selected();
        if (l == null) {
            // no text selected: remove the frame if there is one
            for (TextLayer f : stage.layers) if (f.type == TextLayer.FRAME) { stage.layers.remove(f); stage.invalidate(); return; }
            needSelected();
            return;
        }
        stage.layers.remove(l);
        stage.select(null);
    }

    // ------------------------------------------------------------------ fonts

    private void chooseFont() {
        TextLayer l = needSelected();
        if (l == null) return;
        List<FontStore.Font> fonts = FontStore.list(this);
        ListView list = new ListView(this);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return fonts.size(); }
            @Override public Object getItem(int i) { return fonts.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout row = new LinearLayout(TextEditorActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(Ui.dp(TextEditorActivity.this, 18), Ui.dp(TextEditorActivity.this, 10), Ui.dp(TextEditorActivity.this, 18), Ui.dp(TextEditorActivity.this, 10));
                FontStore.Font f = fonts.get(i);
                // the font's own name written in that font, nothing else
                int dash = f.label.indexOf(" — ");
                String shown = dash < 0 ? f.label : f.arabic ? f.label.substring(0, dash) : f.label.substring(dash + 3);
                TextView sample = new TextView(TextEditorActivity.this);
                sample.setText(shown + (f.id.equals(l.font) ? "  ✓" : ""));
                sample.setTextSize(26);
                sample.setTypeface(FontStore.get(TextEditorActivity.this, f.id));
                sample.setTextColor(f.id.equals(l.font) ? theme.primary : 0xFF1A1A22);
                row.addView(sample);
                return row;
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("الخط (" + fonts.size() + ")").setView(list).setNegativeButton("إغلاق", null).create();
        list.setOnItemClickListener((p, v, i, id) -> {
            l.font = fonts.get(i).id;
            stage.invalidate();
            dialog.dismiss();
        });
        dialog.show();
    }

    // ------------------------------------------------------------------ colours

    private void chooseColor() {
        TextLayer l = stage.selected();
        TextLayer target = l;
        if (target == null) for (TextLayer f : stage.layers) if (f.type == TextLayer.FRAME) target = f;
        if (target == null) { needSelected(); return; }
        TextLayer layer = target;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), 0);
        LinearLayout specials = new LinearLayout(this);
        specials.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 10));
        TextView rainbowButton = label("🌈 قوس قزح", 15, true);
        rainbowButton.setBackground(Ui.gradient(0xFFFF3B6B, 0xFF6B7BFF, 16, this));
        TextView gradientButton = label("✦ تدرج لونين", 15, true);
        gradientButton.setBackground(Ui.rounded(0xFF3A2E52, 16, this));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1);
        sp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        specials.addView(rainbowButton, sp);
        specials.addView(gradientButton, new LinearLayout.LayoutParams(sp));
        box.addView(specials);
        GridView grid = new GridView(this);
        grid.setNumColumns(7);
        grid.setVerticalSpacing(Ui.dp(this, 8));
        grid.setHorizontalSpacing(Ui.dp(this, 8));
        boolean[] gradientMode = {false};
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return PALETTE.length; }
            @Override public Object getItem(int i) { return PALETTE[i]; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                View swatch = new View(TextEditorActivity.this);
                android.graphics.drawable.GradientDrawable d = Ui.rounded(PALETTE[i], 40, TextEditorActivity.this);
                d.setStroke(Ui.dp(TextEditorActivity.this, 1), 0x55000000);
                swatch.setBackground(d);
                swatch.setLayoutParams(new GridView.LayoutParams(-1, Ui.dp(TextEditorActivity.this, 36)));
                return swatch;
            }
        });
        box.addView(grid, new LinearLayout.LayoutParams(-1, Ui.dp(this, 200)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("اللون").setView(box).setNegativeButton("إغلاق", null).create();
        rainbowButton.setOnClickListener(v -> { layer.rainbow = true; layer.color2 = 0; stage.invalidate(); dialog.dismiss(); });
        gradientButton.setOnClickListener(v -> {
            gradientMode[0] = true;
            gradientButton.setText("اختار اللون الأول ثم الثاني");
        });
        int[] picks = {0};
        grid.setOnItemClickListener((p, v, i, id) -> {
            if (gradientMode[0]) {
                if (picks[0]++ == 0) { layer.color = PALETTE[i]; layer.rainbow = false; gradientButton.setText("هسه اللون الثاني"); return; }
                layer.color2 = PALETTE[i];
            } else {
                layer.color = PALETTE[i];
                layer.color2 = 0;
                layer.rainbow = false;
            }
            stage.invalidate();
            dialog.dismiss();
        });
        dialog.show();
    }

    // ------------------------------------------------------------------ effects

    private void chooseEffect() {
        TextLayer l = needSelected();
        if (l == null) return;
        String[] items = {
                "توهج نيون: " + (l.glow ? "✓" : "✗"),
                "حدود حول الحروف: " + (l.outline == 0 ? "بدون" : l.outline == 1 ? "خفيفة" : "سميكة"),
                "لون الحدود: " + (l.outlineColor == 0xFF000000 ? "أسود" : "أبيض"),
                "ظل: " + (l.shadow ? "✓" : "✗"),
                "خلفية للنص: " + (l.boxColor == 0 ? "بدون" : (l.boxColor >>> 24) < 0xC0 ? "شفافة" : "ملوّنة"),
                "المحاذاة: " + (l.align == 0 ? "وسط" : l.align == 1 ? "بداية" : "نهاية"),
                "تباعد الحروف",
                "الحجم الأساسي",
        };
        new AlertDialog.Builder(this).setTitle("التأثير").setItems(items, (d, which) -> {
            switch (which) {
                case 0: l.glow = !l.glow; break;
                case 1: l.outline = (l.outline + 1) % 3; break;
                case 2: l.outlineColor = l.outlineColor == 0xFF000000 ? 0xFFFFFFFF : 0xFF000000; if (l.outline == 0) l.outline = 1; break;
                case 3: l.shadow = !l.shadow; break;
                case 4: l.boxColor = l.boxColor == 0 ? 0x99000000 : (l.boxColor >>> 24) < 0xC0 ? LayerRenderer.withAlpha(theme.primary, 230) : 0; break;
                case 5: l.align = (l.align + 1) % 3; break;
                case 6: slider("تباعد الحروف", Math.round(l.spacing * 100) + 20, 80, v -> { l.spacing = (v - 20) / 100f; stage.invalidate(); }); return;
                default: slider("الحجم", Math.round(l.size * 400), 120, v -> { l.size = Math.max(4, v) / 400f; stage.invalidate(); }); return;
            }
            stage.invalidate();
            chooseEffect();
        }).setNegativeButton("تم", null).show();
    }

    private interface IntSetter { void set(int value); }

    private void slider(String title, int value, int max, IntSetter setter) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) setter.set(p); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        new AlertDialog.Builder(this).setTitle(title).setView(bar).setPositiveButton("تم", null).show();
    }

    // ------------------------------------------------------------------ decoration, stickers, frames

    private void chooseDecor() {
        TextLayer l = needSelected();
        if (l == null) return;
        String[] previews = new String[TextDecor.STYLES.length];
        String sample = l.raw.length() > 14 ? l.raw.substring(0, 14) : l.raw;
        for (int i = 0; i < previews.length; i++) {
            String shown = TextDecor.apply(sample, i);
            previews[i] = (i == l.decor ? "✓ " : "") + TextDecor.STYLES[i] + (i == 0 || shown.equals(sample) ? "" : "\n" + shown);
        }
        new AlertDialog.Builder(this).setTitle("زخرفة النص").setItems(previews, (d, which) -> {
            l.decor = which;
            l.setText(l.raw);
            stage.invalidate();
        }).setNegativeButton("إغلاق", null).show();
    }

    private void chooseSticker() {
        GridView grid = new GridView(this);
        grid.setNumColumns(6);
        grid.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return STICKERS.length; }
            @Override public Object getItem(int i) { return STICKERS[i]; }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View convert, ViewGroup parent) {
                TextView v = new TextView(TextEditorActivity.this);
                v.setText(STICKERS[i]);
                v.setTextSize(28);
                v.setGravity(Gravity.CENTER);
                v.setTextColor(0xFF2A1A44);
                v.setLayoutParams(new GridView.LayoutParams(-1, Ui.dp(TextEditorActivity.this, 54)));
                return v;
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("زخارف وملصقات").setView(grid).setNegativeButton("إغلاق", null).create();
        grid.setOnItemClickListener((p, v, i, id) -> {
            TextLayer layer = new TextLayer();
            layer.font = "sys";
            layer.size = 0.16f;
            layer.color = theme.primary;
            layer.glow = STICKERS[i].codePointAt(0) < 0x1F000; // ornaments glow, emoji stay natural
            layer.setText(STICKERS[i]);
            layer.cy = 0.35f;
            stage.layers.add(layer);
            stage.select(layer);
            hint.setVisibility(View.GONE);
            dialog.dismiss();
        });
        dialog.show();
    }

    private void chooseFrame() {
        String[] items = new String[LayerRenderer.FRAMES.length + 1];
        System.arraycopy(LayerRenderer.FRAMES, 0, items, 0, LayerRenderer.FRAMES.length);
        items[items.length - 1] = "بدون إطار";
        new AlertDialog.Builder(this).setTitle("إطار للصورة").setItems(items, (d, which) -> {
            TextLayer frame = null;
            for (TextLayer f : stage.layers) if (f.type == TextLayer.FRAME) frame = f;
            if (which == items.length - 1) { if (frame != null) stage.layers.remove(frame); stage.invalidate(); return; }
            if (frame == null) {
                frame = new TextLayer();
                frame.type = TextLayer.FRAME;
                frame.color = theme.primary;
                stage.layers.add(0, frame);
            }
            frame.frameStyle = which;
            if (which == 6) frame.color = Color.WHITE;
            stage.invalidate();
            Toast.makeText(this, "تگدر تغيّر لون الإطار من 🎨 (بدون ما تختار نص)", Toast.LENGTH_SHORT).show();
        }).show();
    }

    // ------------------------------------------------------------------ save

    private void save() {
        if (stage.layers.isEmpty()) { Toast.makeText(this, "ما كتبت شي بعد", Toast.LENGTH_SHORT).show(); return; }
        stage.select(null);
        if (video) saveVideo(); else saveImage();
    }

    private void showProgress(String title) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20));
        box.addView(new ProgressBar(this));
        progressDialog = new AlertDialog.Builder(this).setTitle(title).setView(box).setCancelable(false).create();
        progressDialog.show();
    }

    private void saveImage() {
        showProgress("جارِ حفظ الصورة…");
        List<TextLayer> layers = new java.util.ArrayList<>(stage.layers);
        worker.execute(() -> {
            Uri saved = null;
            try {
                Bitmap full = decode(4096);
                if (full != null) {
                    if (!full.isMutable()) full = full.copy(Bitmap.Config.ARGB_8888, true);
                    LayerRenderer.drawAll(this, new Canvas(full), layers, full.getWidth(), full.getHeight());
                    saved = store(full);
                }
            } catch (Throwable ignored) { }
            Uri result = saved;
            runOnUiThread(() -> {
                if (progressDialog != null) progressDialog.dismiss();
                if (result == null) { Toast.makeText(this, "تعذر حفظ الصورة", Toast.LENGTH_LONG).show(); return; }
                Toast.makeText(this, "انحفظت نسخة جديدة في Pictures/Gallery X Edits ✓", Toast.LENGTH_LONG).show();
                setResult(RESULT_OK);
                finish();
            });
        });
    }

    private Uri store(Bitmap bitmap) {
        Uri out = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "GalleryX_Text_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Gallery X Edits");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            out = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (out == null) return null;
            try (OutputStream stream = getContentResolver().openOutputStream(out)) {
                if (stream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw new java.io.IOException();
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(out, done, null, null);
            }
            return out;
        } catch (Exception e) {
            if (out != null) try { getContentResolver().delete(out, null, null); } catch (Exception ignored) { }
            return null;
        }
    }

    private void saveVideo() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 8));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView percent = new TextView(this);
        percent.setText("0%");
        percent.setGravity(Gravity.CENTER);
        box.addView(bar);
        box.addView(percent);
        progressDialog = new AlertDialog.Builder(this).setTitle("جارِ حفظ الفيديو…").setView(box).setCancelable(false)
                .setNegativeButton("إلغاء", (d, w) -> { if (exporter != null) exporter.cancel(); }).create();
        progressDialog.show();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        File output = new File(getCacheDir(), "gx-text-video.mp4");
        output.delete();
        exporter = new VideoTextExporter();
        try {
            exporter.start(this, uri, stage.layers, output, new VideoTextExporter.Callback() {
                @Override public void onProgress(int p) { bar.setProgress(p); percent.setText(p + "%"); }
                @Override public void onDone(File file) {
                    worker.execute(() -> {
                        boolean ok = storeVideo(file);
                        file.delete();
                        runOnUiThread(() -> finishVideo(ok ? "انحفظ الفيديو في Movies/Gallery X Edits ✓" : "تعذر حفظ الفيديو"));
                    });
                }
                @Override public void onError(String message) { finishVideo("تعذر حفظ الفيديو: " + message); }
            });
        } catch (Throwable error) {
            finishVideo("هاتفك ما يدعم الكتابة على هذا الفيديو");
        }
    }

    private void finishVideo(String message) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (isFinishing()) return;
        if (progressDialog != null) progressDialog.dismiss();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (message.endsWith("✓")) { setResult(RESULT_OK); finish(); }
    }

    private boolean storeVideo(File file) {
        Uri out = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "GalleryX_Text_" + System.currentTimeMillis() + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Gallery X Edits");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
            }
            out = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (out == null) return false;
            try (InputStream in = new FileInputStream(file); OutputStream stream = getContentResolver().openOutputStream(out)) {
                if (stream == null) throw new java.io.IOException();
                byte[] buffer = new byte[256 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) stream.write(buffer, 0, n);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(out, done, null, null);
            }
            return true;
        } catch (Exception e) {
            if (out != null) try { getContentResolver().delete(out, null, null); } catch (Exception ignored) { }
            return false;
        }
    }

    private void confirmLeave() {
        if (stage.layers.isEmpty()) { finish(); return; }
        new AlertDialog.Builder(this).setTitle("تطلع بدون حفظ؟").setMessage("الكتابة اللي سويتها راح تروح.")
                .setNegativeButton("ارجع", null).setPositiveButton("اطلع", (d, w) -> finish()).show();
    }

    @Override public void onBackPressed() { confirmLeave(); }

    @Override protected void onDestroy() {
        if (exporter != null) exporter.cancel();
        worker.shutdownNow();
        super.onDestroy();
    }
}
