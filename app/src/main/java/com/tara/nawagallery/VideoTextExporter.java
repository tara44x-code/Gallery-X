package com.tara.nawagallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.TextureOverlay;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Burns the text layers into a video with Media3 Transformer: the layers are drawn once into a
 * picture the size of the video frame and laid over every frame, keeping the original sound.
 */
final class VideoTextExporter {
    interface Callback {
        void onProgress(int percent);
        void onDone(File output);
        void onError(String message);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Transformer transformer;
    private boolean finished;

    /** Displayed width and height of the video (rotation applied). */
    private static int[] videoSize(Context context, Uri video) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, video);
            int w = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rotation = rot == null ? 0 : Integer.parseInt(rot);
            return rotation % 180 != 0 ? new int[]{h, w} : new int[]{w, h};
        } catch (Exception e) {
            return new int[]{1280, 720};
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    /** Must be called on the main thread. */
    void start(Context context, Uri video, List<TextLayer> layers, File output, Callback callback) {
        Context app = context.getApplicationContext();
        List<TextLayer> snapshot = new ArrayList<>(layers);
        int[] guess = videoSize(app, video);
        BitmapOverlay overlay = new BitmapOverlay() {
            private int w = guess[0], h = guess[1];
            private Bitmap cached;

            // Called by Media3 with the real frame size (after rotation) before drawing.
            public void configure(Size videoSize) {
                if (videoSize.getWidth() > 0 && videoSize.getHeight() > 0) { w = videoSize.getWidth(); h = videoSize.getHeight(); }
            }

            @Override public Bitmap getBitmap(long presentationTimeUs) {
                if (cached == null || cached.getWidth() != w || cached.getHeight() != h) {
                    cached = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    LayerRenderer.drawAll(app, new Canvas(cached), snapshot, w, h);
                }
                return cached;
            }
        };
        Effects effects = new Effects(ImmutableList.<AudioProcessor>of(),
                ImmutableList.<Effect>of(new OverlayEffect(ImmutableList.<TextureOverlay>of(overlay))));
        EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(video)).setEffects(effects).build();
        transformer = new Transformer.Builder(app)
                .addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition composition, ExportResult result) {
                        finished = true;
                        callback.onDone(output);
                    }

                    @Override public void onError(Composition composition, ExportResult result, ExportException error) {
                        finished = true;
                        output.delete();
                        callback.onError(error.getMessage() == null ? "تعذر حفظ الفيديو" : error.getMessage());
                    }
                })
                .build();
        transformer.start(item, output.getAbsolutePath());
        ProgressHolder holder = new ProgressHolder();
        main.post(new Runnable() {
            @Override public void run() {
                if (finished || transformer == null) return;
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) callback.onProgress(holder.progress);
                main.postDelayed(this, 400);
            }
        });
    }

    void cancel() {
        finished = true;
        if (transformer != null) transformer.cancel();
    }
}
