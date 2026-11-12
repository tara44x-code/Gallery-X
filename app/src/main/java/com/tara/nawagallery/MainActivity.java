package com.tara.nawagallery;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 42;
    private static final int VIEWER_REQUEST = 43;
    private static final int BATCH_TRASH_REQUEST = 44;
    private static final int VAULT_TRASH_REQUEST = 45;
    private static final int TRASH_RESTORE_REQUEST = 46;
    private static final int TRASH_DELETE_REQUEST = 47;
    private static final int BACKUP_EXPORT_REQUEST = 48;
    private static final int BACKUP_IMPORT_REQUEST = 49;
    private static final int VAULT_ADD_FILES_REQUEST = 50;
    private enum Mode { PHOTOS, VIDEOS, ALBUMS, FAVORITES, SETTINGS, ALBUM, VAULT, DUPLICATES, TRASH }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Album> albums = new ArrayList<>();
    private final List<MediaItem> visiblePhotos = new ArrayList<>();
    private final Set<String> selectedUris = new HashSet<>();
    private final ArrayList<String> pendingVaultFiles = new ArrayList<>();
    private final Set<String> pendingVaultSources = new HashSet<>();
    private final Set<String> pendingLegacyDeletes = new HashSet<>();
    private FrameLayout contentHost;
    private TextView title;
    private EditText search;
    private LinearLayout searchBox;
    private LinearLayout bottomBar;
    private LinearLayout selectionBar;
    private TextView favoriteAction;
    private TextView shareAction;
    private TextView privateAction;
    private TextView deleteAction;
    private PhotoAdapter photoAdapter;
    private TextView photosTab;
    private TextView videosTab;
    private TextView favoritesTab;
    private TextView settingsTab;
    private int accent;
    private ThemePrefs.ThemeStyle theme;
    private Mode mode = Mode.PHOTOS;
    private Album openedAlbum;
    private boolean vaultOperationRunning;
    private boolean reopenVaultAfterMove;
    private boolean legacyMigrationPromptShown;
    private long vaultBackgroundAt;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!AppIntegrity.verifyOrClose(this)) return;
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(true);
        buildUi();
        if (hasImageAccess()) {
            if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_VIDEO}, PERMISSION_REQUEST);
            } else loadGallery();
        } else showPermissionScreen();
    }

    private void buildUi() {
        theme = ThemePrefs.getStyle(this);
        accent = theme.primary;
        getWindow().setStatusBarColor(theme.surface);
        getWindow().setNavigationBarColor(theme.background);

        FrameLayout scene = new FrameLayout(this);
        scene.addView(new NeonBackgroundView(this, theme), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        toolbar.setBackgroundColor(Ui.withAlpha(theme.surface, 238));
        title = new TextView(this);
        title.setText(R.string.photos);
        title.setTextColor(Color.WHITE);
        title.setTextSize(Ui.textSize(this, 25));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 62), 1));

        ImageView animatedLogo = new ImageView(this);
        animatedLogo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        animatedLogo.setContentDescription(getString(R.string.choose_color));
        animatedLogo.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        animatedLogo.setOnClickListener(v -> showColorPicker());
        loadAnimatedHeaderLogo(animatedLogo);
        toolbar.addView(animatedLogo, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 58)));
        root.addView(toolbar);

        searchBox = new LinearLayout(this);
        searchBox.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        searchBox.setBackgroundColor(Ui.withAlpha(theme.surface, 238));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xFFE6DDEF);
        search.setHint(R.string.search_hint);
        search.setTextSize(Ui.textSize(this, 17));
        search.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        search.setBackground(Ui.rounded(Ui.withAlpha(theme.surfaceAlt, 242), 22, this));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applySearch(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
        searchBox.addView(search, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        root.addView(searchBox);

        contentHost = new FrameLayout(this);
        root.addView(contentHost, new LinearLayout.LayoutParams(-1, 0, 1));

        selectionBar = new LinearLayout(this);
        selectionBar.setGravity(Gravity.CENTER);
        selectionBar.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 8));
        selectionBar.setBackgroundColor(Ui.withAlpha(theme.surface, 250));
        addSelectionAction(selectionBar, "إلغاء", this::leaveSelectionMode);
        favoriteAction = addSelectionAction(selectionBar, "♡ مفضلة", this::favoriteSelected);
        shareAction = addSelectionAction(selectionBar, "↗ مشاركة", this::shareSelected);
        privateAction = addSelectionAction(selectionBar, "🔒 خاص", this::privateSelected);
        deleteAction = addSelectionAction(selectionBar, "⌫ حذف", this::trashSelected);
        selectionBar.setVisibility(View.GONE);
        root.addView(selectionBar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 70)));

        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 8));
        bottomBar.setBackgroundColor(Ui.withAlpha(theme.surface, 244));
        photosTab = addTab(bottomBar, "▧\n" + getString(R.string.photos), this::showAllPhotos);
        videosTab = addTab(bottomBar, "▶\n" + getString(R.string.videos), this::showAllVideos);
        addTab(bottomBar, "♫\nموسيقى", () -> startActivity(new Intent(this, MusicLibraryActivity.class)));
        favoritesTab = addTab(bottomBar, "♡\n" + getString(R.string.favorites), this::showFavorites);
        settingsTab = addTab(bottomBar, "⚙\n" + getString(R.string.settings), this::showSettings);
        root.addView(bottomBar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 78)));
        scene.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(scene);
    }

    private void loadAnimatedHeaderLogo(ImageView target) {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                Drawable drawable = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(getResources(), R.drawable.nawa_neon_logo));
                target.setImageDrawable(drawable);
                if (drawable instanceof Animatable) ((Animatable) drawable).start();
                return;
            } catch (Exception ignored) { }
        }
        target.setImageResource(R.drawable.nawa_launcher);
    }

    private TextView addSelectionAction(LinearLayout bar, String label, Runnable action) {
        TextView item = new TextView(this);
        item.setText(label);
        item.setTextColor(Color.WHITE);
        item.setTextSize(Ui.textSize(this, 14.5f));
        item.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        item.setGravity(Gravity.CENTER);
        item.setOnClickListener(v -> action.run());
        bar.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        return item;
    }

    private TextView addTab(LinearLayout bar, String label, Runnable action) {
        TextView tab = new TextView(this);
        tab.setText(label);
        tab.setTextColor(0xFFF2ECF7);
        tab.setTextSize(Ui.textSize(this, 16));
        tab.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tab.setShadowLayer(2.5f, 0f, 1f, 0xCC000000);
        tab.setGravity(Gravity.CENTER);
        tab.setLines(2);
        tab.setOnClickListener(v -> action.run());
        bar.addView(tab, new LinearLayout.LayoutParams(0, -1, 1));
        return tab;
    }

    private void selectTab(TextView selected) {
        TextView[] tabs = {photosTab, videosTab, favoritesTab, settingsTab};
        for (TextView tab : tabs) {
            boolean active = tab == selected;
            tab.setTextColor(active ? Color.WHITE : 0xFFF2ECF7);
            tab.setBackground(active ? Ui.rounded(Ui.withAlpha(accent, 125), 18, this) : null);
        }
    }

    private void replaceContent(View view) {
        contentHost.removeAllViews();
        contentHost.addView(view, new FrameLayout.LayoutParams(-1, -1));
    }

    private boolean hasImageAccess() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) return true;
            return Build.VERSION.SDK_INT >= 34 && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestImageAccess() {
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, PERMISSION_REQUEST);
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO}, PERMISSION_REQUEST);
        } else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
    }

    private void showPermissionScreen() {
        searchBox.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(Ui.dp(this, 36), Ui.dp(this, 36), Ui.dp(this, 36), Ui.dp(this, 36));
        TextView icon = message("▧", 72);
        icon.setTextColor(theme.secondary);
        panel.addView(icon);
        panel.addView(message(getString(R.string.permission_explanation), 18));
        Button grant = new Button(this);
        grant.setText(R.string.grant_access);
        grant.setTextColor(Color.WHITE);
        grant.setTextSize(Ui.textSize(this, 17));
        grant.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        grant.setAllCaps(false);
        grant.setBackground(Ui.rounded(accent, 18, this));
        grant.setOnClickListener(v -> requestImageAccess());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54));
        lp.setMargins(0, Ui.dp(this, 28), 0, 0);
        panel.addView(grant, lp);
        replaceContent(panel);
    }

    private TextView message(String value, float size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(0xFFEAE2F4);
        text.setTextSize(Ui.textSize(this, size));
        text.setGravity(Gravity.CENTER);
        text.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        return text;
    }

    private void loadGallery() { loadGallery(false); }

    private void loadGallery(boolean reopenVault) {
        searchBox.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.VISIBLE);
        ProgressBar progress = new ProgressBar(this);
        FrameLayout loading = new FrameLayout(this);
        loading.addView(progress, new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.CENTER));
        replaceContent(loading);
        executor.execute(() -> {
            List<Album> loaded = GalleryRepository.loadAlbums(this);
            runOnUiThread(() -> {
                albums.clear();
                albums.addAll(loaded);
                if (reopenVault) showVaultUnlocked(); else showAllPhotos();
            });
        });
    }

    private List<MediaItem> allMedia() {
        if (albums.isEmpty()) return new ArrayList<>();
        Set<String> privateUris = PrivateStore.getUris(this);
        Set<String> hiddenBuckets = HiddenFoldersStore.getAll(this);
        List<MediaItem> result = new ArrayList<>();
        for (MediaItem item : albums.get(0).items) {
            if (!privateUris.contains(item.uri.toString()) && !hiddenBuckets.contains(item.bucketId)) result.add(item);
        }
        return result;
    }

    private List<MediaItem> allImages() {
        List<MediaItem> result = new ArrayList<>();
        for (MediaItem item : allMedia()) if (!item.isVideo()) result.add(item);
        return result;
    }

    private List<MediaItem> allVideos() {
        List<MediaItem> result = new ArrayList<>();
        for (MediaItem item : allMedia()) if (item.isVideo()) result.add(item);
        return result;
    }

    private void showAllPhotos() {
        clearVaultProtection();
        mode = Mode.PHOTOS;
        openedAlbum = null;
        title.setText(R.string.photos);
        searchBox.setVisibility(View.VISIBLE);
        selectTab(photosTab);
        renderPhotos(filterPhotos(allImages(), search.getText().toString()), getString(R.string.no_photos));
    }

    private void showAllVideos() {
        clearVaultProtection();
        mode = Mode.VIDEOS;
        openedAlbum = null;
        title.setText(R.string.videos);
        searchBox.setVisibility(View.VISIBLE);
        selectTab(videosTab);
        renderPhotos(filterPhotos(allVideos(), search.getText().toString()), getString(R.string.no_videos));
    }

    private void showAlbums() {
        clearVaultProtection();
        mode = Mode.ALBUMS;
        openedAlbum = null;
        title.setText(R.string.albums);
        searchBox.setVisibility(View.VISIBLE);
        selectTab(photosTab);
        renderAlbums(filterAlbums(search.getText().toString()));
    }

    private void renderAlbums(List<Album> items) {
        if (items.isEmpty()) {
            replaceContent(message(getString(R.string.empty_gallery), 19));
            return;
        }
        GridView grid = new GridView(this);
        grid.setNumColumns(2);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 20));
        grid.setClipToPadding(false);
        grid.setAdapter(new AlbumAdapter(this, items));
        grid.setOnItemClickListener((parent, view, position, id) -> showAlbum(items.get(position)));
        replaceContent(grid);
    }

    private List<Album> filterAlbums(String query) {
        String needle = query == null ? "" : query.toLowerCase(Locale.getDefault()).trim();
        Set<String> privateUris = PrivateStore.getUris(this);
        Set<String> hidden = HiddenFoldersStore.getAll(this);
        List<Album> result = new ArrayList<>();
        for (Album album : albums) {
            if (!"__all__".equals(album.id) && hidden.contains(album.id)) continue;
            if (!needle.isEmpty() && !album.name.toLowerCase(Locale.getDefault()).contains(needle)) continue;
            Album visible = new Album(album.id, album.name);
            for (MediaItem item : album.items) {
                if (!privateUris.contains(item.uri.toString()) && !hidden.contains(item.bucketId)) visible.items.add(item);
            }
            if (!visible.items.isEmpty()) result.add(visible);
        }
        return result;
    }

    private void showAlbum(Album album) {
        clearVaultProtection();
        mode = Mode.ALBUM;
        openedAlbum = album;
        title.setText(album.name);
        selectTab(photosTab);
        renderPhotos(filterPhotos(publicItems(album.items), search.getText().toString()), getString(R.string.empty_gallery));
    }

    private void showFavorites() {
        clearVaultProtection();
        mode = Mode.FAVORITES;
        openedAlbum = null;
        title.setText(R.string.favorites);
        searchBox.setVisibility(View.VISIBLE);
        selectTab(favoritesTab);
        Set<String> favorites = FavoritesStore.getAll(this);
        List<MediaItem> result = new ArrayList<>();
        for (MediaItem item : allMedia()) if (favorites.contains(item.uri.toString())) result.add(item);
        renderPhotos(filterPhotos(result, search.getText().toString()), getString(R.string.no_favorites));
    }

    private List<MediaItem> publicItems(List<MediaItem> source) {
        Set<String> privateUris = PrivateStore.getUris(this);
        Set<String> hidden = HiddenFoldersStore.getAll(this);
        List<MediaItem> result = new ArrayList<>();
        for (MediaItem item : source) {
            if (!privateUris.contains(item.uri.toString()) && !hidden.contains(item.bucketId)) result.add(item);
        }
        return result;
    }

    private List<MediaItem> filterPhotos(List<MediaItem> source, String query) {
        if (query == null || query.trim().isEmpty()) return new ArrayList<>(source);
        String needle = query.toLowerCase(Locale.getDefault()).trim();
        List<MediaItem> result = new ArrayList<>();
        for (MediaItem item : source) {
            String name = item.name == null ? "" : item.name.toLowerCase(Locale.getDefault());
            String folder = item.bucketName == null ? "" : item.bucketName.toLowerCase(Locale.getDefault());
            if (name.contains(needle) || folder.contains(needle)) result.add(item);
        }
        return result;
    }

    private void renderPhotos(List<MediaItem> photos, String emptyText) {
        visiblePhotos.clear();
        visiblePhotos.addAll(photos);
        if (visiblePhotos.isEmpty()) {
            replaceContent(message(emptyText, 19));
            return;
        }
        GridView grid = new GridView(this);
        int columns = GalleryPrefs.getColumns(this);
        grid.setNumColumns(columns);
        grid.setVerticalSpacing(Ui.dp(this, 3));
        grid.setHorizontalSpacing(Ui.dp(this, 3));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 20));
        grid.setClipToPadding(false);
        photoAdapter = new PhotoAdapter(this, visiblePhotos, selectedUris, columns);
        grid.setAdapter(photoAdapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (selectionBar.getVisibility() == View.VISIBLE || mode == Mode.TRASH) toggleSelection(position);
            else openViewer(position);
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            toggleSelection(position);
            return true;
        });
        replaceContent(grid);
    }

    private void toggleSelection(int position) {
        String uri = visiblePhotos.get(position).uri.toString();
        if (!selectedUris.add(uri)) selectedUris.remove(uri);
        if (selectedUris.isEmpty()) {
            leaveSelectionMode();
            return;
        }
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        selectionBar.setVisibility(View.VISIBLE);
        bottomBar.setVisibility(View.GONE);
        boolean trashMode = mode == Mode.TRASH;
        favoriteAction.setVisibility(trashMode ? View.GONE : View.VISIBLE);
        shareAction.setVisibility(trashMode ? View.GONE : View.VISIBLE);
        privateAction.setText(trashMode ? "↶ استرجاع" : mode == Mode.VAULT ? "🔓 إخراج" : "🔒 خاص");
        deleteAction.setText(trashMode ? "⌫ حذف نهائي" : "⌫ حذف");
        title.setText(selectedUris.size() + " محدد");
        if (photoAdapter != null) photoAdapter.notifyDataSetChanged();
    }

    private void leaveSelectionMode() {
        selectedUris.clear();
        selectionBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.VISIBLE);
        restoreTitle();
        if (photoAdapter != null) photoAdapter.notifyDataSetChanged();
    }

    private void restoreTitle() {
        if (mode == Mode.PHOTOS) title.setText(R.string.photos);
        else if (mode == Mode.VIDEOS) title.setText(R.string.videos);
        else if (mode == Mode.FAVORITES) title.setText(R.string.favorites);
        else if (mode == Mode.ALBUM && openedAlbum != null) title.setText(openedAlbum.name);
        else if (mode == Mode.SETTINGS) title.setText(R.string.settings);
        else if (mode == Mode.VAULT) title.setText("الخزنة الخاصة");
        else if (mode == Mode.DUPLICATES) title.setText("الصور المكررة");
        else if (mode == Mode.TRASH) title.setText("سلة المحذوفات");
        else title.setText(R.string.photos);
    }

    private ArrayList<Uri> selectedUriList() {
        ArrayList<Uri> result = new ArrayList<>();
        for (String value : selectedUris) result.add(Uri.parse(value));
        return result;
    }

    private List<MediaItem> selectedMediaItems() {
        List<MediaItem> result = new ArrayList<>();
        for (MediaItem item : visiblePhotos) {
            if (selectedUris.contains(item.uri.toString())) result.add(item);
        }
        return result;
    }

    private void favoriteSelected() {
        FavoritesStore.addAll(this, new HashSet<>(selectedUris));
        Toast.makeText(this, "تمت إضافة الصور إلى المفضلة", Toast.LENGTH_SHORT).show();
        leaveSelectionMode();
    }

    private void privateSelected() {
        if (selectedUris.isEmpty()) return;
        if (mode == Mode.TRASH) {
            requestTrashRestore();
            return;
        }
        if (mode == Mode.VAULT) {
            confirmRestoreSelected();
            return;
        }
        List<MediaItem> items = selectedMediaItems();
        if (items.isEmpty() || vaultOperationRunning) return;
        new AlertDialog.Builder(this)
                .setTitle("نقل إلى الخزنة الخاصة")
                .setMessage("سيتم حفظ نسخة داخل الخزنة أولاً، ثم يطلب أندرويد نقل الأصل إلى سلة المحذوفات حتى يختفي من استوديو النظام.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("متابعة", (dialog, which) -> requireVault(() -> moveItemsToVault(items)))
                .show();
    }

    private void moveItemsToVault(List<MediaItem> source) {
        if (source == null || source.isEmpty() || vaultOperationRunning) return;
        List<MediaItem> items = new ArrayList<>();
        for (MediaItem item : source) if (!VaultStore.isVaultUri(item.uri)) items.add(item);
        if (items.isEmpty()) return;
        vaultOperationRunning = true;
        reopenVaultAfterMove = mode == Mode.VAULT;
        pendingVaultFiles.clear();
        pendingVaultSources.clear();
        Toast.makeText(this, "جاري حفظ الصور بأمان داخل الخزنة…", Toast.LENGTH_LONG).show();
        executor.execute(() -> {
            ArrayList<String> copied = new ArrayList<>();
            HashSet<String> sources = new HashSet<>();
            boolean copiedAll = true;
            for (MediaItem item : items) {
                String fileName = VaultStore.copyToVault(this, item);
                if (fileName == null) {
                    copiedAll = false;
                    break;
                }
                copied.add(fileName);
                sources.add(item.uri.toString());
            }
            if (!copiedAll || copied.size() != items.size()) {
                for (String fileName : copied) VaultStore.deleteFileName(this, fileName);
            }
            boolean success = copiedAll && copied.size() == items.size();
            runOnUiThread(() -> {
                if (!success) {
                    clearPendingVaultMove();
                    Toast.makeText(this, "تعذر نسخ إحدى الصور؛ لم يُحذف أي أصل", Toast.LENGTH_LONG).show();
                    return;
                }
                pendingVaultFiles.addAll(copied);
                pendingVaultSources.addAll(sources);
                requestVaultOriginalsTrash();
            });
        });
    }

    private void requestVaultOriginalsTrash() {
        ArrayList<Uri> uris = new ArrayList<>();
        for (String value : pendingVaultSources) uris.add(Uri.parse(value));
        if (uris.isEmpty()) {
            rollbackPendingVaultMove("تعذر تحديد الصور الأصلية");
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                PendingIntent request = MediaStore.createTrashRequest(getContentResolver(), uris, true);
                startIntentSenderForResult(request.getIntentSender(), VAULT_TRASH_REQUEST,
                        null, 0, 0, 0);
            } catch (Exception error) {
                rollbackPendingVaultMove("تعذر فتح طلب أندرويد؛ لم يُحذف أي أصل");
            }
            return;
        }
        executor.execute(() -> {
            int removed = 0;
            for (Uri uri : uris) {
                try { if (getContentResolver().delete(uri, null, null) > 0) removed++; }
                catch (Exception ignored) { }
            }
            int deletedCount = removed;
            runOnUiThread(() -> {
                if (deletedCount == uris.size()) finishVaultMove();
                else finishLegacyVaultMoveWithWarning(deletedCount, uris.size());
            });
        });
    }

    private void finishVaultMove() {
        int count = pendingVaultFiles.size();
        boolean reopen = reopenVaultAfterMove;
        PrivateStore.removeAll(this, new HashSet<>(pendingVaultSources));
        clearPendingVaultMove();
        leaveSelectionMode();
        Toast.makeText(this, "تم نقل " + count + " صورة إلى الخزنة واختفت من استوديو النظام", Toast.LENGTH_LONG).show();
        loadGallery(reopen);
    }

    private void finishLegacyVaultMoveWithWarning(int deleted, int total) {
        boolean reopen = reopenVaultAfterMove;
        PrivateStore.removeAll(this, new HashSet<>(pendingVaultSources));
        clearPendingVaultMove();
        leaveSelectionMode();
        Toast.makeText(this, "حُفظت الصور في الخزنة، لكن أندرويد حذف " + deleted + " من " + total + " أصلاً فقط", Toast.LENGTH_LONG).show();
        loadGallery(reopen);
    }

    private void rollbackPendingVaultMove(String message) {
        ArrayList<String> files = new ArrayList<>(pendingVaultFiles);
        executor.execute(() -> {
            for (String fileName : files) VaultStore.deleteFileName(this, fileName);
            runOnUiThread(() -> {
                clearPendingVaultMove();
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void clearPendingVaultMove() {
        pendingVaultFiles.clear();
        pendingVaultSources.clear();
        reopenVaultAfterMove = false;
        vaultOperationRunning = false;
        if (mode != Mode.VAULT) VaultSession.lock();
    }

    private void confirmRestoreSelected() {
        List<MediaItem> items = selectedMediaItems();
        if (items.isEmpty() || vaultOperationRunning) return;
        new AlertDialog.Builder(this)
                .setTitle("إخراج من الخزنة")
                .setMessage("ستظهر الصور والفيديوهات مجدداً في استوديو النظام داخل مجلد " + VaultStore.RESTORE_FOLDER + ".")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("إخراج", (dialog, which) -> restoreSelectedFromVault(items))
                .show();
    }

    private void restoreSelectedFromVault(List<MediaItem> items) {
        vaultOperationRunning = true;
        executor.execute(() -> {
            int restored = 0;
            HashSet<String> legacy = new HashSet<>();
            for (MediaItem item : items) {
                if (VaultStore.isVaultUri(item.uri)) {
                    if (VaultStore.restoreToGallery(this, item)) restored++;
                } else {
                    legacy.add(item.uri.toString());
                    restored++;
                }
            }
            int restoredCount = restored;
            runOnUiThread(() -> {
                PrivateStore.removeAll(this, legacy);
                vaultOperationRunning = false;
                leaveSelectionMode();
                Toast.makeText(this, "أُعيدت " + restoredCount + " من " + items.size() + " صورة إلى المعرض", Toast.LENGTH_LONG).show();
                loadGallery(true);
            });
        });
    }

    private void shareSelected() {
        if (selectedUris.isEmpty()) return;
        ShareHelper.share(this, selectedMediaItems(), getString(R.string.share));
    }

    private void trashSelected() {
        if (selectedUris.isEmpty()) return;
        if (mode == Mode.TRASH) {
            new AlertDialog.Builder(this)
                    .setTitle("حذف نهائي")
                    .setMessage("سيتم حذف " + selectedUris.size() + " صورة نهائياً ولا يمكن استرجاعها.")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("حذف نهائي", (dialog, which) -> requestTrashPermanentDelete())
                    .show();
            return;
        }
        if (mode == Mode.VAULT) {
            List<MediaItem> items = selectedMediaItems();
            new AlertDialog.Builder(this)
                    .setTitle("حذف نهائي من الخزنة")
                    .setMessage("سيتم حذف " + items.size() + " صورة نهائياً من الخزنة الخاصة.")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("حذف نهائي", (dialog, which) -> deleteSelectedFromVault(items))
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("حذف الصور المحددة")
                .setMessage("نقل " + selectedUris.size() + " صورة إلى سلة المحذوفات؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف", (dialog, which) -> requestBatchTrash())
                .show();
    }

    private void requestTrashRestore() {
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, "الاسترجاع من السلة يحتاج Android 11 أو أحدث", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            PendingIntent request = MediaStore.createTrashRequest(
                    getContentResolver(), selectedUriList(), false);
            startIntentSenderForResult(request.getIntentSender(), TRASH_RESTORE_REQUEST,
                    null, 0, 0, 0);
        } catch (Exception error) {
            Toast.makeText(this, "تعذر فتح طلب استرجاع الصور", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestTrashPermanentDelete() {
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, "الحذف النهائي من السلة يحتاج Android 11 أو أحدث", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            PendingIntent request = MediaStore.createDeleteRequest(
                    getContentResolver(), selectedUriList());
            startIntentSenderForResult(request.getIntentSender(), TRASH_DELETE_REQUEST,
                    null, 0, 0, 0);
        } catch (Exception error) {
            Toast.makeText(this, "تعذر فتح طلب الحذف النهائي", Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteSelectedFromVault(List<MediaItem> items) {
        if (items.isEmpty() || vaultOperationRunning) return;
        vaultOperationRunning = true;
        executor.execute(() -> {
            int deleted = 0;
            HashSet<String> legacy = new HashSet<>();
            for (MediaItem item : items) {
                if (VaultStore.isVaultUri(item.uri)) {
                    if (VaultStore.delete(this, item.uri)) deleted++;
                } else legacy.add(item.uri.toString());
            }
            int deletedPrivate = deleted;
            runOnUiThread(() -> {
                vaultOperationRunning = false;
                if (legacy.isEmpty()) {
                    leaveSelectionMode();
                    Toast.makeText(this, "تم حذف " + deletedPrivate + " صورة من الخزنة", Toast.LENGTH_SHORT).show();
                    showVaultUnlocked();
                    return;
                }
                pendingLegacyDeletes.clear();
                pendingLegacyDeletes.addAll(legacy);
                ArrayList<Uri> uris = new ArrayList<>();
                for (String value : legacy) uris.add(Uri.parse(value));
                requestLegacyVaultDelete(uris, deletedPrivate);
            });
        });
    }

    private void requestLegacyVaultDelete(ArrayList<Uri> uris, int deletedPrivate) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                PendingIntent request = MediaStore.createTrashRequest(getContentResolver(), uris, true);
                startIntentSenderForResult(request.getIntentSender(), BATCH_TRASH_REQUEST,
                        null, 0, 0, 0);
            } catch (Exception error) {
                pendingLegacyDeletes.clear();
                leaveSelectionMode();
                showVaultUnlocked();
                Toast.makeText(this, "حُذف " + deletedPrivate + " من الخزنة وتعذر فتح طلب حذف الصور القديمة", Toast.LENGTH_LONG).show();
            }
            return;
        }
        int removed = 0;
        for (Uri uri : uris) {
            try { if (getContentResolver().delete(uri, null, null) > 0) removed++; }
            catch (Exception ignored) { }
        }
        if (removed == uris.size()) PrivateStore.removeAll(this, new HashSet<>(pendingLegacyDeletes));
        pendingLegacyDeletes.clear();
        leaveSelectionMode();
        showVaultUnlocked();
    }

    private void requestBatchTrash() {
        ArrayList<Uri> uris = selectedUriList();
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                PendingIntent request = MediaStore.createTrashRequest(getContentResolver(), uris, true);
                startIntentSenderForResult(request.getIntentSender(), BATCH_TRASH_REQUEST,
                        null, 0, 0, 0);
            } catch (Exception error) {
                Toast.makeText(this, "تعذر فتح طلب الحذف", Toast.LENGTH_SHORT).show();
            }
        } else {
            for (Uri uri : uris) {
                try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
            }
            leaveSelectionMode();
            loadGallery();
        }
    }

    private void openViewer(int position) {
        MediaItem item = visiblePhotos.get(position);
        if (item.isVideo()) {
            List<MediaItem> videos = new ArrayList<>();
            int videoPosition = 0;
            for (MediaItem candidate : visiblePhotos) {
                if (candidate.isVideo()) {
                    if (candidate.uri.equals(item.uri)) videoPosition = videos.size();
                    videos.add(candidate);
                }
            }
            VideoSession.open(videos, videoPosition);
            Intent video = new Intent(this, VideoPlayerActivity.class);
            video.putExtra(VideoPlayerActivity.EXTRA_URI, item.uri.toString());
            video.putExtra(VideoPlayerActivity.EXTRA_NAME, item.name);
            video.putExtra(VideoPlayerActivity.EXTRA_MIME, item.mimeType);
            video.putExtra(VideoPlayerActivity.EXTRA_PATH, item.filePath);
            startActivityForResult(video, VIEWER_REQUEST);
            return;
        }
        List<MediaItem> images = new ArrayList<>();
        int imagePosition = 0;
        for (MediaItem candidate : visiblePhotos) {
            if (!candidate.isVideo()) {
                if (candidate.uri.equals(item.uri)) imagePosition = images.size();
                images.add(candidate);
            }
        }
        ViewerSession.open(images, imagePosition);
        Intent intent = new Intent(this, PhotoViewerActivity.class);
        intent.putExtra(PhotoViewerActivity.EXTRA_URI, item.uri.toString());
        intent.putExtra(PhotoViewerActivity.EXTRA_NAME, item.name);
        startActivityForResult(intent, VIEWER_REQUEST);
    }

    private void applySearch(String query) {
        if (albums.isEmpty()) return;
        switch (mode) {
            case ALBUMS: renderAlbums(filterAlbums(query)); break;
            case PHOTOS: renderPhotos(filterPhotos(allImages(), query), getString(R.string.no_photos)); break;
            case VIDEOS: renderPhotos(filterPhotos(allVideos(), query), getString(R.string.no_videos)); break;
            case FAVORITES: showFavorites(); break;
            case ALBUM:
                if (openedAlbum != null) renderPhotos(filterPhotos(openedAlbum.items, query), getString(R.string.empty_gallery));
                break;
            default: break;
        }
    }

    private void showSettings() {
        clearVaultProtection();
        mode = Mode.SETTINGS;
        openedAlbum = null;
        title.setText(R.string.settings);
        searchBox.setVisibility(View.GONE);
        selectTab(settingsTab);
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 32));
        panel.addView(settingCard(getString(R.string.theme_title), "عشرة ثيمات احترافية مع نيون متحرك حسب ألوان الثيم", v -> showColorPicker()));
        panel.addView(settingCard("حجم الكتابة", "الحجم الحالي: " + Math.round(GalleryPrefs.getFontScale(this) * 100) + "% — اضغط للتغيير", v -> showFontSizePicker()));
        panel.addView(settingCard("سلة المحذوفات", "تحديد الكل، استرجاع الكل، إفراغ السلة أو إدارة عناصر محددة", v -> showTrash()));
        panel.addView(settingCard("الخزنة الخاصة", "ملفات مشفّرة بالكامل تنفتح برقمك السري فقط، وتنقفل تلقائياً بعد مغادرة التطبيق", v -> openVault()));
        panel.addView(settingCard("أدوات الخزنة", "نسخة احتياطية مشفّرة، استعادتها على أي هاتف، وإضافة ملفات من مدير الملفات", v -> showVaultTools()));
        panel.addView(settingCard("الصور المكررة", "فحص آمن للنسخ المتطابقة بالحجم وبصمة SHA-256", v -> scanDuplicates()));
        panel.addView(settingCard("المجلدات المخفية", "اختر المجلدات التي لا تريد ظهورها في Gallery X", v -> showHiddenFoldersPicker()));
        panel.addView(settingCard("حجم شبكة الصور", "عدد الأعمدة الحالي: " + GalleryPrefs.getColumns(this) + " — اضغط للاختيار من 2 إلى 5", v -> showGridSizePicker()));
        panel.addView(settingCard("التطبيق الافتراضي للصور والفيديو", "افتح صورة أو فيديو من مدير الملفات أو تيليگرام، واختر Gallery X ثم «دائمًا». اضغط هنا لإدارة الاختيار.", v -> openDefaultSettings()));
        panel.addView(developerCard());
        scroll.addView(panel);
        replaceContent(scroll);
    }

    private View settingCard(String heading, String body, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16));
        card.setBackground(Ui.rounded(Ui.withAlpha(theme.surfaceAlt, 236), 20, this));
        TextView h = message(heading, 20);
        h.setGravity(Gravity.START);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        h.setPadding(0, 0, 0, Ui.dp(this, 6));
        TextView b = message(body, 16);
        b.setGravity(Gravity.START);
        b.setTextColor(0xFFF0E8F5);
        b.setPadding(0, 0, 0, 0);
        card.addView(h);
        card.addView(b);
        if (listener != null) card.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, Ui.dp(this, 12));
        card.setLayoutParams(lp);
        return card;
    }

    private View developerCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 14));
        card.setBackground(Ui.rounded(Ui.withAlpha(theme.surfaceAlt, 236), 20, this));
        TextView label = message("المطوّر", 15);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(0xFFD8CCE2);
        TextView signature = message("مهند سات لنك", 27);
        signature.setGravity(Gravity.CENTER);
        signature.setTextColor(theme.secondary);
        signature.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD_ITALIC));
        signature.setRotation(-1.2f);
        signature.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        TextView version = message("رقم الإصدار: 3.4.1", 15);
        version.setGravity(Gravity.CENTER);
        version.setTextColor(0xFFF0E8F5);
        card.addView(label);
        card.addView(signature);
        card.addView(version);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, Ui.dp(this, 12));
        card.setLayoutParams(params);
        return card;
    }

    private void showTrash() {
        clearVaultProtection();
        mode = Mode.TRASH;
        openedAlbum = null;
        title.setText("سلة المحذوفات");
        searchBox.setVisibility(View.GONE);
        selectTab(settingsTab);
        ProgressBar progress = new ProgressBar(this);
        FrameLayout loading = new FrameLayout(this);
        loading.addView(progress, new FrameLayout.LayoutParams(
                Ui.dp(this, 56), Ui.dp(this, 56), Gravity.CENTER));
        replaceContent(loading);
        executor.execute(() -> {
            List<MediaItem> trashed = GalleryRepository.loadTrashedImages(this);
            runOnUiThread(() -> {
                if (mode != Mode.TRASH) return;
                renderTrash(trashed, Build.VERSION.SDK_INT >= 30
                        ? "سلة المحذوفات فارغة"
                        : "سلة المحذوفات تحتاج Android 11 أو أحدث");
            });
        });
    }

    private void renderTrash(List<MediaItem> trashed, String emptyText) {
        renderPhotos(trashed, emptyText);
        View body = contentHost.getChildCount() == 0 ? message(emptyText, 19) : contentHost.getChildAt(0);
        if (body.getParent() == contentHost) contentHost.removeView(body);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        LinearLayout commands = new LinearLayout(this);
        commands.setGravity(Gravity.CENTER);
        commands.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        commands.setBackgroundColor(Ui.withAlpha(theme.surface, 242));
        commands.addView(trashCommand("تحديد الكل", this::selectAllTrash), new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        commands.addView(trashCommand("استرجاع الكل", this::restoreAllTrash), new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        commands.addView(trashCommand("إفراغ السلة", this::emptyTrash), new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        wrapper.addView(commands);
        wrapper.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        replaceContent(wrapper);
    }

    private Button trashCommand(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(Ui.textSize(this, 14));
        button.setAllCaps(false);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setBackground(Ui.rounded(Ui.withAlpha(accent, 145), 14, this));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void selectAllTrash() {
        if (visiblePhotos.isEmpty()) {
            Toast.makeText(this, "السلة فارغة", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedUris.clear();
        for (MediaItem item : visiblePhotos) selectedUris.add(item.uri.toString());
        updateSelectionUi();
    }

    private void restoreAllTrash() {
        if (visiblePhotos.isEmpty()) {
            Toast.makeText(this, "السلة فارغة", Toast.LENGTH_SHORT).show();
            return;
        }
        selectAllTrash();
        requestTrashRestore();
    }

    private void emptyTrash() {
        if (visiblePhotos.isEmpty()) {
            Toast.makeText(this, "السلة فارغة", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("إفراغ سلة المحذوفات")
                .setMessage("سيتم حذف جميع العناصر نهائياً ولا يمكن استرجاعها.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("إفراغ السلة", (dialog, which) -> {
                    selectAllTrash();
                    requestTrashPermanentDelete();
                }).show();
    }

    private void refreshAlbumsSilently() {
        executor.execute(() -> {
            List<Album> loaded = GalleryRepository.loadAlbums(this);
            runOnUiThread(() -> {
                albums.clear();
                albums.addAll(loaded);
            });
        });
    }

    private Runnable afterUnlockAction;

    /** Runs {@code action} once the vault key is available, asking for the PIN if needed. */
    private void requireVault(Runnable action) {
        if (VaultSession.isUnlocked()) {
            action.run();
            return;
        }
        afterUnlockAction = action;
        if (!VaultSession.isSetUp(this)) showCreatePinDialog();
        else showUnlockDialog();
    }

    private void openVault() {
        legacyMigrationPromptShown = false;
        afterUnlockAction = null;
        VaultSession.lock();
        if (!VaultSession.isSetUp(this)) showCreatePinDialog();
        else showUnlockDialog();
    }

    private EditText pinInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFFBDB4CE);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setBackground(Ui.rounded(Ui.withAlpha(theme.surfaceAlt, 245), 14, this));
        input.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        return input;
    }

    private void showCreatePinDialog() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), 0);
        TextView warning = new TextView(this);
        warning.setText("الخزنة تتشفّر بهذا الرقم السري. إذا نسيته ما يگدر أحد يفتحها، ولا حتى التطبيق نفسه.");
        warning.setTextColor(0xFFFFC2E8);
        warning.setTextSize(Ui.textSize(this, 14));
        warning.setPadding(0, 0, 0, Ui.dp(this, 10));
        fields.addView(warning);
        EditText first = pinInput("رقم سري — " + VaultSession.MIN_PIN + " أرقام أو أكثر");
        EditText second = pinInput("تأكيد الرقم السري");
        fields.addView(first, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
        secondParams.setMargins(0, Ui.dp(this, 10), 0, 0);
        fields.addView(second, secondParams);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("إنشاء قفل الخزنة")
                .setView(fields)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(v -> {
                String one = first.getText().toString();
                String two = second.getText().toString();
                if (one.length() < VaultSession.MIN_PIN) {
                    first.setError("اكتب " + VaultSession.MIN_PIN + " أرقام أو أكثر");
                    return;
                }
                if (isWeakPin(one)) {
                    first.setError("الرقم سهل التخمين، اختر رقماً غير متسلسل وغير مكرر");
                    return;
                }
                if (!one.equals(two)) {
                    second.setError("الرقمان غير متطابقين");
                    return;
                }
                save.setEnabled(false);
                save.setText("جارِ التشفير…");
                new Thread(() -> {
                    VaultSession.Status status = VaultSession.create(this, one);
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        if (status != VaultSession.Status.OK) {
                            save.setEnabled(true);
                            save.setText("حفظ");
                            Toast.makeText(this, "تعذر إنشاء قفل الخزنة", Toast.LENGTH_LONG).show();
                            return;
                        }
                        dialog.dismiss();
                        afterVaultUnlocked();
                    });
                }, "vault-create").start();
            });
        });
        dialog.show();
    }

    private static boolean isWeakPin(String pin) {
        boolean same = true, up = true, down = true;
        for (int i = 1; i < pin.length(); i++) {
            int a = pin.charAt(i - 1), b = pin.charAt(i);
            if (b != a) same = false;
            if (b != a + 1) up = false;
            if (b != a - 1) down = false;
        }
        return same || up || down;
    }

    private void showUnlockDialog() {
        EditText pin = pinInput("الرقم السري");
        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        holder.addView(pin, new FrameLayout.LayoutParams(-1, Ui.dp(this, 54)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("فتح الخزنة الخاصة")
                .setView(holder)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("فتح", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button open = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            long waiting = VaultSession.lockoutRemaining(this);
            if (waiting > 0) pin.setError(lockoutMessage(waiting));
            open.setOnClickListener(v -> {
                long wait = VaultSession.lockoutRemaining(this);
                if (wait > 0) {
                    pin.setError(lockoutMessage(wait));
                    return;
                }
                String value = pin.getText().toString();
                if (value.isEmpty()) return;
                open.setEnabled(false);
                open.setText("جارِ التحقق…");
                new Thread(() -> {
                    VaultSession.Status status = VaultSession.unlock(this, value);
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        open.setEnabled(true);
                        open.setText("فتح");
                        if (status == VaultSession.Status.OK) {
                            dialog.dismiss();
                            afterVaultUnlocked();
                        } else if (status == VaultSession.Status.WRONG) {
                            pin.setText("");
                            long next = VaultSession.lockoutRemaining(this);
                            pin.setError(next > 0 ? "الرقم غير صحيح. " + lockoutMessage(next) : "الرقم غير صحيح");
                        } else if (status == VaultSession.Status.LOCKED_OUT) {
                            pin.setError(lockoutMessage(VaultSession.lockoutRemaining(this)));
                        } else {
                            pin.setError("تعذر فتح الخزنة على هذا الجهاز");
                        }
                    });
                }, "vault-unlock").start();
            });
        });
        dialog.show();
    }

    private String lockoutMessage(long millis) {
        long seconds = Math.max(1, (millis + 999) / 1000);
        return seconds >= 60 ? "حاول بعد " + ((seconds + 59) / 60) + " دقيقة" : "حاول بعد " + seconds + " ثانية";
    }

    private void afterVaultUnlocked() {
        new Thread(() -> {
            int secured = VaultStore.migrateLegacyFiles(this);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (secured > 0) Toast.makeText(this, "تم تشفير " + secured + " ملف قديم داخل الخزنة", Toast.LENGTH_LONG).show();
                Runnable action = afterUnlockAction;
                afterUnlockAction = null;
                if (action != null) action.run();
                else showVaultUnlocked();
            });
        }, "vault-migrate").start();
    }

    // ================================================================== vault tools: backup, restore, add files

    private void showVaultTools() {
        String[] items = {"تصدير نسخة احتياطية مشفّرة", "استيراد نسخة احتياطية", "إضافة ملفات من مدير الملفات"};
        new AlertDialog.Builder(this)
                .setTitle("أدوات الخزنة")
                .setItems(items, (dialog, which) -> requireVault(() -> {
                    VaultSession.hold(true);
                    Intent intent;
                    if (which == 0) {
                        intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/octet-stream");
                        String day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
                        intent.putExtra(Intent.EXTRA_TITLE, "GalleryX-vault-" + day + VaultBackup.EXTENSION);
                        startActivityForResult(intent, BACKUP_EXPORT_REQUEST);
                    } else if (which == 1) {
                        intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        startActivityForResult(intent, BACKUP_IMPORT_REQUEST);
                    } else {
                        intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        startActivityForResult(intent, VAULT_ADD_FILES_REQUEST);
                    }
                }))
                .show();
    }

    private void handleVaultToolResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) {
            VaultSession.hold(false);
            return;
        }
        if (!VaultSession.isUnlocked()) {
            VaultSession.hold(false);
            Toast.makeText(this, "انقفلت الخزنة، افتحها وحاول مرة ثانية", Toast.LENGTH_LONG).show();
            return;
        }
        if (requestCode == VAULT_ADD_FILES_REQUEST) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) uris.add(data.getData());
            addFilesToVault(uris);
            return;
        }
        Uri target = data.getData();
        if (target == null) {
            VaultSession.hold(false);
            return;
        }
        boolean export = requestCode == BACKUP_EXPORT_REQUEST;
        askBackupPassword(export, password -> {
            if (password == null) {
                VaultSession.hold(false);
                if (export) deleteDocumentQuietly(target);
                return;
            }
            if (export) {
                runVaultTask("جارِ تجهيز النسخة الاحتياطية…", progress -> {
                    try (java.io.OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                        if (out == null) throw new java.io.IOException("cannot write");
                        int count = VaultStore.exportBackup(this, new java.io.BufferedOutputStream(out, 256 * 1024), password, progress);
                        return "تم حفظ " + count + " ملف بالنسخة الاحتياطية.\n\nاحتفظ بالملف بمكان آمن (مثل Google Drive أو فلاشة) وتذكّر كلمة سر النسخة، بدونها ما تنفتح أبداً.";
                    } catch (Exception error) {
                        deleteDocumentQuietly(target);
                        throw error;
                    }
                });
            } else {
                runVaultTask("جارِ فتح النسخة الاحتياطية…", progress -> {
                    try (java.io.InputStream in = getContentResolver().openInputStream(target)) {
                        if (in == null) throw new java.io.IOException("cannot read");
                        int count = VaultStore.importBackup(this, new java.io.BufferedInputStream(in, 256 * 1024), password, progress);
                        return "تمت استعادة " + count + " ملف إلى الخزنة.";
                    } catch (VaultBackup.BadPasswordException wrong) {
                        return "كلمة سر النسخة غلط، أو الملف مو نسخة احتياطية من Gallery X.";
                    } catch (VaultBackup.TruncatedException partial) {
                        return "الملف ناقص (ممكن ما اكتمل تنزيله). انسترجع منه " + partial.restored + " ملف.";
                    }
                });
            }
        });
    }

    private void addFilesToVault(List<Uri> uris) {
        if (uris.isEmpty()) {
            VaultSession.hold(false);
            return;
        }
        ArrayList<Uri> added = new ArrayList<>();
        runVaultTask("جارِ تشفير الملفات داخل الخزنة…", progress -> {
            int done = 0;
            for (Uri uri : uris) {
                String name = documentName(uri);
                String type = null;
                try { type = getContentResolver().getType(uri); } catch (Exception ignored) { }
                if (VaultStore.importDocument(this, uri, name, type) != null) added.add(uri);
                done++;
                progress.step(done, uris.size());
            }
            return "انضاف " + added.size() + " من " + uris.size() + " ملف للخزنة.";
        }, () -> {
            if (added.isEmpty()) return;
            new AlertDialog.Builder(this)
                    .setTitle("حذف الملفات الأصلية؟")
                    .setMessage("الملفات صارت مشفّرة داخل الخزنة. تريد أحذف النسخ الأصلية المكشوفة من مدير الملفات؟")
                    .setNegativeButton("اتركها", null)
                    .setPositiveButton("احذفها", (d, w) -> executor.execute(() -> {
                        int removed = 0;
                        for (Uri uri : added) if (deleteDocumentQuietly(uri)) removed++;
                        int count = removed;
                        runOnUiThread(() -> Toast.makeText(this, count == added.size()
                                ? "انحذفت الملفات الأصلية"
                                : "انحذف " + count + " من " + added.size() + "، والباقي احذفه من مدير الملفات", Toast.LENGTH_LONG).show());
                    }))
                    .show();
        });
    }

    private String documentName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) return cursor.getString(0);
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "ملف" : last.substring(last.lastIndexOf('/') + 1);
    }

    private boolean deleteDocumentQuietly(Uri uri) {
        try { return android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri); }
        catch (Exception ignored) { return false; }
    }

    private void askBackupPassword(boolean create, java.util.function.Consumer<String> result) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), 0);
        TextView note = new TextView(this);
        note.setText(create
                ? "اختار كلمة سر للنسخة الاحتياطية (" + VaultBackup.MIN_PASSWORD + " حروف أو أكثر). تگدر تفتح بيها النسخة على أي هاتف. إذا نسيتها، النسخة ما تنفتح أبداً. لا تستخدم نفس رقم الخزنة."
                : "اكتب كلمة السر اللي حطيتها وقت سوّيت النسخة الاحتياطية.");
        note.setTextColor(0xFFFFC2E8);
        note.setTextSize(Ui.textSize(this, 14));
        note.setPadding(0, 0, 0, Ui.dp(this, 10));
        fields.addView(note);
        EditText first = backupPasswordInput("كلمة سر النسخة");
        fields.addView(first, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));
        EditText second = create ? backupPasswordInput("تأكيد كلمة السر") : null;
        if (second != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
            params.setMargins(0, Ui.dp(this, 10), 0, 0);
            fields.addView(second, params);
        }
        boolean[] answered = {false};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(create ? "تصدير نسخة احتياطية" : "استيراد نسخة احتياطية")
                .setView(fields)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton(create ? "تصدير" : "استيراد", null)
                .create();
        dialog.setOnDismissListener(d -> { if (!answered[0]) result.accept(null); });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String one = first.getText().toString();
            if (create && one.length() < VaultBackup.MIN_PASSWORD) {
                first.setError("اكتب " + VaultBackup.MIN_PASSWORD + " حروف أو أكثر");
                return;
            }
            if (!create && one.isEmpty()) return;
            if (second != null && !one.equals(second.getText().toString())) {
                second.setError("كلمتا السر غير متطابقتين");
                return;
            }
            answered[0] = true;
            dialog.dismiss();
            result.accept(one);
        }));
        dialog.show();
    }

    private EditText backupPasswordInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFFBDB4CE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setBackground(Ui.rounded(Ui.withAlpha(theme.surfaceAlt, 245), 14, this));
        input.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        return input;
    }

    private interface VaultTask { String run(VaultStore.Progress progress) throws Exception; }

    private void runVaultTask(String title, VaultTask task) { runVaultTask(title, task, null); }

    /** Runs a long vault job with a progress dialog; the vault stays unlocked until it ends. */
    private void runVaultTask(String title, VaultTask task, Runnable afterSuccess) {
        VaultSession.hold(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 8));
        box.addView(new ProgressBar(this), new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        TextView status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, Ui.dp(this, 12), 0, 0);
        status.setText("لا تطلع من التطبيق لحد ما يخلص");
        box.addView(status);
        AlertDialog progressDialog = new AlertDialog.Builder(this).setTitle(title).setView(box).setCancelable(false).create();
        progressDialog.show();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            String message;
            boolean ok = true;
            try {
                message = task.run((done, total) -> runOnUiThread(() ->
                        status.setText(total > 0 ? "تم " + done + " من " + total : "تم " + done + " ملف")));
            } catch (Exception error) {
                ok = false;
                message = "ما اكتملت العملية: تأكد من المساحة الفارغة والملف، وحاول مرة ثانية.";
            }
            String finalMessage = message;
            boolean success = ok;
            runOnUiThread(() -> {
                VaultSession.hold(false);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (isFinishing()) return;
                try { progressDialog.dismiss(); } catch (Exception ignored) { }
                if (mode == Mode.VAULT && VaultSession.isUnlocked()) showVaultUnlocked();
                new AlertDialog.Builder(this).setTitle("الخزنة").setMessage(finalMessage)
                        .setPositiveButton("حسناً", (d, w) -> { if (success && afterSuccess != null) afterSuccess.run(); })
                        .show();
            });
        }, "vault-task").start();
    }

    private void showVaultUnlocked() {
        if (!VaultSession.isUnlocked()) {
            openVault();
            return;
        }
        vaultBackgroundAt = 0L;
        mode = Mode.VAULT;
        openedAlbum = null;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        title.setText("الخزنة الخاصة");
        searchBox.setVisibility(View.GONE);
        selectTab(settingsTab);
        List<MediaItem> items = new ArrayList<>(VaultStore.load(this));
        List<MediaItem> legacyItems = legacyVaultItems();
        items.addAll(legacyItems);
        renderPhotos(items, "الخزنة فارغة — حدّد صوراً بالضغط المطوّل ثم اختر خاص");
        if (!legacyItems.isEmpty() && !legacyMigrationPromptShown && !vaultOperationRunning) {
            legacyMigrationPromptShown = true;
            new AlertDialog.Builder(this)
                    .setTitle("تأمين الصور القديمة")
                    .setMessage("توجد صور أُضيفت بالخزنة القديمة لكنها ما زالت في استوديو النظام. اضغط تأمين الآن لنقلها إلى التخزين الخاص وإخفاء الأصل من الاستوديو.")
                    .setNegativeButton("لاحقاً", null)
                    .setPositiveButton("تأمين الآن", (dialog, which) -> moveItemsToVault(legacyItems))
                    .show();
        }
    }

    private List<MediaItem> legacyVaultItems() {
        Set<String> stored = PrivateStore.getUris(this);
        List<MediaItem> result = new ArrayList<>();
        if (stored.isEmpty() || albums.isEmpty()) return result;
        for (MediaItem item : albums.get(0).items) {
            if (stored.contains(item.uri.toString())) result.add(item);
        }
        return result;
    }

    private void clearVaultProtection() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void scanDuplicates() {
        clearVaultProtection();
        mode = Mode.DUPLICATES;
        openedAlbum = null;
        title.setText("الصور المكررة");
        searchBox.setVisibility(View.GONE);
        selectTab(settingsTab);
        ProgressBar progress = new ProgressBar(this);
        FrameLayout loading = new FrameLayout(this);
        loading.addView(progress, new FrameLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56), Gravity.CENTER));
        replaceContent(loading);
        List<MediaItem> source = new ArrayList<>(allMedia());
        executor.execute(() -> {
            List<MediaItem> duplicates = DuplicateFinder.findExact(this, source);
            runOnUiThread(() -> {
                if (mode != Mode.DUPLICATES) return;
                renderPhotos(duplicates, "لا توجد نسخ متطابقة");
                if (!duplicates.isEmpty()) Toast.makeText(this,
                        "وُجدت " + duplicates.size() + " صورة ضمن مجموعات متطابقة — اضغط مطولاً للتحديد",
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    private void showHiddenFoldersPicker() {
        List<Album> folders = new ArrayList<>();
        for (Album album : albums) if (!"__all__".equals(album.id)) folders.add(album);
        String[] names = new String[folders.size()];
        boolean[] checked = new boolean[folders.size()];
        Set<String> selected = HiddenFoldersStore.getAll(this);
        for (int i = 0; i < folders.size(); i++) {
            names[i] = folders.get(i).name + "  (" + folders.get(i).items.size() + ")";
            checked[i] = selected.contains(folders.get(i).id);
        }
        new AlertDialog.Builder(this)
                .setTitle("إخفاء مجلدات من Gallery X")
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> {
                    if (isChecked) selected.add(folders.get(which).id);
                    else selected.remove(folders.get(which).id);
                })
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    HiddenFoldersStore.save(this, selected);
                    Toast.makeText(this, "تم تحديث المجلدات المخفية", Toast.LENGTH_SHORT).show();
                    showSettings();
                }).show();
    }

    private void showColorPicker() {
        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.VERTICAL);
        colors.setPadding(Ui.dp(this, 18), Ui.dp(this, 10), Ui.dp(this, 18), Ui.dp(this, 10));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.choose_color).setView(colors).create();
        for (int i = 0; i < ThemePrefs.THEMES.length; i++) {
            int selectedIndex = i;
            ThemePrefs.ThemeStyle option = ThemePrefs.THEMES[i];
            Button choice = new Button(this);
            choice.setText(option.name + (i == ThemePrefs.getIndex(this) ? "  ✓" : ""));
            choice.setTextColor(Color.WHITE);
            choice.setTextSize(Ui.textSize(this, 17));
            choice.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            choice.setAllCaps(false);
            choice.setBackground(Ui.gradient(option.primary, option.secondary, 16, this));
            choice.setOnClickListener(v -> {
                ThemePrefs.setTheme(this, selectedIndex);
                dialog.dismiss();
                recreate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 50));
            lp.setMargins(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
            colors.addView(choice, lp);
        }
        dialog.show();
    }

    private void showGridSizePicker() {
        String[] choices = {"عمودان — صور كبيرة", "3 أعمدة — متوازن", "4 أعمدة — صور أكثر", "5 أعمدة — مضغوط"};
        int checked = GalleryPrefs.getColumns(this) - 2;
        new AlertDialog.Builder(this)
                .setTitle("عدد أعمدة الصور")
                .setSingleChoiceItems(choices, checked, (dialog, which) -> {
                    GalleryPrefs.setColumns(this, which + 2);
                    dialog.dismiss();
                    showSettings();
                }).show();
    }

    private void showFontSizePicker() {
        String[] choices = {"مضغوط — 90%", "اعتيادي — 100%", "كبير — 115%", "كبير جداً — 130%"};
        float current = GalleryPrefs.getFontScale(this);
        int checked = current < 0.95f ? 0 : current < 1.075f ? 1 : current < 1.225f ? 2 : 3;
        float[] values = {0.9f, 1f, 1.15f, 1.3f};
        new AlertDialog.Builder(this)
                .setTitle("حجم الكتابة")
                .setSingleChoiceItems(choices, checked, (dialog, which) -> {
                    GalleryPrefs.setFontScale(this, values[which]);
                    dialog.dismiss();
                    recreate();
                }).show();
    }

    private void openDefaultSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            if (hasImageAccess()) loadGallery(); else showPermissionScreen();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BACKUP_EXPORT_REQUEST || requestCode == BACKUP_IMPORT_REQUEST || requestCode == VAULT_ADD_FILES_REQUEST) {
            handleVaultToolResult(requestCode, resultCode, data);
            return;
        }
        if (requestCode == TRASH_RESTORE_REQUEST || requestCode == TRASH_DELETE_REQUEST) {
            if (resultCode == RESULT_OK) {
                leaveSelectionMode();
                Toast.makeText(this, requestCode == TRASH_RESTORE_REQUEST
                        ? "تم استرجاع الصور" : "تم حذف الصور نهائياً", Toast.LENGTH_SHORT).show();
                showTrash();
                refreshAlbumsSilently();
            }
            return;
        }
        if (requestCode == VAULT_TRASH_REQUEST) {
            if (resultCode == RESULT_OK) finishVaultMove();
            else rollbackPendingVaultMove("أُلغي طلب أندرويد؛ بقي الأصل مكانه ولم تُحفظ نسخة مكررة");
            return;
        }
        if (requestCode == VIEWER_REQUEST && resultCode == RESULT_OK) {
            loadGallery(mode == Mode.VAULT);
            return;
        }
        if (requestCode == BATCH_TRASH_REQUEST && !pendingLegacyDeletes.isEmpty()) {
            if (resultCode == RESULT_OK) {
                PrivateStore.removeAll(this, new HashSet<>(pendingLegacyDeletes));
                Toast.makeText(this, "تم حذف الصور القديمة المحددة", Toast.LENGTH_SHORT).show();
            }
            pendingLegacyDeletes.clear();
            leaveSelectionMode();
            loadGallery(true);
            return;
        }
        if (requestCode == BATCH_TRASH_REQUEST && resultCode == RESULT_OK) {
            leaveSelectionMode();
            loadGallery();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (mode == Mode.VAULT && !VaultSession.isUnlocked()) {
            vaultBackgroundAt = 0L;
            showSettings();
            contentHost.post(this::openVault);
            return;
        }
        if (!albums.isEmpty() && mode == Mode.FAVORITES) showFavorites();
    }

    @Override protected void onStop() {
        if (mode == Mode.VAULT) vaultBackgroundAt = SystemClock.elapsedRealtime();
        super.onStop();
    }

    @Override public void onBackPressed() {
        if (selectionBar.getVisibility() == View.VISIBLE) leaveSelectionMode();
        else if (mode == Mode.VAULT || mode == Mode.DUPLICATES || mode == Mode.TRASH) showSettings();
        else if (mode == Mode.ALBUM) showAllPhotos(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
