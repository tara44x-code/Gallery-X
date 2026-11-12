package com.tara.nawagallery;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays music in the background: keeps playing with the app closed or the screen off, and is
 * controlled from the notification, lock screen, widget, headphones and Bluetooth (MediaSession).
 * Songs fade in and out (crossfade), sound effects live on one fixed audio session, and a song
 * opened on its own joins the rest of the library so Next/Previous always work.
 */
public class MusicService extends Service {
    static final String ACTION_QUEUE = "gx.music.QUEUE";
    static final String ACTION_TOGGLE = "gx.music.TOGGLE";
    static final String ACTION_NEXT = "gx.music.NEXT";
    static final String ACTION_PREVIOUS = "gx.music.PREVIOUS";
    static final String ACTION_STOP = "gx.music.STOP";
    private static final String CHANNEL = "gx_music";
    private static final int NOTIFICATION_ID = 7310;
    private static final int FADE_MS = 280;

    interface Listener { void onMusicChanged(); }

    private static MusicService instance;
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static List<Song> pendingQueue;
    private static int pendingIndex;
    private static boolean pendingJoinLibrary;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Song> queue = new ArrayList<>();
    private int index = -1;
    private MediaPlayer player;          // the song being heard
    private MediaPlayer fadingOut;       // previous song during a crossfade
    private boolean prepared;
    private boolean playWhenReady;
    private boolean resumeOnFocus;
    private boolean noisyRegistered;
    private boolean foreground;
    private boolean crossfading;
    private boolean countedPlay;
    private int seekWhenReady = -1;
    private float gain = 1f, gainTarget = 1f, outGain = 0f, duck = 1f;
    private long fadeStarted;
    private int fadeLength = FADE_MS;
    private Runnable afterFade;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private int audioSession;
    private AudioEffects effects;
    private Bitmap art;
    private Song artFor;

    // ------------------------------------------------------------------ static API for activities

    /** Plays {@code songs} from {@code start}. With {@code joinLibrary}, the rest of the library follows. */
    static void play(Context context, List<Song> songs, int start, boolean joinLibrary) {
        if (songs == null || songs.isEmpty()) return;
        pendingQueue = new ArrayList<>(songs);
        pendingIndex = Math.max(0, Math.min(start, songs.size() - 1));
        pendingJoinLibrary = joinLibrary;
        send(context, ACTION_QUEUE);
    }

    static void play(Context context, List<Song> songs, int start) { play(context, songs, start, false); }

    /** Sends a control action; starts the service if needed (widget, notification, headphones). */
    static void send(Context context, String action) {
        Intent intent = new Intent(context, MusicService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception ignored) { }
    }

    static MusicService get() { return instance; }

    static void addListener(Listener listener) { if (!LISTENERS.contains(listener)) LISTENERS.add(listener); }
    static void removeListener(Listener listener) { LISTENERS.remove(listener); }

    Song current() { return index >= 0 && index < queue.size() ? queue.get(index) : null; }
    Bitmap art() { return art; }
    boolean isPlaying() { return player != null && prepared && player.isPlaying(); }
    boolean hasNext() { return index + 1 < queue.size(); }
    int queueSize() { return queue.size(); }
    int position() { try { return player != null && prepared ? player.getCurrentPosition() : Math.max(0, seekWhenReady); } catch (Exception e) { return 0; } }
    int duration() { try { return player != null && prepared ? player.getDuration() : 0; } catch (Exception e) { return 0; } }
    AudioEffects effects() { return effects; }

