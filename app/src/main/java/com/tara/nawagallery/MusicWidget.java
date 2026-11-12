package com.tara.nawagallery;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.widget.RemoteViews;

/** Home-screen music widget: cover, title, artist and Previous / Play-Pause / Next. */
public class MusicWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        MusicService service = MusicService.get();
        if (service != null && service.current() != null) update(context, service.current(), service.art(), service.isPlaying());
        else update(context, null, null, false);
    }

    /** Called by the music service whenever the song or play state changes. */
    static void update(Context context, Song song, Bitmap art, boolean playing) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, MusicWidget.class));
        if (ids == null || ids.length == 0) return;
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_music);
        String title = song != null ? song.title : MusicStore.lastTitle(context);
        String artist = song != null ? song.artist : MusicStore.lastArtist(context);
        views.setTextViewText(R.id.widget_title, title == null ? context.getString(R.string.widget_idle) : title);
        views.setTextViewText(R.id.widget_artist, artist == null || artist.isEmpty() ? "Gallery X" : artist);
        if (art != null) views.setImageViewBitmap(R.id.widget_art, Bitmap.createScaledBitmap(art, 160, 160, true));
        else views.setImageViewResource(R.id.widget_art, R.drawable.nawa_launcher);
        views.setImageViewResource(R.id.widget_play, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        views.setOnClickPendingIntent(R.id.widget_previous, MusicService.action(context, MusicService.ACTION_PREVIOUS, 11));
        views.setOnClickPendingIntent(R.id.widget_play, MusicService.action(context, MusicService.ACTION_TOGGLE, 12));
        views.setOnClickPendingIntent(R.id.widget_next, MusicService.action(context, MusicService.ACTION_NEXT, 13));
        PendingIntent open = MusicService.openPlayer(context);
        views.setOnClickPendingIntent(R.id.widget_root, open);
        manager.updateAppWidget(ids, views);
    }
}
