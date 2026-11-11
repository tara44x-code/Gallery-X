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
    private final Runnable slideshowTick = new Runnable() {
        @Override public void run() {
            if (!slideshowRunning || isFinishing()) return;
            MediaItem item = ViewerSession.next();
            if (item == null) {
                stopSlideshow();
                return;
            }
            current = item;
            showCurrent(-1);
            mainHandler.postDelayed(this, 2800);
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
        root.addView(image, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this);
        root.addView(progress, new FrameLayout.LayoutParams(Ui.dp(this, 54), Ui.dp(this, 54), Gravity.CENTER));

        LinearLayout top = new LinearLayout(this);
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
        root.addView(top, new FrameLayout.LayoutParams(-1, Ui.dp(this, 64), Gravity.TOP));

        LinearLayout actions = new LinearLayout(this);
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
            Drawable result = drawable;
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

    private void toggleSlideshow() {
        if (slideshowRunning) stopSlideshow();
        else {
            slideshowRunning = true;
            slideshowButton.setText("Ⅱ\nإيقاف");
            slideshowButton.setTextColor(0xFFFF7AD9);
            mainHandler.postDelayed(slideshowTick, 1200);
        }
    }

    private void stopSlideshow() {
        slideshowRunning = false;
        mainHandler.removeCallbacks(slideshowTick);
        if (slideshowButton != null) {
            slideshowButton.setText("▶\nعرض");
            slideshowButton.setTextColor(Color.WHITE);
        }
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
