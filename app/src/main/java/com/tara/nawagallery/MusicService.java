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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays music in the background: keeps playing with the app closed or the screen off, and is
 * controlled from the notification, the lock screen, headphones and Bluetooth (MediaSession).
 * Pauses on headphone unplug and when another app takes the audio (calls, other players).
 */
public class MusicService extends Service {
    static final String ACTION_QUEUE = "gx.music.QUEUE";
    static final String ACTION_TOGGLE = "gx.music.TOGGLE";
    static final String ACTION_NEXT = "gx.music.NEXT";
    static final String ACTION_PREVIOUS = "gx.music.PREVIOUS";
    static final String ACTION_STOP = "gx.music.STOP";
    private static final String CHANNEL = "gx_music";
    private static final int NOTIFICATION_ID = 7310;

    interface Listener { void onMusicChanged(); }

    private static MusicService instance;
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static List<Song> pendingQueue;
    private static int pendingIndex;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Song> queue = new ArrayList<>();
    private int index = -1;
    private MediaPlayer player;
    private boolean prepared;
    private boolean playWhenReady;
    private boolean resumeOnFocus;
    private boolean noisyRegistered;
    private boolean foreground;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private Bitmap art;
    private int artFor = -1;

    // ------------------------------------------------------------------ static API for activities

    /** Starts playing {@code songs} from {@code start}. */
    static void play(Context context, List<Song> songs, int start) {
        if (songs == null || songs.isEmpty()) return;
        pendingQueue = new ArrayList<>(songs);
        pendingIndex = Math.max(0, Math.min(start, songs.size() - 1));
        Intent intent = new Intent(context, MusicService.class).setAction(ACTION_QUEUE);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static MusicService get() { return instance; }

    static void addListener(Listener listener) { if (!LISTENERS.contains(listener)) LISTENERS.add(listener); }
    static void removeListener(Listener listener) { LISTENERS.remove(listener); }

    Song current() { return index >= 0 && index < queue.size() ? queue.get(index) : null; }
    boolean isPlaying() { return player != null && prepared && player.isPlaying(); }
    boolean hasNext() { return index + 1 < queue.size(); }
    int position() { try { return player != null && prepared ? player.getCurrentPosition() : 0; } catch (Exception e) { return 0; } }
    int duration() { try { return player != null && prepared ? player.getDuration() : 0; } catch (Exception e) { return 0; } }

    // ------------------------------------------------------------------ lifecycle

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "تشغيل الموسيقى", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        session = new MediaSession(this, "GalleryXMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
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
        // Started through startForegroundService / getForegroundService: promote right away.
        goForeground();
        String action = intent == null ? null : intent.getAction();
        if (ACTION_QUEUE.equals(action) && pendingQueue != null) {
            queue.clear();
            queue.addAll(pendingQueue);
            pendingQueue = null;
            load(pendingIndex, true);
        } else if (ACTION_TOGGLE.equals(action)) toggle();
        else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREVIOUS.equals(action)) previous();
        else if (ACTION_STOP.equals(action)) { stopAll(); return START_NOT_STICKY; }
        if (current() == null) { stopAll(); return START_NOT_STICKY; }
        refresh();
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (!isPlaying()) stopAll();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        releasePlayer();
        abandonFocus();
        unregisterNoisy();
        if (session != null) session.release();
        instance = null;
        notifyListeners();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------------------ playback

    private void load(int position, boolean autoPlay) {
        if (position < 0 || position >= queue.size()) return;
        index = position;
        releasePlayer();
        prepared = false;
        playWhenReady = autoPlay;
        Song song = queue.get(index);
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(this, song.uri);
            mp.setOnPreparedListener(p -> {
                if (p != player) return;
                prepared = true;
                if (playWhenReady) resume(); else refresh();
            });
            mp.setOnCompletionListener(p -> {
                if (p != player) return;
                if (hasNext()) load(index + 1, true);
                else { seekTo(0); pause(); }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (p == player && hasNext()) main.post(() -> load(index + 1, true));
                else main.post(this::pause);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception error) {
            if (hasNext()) main.post(() -> load(index + 1, true));
        }
        loadArt(song);
        refresh();
    }

    void toggle() { if (isPlaying()) pause(); else resume(); }

    void resume() {
        if (player == null) return;
        if (!prepared) { playWhenReady = true; return; }
        if (!requestFocus()) return;
        player.setVolume(1f, 1f);
        player.start();
        registerNoisy();
        refresh();
    }

    void pause() {
        playWhenReady = false;
        if (player != null && prepared && player.isPlaying()) player.pause();
        refresh();
    }

    void next() { if (hasNext()) load(index + 1, true); }

    /** Back to the start if more than 3 s in, otherwise the previous song. */
    void previous() {
        if (position() > 3000 || index == 0) seekTo(0);
        else load(index - 1, true);
    }

    void seekTo(int ms) {
        if (player != null && prepared) player.seekTo(Math.max(0, ms));
        refresh();
    }

    void stopAll() {
        releasePlayer();
        abandonFocus();
        unregisterNoisy();
        queue.clear();
        index = -1;
        stopForeground(true);
        foreground = false;
        notifyListeners();
        stopSelf();
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        prepared = false;
    }

    // ------------------------------------------------------------------ audio focus and headphones

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS) { resumeOnFocus = false; pause(); }
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { resumeOnFocus = isPlaying(); pause(); }
        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { if (player != null) player.setVolume(0.25f, 0.25f); }
        else if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (player != null) player.setVolume(1f, 1f);
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

    // ------------------------------------------------------------------ session, notification, listeners

    private void loadArt(Song song) {
        int forIndex = index;
        art = null;
        artFor = forIndex;
        new Thread(() -> {
            Bitmap bitmap = MusicRepository.art(this, song, 512);
            main.post(() -> {
                if (artFor != forIndex || current() != song) return;
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
        boolean playing = isPlaying() || (playWhenReady && !prepared);
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, position(), playing ? 1f : 0f)
                .build());
        Notification notification = buildNotification(song, playing);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (playing) {
            goForeground(notification);
        } else {
            if (foreground) { stopForeground(false); foreground = false; }
            if (manager != null) manager.notify(NOTIFICATION_ID, notification);
        }
        notifyListeners();
    }

    private void goForeground() {
        Song song = current();
        goForeground(buildNotification(song, isPlaying()));
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
                .setDeleteIntent(action(ACTION_STOP, 4))
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_previous), "السابق", action(ACTION_PREVIOUS, 1)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play), playing ? "إيقاف مؤقت" : "تشغيل", action(ACTION_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_next), "التالي", action(ACTION_NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        return builder.build();
    }

    private PendingIntent action(String name, int code) {
        Intent intent = new Intent(this, MusicService.class).setAction(name);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(this, code, intent, flags)
                : PendingIntent.getService(this, code, intent, flags);
    }

    private PendingIntent openPlayer() {
        Intent intent = new Intent(this, MusicPlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void notifyListeners() {
        for (Listener listener : new ArrayList<>(LISTENERS)) listener.onMusicChanged();
    }
}
