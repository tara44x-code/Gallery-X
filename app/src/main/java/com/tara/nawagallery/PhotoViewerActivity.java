package com.tara.nawagallery;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhotoViewerActivity extends Activity {
    public static final String EXTRA_URI = "photo_uri";
    public static final String EXTRA_NAME = "photo_name";
    private static final int REQUEST_TRASH = 88;
    private static final int REQUEST_EDITOR = 89;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ZoomImageView image;
    private ProgressBar progress;
    private TextView nameLabel;
    private TextView positionLabel;
    private TextView favoriteButton;
    private TextView slideshowButton;
    private MediaItem current;
    private int loadGeneration;
    private boolean slideshowRunning;
    private static final String PREFS = "gx_slideshow";
    /** Slideshow transitions; 0 picks a different one each time. */
    private static final String[] EFFECTS = {"✨ عشوائي", "تلاشي", "انزلاق", "دفع للأعلى", "تكبير", "عبور", "قلب ثلاثي الأبعاد",
            "مكعب", "دوامة", "دائرة كاشفة", "ضباب", "ومضة نيون", "ستارة"};
    private static final int[] SECONDS = {2, 3, 4, 6, 8, 12};
    private ImageView outgoing;
    private View flash;
    private LinearLayout topBar, actionsBar, slidePill;
    private TextView pillEffect, pillTime;
    private int lastEffect;
    private final java.util.Random random = new java.util.Random();
    private final Runnable slideshowTick = new Runnable() {
        @Override public void run() {
            if (!slideshowRunning || isFinishing()) return;
            MediaItem item = ViewerSession.next();
            if (item == null) item = ViewerSession.first(); // loop back to the first photo
            if (item == null) { stopSlideshow(); return; }
            slideTo(item);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!AppIntegrity.verifyOrClose(this)) return;
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(true);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();

        Uri incoming = getIntent().getData();
        boolean openedExternally = incoming != null;
        if (incoming != null) {
            ViewerSession.clear();
            String name = incoming.getLastPathSegment();
            String mime = getIntent().getType();
            current = new MediaItem(0, incoming, name == null ? "صورة" : name,
                    "", "", 0, mime == null ? "image/*" : mime);
        } else if (!ViewerSession.isEmpty()) current = ViewerSession.current();
        if (current == null) {
            String uriValue = getIntent().getStringExtra(EXTRA_URI);
            if (uriValue == null) { finish(); return; }
            current = new MediaItem(0, Uri.parse(uriValue), getIntent().getStringExtra(EXTRA_NAME),
                    "", "", 0, "image/*");
        }
        if (VaultStore.isVaultUri(current.uri)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        showCurrent(0);
        if (openedExternally) loadExternalFolder(incoming);
    }

    private void loadExternalFolder(Uri incoming) {
        executor.execute(() -> {
            GalleryRepository.ImageSession session = GalleryRepository.loadImageSession(this, incoming);
            runOnUiThread(() -> {
                if (isFinishing() || session.items.isEmpty()) return;
                ViewerSession.open(session.items, session.selectedIndex);
                current = ViewerSession.current();
                nameLabel.setText(current.name == null ? "صورة" : current.name);
                positionLabel.setText(getString(R.string.photo_position,
                        ViewerSession.index() + 1, ViewerSession.size()));
                updateFavoriteButton();
            });
        });
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        image = new ZoomImageView(this);
        image.setSwipeListener(new ZoomImageView.SwipeListener() {
            @Override public void onSwipeLeft() { showNext(); }
            @Override public void onSwipeRight() { showPrevious(); }
        });
        outgoing = new ImageView(this);
        outgoing.setScaleType(ImageView.ScaleType.MATRIX);
        outgoing.setVisibility(View.GONE);
        root.addView(outgoing, new FrameLayout.LayoutParams(-1, -1));
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        flash = new View(this);
        flash.setAlpha(0f);
        flash.setVisibility(View.GONE);
        root.addView(flash, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this);
        root.addView(progress, new FrameLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54), Gravity.CENTER));

        LinearLayout top = new LinearLayout(this);
        topBar = top;
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 12), 0);
        top.setBackgroundColor(0x88000000);
        TextView back = action("‹", "رجوع", v -> finish());
        back.setTextSize(Ui.textSize(this, 44));
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 58)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        nameLabel = new TextView(this);
        nameLabel.setTextColor(Color.WHITE);
        nameLabel.setTextSize(Ui.textSize(this, 17));
        nameLabel.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        nameLabel.setSingleLine(true);
        positionLabel = new TextView(this);
        positionLabel.setTextColor(0xFFC7BBCF);
        positionLabel.setTextSize(Ui.textSize(this, 14));
        labels.addView(nameLabel);
        labels.addView(positionLabel);
        top.addView(labels, new LinearLayout.LayoutParams(0, -1, 1));
        TextView info = action("ⓘ", "معلومات الصورة", v -> showInfo());
        info.setTextSize(Ui.textSize(this, 24));
        top.addView(info, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 58)));
        root.addView(top, new FrameLayout.LayoutParams(-1, Ui.dp(this, 64), Gravity.TOP));

        LinearLayout actions = new LinearLayout(this);
        actionsBar = actions;
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 8));
        actions.setBackgroundColor(0xDD171020);
        actions.addView(action("⇧\n" + getString(R.string.share), getString(R.string.share), v -> share()), actionParams());
        actions.addView(action("✎\nتعديل", "تعديل الصورة", v -> edit()), actionParams());
        favoriteButton = action("♡\n" + getString(R.string.favorite), getString(R.string.favorite), v -> toggleFavorite());
        actions.addView(favoriteButton, actionParams());
        slideshowButton = action("▶\nعرض", "عرض شرائح", v -> toggleSlideshow());
        actions.addView(slideshowButton, actionParams());
        actions.addView(action("⌫\n" + getString(R.string.trash), getString(R.string.trash), v -> confirmTrash()), actionParams());
        root.addView(actions, new FrameLayout.LayoutParams(-1, Ui.dp(this, 70), Gravity.BOTTOM));

        slidePill = new LinearLayout(this);
        slidePill.setGravity(Gravity.CENTER);
        slidePill.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        slidePill.setBackground(Ui.rounded(0xCC171020, 28, this));
        slidePill.setElevation(Ui.dp(this, 10));
        TextView pause = action("Ⅱ إيقاف", "إيقاف العرض", v -> stopSlideshow());
        pillEffect = action("", "تأثير الانتقال", v -> chooseEffect());
        pillTime = action("", "مدة كل صورة", v -> chooseTime());
        for (TextView t : new TextView[]{pause, pillEffect, pillTime}) {
            t.setTextSize(Ui.textSize(this, 14.5f));
            t.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
            slidePill.addView(t, new LinearLayout.LayoutParams(-2, -1));
        }
        slidePill.setVisibility(View.GONE);
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(-2, Ui.dp(this, 52), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pillParams.bottomMargin = Ui.dp(this, 26);
        root.addView(slidePill, pillParams);
        setContentView(root);
    }

    private LinearLayout.LayoutParams actionParams() {
        return new LinearLayout.LayoutParams(0, -1, 1);
    }

    private TextView action(String label, String description, View.OnClickListener listener) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(Ui.textSize(this, 15.5f));
        view.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setOnClickListener(listener);
        return view;
    }

    private void showNext() {
        MediaItem item = ViewerSession.next();
        if (item == null) {
            image.animate().translationX(-Ui.dp(this, 18)).setDuration(80)
                    .withEndAction(() -> image.animate().translationX(0).setDuration(100).start()).start();
            return;
        }
        current = item;
        showCurrent(-1);
    }

    private void showPrevious() {
        MediaItem item = ViewerSession.previous();
        if (item == null) {
            image.animate().translationX(Ui.dp(this, 18)).setDuration(80)
                    .withEndAction(() -> image.animate().translationX(0).setDuration(100).start()).start();
            return;
        }
        current = item;
        showCurrent(1);
    }

    private void showCurrent(int direction) {
        resetTransition();
        int generation = ++loadGeneration;
        progress.setVisibility(View.VISIBLE);
        image.setImageDrawable(null);
        nameLabel.setText(current.name == null ? "صورة" : current.name);
        if (ViewerSession.isEmpty()) positionLabel.setText("");
        else positionLabel.setText(getString(R.string.photo_position, ViewerSession.index() + 1, ViewerSession.size()));
        updateFavoriteButton();
        if (direction != 0) {
            image.setTranslationX(direction * Ui.dp(this, 80));
            image.setAlpha(0.2f);
            image.animate().translationX(0).alpha(1f).setDuration(180).start();
        }
        loadImage(current.uri, generation);
    }

    private void loadImage(Uri uri, int generation) {
        executor.execute(() -> {
            Drawable result = decode(uri);
            runOnUiThread(() -> {
                if (generation != loadGeneration || isFinishing()) return;
                progress.setVisibility(View.GONE);
                if (result != null) {
                    image.setImageDrawable(result);
                    if (result instanceof Animatable) ((Animatable) result).start();
                } else Toast.makeText(this, "تعذر فتح هذه الصورة", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private Drawable decode(Uri uri) {
        {
            Drawable drawable = null;
            try {
                if (Build.VERSION.SDK_INT >= 28) {
                    ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
                    drawable = ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        int screen = Math.max(getResources().getDisplayMetrics().widthPixels,
                                getResources().getDisplayMetrics().heightPixels) * 2;
                        int sourceMax = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                        int sample = 1;
                        while (sourceMax / sample > screen) sample *= 2;
                        decoder.setTargetSampleSize(sample);
                    });
                } else {
                    try (InputStream stream = getContentResolver().openInputStream(uri)) {
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(stream);
                        if (bitmap != null) drawable = new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
                    }
                }
            } catch (Exception ignored) { }
            return drawable;
        }
    }

    private void toggleFavorite() {
        boolean favorite = FavoritesStore.toggle(this, current.uri.toString());
        updateFavoriteButton();
        Toast.makeText(this, favorite ? "أضيفت إلى المفضلة" : "أزيلت من المفضلة", Toast.LENGTH_SHORT).show();
    }

    private void updateFavoriteButton() {
        boolean favorite = current != null && FavoritesStore.contains(this, current.uri.toString());
        favoriteButton.setText((favorite ? "♥" : "♡") + "\n" + getString(favorite ? R.string.remove_favorite : R.string.favorite));
        favoriteButton.setTextColor(favorite ? 0xFFFF7AD9 : Color.WHITE);
    }

    private void share() {
        ShareHelper.share(this, java.util.Collections.singletonList(current), getString(R.string.share));
    }

    private void edit() {
        Intent intent = new Intent(this, EditorActivity.class);
        intent.putExtra(EditorActivity.EXTRA_URI, current.uri.toString());
        startActivityForResult(intent, REQUEST_EDITOR);
    }

    // ================================================================== slideshow with transitions

    private void toggleSlideshow() {
        if (slideshowRunning) { stopSlideshow(); return; }
        if (ViewerSession.size() < 2) { Toast.makeText(this, "العرض يحتاج صورتين أو أكثر", Toast.LENGTH_SHORT).show(); return; }
        slideshowRunning = true;
        slideshowButton.setText("Ⅱ\nإيقاف");
        slideshowButton.setTextColor(0xFFFF7AD9);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        topBar.animate().alpha(0f).setDuration(250).withEndAction(() -> topBar.setVisibility(View.GONE)).start();
        actionsBar.animate().alpha(0f).setDuration(250).withEndAction(() -> actionsBar.setVisibility(View.GONE)).start();
        updatePill();
        slidePill.setAlpha(0f);
        slidePill.setVisibility(View.VISIBLE);
        slidePill.animate().alpha(1f).setDuration(250).start();
        startKenBurns();
        mainHandler.postDelayed(slideshowTick, intervalMs());
    }

    private void stopSlideshow() {
        boolean wasRunning = slideshowRunning;
        slideshowRunning = false;
        mainHandler.removeCallbacks(slideshowTick);
        if (slideshowButton != null) {
            slideshowButton.setText("▶\nعرض");
            slideshowButton.setTextColor(Color.WHITE);
        }
        if (!wasRunning || topBar == null) return;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        resetTransition();
        for (View bar : new View[]{topBar, actionsBar}) {
            bar.setVisibility(View.VISIBLE);
            bar.animate().alpha(1f).setDuration(250).start();
        }
        slidePill.setVisibility(View.GONE);
    }

    private int effectSetting() { return getSharedPreferences(PREFS, MODE_PRIVATE).getInt("effect", 0); }
    private int intervalMs() { return getSharedPreferences(PREFS, MODE_PRIVATE).getInt("seconds", 4) * 1000; }

    private void updatePill() {
        pillEffect.setText(EFFECTS[Math.min(effectSetting(), EFFECTS.length - 1)]);
        pillTime.setText("⏱ " + intervalMs() / 1000 + " ث");
    }

    private void chooseEffect() {
        new AlertDialog.Builder(this).setTitle("تأثير الانتقال")
                .setSingleChoiceItems(EFFECTS, effectSetting(), (d, which) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("effect", which).apply();
                    updatePill();
                    d.dismiss();
                }).show();
    }

    private void chooseTime() {
        String[] labels = new String[SECONDS.length];
        int checked = 0;
        for (int i = 0; i < SECONDS.length; i++) {
            labels[i] = SECONDS[i] + " ثواني";
            if (SECONDS[i] * 1000 == intervalMs()) checked = i;
        }
        new AlertDialog.Builder(this).setTitle("مدة كل صورة")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("seconds", SECONDS[which]).apply();
                    updatePill();
                    d.dismiss();
                }).show();
    }

    /** Keeps the photo that is leaving on a layer underneath, loads the next one, then plays the transition. */
    private void slideTo(MediaItem item) {
        resetTransition();
        Drawable old = image.getDrawable();
        if (old != null) {
            outgoing.setImageDrawable(old);
            outgoing.setImageMatrix(new android.graphics.Matrix(image.getImageMatrix()));
            outgoing.setVisibility(View.VISIBLE);
        }
        current = item;
        nameLabel.setText(current.name == null ? "صورة" : current.name);
        positionLabel.setText(getString(R.string.photo_position, ViewerSession.index() + 1, ViewerSession.size()));
        updateFavoriteButton();
        int generation = ++loadGeneration;
        executor.execute(() -> {
            Drawable next = decode(item.uri);
            runOnUiThread(() -> {
                if (generation != loadGeneration || isFinishing() || !slideshowRunning) return;
                if (next == null) { outgoing.setVisibility(View.GONE); mainHandler.postDelayed(slideshowTick, 300); return; }
                image.setImageDrawable(next);
                if (next instanceof Animatable) ((Animatable) next).start();
                int effect = effectSetting();
                if (effect == 0) {
                    do effect = 1 + random.nextInt(EFFECTS.length - 1); while (effect == lastEffect);
                }
                lastEffect = effect;
                playTransition(effect, () -> {
                    outgoing.setVisibility(View.GONE);
                    outgoing.setImageDrawable(null);
                    startKenBurns();
                    if (slideshowRunning) mainHandler.postDelayed(slideshowTick, intervalMs());
                });
            });
        });
    }

    /** Slow cinematic zoom while a photo is on screen. */
    private void startKenBurns() {
        image.setPivotX(image.getWidth() * (0.3f + random.nextFloat() * 0.4f));
        image.setPivotY(image.getHeight() * (0.3f + random.nextFloat() * 0.4f));
        image.animate().scaleX(1.07f).scaleY(1.07f).setDuration(intervalMs() + 1200)
                .setInterpolator(new android.view.animation.LinearInterpolator()).start();
    }

    private void playTransition(int effect, Runnable done) {
        float w = image.getWidth(), h = image.getHeight();
        long d = 900;
        android.view.animation.Interpolator ease = new android.view.animation.DecelerateInterpolator(1.6f);
        image.animate().setInterpolator(ease);
        outgoing.animate().setInterpolator(ease);
        switch (effect) {
            case 2: // slide
                image.setTranslationX(w);
                image.animate().translationX(0).setDuration(d).withEndAction(done).start();
                outgoing.animate().translationX(-w * 0.35f).alpha(0.4f).setDuration(d).start();
                break;
            case 3: // push up
                image.setTranslationY(h);
                image.animate().translationY(0).setDuration(d).withEndAction(done).start();
                outgoing.animate().translationY(-h * 0.3f).alpha(0.3f).setDuration(d).start();
                break;
            case 4: // zoom in
                image.setAlpha(0f); image.setScaleX(1.3f); image.setScaleY(1.3f);
                image.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(d).withEndAction(done).start();
                outgoing.animate().scaleX(0.85f).scaleY(0.85f).setDuration(d).start();
                break;
            case 5: // fly through
                image.setAlpha(0f); image.setScaleX(0.6f); image.setScaleY(0.6f);
                image.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(d).withEndAction(done).start();
                outgoing.animate().scaleX(1.7f).scaleY(1.7f).alpha(0f).setDuration(d).start();
                break;
            case 6: { // 3-D flip
                float camera = 12000 * getResources().getDisplayMetrics().density;
                image.setCameraDistance(camera); outgoing.setCameraDistance(camera);
                image.setAlpha(0f); image.setRotationY(-90f);
                outgoing.animate().rotationY(90f).setDuration(d / 2).setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(() -> {
                            outgoing.setVisibility(View.INVISIBLE);
                            image.setAlpha(1f);
                            image.animate().rotationY(0f).setDuration(d / 2).withEndAction(done).start();
                        }).start();
                break;
            }
            case 7: { // cube
                float camera = 12000 * getResources().getDisplayMetrics().density;
                image.setCameraDistance(camera); outgoing.setCameraDistance(camera);
                outgoing.setPivotX(w); outgoing.setPivotY(h / 2);
                image.setPivotX(0); image.setPivotY(h / 2);
                image.setTranslationX(w); image.setRotationY(90f);
                image.animate().translationX(0).rotationY(0f).setDuration(d).withEndAction(done).start();
                outgoing.animate().translationX(-w).rotationY(-90f).setDuration(d).start();
                break;
            }
            case 8: // swirl
                image.setAlpha(0f); image.setRotation(-30f); image.setScaleX(0.4f); image.setScaleY(0.4f);
                image.animate().alpha(1f).rotation(0f).scaleX(1f).scaleY(1f).setDuration(d).withEndAction(done).start();
                outgoing.animate().rotation(25f).scaleX(1.3f).scaleY(1.3f).alpha(0f).setDuration(d).start();
                break;
            case 9: { // circular reveal
                android.animation.Animator reveal = android.view.ViewAnimationUtils.createCircularReveal(image,
                        (int) (w / 2), (int) (h / 2), 0f, (float) Math.hypot(w, h) / 2f);
                reveal.setDuration(d);
                reveal.setInterpolator(ease);
                reveal.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) { done.run(); }
                });
                reveal.start();
                break;
            }
            case 10: // blur dissolve (blur on Android 12+, a soft fade elsewhere)
                image.setAlpha(0f);
                android.animation.ValueAnimator blur = android.animation.ValueAnimator.ofFloat(0f, 1f);
                blur.setDuration(d + 200);
                blur.addUpdateListener(v -> {
                    float t = (float) v.getAnimatedValue();
                    if (Build.VERSION.SDK_INT >= 31) {
                        float radius = 1f + t * 40f;
                        outgoing.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP));
                        float r2 = 1f + (1f - t) * 30f;
                        image.setRenderEffect(t >= 0.99f ? null : android.graphics.RenderEffect.createBlurEffect(r2, r2, android.graphics.Shader.TileMode.CLAMP));
                    }
                    image.setAlpha(t);
                    outgoing.setAlpha(1f - t);
                });
                blur.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) {
                        if (Build.VERSION.SDK_INT >= 31) { image.setRenderEffect(null); outgoing.setRenderEffect(null); }
                        done.run();
                    }
                });
                blur.start();
                break;
            case 11: { // neon flash
                int neon = ThemePrefs.getStyle(this).primary;
                flash.setBackgroundColor(neon);
                flash.setVisibility(View.VISIBLE);
                image.setAlpha(0f);
                flash.animate().alpha(0.85f).setDuration(220).withEndAction(() -> {
                    outgoing.setVisibility(View.INVISIBLE);
                    image.setAlpha(1f);
                    flash.animate().alpha(0f).setDuration(520).withEndAction(() -> { flash.setVisibility(View.GONE); done.run(); }).start();
                }).start();
                break;
            }
            case 12: { // curtain opening from the centre
                android.animation.ValueAnimator curtain = android.animation.ValueAnimator.ofFloat(0f, 1f);
                curtain.setDuration(d);
                curtain.setInterpolator(ease);
                android.graphics.Rect clip = new android.graphics.Rect();
                curtain.addUpdateListener(v -> {
                    float t = (float) v.getAnimatedValue();
                    int half = (int) (w / 2 * t);
                    clip.set((int) (w / 2) - half, 0, (int) (w / 2) + half, (int) h);
                    image.setClipBounds(clip);
                });
                curtain.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) { image.setClipBounds(null); done.run(); }
                });
                curtain.start();
                break;
            }
            default: // 1: crossfade
                image.setAlpha(0f);
                image.animate().alpha(1f).setDuration(d).withEndAction(done).start();
                break;
        }
    }

    /** Puts both layers back to normal (after a transition, or when the user takes over). */
    private void resetTransition() {
        if (outgoing == null) return;
        for (View v : new View[]{image, outgoing}) {
            v.animate().cancel();
            v.setTranslationX(0); v.setTranslationY(0);
            v.setScaleX(1f); v.setScaleY(1f);
            v.setRotation(0f); v.setRotationY(0f);
            v.setAlpha(1f);
            v.setPivotX(v.getWidth() / 2f); v.setPivotY(v.getHeight() / 2f);
            v.setClipBounds(null);
            if (Build.VERSION.SDK_INT >= 31) v.setRenderEffect(null);
        }
        outgoing.setVisibility(View.GONE);
        outgoing.setImageDrawable(null);
        flash.animate().cancel();
        flash.setAlpha(0f);
        flash.setVisibility(View.GONE);
    }

    // ================================================================== photo info

    private void showInfo() {
        MediaItem item = current;
        if (item == null) return;
        executor.execute(() -> {
            String text = PhotoInfo.describe(this, item);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                new AlertDialog.Builder(this).setTitle("ⓘ معلومات الصورة").setMessage(text)
                        .setPositiveButton("حسناً", null).show();
            });
        });
    }

    private void confirmTrash() {
        boolean vaultImage = VaultStore.isVaultUri(current.uri);
        new AlertDialog.Builder(this)
                .setTitle(vaultImage ? "حذف نهائي من الخزنة" : "حذف الصورة")
                .setMessage(vaultImage ? "سيتم حذف هذه الصورة نهائياً من الخزنة الخاصة." : getString(R.string.confirm_trash))
                .setNegativeButton("إلغاء", null)
                .setPositiveButton(vaultImage ? "حذف نهائي" : getString(R.string.trash), (dialog, which) -> moveToTrash())
                .show();
    }

    private void moveToTrash() {
        if (VaultStore.isVaultUri(current.uri)) {
            if (VaultStore.delete(this, current.uri)) afterTrash();
            else Toast.makeText(this, "تعذر حذف الصورة من الخزنة", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                PendingIntent request = MediaStore.createTrashRequest(getContentResolver(),
                        Collections.singletonList(current.uri), true);
                startIntentSenderForResult(request.getIntentSender(), REQUEST_TRASH, null, 0, 0, 0);
            } else {
                int removed = getContentResolver().delete(current.uri, null, null);
                if (removed > 0) afterTrash(); else Toast.makeText(this, "تعذر حذف الصورة", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception error) {
            Toast.makeText(this, "تعذر نقل الصورة إلى السلة", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TRASH && resultCode == RESULT_OK) afterTrash();
        if (requestCode == REQUEST_EDITOR && resultCode == RESULT_OK) setResult(RESULT_OK);
    }

    private void afterTrash() {
        setResult(RESULT_OK);
        FavoritesStore.toggleIfPresent(this, current.uri.toString());
        ViewerSession.removeCurrent();
        current = ViewerSession.current();
        if (current == null) finish(); else showCurrent(0);
    }

    @Override protected void onResume() {
        super.onResume();
        // The vault locked itself while the app was in the background: never keep showing its files.
        if (current != null && VaultStore.isVaultUri(current.uri) && !VaultSession.isUnlocked()) finish();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopSlideshow();
        executor.shutdownNow();
    }
}
