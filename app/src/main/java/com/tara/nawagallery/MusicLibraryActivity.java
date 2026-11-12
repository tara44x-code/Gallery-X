package com.tara.nawagallery;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Music section: all songs, or grouped by artist, album or folder, with search and a now-playing bar. */
public class MusicLibraryActivity extends Activity implements MusicService.Listener {
    private static final int PERMISSION_REQUEST = 71;
    private static final String[] VIEWS = {"الأغاني", "الفنانين", "الألبومات", "المجلدات"};

    private ThemePrefs.ThemeStyle theme;
    private final List<Song> all = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>(); // Song or Group
    private final List<TextView> chips = new ArrayList<>();
    private int view;
    private Group openGroup;
    private String query = "";
    private ListView list;
    private RowAdapter adapter;
    private TextView empty, backToGroups, nowTitle, nowPlay;
    private LinearLayout nowBar;

    private static final class Group {
        final String name;
        final List<Song> songs = new ArrayList<>();
        Group(String name) { this.name = name; }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!AppIntegrity.verifyOrClose(this)) return;
        theme = ThemePrefs.getStyle(this);
        getWindow().setStatusBarColor(Color.BLACK);
        buildUi();
        if (hasPermission()) load();
        else requestPermissions(new String[]{Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
    }

    private boolean hasPermission() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != PERMISSION_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) load();
        else showEmpty("اسمح لـ Gallery X يوصل للموسيقى حتى تظهر أغانيك هنا.");
    }

    private void load() {
        showEmpty("جارِ تحميل الأغاني…");
        new Thread(() -> {
            List<Song> songs = MusicRepository.load(this);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                all.clear();
                all.addAll(songs);
                rebuild();
            });
        }, "gx-music-load").start();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        FrameLayout scene = new FrameLayout(this);
        scene.addView(new NeonBackgroundView(this, theme), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.dp(this, 8), Ui.dp(this, 26), Ui.dp(this, 14), Ui.dp(this, 4));
        TextView back = label("‹", 34, true);
        back.setContentDescription("رجوع");
        back.setOnClickListener(v -> onBackPressed());
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        TextView heading = label("الموسيقى", 25, true);
        heading.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        top.addView(heading, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        root.addView(top);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("ابحث باسم الأغنية أو الفنان");
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xFFE6DDEF);
        search.setTextSize(Ui.textSize(this, 16));
        search.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        search.setBackground(Ui.rounded(Ui.withAlpha(theme.surfaceAlt, 242), 22, this));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { query = s.toString().trim().toLowerCase(Locale.ROOT); rebuild(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        searchParams.setMargins(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 6));
        root.addView(search, searchParams);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        for (int i = 0; i < VIEWS.length; i++) {
            int which = i;
            TextView chip = label(VIEWS[i], 15, true);
            chip.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
            chip.setOnClickListener(v -> { view = which; openGroup = null; rebuild(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, Ui.dp(this, 40));
            p.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            chipRow.addView(chip, p);
            chips.add(chip);
        }
        chipScroll.addView(chipRow);
        root.addView(chipScroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));

        backToGroups = label("", 15, true);
        backToGroups.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        backToGroups.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
        backToGroups.setOnClickListener(v -> { openGroup = null; rebuild(); });
        backToGroups.setVisibility(View.GONE);
        root.addView(backToGroups, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));

        FrameLayout body = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(Ui.dp(this, 2));
        list.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 10));
        list.setClipToPadding(false);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, item, position, id) -> onRow(position));
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = label("", 16, false);
        empty.setPadding(Ui.dp(this, 30), 0, Ui.dp(this, 30), 0);
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        nowBar = new LinearLayout(this);
        nowBar.setGravity(Gravity.CENTER_VERTICAL);
        nowBar.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 8), 0);
        nowBar.setBackground(Ui.rounded(Ui.withAlpha(theme.surface, 248), 20, this));
        nowBar.setOnClickListener(v -> startActivity(new Intent(this, MusicPlayerActivity.class)));
        nowTitle = label("", 15, true);
        nowTitle.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        nowTitle.setSingleLine(true);
        nowTitle.setEllipsize(TextUtils.TruncateAt.END);
        nowBar.addView(nowTitle, new LinearLayout.LayoutParams(0, -1, 1));
        nowPlay = label("▶", 24, true);
        nowPlay.setContentDescription("تشغيل أو إيقاف مؤقت");
        nowPlay.setBackground(Ui.rounded(theme.primary, 24, this));
        nowPlay.setOnClickListener(v -> { MusicService s = MusicService.get(); if (s != null) s.toggle(); });
        nowBar.addView(nowPlay, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        nowBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams nowParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 64));
        nowParams.setMargins(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 14));
        root.addView(nowBar, nowParams);

        scene.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(scene);
        updateChips();
    }

    private TextView label(String value, float size, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextColor(Color.WHITE);
        text.setTextSize(Ui.textSize(this, size));
        text.setGravity(Gravity.CENTER);
        if (bold) text.setTypeface(Typeface.DEFAULT_BOLD);
        return text;
    }

    private void updateChips() {
        for (int i = 0; i < chips.size(); i++) {
            boolean active = i == view;
            chips.get(i).setBackground(Ui.rounded(active ? Ui.withAlpha(theme.primary, 170) : Ui.withAlpha(theme.surfaceAlt, 200), 20, this));
        }
    }

    private void showEmpty(String message) {
        empty.setText(message);
        empty.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ data

    private boolean matches(Song song) {
        if (query.isEmpty()) return true;
        return song.title.toLowerCase(Locale.ROOT).contains(query) || song.artist.toLowerCase(Locale.ROOT).contains(query)
                || song.album.toLowerCase(Locale.ROOT).contains(query);
    }

    private void rebuild() {
        rows.clear();
        updateChips();
        if (view == 0 || openGroup != null) {
            List<Song> source = openGroup != null ? openGroup.songs : all;
            for (Song song : source) if (matches(song)) rows.add(song);
        } else {
            Map<String, Group> groups = new LinkedHashMap<>();
            for (Song song : all) {
                if (!matches(song)) continue;
                String key = view == 1 ? song.artist : view == 2 ? song.album : song.folder;
                Group group = groups.get(key);
                if (group == null) { group = new Group(key); groups.put(key, group); }
                group.songs.add(song);
            }
            List<Group> sorted = new ArrayList<>(groups.values());
            sorted.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            rows.addAll(sorted);
        }
        backToGroups.setVisibility(openGroup == null ? View.GONE : View.VISIBLE);
        if (openGroup != null) backToGroups.setText("‹  " + VIEWS[view] + "  /  " + openGroup.name);
        adapter.notifyDataSetChanged();
        if (all.isEmpty()) showEmpty(hasPermission() ? "ما لگيت أغاني بهاتفك." : "اسمح لـ Gallery X يوصل للموسيقى حتى تظهر أغانيك هنا.");
        else showEmpty(rows.isEmpty() ? "ماكو نتائج." : null);
    }

    private void onRow(int position) {
        Object row = rows.get(position);
        if (row instanceof Group) {
            openGroup = (Group) row;
            rebuild();
            list.setSelection(0);
            return;
        }
        List<Song> queue = new ArrayList<>();
        int start = 0;
        for (Object item : rows) if (item instanceof Song) {
            if (item == row) start = queue.size();
            queue.add((Song) item);
        }
        MusicService.play(this, queue, start);
        startActivity(new Intent(this, MusicPlayerActivity.class).putExtra(MusicPlayerActivity.EXTRA_STARTING, true));
    }

    @Override public void onBackPressed() {
        if (openGroup != null) { openGroup = null; rebuild(); }
        else super.onBackPressed();
    }

    @Override public void onMusicChanged() {
        MusicService service = MusicService.get();
        Song song = service == null ? null : service.current();
        nowBar.setVisibility(song == null ? View.GONE : View.VISIBLE);
        if (song != null) {
            nowTitle.setText("♫  " + song.title + " — " + song.artist);
            nowPlay.setText(service.isPlaying() ? "⏸" : "▶");
        }
        adapter.notifyDataSetChanged();
    }

    @Override protected void onStart() {
        super.onStart();
        MusicService.addListener(this);
        onMusicChanged();
    }

    @Override protected void onStop() {
        MusicService.removeListener(this);
        super.onStop();
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout row;
            TextView icon, first, second;
            if (convert instanceof LinearLayout) {
                row = (LinearLayout) convert;
                icon = (TextView) row.getChildAt(0);
                LinearLayout texts = (LinearLayout) row.getChildAt(1);
                first = (TextView) texts.getChildAt(0);
                second = (TextView) texts.getChildAt(1);
            } else {
                row = new LinearLayout(MusicLibraryActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(Ui.dp(MusicLibraryActivity.this, 10), Ui.dp(MusicLibraryActivity.this, 8), Ui.dp(MusicLibraryActivity.this, 10), Ui.dp(MusicLibraryActivity.this, 8));
                icon = label("♫", 20, true);
                row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(MusicLibraryActivity.this, 48), Ui.dp(MusicLibraryActivity.this, 48)));
                LinearLayout texts = new LinearLayout(MusicLibraryActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(Ui.dp(MusicLibraryActivity.this, 12), 0, 0, 0);
                first = label("", 16, true);
                first.setGravity(Gravity.START);
                first.setSingleLine(true);
                first.setEllipsize(TextUtils.TruncateAt.END);
                second = label("", 13, false);
                second.setGravity(Gravity.START);
                second.setTextColor(0xFFCFC6DC);
                second.setSingleLine(true);
                second.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(first);
                texts.addView(second);
                row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            }
            Object item = rows.get(position);
            MusicService service = MusicService.get();
            Song playing = service == null ? null : service.current();
            if (item instanceof Song) {
                Song song = (Song) item;
                boolean current = playing != null && playing.uri.equals(song.uri);
                icon.setText(current ? "▶" : "♫");
                first.setText(song.title);
                second.setText(song.artist + " · " + MusicRepository.time(song.durationMs));
                icon.setBackground(Ui.rounded(current ? theme.primary : Ui.withAlpha(theme.surfaceAlt, 230), 24, MusicLibraryActivity.this));
                row.setBackground(current ? Ui.rounded(Ui.withAlpha(theme.primary, 55), 16, MusicLibraryActivity.this) : null);
            } else {
                Group group = (Group) item;
                icon.setText(view == 1 ? "♪" : view == 2 ? "◎" : "▤");
                first.setText(group.name);
                second.setText(group.songs.size() + " أغنية");
                icon.setBackground(Ui.rounded(Ui.withAlpha(theme.secondary, 150), 24, MusicLibraryActivity.this));
                row.setBackground(null);
            }
            return row;
        }
    }
}
