package com.tara.nawagallery;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Collections;

/** Now-playing screen: the visualizer, song details, seek bar and controls. Also opens audio files from other apps. */
public class MusicPlayerActivity extends Activity implements MusicService.Listener {
    static final String EXTRA_STARTING = "starting";
    private static final String PREFS = "gx_music_ui";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ThemePrefs.ThemeStyle theme;
    private VisualizerView visualizer;
    private TextView title, artist, elapsed, total, play, modeLabel;
    private SeekBar seek;
    private boolean dragging;
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
            }
            handler.postDelayed(this, 250);
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

    /** "Open with → Gallery X" for an audio file from Telegram, a file manager or the browser. */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            Uri uri = intent.getData();
            new Thread(() -> {
                Song song = MusicRepository.fromUri(this, uri);
                runOnUiThread(() -> MusicService.play(this, Collections.singletonList(song), 0));
            }, "gx-open-audio").start();
        } else if (!intent.getBooleanExtra(EXTRA_STARTING, false)
                && (MusicService.get() == null || MusicService.get().current() == null)) {
            startActivity(new Intent(this, MusicLibraryActivity.class));
            finish();
        }
    }

    private void buildUi() {
        FrameLayout scene = new FrameLayout(this);
        scene.addView(new NeonBackgroundView(this, theme), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 28), Ui.dp(this, 16), Ui.dp(this, 22));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 34, true);
        back.setContentDescription("رجوع");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        TextView heading = text("قيد التشغيل", 18, true);
        top.addView(heading, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1));
        TextView library = text("☰", 22, true);
        library.setContentDescription("مكتبة الموسيقى");
        library.setOnClickListener(v -> startActivity(new Intent(this, MusicLibraryActivity.class)));
        top.addView(library, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        root.addView(top);

        visualizer = new VisualizerView(this, theme.primary, theme.secondary);
        visualizer.setMode(getSharedPreferences(PREFS, MODE_PRIVATE).getInt("mode", 0));
        visualizer.setContentDescription("المؤثرات، اضغط للتبديل");
        visualizer.setOnClickListener(v -> switchMode(1));
        visualizer.setSource(new VisualizerView.Source() {
            @Override public SpectrumAnalyzer.Frames frames() { return analysis == null ? null : analysis.frames(); }
            @Override public long position() { MusicService s = MusicService.get(); return s == null ? 0 : s.position(); }
            @Override public boolean playing() { MusicService s = MusicService.get(); return s != null && s.isPlaying(); }
        });
        root.addView(visualizer, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER);
        TextView previousMode = text("‹", 24, true);
        previousMode.setContentDescription("الشكل السابق");
        previousMode.setOnClickListener(v -> switchMode(-1));
        modeLabel = text("", 15, true);
        modeLabel.setBackground(Ui.rounded(Ui.withAlpha(theme.primary, 70), 16, this));
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
        title.setPadding(0, Ui.dp(this, 10), 0, 0);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        artist = text("", 15, false);
        artist.setTextColor(0xFFCFC6DC);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(artist, new LinearLayout.LayoutParams(-1, -2));

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
        seekParams.topMargin = Ui.dp(this, 10);
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
        TextView previous = round("⏮", 22, theme.surfaceAlt, 64);
        previous.setContentDescription("السابق");
        previous.setOnClickListener(v -> { MusicService s = MusicService.get(); if (s != null) s.previous(); });
        play = round("▶", 30, theme.primary, 84);
        play.setContentDescription("تشغيل أو إيقاف مؤقت");
        play.setOnClickListener(v -> { MusicService s = MusicService.get(); if (s != null) s.toggle(); });
        TextView next = round("⏭", 22, theme.surfaceAlt, 64);
        next.setContentDescription("التالي");
        next.setOnClickListener(v -> { MusicService s = MusicService.get(); if (s != null) s.next(); });
        addControl(controls, previous, 64);
        addControl(controls, play, 84);
        addControl(controls, next, 64);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 100));
        controlsParams.topMargin = Ui.dp(this, 8);
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

    private TextView round(String value, float size, int color, int diameter) {
        TextView view = text(value, size, true);
        view.setBackground(Ui.rounded(color, diameter / 2f, this));
        view.setElevation(Ui.dp(this, 8));
        return view;
    }

    private void switchMode(int step) {
        visualizer.setMode(visualizer.mode() + step);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("mode", visualizer.mode()).apply();
        updateModeLabel();
    }

    private void updateModeLabel() {
        modeLabel.setText("✦ " + VisualizerView.MODES[visualizer.mode()]);
    }

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
            if (analysis != null) analysis.cancel();
            analysis = new AudioAnalysis(this, song.uri);
            visualizer.setArt(null);
            new Thread(() -> {
                Bitmap art = MusicRepository.art(this, song, 720);
                runOnUiThread(() -> { if (shownSong == song) visualizer.setArt(art); });
            }, "gx-player-art").start();
        }
        visualizer.invalidate();
    }

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
