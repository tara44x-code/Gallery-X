package com.tara.nawagallery;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EditorActivity extends Activity {
    public static final String EXTRA_URI = "editor_uri";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ImageView preview;
    private ProgressBar progress;
    private CropOverlayView cropOverlay;
    private Button applyCropButton;
    private Button cancelCropButton;
    private Bitmap original;
    private Bitmap working;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!AppIntegrity.verifyOrClose(this)) return;
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
        String value = getIntent().getStringExtra(EXTRA_URI);
        if (value == null) { finish(); return; }
        load(Uri.parse(value));
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D0914);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        toolbar.setBackgroundColor(0xFF171020);
        TextView back = label("‹", 40);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 58)));
        TextView title = label("محرر الصور", 22);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 58), 1));
        Button save = button("حفظ نسخة");
        save.setOnClickListener(v -> saveCopy());
        toolbar.addView(save, new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 44)));
        root.addView(toolbar);

        FrameLayout stage = new FrameLayout(this);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        cropOverlay = new CropOverlayView(this);
        cropOverlay.setVisibility(View.GONE);
        stage.addView(cropOverlay, new FrameLayout.LayoutParams(-1, -1));
        progress = new ProgressBar(this);
        stage.addView(progress, new FrameLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52), Gravity.CENTER));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = new LinearLayout(this);
        tools.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 10));
        tools.setBackgroundColor(0xFF171020);
        addTool(tools, "↶ تدوير", () -> rotate(-90));
        addTool(tools, "↷ تدوير", () -> rotate(90));
        addTool(tools, "✂ قص اختياري", this::startCrop);
        applyCropButton = addTool(tools, "✓ تطبيق القص", this::applyCrop);
        cancelCropButton = addTool(tools, "✕ إلغاء القص", this::cancelCrop);
        applyCropButton.setVisibility(View.GONE);
        cancelCropButton.setVisibility(View.GONE);
        addTool(tools, "◐ رمادي", this::grayscale);
        addTool(tools, "☀ دافئ", this::warm);
        addTool(tools, "↺ الأصل", this::reset);
        scroll.addView(tools);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 70)));
        setContentView(root);
    }

    private TextView label(String text, float size) {
        TextView value = new TextView(this);
        value.setText(text);
        value.setTextColor(Color.WHITE);
        value.setTextSize(Ui.textSize(this, size));
        value.setGravity(Gravity.CENTER_VERTICAL);
        return value;
    }

    private Button button(String text) {
        Button value = new Button(this);
        value.setText(text);
        value.setTextColor(Color.WHITE);
        value.setTextSize(Ui.textSize(this, 15));
        value.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        value.setAllCaps(false);
        value.setBackground(Ui.rounded(ThemePrefs.getStyle(this).primary, 14, this));
        return value;
    }

    private Button addTool(LinearLayout tools, String text, Runnable action) {
        Button tool = button(text);
        tool.setBackground(Ui.rounded(0xFF342348, 14, this));
        tool.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(this, 116), Ui.dp(this, 52));
        params.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        tools.addView(tool, params);
        return tool;
    }

    private void load(Uri uri) {
        progress.setVisibility(android.view.View.VISIBLE);
        executor.execute(() -> {
            Bitmap bitmap = decode(uri);
            runOnUiThread(() -> {
                progress.setVisibility(android.view.View.GONE);
                if (bitmap == null) {
                    Toast.makeText(this, "تعذر فتح الصورة للتحرير", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                original = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                working = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                preview.setImageBitmap(working);
                updateCropBitmapSize();
            });
        });
    }

    private Bitmap decode(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri),
                        (decoder, info, source) -> {
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                            int max = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                            int sample = 1;
                            while (max / sample > 2400) sample *= 2;
                            decoder.setTargetSampleSize(sample);
                        });
            }
            try (InputStream stream = getContentResolver().openInputStream(uri)) {
                return android.graphics.BitmapFactory.decodeStream(stream);
            }
        } catch (Exception ignored) { return null; }
    }

    private void rotate(float degrees) {
        if (working == null) return;
        cancelCrop();
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        working = Bitmap.createBitmap(working, 0, 0, working.getWidth(), working.getHeight(), matrix, true);
        preview.setImageBitmap(working);
        updateCropBitmapSize();
    }

    private void startCrop() {
        if (working == null) return;
        updateCropBitmapSize();
        cropOverlay.setVisibility(View.VISIBLE);
        cropOverlay.resetSelection();
        applyCropButton.setVisibility(View.VISIBLE);
        cancelCropButton.setVisibility(View.VISIBLE);
        Toast.makeText(this, "اسحب الزوايا لتحديد الجزء المطلوب ثم اضغط تطبيق القص", Toast.LENGTH_LONG).show();
    }

    private void applyCrop() {
        applyCropSelection(true);
    }

    private boolean applyCropSelection(boolean showMessage) {
        if (working == null || cropOverlay.getVisibility() != View.VISIBLE) return false;
        Rect selected = cropOverlay.getCropBoundsInBitmap();
        if (selected == null || selected.width() < 2 || selected.height() < 2) {
            Toast.makeText(this, "حدد جزءاً صالحاً من الصورة", Toast.LENGTH_SHORT).show();
            return false;
        }
        working = Bitmap.createBitmap(working, selected.left, selected.top,
                selected.width(), selected.height());
        preview.setImageBitmap(working);
        cancelCrop();
        updateCropBitmapSize();
        if (showMessage) Toast.makeText(this, "تم تطبيق القص — اضغط حفظ نسخة", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void cancelCrop() {
        if (cropOverlay == null) return;
        cropOverlay.setVisibility(View.GONE);
        if (applyCropButton != null) applyCropButton.setVisibility(View.GONE);
        if (cancelCropButton != null) cancelCropButton.setVisibility(View.GONE);
    }

    private void updateCropBitmapSize() {
        if (working != null && cropOverlay != null) {
            cropOverlay.setBitmapSize(working.getWidth(), working.getHeight());
        }
    }

    private void grayscale() {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        apply(matrix);
    }

    private void warm() {
        ColorMatrix matrix = new ColorMatrix(new float[]{
                1.12f, 0, 0, 0, 8,
                0, 1.03f, 0, 0, 2,
                0, 0, 0.88f, 0, 0,
                0, 0, 0, 1, 0
        });
        apply(matrix);
    }

    private void apply(ColorMatrix matrix) {
        if (working == null) return;
        cancelCrop();
        Bitmap result = Bitmap.createBitmap(working.getWidth(), working.getHeight(), Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        new Canvas(result).drawBitmap(working, 0, 0, paint);
        working = result;
        preview.setImageBitmap(working);
        updateCropBitmapSize();
    }

    private void reset() {
        if (original == null) return;
        cancelCrop();
        working = original.copy(Bitmap.Config.ARGB_8888, true);
        preview.setImageBitmap(working);
        updateCropBitmapSize();
    }

    private void saveCopy() {
        if (working == null) return;
        if (cropOverlay.getVisibility() == View.VISIBLE && !applyCropSelection(false)) return;
        progress.setVisibility(android.view.View.VISIBLE);
        Bitmap snapshot = working;
        executor.execute(() -> {
            Uri saved = saveBitmap(snapshot);
            runOnUiThread(() -> {
                progress.setVisibility(android.view.View.GONE);
                if (saved != null) {
                    setResult(RESULT_OK);
                    Toast.makeText(this, "حُفظت نسخة في Pictures/Gallery X Edits", Toast.LENGTH_LONG).show();
                } else Toast.makeText(this, "تعذر حفظ النسخة", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private Uri saveBitmap(Bitmap bitmap) {
        Uri uri = null;
        try {
            String name = "GalleryX_Edit_" + System.currentTimeMillis() + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Gallery X Edits");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)) throw new Exception();
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, ready, null, null);
            }
            return uri;
        } catch (Exception ignored) {
            if (uri != null) try { getContentResolver().delete(uri, null, null); } catch (Exception ignoredDelete) { }
            return null;
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
