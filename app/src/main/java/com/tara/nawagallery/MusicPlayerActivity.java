package com.tara.nawagallery;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Now-playing screen: visualizer or synced lyrics, song details, controls, equalizer, speed and playlists. */
public class MusicPlayerActivity extends Activity implements MusicService.Listener {
    static final String EXTRA_STARTING = "starting";
    private static final String PREFS = "gx_music_ui";
    private static final int REQUEST_LYRICS = 81, REQUEST_PERMISSION = 82;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ThemePrefs.ThemeStyle theme;
    private ArtGlowView glow;
    private VisualizerView visualizer;
    private LyricsView lyrics;
    private TextView title, artist, elapsed, total, play, modeLabel, favorite, lyricsChip, speedChip;
    private SeekBar seek;
    private boolean dragging, showLyrics;
    private AudioAnalysis analysis;
    private Song shownSong;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            MusicService service = MusicService.get();
            if (service != null && !dragging) {
                int duration = service.duration();
                int position = service.position();
                seek.setMax(Math.max(1, duration));
                seek.setProgress(position);
                elapsed.setText(MusicRepository.time(position));
                total.setText(MusicRepository.time(duration));
                if (showLyrics) lyrics.setPosition(position);
            }
            handler.postDelayed(this, showLyrics ? 100 : 250);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!AppIntegrity.verifyOrClose(this)) return;
        theme = ThemePrefs.getStyle(this);
        getWindow().setStatusBarColor(Color.BLACK);
        buildUi();
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** "Open with → Gallery X" for an audio file: plays it, then the rest of the library follows. */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            Uri uri = intent.getData();
            new Thread(() -> {
                Song song = MusicRepository.fromUri(this, uri);
                runOnUiThread(() -> MusicService.play(this, Collections.singletonList(song), 0, true));
            }, "gx-open-audio").start();
            if (!canReadMusic()) {
                requestPermissions(new String[]{Build.VERSION.SDK_INT >= 33
                        ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_PERMISSION);
            }
        } else if (!intent.getBooleanExtra(EXTRA_STARTING, false)
                && (MusicService.get() == null || MusicService.get().current() == null)) {
            startActivity(new Intent(this, MusicLibraryActivity.class));
            finish();
        }
    }

    private boolean canReadMusic() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQUEST_PERMISSION && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            MusicService service = MusicService.get();
            if (service != null) service.joinLibrary(); // now Next/Previous move through your songs
        }
    }

    // ------------------------------------------------------------------ layout

    private void buildUi() {
        FrameLayout scene = new FrameLayout(this);
        glow = new ArtGlowView(this, theme.background, theme.primary, theme.secondary);
        scene.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 28), Ui.dp(this, 16), Ui.dp(this, 18));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 34, true);
        back.setContentDescription("رجوع");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        top.addView(text("قيد التشغيل", 18, true), new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        TextView library = text("☰", 22, true);
        library.setContentDescription("مكتبة الموسيقى");
        library.setOnClickListener(v -> startActivity(new Intent(this, MusicLibraryActivity.class)));
        top.addView(library, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        root.addView(top);

        FrameLayout stage = new FrameLayout(this);
        visualizer = new VisualizerView(this, theme.primary, theme.secondary);
        visualizer.setMode(getSharedPreferences(PREFS, MODE_PRIVATE).getInt("mode", 0));
        visualizer.setContentDescription("المؤثرات، اضغط للتبديل");
        visualizer.setOnClickListener(v -> switchMode(1));
        visualizer.setSource(new VisualizerView.Source() {
            @Override public SpectrumAnalyzer.Frames frames() { return analysis == null ? null : analysis.frames(); }
            @Override public long position() { MusicService s = MusicService.get(); return s == null ? 0 : s.position(); }
            @Override public boolean playing() { MusicService s = MusicService.get(); return s != null && s.isPlaying(); }
        });
        stage.addView(visualizer, new FrameLayout.LayoutParams(-1, -1));
        lyrics = new LyricsView(this, theme.primary);
        lyrics.setSeek(ms -> { MusicService s = MusicService.get(); if (s != null) s.seekTo((int) ms); });
        lyrics.setVisibility(View.GONE);
        stage.addView(lyrics, new FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER);
        TextView previousMode = text("‹", 24, true);
        previousMode.setContentDescription("الشكل السابق");
        previousMode.setOnClickListener(v -> switchMode(-1));
        modeLabel = text("", 15, true);
        modeLabel.setBackground(Ui.rounded(Ui.withAlpha(Color.WHITE, 30), 16, this));
        modeLabel.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        modeLabel.setOnClickListener(v -> switchMode(1));
        TextView nextMode = text("›", 24, true);
        nextMode.setContentDescription("الشكل التالي");
        nextMode.setOnClickListener(v -> switchMode(1));
        modes.addView(previousMode, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));
        modes.addView(modeLabel, new LinearLayout.LayoutParams(-2, Ui.dp(this, 38)));
        modes.addView(nextMode, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 44)));
        root.addView(modes, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));

        title = text("", 22, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        title.setMarqueeRepeatLimit(-1);
        title.setSelected(true);
        title.setPadding(0, Ui.dp(this, 6), 0, 0);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        artist = text("", 15, false);
        artist.setTextColor(0xFFCFC6DC);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(artist, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView toolScroll = new HorizontalScrollView(this);
        toolScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        favorite = chip(tools, "♡ مفضلة", v -> toggleFavorite());
        chip(tools, "＋ قائمة", v -> addToPlaylist());
        lyricsChip = chip(tools, "♪ كلمات", v -> toggleLyrics());
        chip(tools, "🎚 المعادل", v -> showEqualizer());
        speedChip = chip(tools, speedLabel(MusicStore.speed(this)), v -> chooseSpeed());
        boolean rainbow = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("rainbow", true);
        visualizer.setRainbow(rainbow);
        TextView[] colorChip = new TextView[1];
        colorChip[0] = chip(tools, rainbow ? "🌈 طيف نيون" : "🎨 ألوان الغلاف", v -> {
            boolean on = !visualizer.rainbow();
            visualizer.setRainbow(on);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("rainbow", on).apply();
            colorChip[0].setText(on ? "🌈 طيف نيون" : "🎨 ألوان الغلاف");
        });
        toolScroll.addView(tools);
        LinearLayout.LayoutParams toolParams = new LinearLayout.LayoutParams(-2, Ui.dp(this, 46));
        toolParams.gravity = Gravity.CENTER_HORIZONTAL;
        toolParams.topMargin = Ui.dp(this, 8);
        root.addView(toolScroll, toolParams);

        seek = new SeekBar(this);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(theme.primary));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        seek.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) { if (user) elapsed.setText(MusicRepository.time(progress)); }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                MusicService s = MusicService.get();
                if (s != null) s.seekTo(bar.getProgress());
            }
        });
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 40));
        seekParams.topMargin = Ui.dp(this, 4);
        root.addView(seek, seekParams);
        LinearLayout times = new LinearLayout(this);
        times.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        elapsed = text("0:00", 13, false);
        elapsed.setGravity(Gravity.START);
        total = text("0:00", 13, false);
        total.setGravity(Gravity.END);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1));
        times.addView(total, new LinearLayout.LayoutParams(0, -2, 1));
        times.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        root.addView(times);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        TextView previous = round("⏮", 22, Ui.withAlpha(Color.WHITE, 40));
        previous.setContentDescription("السابق");
        previous.setOnClickListener(v -> { MusicService s = MusicService.get(); if (s != null) s.previous(); });
        play = round("▶", 30, theme.primary);
        play.setContentDescription("تشغيل أو إيقاف مؤقت");
        play.setOnClickListener(v -> { MusicService s = MusicService.get(); if (s != null) s.toggle(); });
        TextView next = round("⏭", 22, Ui.withAlpha(Color.WHITE, 40));
        next.setContentDescription("التالي");
        next.setOnClickListener(v -> { MusicService s = MusicService.get(); if (s != null) s.next(); });
        addControl(controls, previous, 64);
        addControl(controls, play, 84);
        addControl(controls, next, 64);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 96));
        controlsParams.topMargin = Ui.dp(this, 4);
        root.addView(controls, controlsParams);

        scene.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(scene);
        updateModeLabel();
    }

    private void addControl(LinearLayout row, View view, int size) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(this, size), Ui.dp(this, size));
        params.setMargins(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        row.addView(view, params);
    }

    private TextView text(String value, float size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(Ui.textSize(this, size));
        view.setGravity(Gravity.CENTER);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView round(String value, float size, int color) {
        TextView view = text(value, size, true);
        view.setBackground(Ui.rounded(color, 50, this));
        view.setElevation(Ui.dp(this, 8));
        return view;
    }

    private TextView chip(LinearLayout row, String value, View.OnClickListener click) {
        TextView view = text(value, 14, true);
        view.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        view.setBackground(Ui.rounded(Ui.withAlpha(Color.WHITE, 34), 18, this));
        view.setOnClickListener(click);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, Ui.dp(this, 40));
        params.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        row.addView(view, params);
        return view;
    }

    private void switchMode(int step) {
        visualizer.setMode(visualizer.mode() + step);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("mode", visualizer.mode()).apply();
        updateModeLabel();
    }

    private void updateModeLabel() { modeLabel.setText("✦ " + VisualizerView.MODES[visualizer.mode()]); }

    // ------------------------------------------------------------------ song changes

    @Override public void onMusicChanged() {
        MusicService service = MusicService.get();
        Song song = service == null ? null : service.current();
        if (song == null) {
            play.setText("▶");
            return;
        }
        play.setText(service.isPlaying() ? "⏸" : "▶");
        if (song != shownSong) {
            shownSong = song;
            title.setText(song.title);
            artist.setText(song.artist + " · " + song.album);
            updateFavorite();
            if (analysis != null) analysis.cancel();
            analysis = new AudioAnalysis(this, song.uri);
            visualizer.setArt(null);
            lyrics.setLines(null);
            lyricsChip.setText("♪ كلمات");
            new Thread(() -> {
                Bitmap art = MusicRepository.art(this, song, 720);
                int[] colors = colorsOf(art);
                runOnUiThread(() -> {
                    if (shownSong != song) return;
                    visualizer.setArt(art);
                    int a = colors == null ? theme.primary : colors[0];
                    int b = colors == null ? theme.secondary : colors[1];
                    visualizer.setColors(a, b);
                    glow.setColors(a, b);
                    lyrics.setGlow(a);
                    seek.setProgressTintList(android.content.res.ColorStateList.valueOf(a));
                    play.setBackground(Ui.rounded(a, 50, this));
                });
            }, "gx-player-art").start();
            findLyrics(song);
        }
        visualizer.invalidate();
    }

    /** Two neon colours from the cover, or null to keep the theme colours. */
    private static int[] colorsOf(Bitmap art) {
        if (art == null) return null;
        try {
            Bitmap small = Bitmap.createScaledBitmap(art, 32, 32, true);
            int[] pixels = new int[32 * 32];
            small.getPixels(pixels, 0, 32, 0, 0, 32, 32);
            return ArtColors.pick(pixels);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ lyrics

    private void findLyrics(Song song) {
        new Thread(() -> {
            String text = null;
            String remembered = MusicStore.lyrics(this, song);
            if (remembered != null) text = FolderSearch.read(this, Uri.parse(remembered));
            String fileName = song.path != null ? new File(song.path).getName() : song.title + ".mp3";
            String byTitle = song.title + ".lrc";
            FolderSearch.NameScore score = name -> LrcParser.matches(fileName, name) ? 2
                    : name != null && name.equalsIgnoreCase(byTitle) ? 1 : -1;
            if (text == null && song.path != null) {
                File file = FolderSearch.nextTo(song.path, score);
                if (file != null) text = FolderSearch.read(file);
            }
            if (text == null) {
                Uri found = FolderSearch.inAllowedFolders(this, score);
                if (found != null) text = FolderSearch.read(this, found);
            }
            List<LrcParser.Line> lines = LrcParser.parse(text);
            runOnUiThread(() -> {
                if (shownSong != song) return;
                lyrics.setLines(lines);
                lyricsChip.setText(lines.isEmpty() ? "♪ كلمات" : "♪ كلمات ✓");
            });
        }, "gx-lyrics").start();
    }

    private void toggleLyrics() {
        if (!showLyrics && !lyrics.hasLines()) {
            new AlertDialog.Builder(this).setTitle("كلمات الأغنية")
                    .setMessage("ما لگيت ملف كلمات لهالأغنية.\n\nحط ملف .lrc بنفس اسم الأغنية جنبها، أو بمجلد الترجمات اللي اخترته بمشغّل الفيديو، أو اختاره هسه بإيدك.")
                    .setPositiveButton("اختيار ملف .lrc", (d, w) -> pickLyrics())
                    .setNegativeButton("إلغاء", null).show();
            return;
        }
        showLyrics = !showLyrics;
        lyrics.setVisibility(showLyrics ? View.VISIBLE : View.GONE);
        visualizer.setAlpha(showLyrics ? 0.3f : 1f);
        lyricsChip.setBackground(Ui.rounded(Ui.withAlpha(showLyrics ? theme.primary : Color.WHITE, showLyrics ? 160 : 34), 18, this));
    }

    private void pickLyrics() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(pick, REQUEST_LYRICS);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_LYRICS || result != RESULT_OK || data == null || data.getData() == null || shownSong == null) return;
        Uri uri = data.getData();
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
        Song song = shownSong;
        new Thread(() -> {
            List<LrcParser.Line> lines = LrcParser.parse(FolderSearch.read(this, uri));
            runOnUiThread(() -> {
                if (lines.isEmpty()) { Toast.makeText(this, "هذا الملف ما بيه كلمات متزامنة", Toast.LENGTH_LONG).show(); return; }
                MusicStore.setLyrics(this, song, uri.toString());
                if (shownSong != song) return;
                lyrics.setLines(lines);
                lyricsChip.setText("♪ كلمات ✓");
                if (!showLyrics) toggleLyrics();
            });
        }, "gx-lyrics-pick").start();
    }

    // ------------------------------------------------------------------ favourites and playlists

    private void updateFavorite() {
        boolean fav = shownSong != null && MusicStore.isFavorite(this, shownSong);
        favorite.setText(fav ? "♥ مفضلة" : "♡ مفضلة");
        favorite.setTextColor(fav ? 0xFFFF7AD9 : Color.WHITE);
    }

    private void toggleFavorite() {
        if (shownSong == null) return;
        boolean now = MusicStore.toggleFavorite(this, shownSong);
        updateFavorite();
        Toast.makeText(this, now ? "انضافت للمفضلة" : "انشالت من المفضلة", Toast.LENGTH_SHORT).show();
    }

    private void addToPlaylist() {
        if (shownSong == null) return;
        Song song = shownSong;
        List<String> names = new ArrayList<>(MusicStore.playlists(this).keySet());
        names.add("＋ قائمة جديدة");
        new AlertDialog.Builder(this).setTitle("إضافة إلى قائمة")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which == names.size() - 1) askPlaylistName(name -> {
                        MusicStore.addToPlaylist(this, name, song);
                        Toast.makeText(this, "انضافت إلى «" + name + "»", Toast.LENGTH_SHORT).show();
                    });
                    else {
                        MusicStore.addToPlaylist(this, names.get(which), song);
                        Toast.makeText(this, "انضافت إلى «" + names.get(which) + "»", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void askPlaylistName(java.util.function.Consumer<String> done) {
        EditText input = new EditText(this);
        input.setHint("اسم القائمة");
        input.setSingleLine(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(input);
        new AlertDialog.Builder(this).setTitle("قائمة جديدة").setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) done.accept(name);
                }).show();
    }

    // ------------------------------------------------------------------ speed and equalizer

    private static String speedLabel(float speed) {
        String text = String.valueOf(speed);
        if (text.endsWith(".0")) text = text.substring(0, text.length() - 2);
        return "⏱ " + text + "×";
    }

    private void chooseSpeed() {
        float[] values = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) labels[i] = speedLabel(values[i]).substring(2);
        new AlertDialog.Builder(this).setTitle("سرعة التشغيل").setItems(labels, (d, which) -> {
            MusicStore.setSpeed(this, values[which]);
            speedChip.setText(speedLabel(values[which]));
            MusicService s = MusicService.get();
            if (s != null) s.applySpeed();
        }).show();
    }

    private void showEqualizer() {
        MusicService service = MusicService.get();
        AudioEffects fx = service == null ? null : service.effects();
        if (fx == null || (!fx.hasEqualizer() && !fx.hasBass())) {
            Toast.makeText(this, "شغّل أغنية أولاً، أو هاتفك ما يدعم معادل الصوت", Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 6));

        Switch enabled = new Switch(this);
        enabled.setText("تشغيل المعادل والبيس");
        enabled.setTextSize(16);
        enabled.setChecked(MusicStore.eqEnabled(this));
        enabled.setOnCheckedChangeListener((b, on) -> { MusicStore.setEqEnabled(this, on); fx.apply(); });
        box.addView(enabled, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));

        List<SeekBar> bandBars = new ArrayList<>();
        List<TextView> presetChips = new ArrayList<>();
        HorizontalScrollView presetScroll = new HorizontalScrollView(this);
        LinearLayout presetRow = new LinearLayout(this);
        Runnable paintPresets = () -> {
            int selected = MusicStore.preset(this);
            for (int i = 0; i < presetChips.size(); i++) {
                boolean on = i == selected;
                presetChips.get(i).setBackground(Ui.rounded(on ? theme.primary : 0xFFE4DEEC, 16, this));
                presetChips.get(i).setTextColor(on ? Color.WHITE : 0xFF2A2433);
            }
            for (int b = 0; b < bandBars.size(); b++) bandBars.get(b).setProgress(fx.presetLevel(selected, b) - fx.minLevel());
        };
        for (int i = 0; i < MusicStore.PRESETS.length; i++) {
            int preset = i;
            TextView chip = new TextView(this);
            chip.setText(MusicStore.PRESETS[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
            chip.setOnClickListener(v -> {
                MusicStore.setPreset(this, preset);
                if (!MusicStore.eqEnabled(this)) enabled.setChecked(true);
                fx.apply();
                paintPresets.run();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, Ui.dp(this, 36));
            p.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            presetRow.addView(chip, p);
            presetChips.add(chip);
        }
        presetScroll.addView(presetRow);
        box.addView(presetScroll, new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)));

        if (fx.hasEqualizer()) {
            int range = fx.maxLevel() - fx.minLevel();
            for (int b = 0; b < fx.bands(); b++) {
                int band = b;
                int hz = fx.bandFrequency(b);
                TextView label = new TextView(this);
                label.setText(hz >= 1000 ? String.format(Locale.US, "%.1f kHz", hz / 1000f) : hz + " Hz");
                box.addView(label);
                SeekBar bar = new SeekBar(this);
                bar.setMax(range);
                bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar s, int progress, boolean user) {
                        if (!user) return;
                        int custom = MusicStore.PRESETS.length - 1;
                        int current = MusicStore.preset(MusicPlayerActivity.this);
                        if (current != custom) {
                            for (int k = 0; k < fx.bands(); k++) MusicStore.setBandLevel(MusicPlayerActivity.this, k, fx.presetLevel(current, k));
                            MusicStore.setPreset(MusicPlayerActivity.this, custom);
                        }
                        MusicStore.setBandLevel(MusicPlayerActivity.this, band, progress + fx.minLevel());
                        if (!MusicStore.eqEnabled(MusicPlayerActivity.this)) enabled.setChecked(true);
                        fx.apply();
                        for (int k = 0; k < presetChips.size(); k++) {
                            boolean on = k == custom;
                            presetChips.get(k).setBackground(Ui.rounded(on ? theme.primary : 0xFFE4DEEC, 16, MusicPlayerActivity.this));
                            presetChips.get(k).setTextColor(on ? Color.WHITE : 0xFF2A2433);
                        }
                    }
                    @Override public void onStartTrackingTouch(SeekBar s) { }
                    @Override public void onStopTrackingTouch(SeekBar s) { }
                });
                box.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 36)));
                bandBars.add(bar);
            }
        }
        if (fx.hasBass()) addStrength(box, "تقوية البيس", MusicStore.bass(this), 1000, v -> { MusicStore.setBass(this, v); enableAndApply(enabled, fx); });
        if (fx.hasSurround()) addStrength(box, "صوت محيطي (للسماعات)", MusicStore.surround(this), 1000, v -> { MusicStore.setSurround(this, v); enableAndApply(enabled, fx); });
        TextView fadeLabel = new TextView(this);
        fadeLabel.setPadding(0, Ui.dp(this, 10), 0, 0);
        fadeLabel.setText(fadeText(MusicStore.crossfade(this) / 1000));
        box.addView(fadeLabel);
        addStrength(box, "", MusicStore.crossfade(this) / 1000, 10, v -> {
            MusicStore.setCrossfade(this, v * 1000);
            fadeLabel.setText(fadeText(v));
        });
        paintPresets.run();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this).setTitle("🎚 معادل الصوت").setView(scroll).setPositiveButton("تم", null).show();
    }

    private static String fadeText(int seconds) {
        return seconds == 0 ? "الانتقال بين الأغاني: مباشر" : "الانتقال الناعم بين الأغاني: " + seconds + " ثانية";
    }

    private void enableAndApply(Switch enabled, AudioEffects fx) {
        if (!MusicStore.eqEnabled(this)) enabled.setChecked(true);
        fx.apply();
    }

    private interface IntSetter { void set(int value); }

    private void addStrength(LinearLayout box, String name, int value, int max, IntSetter setter) {
        if (!name.isEmpty()) {
            TextView label = new TextView(this);
            label.setText(name);
            label.setPadding(0, Ui.dp(this, 10), 0, 0);
            box.addView(label);
        }
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean user) { if (user) setter.set(progress); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 36)));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onStart() {
        super.onStart();
        MusicService.addListener(this);
        onMusicChanged();
        handler.post(ticker);
    }

    @Override protected void onStop() {
        MusicService.removeListener(this);
        handler.removeCallbacks(ticker);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (analysis != null) analysis.cancel();
        super.onDestroy();
    }
}