    // ------------------------------------------------------------------ lifecycle

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        audioSession = audio.generateAudioSessionId();
        effects = new AudioEffects(this, audioSession);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "تشغيل الموسيقى", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        session = new MediaSession(this, "GalleryXMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { if (current() == null) restoreLast(); else resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long position) { seekTo((int) position); }
            @Override public void onStop() { stopAll(); }
        });
        session.setSessionActivity(openPlayer());
        session.setActive(true);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground(buildNotification(current(), isPlaying())); // required right after startForegroundService
        String action = intent == null ? null : intent.getAction();
        if (ACTION_QUEUE.equals(action) && pendingQueue != null) {
            queue.clear();
            queue.addAll(pendingQueue);
            pendingQueue = null;
            load(pendingIndex, true, false);
            if (pendingJoinLibrary) joinLibrary();
        } else if (ACTION_TOGGLE.equals(action)) { if (current() == null) restoreLast(); else toggle(); }
        else if (ACTION_NEXT.equals(action)) { if (current() == null) restoreLast(); else next(); }
        else if (ACTION_PREVIOUS.equals(action)) { if (current() == null) restoreLast(); else previous(); }
        else if (ACTION_STOP.equals(action)) { stopAll(); return START_NOT_STICKY; }
        if (current() == null && !restoring) { stopAll(); return START_NOT_STICKY; }
        refresh();
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (!isPlaying()) stopAll();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        saveState();
        main.removeCallbacksAndMessages(null);
        releasePlayers();
        if (effects != null) effects.release();
        abandonFocus();
        unregisterNoisy();
        if (session != null) session.release();
        instance = null;
        notifyListeners();
        MusicWidget.update(this, null, null, false);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------------------ queue

    private boolean canReadLibrary() {
        String permission = Build.VERSION.SDK_INT >= 33 ? android.Manifest.permission.READ_MEDIA_AUDIO
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** A song opened on its own (from Telegram, a file manager…) is followed by the whole library. */
    void joinLibrary() {
        Song playing = current();
        if (playing == null || !canReadLibrary()) return;
        new Thread(() -> {
            List<Song> library = MusicRepository.load(this);
            main.post(() -> {
                if (current() != playing || library.isEmpty()) return;
                int found = -1;
                for (int i = 0; i < library.size(); i++) if (same(library.get(i), playing)) { found = i; break; }
                List<Song> joined = new ArrayList<>(library);
                if (found < 0) { joined.add(0, playing); found = 0; }
                else joined.set(found, playing); // keep the object being played
                queue.clear();
                queue.addAll(joined);
                index = found;
                refresh();
            });
        }, "gx-music-join").start();
    }

    private static boolean same(Song a, Song b) {
        if (a.uri.equals(b.uri)) return true;
        if (a.path != null && a.path.equals(b.path)) return true;
        return a.title.equalsIgnoreCase(b.title) && b.durationMs > 0 && Math.abs(a.durationMs - b.durationMs) < 1500;
    }

    private boolean restoring;

    /** Widget / headphone play with nothing loaded: continue the last song where it stopped. */
    private void restoreLast() {
        String key = MusicStore.lastKey(this);
        String uri = MusicStore.lastUri(this);
        if (key == null || uri == null || restoring) return;
        restoring = true;
        int position = MusicStore.lastPosition(this);
        new Thread(() -> {
            List<Song> library = canReadLibrary() ? MusicRepository.load(this) : new ArrayList<>();
            int found = -1;
            for (int i = 0; i < library.size(); i++) if (MusicStore.key(library.get(i)).equals(key)) { found = i; break; }
            List<Song> list = library;
            if (found < 0) {
                list = new ArrayList<>(library);
                list.add(0, MusicRepository.fromUri(this, Uri.parse(uri)));
                found = 0;
            }
            List<Song> result = list;
            int start = found;
            main.post(() -> {
                restoring = false;
                queue.clear();
                queue.addAll(result);
                seekWhenReady = position;
                load(start, true, false);
            });
        }, "gx-music-restore").start();
    }

    // ------------------------------------------------------------------ playback

    private MediaPlayer create(Song song) throws Exception {
        MediaPlayer mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        mp.setAudioSessionId(audioSession);
        mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mp.setDataSource(this, song.uri);
        return mp;
    }

    /** @param crossfade keep the current song playing and fade it out while the new one fades in */
    private void load(int position, boolean autoPlay, boolean crossfade) {
        if (position < 0 || position >= queue.size()) return;
        saveState();
        if (crossfade && player != null && prepared) {
            if (fadingOut != null) fadingOut.release();
            fadingOut = player;
            outFrom = gain;
            outGain = gain;
            outStarted = SystemClock.uptimeMillis();
            outLength = Math.max(FADE_MS, MusicStore.crossfade(this));
            crossfading = true;
        } else {
            releasePlayers();
        }
        player = null;
        index = position;
        prepared = false;
        playWhenReady = autoPlay;
        countedPlay = false;
        Song song = queue.get(index);
        try {
            MediaPlayer mp = create(song);
            player = mp;
            mp.setOnPreparedListener(p -> {
                if (p != player) return;
                prepared = true;
                if (seekWhenReady > 0) { p.seekTo(seekWhenReady); }
                seekWhenReady = -1;
                if (playWhenReady) resume(); else refresh();
            });
            mp.setOnCompletionListener(p -> {
                if (p != player) return;
                if (hasNext()) load(index + 1, true, false);
                else { seekTo(0); pause(); }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (p == fadingOut) { finishCrossfade(); return true; }
                if (p == player && hasNext()) main.post(() -> load(index + 1, true, false));
                else main.post(this::pause);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception error) {
            if (hasNext()) main.post(() -> load(index + 1, true, false));
        }
        loadArt(song);
        if (crossfading) startTicker();
        refresh();
    }

    void toggle() { if (isPlaying()) pause(); else resume(); }

    void resume() {
        if (player == null) return;
        if (!prepared) { playWhenReady = true; return; }
        if (!requestFocus()) return;
        playWhenReady = true;
        if (!player.isPlaying()) {
            gain = 0f;
            applyVolume();
            player.start();
            applySpeed();
        }
        if (!countedPlay) { countedPlay = true; MusicStore.played(this, current()); }
        fadeTo(1f, crossfading ? Math.max(FADE_MS, MusicStore.crossfade(this)) : FADE_MS, null);
        registerNoisy();
        startTicker();
        refresh();
    }

    void pause() {
        playWhenReady = false;
        if (player != null && prepared && player.isPlaying()) {
            fadeTo(0f, FADE_MS, () -> {
                if (player != null && prepared && player.isPlaying() && !playWhenReady) player.pause();
                saveState();
                refresh();
            });
        }
        finishCrossfade();
        refresh();
    }

    /** Next song; after the last one, back to the first. */
    void next() {
        if (queue.isEmpty()) return;
        load(hasNext() ? index + 1 : 0, true, false);
    }

    /** Back to the start if more than 3 s in, otherwise the previous song (from the first: the last). */
    void previous() {
        if (queue.isEmpty()) return;
        if (position() > 3000) seekTo(0);
        else load(index > 0 ? index - 1 : queue.size() - 1, true, false);
    }

    void seekTo(int ms) {
        if (player != null && prepared) player.seekTo(Math.max(0, ms));
        refresh();
    }

    void applySpeed() {
        if (player == null || !prepared || !player.isPlaying()) return;
        try { player.setPlaybackParams(player.getPlaybackParams().setSpeed(MusicStore.speed(this))); } catch (Exception ignored) { }
    }

    void stopAll() {
        saveState();
        releasePlayers();
        abandonFocus();
        unregisterNoisy();
        queue.clear();
        index = -1;
        stopForeground(true);
        foreground = false;
        notifyListeners();
        stopSelf();
    }

    private void releasePlayers() {
        if (player != null) { try { player.release(); } catch (Exception ignored) { } player = null; }
        if (fadingOut != null) { try { fadingOut.release(); } catch (Exception ignored) { } fadingOut = null; }
        crossfading = false;
        prepared = false;
    }

    private void finishCrossfade() {
        if (fadingOut != null) { try { fadingOut.release(); } catch (Exception ignored) { } fadingOut = null; }
        crossfading = false;
    }

    private void saveState() {
        Song song = current();
        if (song != null) MusicStore.saveLast(this, song, position());
    }

    // ------------------------------------------------------------------ volume ramps and crossfade timing

    private void fadeTo(float target, int ms, Runnable done) {
        gainTarget = target;
        fadeLength = Math.max(1, ms);
        fadeStarted = SystemClock.uptimeMillis();
        afterFade = done;
        startTicker();
    }

    private float fadeFrom = 1f, outFrom = 1f;
    private long outStarted;
    private int outLength = FADE_MS;

    private void applyVolume() {
        if (player != null) try { player.setVolume(gain * duck, gain * duck); } catch (Exception ignored) { }
        if (fadingOut != null) try { fadingOut.setVolume(outGain * duck, outGain * duck); } catch (Exception ignored) { }
    }

    private boolean tickerRunning;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            boolean busy = false;
            long now = SystemClock.uptimeMillis();
            float t = Math.min(1f, (now - fadeStarted) / (float) fadeLength);
            if (Math.abs(gain - gainTarget) > 0.001f) {
                gain = fadeFrom + (gainTarget - fadeFrom) * t;
                if (t >= 1f) gain = gainTarget;
                busy = true;
            } else if (afterFade != null) {
                Runnable done = afterFade;
                afterFade = null;
                done.run();
            }
            if (fadingOut != null) {
                float out = Math.min(1f, (now - outStarted) / (float) outLength);
                outGain = Math.max(0f, outFrom * (1f - out));
                if (out >= 1f) finishCrossfade(); else busy = true;
            }
            applyVolume();
            // Crossfade: start the next song a few seconds before this one ends.
            int crossfade = MusicStore.crossfade(MusicService.this);
            if (isPlaying() && !crossfading && crossfade > 0 && hasNext()) {
                int duration = duration(), position = position();
                if (duration > crossfade * 3 && duration - position <= crossfade && duration - position > 300) {
                    load(index + 1, true, true);
                }
            }
            if (isPlaying()) busy = true;
            if (busy) main.postDelayed(this, 40); else tickerRunning = false;
        }
    };

    private void startTicker() {
        fadeFrom = gain;
        if (tickerRunning) return;
        tickerRunning = true;
        main.post(ticker);
    }

    // ------------------------------------------------------------------ audio focus and headphones

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) { resumeOnFocus = false; pause(); }
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { resumeOnFocus = isPlaying(); pause(); }
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { duck = 0.25f; applyVolume(); }
        else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            duck = 1f;
            applyVolume();
            if (resumeOnFocus) { resumeOnFocus = false; resume(); }
        }
    };

    private boolean requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setOnAudioFocusChangeListener(focusListener, main)
                        .build();
            }
            return audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) { if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest); }
        else audio.abandonAudioFocus(focusListener);
    }

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pause(); }
    };

    private void registerNoisy() {
        if (noisyRegistered) return;
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, filter);
        noisyRegistered = true;
    }

    private void unregisterNoisy() {
        if (!noisyRegistered) return;
        try { unregisterReceiver(noisy); } catch (Exception ignored) { }
        noisyRegistered = false;
    }

    // ------------------------------------------------------------------ session, notification, widget, listeners

    private void loadArt(Song song) {
        art = null;
        artFor = song;
        new Thread(() -> {
            Bitmap bitmap = MusicRepository.art(this, song, 512);
            main.post(() -> {
                if (artFor != song) return;
                art = bitmap;
                refresh();
            });
        }, "gx-music-art").start();
    }

    private void refresh() {
        Song song = current();
        if (song == null || session == null) { notifyListeners(); return; }
        MediaMetadata.Builder meta = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, song.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, prepared ? duration() : song.durationMs);
        if (art != null) meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(meta.build());
        boolean playing = playWhenReady && (isPlaying() || !prepared);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, position(),
                        playing ? MusicStore.speed(this) : 0f)
                .build());
        Notification notification = buildNotification(song, playing);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (playing) goForeground(notification);
        else {
            if (foreground) { stopForeground(false); foreground = false; }
            if (manager != null) manager.notify(NOTIFICATION_ID, notification);
        }
        MusicWidget.update(this, song, art, playing);
        notifyListeners();
    }

    private void goForeground(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIFICATION_ID, notification);
            foreground = true;
        } catch (Exception ignored) { }
    }

    private Notification buildNotification(Song song, boolean playing) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(song == null ? "Gallery X" : song.title)
                .setContentText(song == null ? "" : song.artist)
                .setLargeIcon(art)
                .setContentIntent(openPlayer())
                .setDeleteIntent(action(this, ACTION_STOP, 4))
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_previous), "السابق", action(this, ACTION_PREVIOUS, 1)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play), playing ? "إيقاف مؤقت" : "تشغيل", action(this, ACTION_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_next), "التالي", action(this, ACTION_NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return builder.build();
    }

    static PendingIntent action(Context context, String name, int code) {
        Intent intent = new Intent(context, MusicService.class).setAction(name);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(context, code, intent, flags)
                : PendingIntent.getService(context, code, intent, flags);
    }

    static PendingIntent openPlayer(Context context) {
        Intent intent = new Intent(context, MusicPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent openPlayer() { return openPlayer(this); }

    private void notifyListeners() {
        for (Listener listener : new ArrayList<>(LISTENERS)) listener.onMusicChanged();
    }
}
