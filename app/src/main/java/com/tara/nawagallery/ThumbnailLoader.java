package com.tara.nawagallery;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.InputStream;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ThumbnailLoader {
    private static ThumbnailLoader instance;
    private final LruCache<String, Bitmap> cache;
    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final ThreadPoolExecutor executor;
    private final Map<ImageView, Request> requests =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final ContentResolver resolver;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ThumbnailLoader(Context context) {
        resolver = context.getApplicationContext().getContentResolver();
        executor = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, queue);
        executor.prestartAllCoreThreads();
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        cache = new LruCache<String, Bitmap>(Math.max(8 * 1024, maxKb / 8)) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public static synchronized ThumbnailLoader get(Context context) {
        if (instance == null) instance = new ThumbnailLoader(context);
        return instance;
    }

    public void load(MediaItem item, ImageView target, int sizePx) {
        String key = item.uri + ":" + sizePx;
        target.setTag(key);
        target.animate().cancel();
        target.setAlpha(1f);
        Request previous;
        synchronized (requests) { previous = requests.remove(target); }
        if (previous != null) {
            previous.cancel();
            executor.remove(previous);
        }
        Bitmap cached = cache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageResource(R.drawable.ic_photo_placeholder);
        Request request = new Request(item, target, sizePx, key);
        synchronized (requests) { requests.put(target, request); }
        executor.execute(request);
    }

    private final class Request implements Runnable {
        final MediaItem item;
        final ImageView target;
        final int sizePx;
        final String key;
        final CancellationSignal signal = new CancellationSignal();
        int attempt;

        Request(MediaItem item, ImageView target, int sizePx, String key) {
            this.item = item;
            this.target = target;
            this.sizePx = sizePx;
            this.key = key;
        }

        void cancel() { signal.cancel(); }

        @Override public void run() {
            if (!isCurrent(this)) return;
            Bitmap bitmap = null;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    bitmap = resolver.loadThumbnail(item.uri, new Size(sizePx, sizePx), signal);
                } else {
                    bitmap = legacyThumbnail(item);
                }
            } catch (Exception ignored) { }
            if (bitmap == null && isCurrent(this)) bitmap = decodeFallback(item.uri, sizePx);
            if (bitmap == null && isCurrent(this)) bitmap = decodeFileDescriptor(item.uri, sizePx);
            if (bitmap == null && isCurrent(this)) bitmap = decodeTypedDescriptor(item.uri, sizePx);
            if (bitmap == null && isCurrent(this)) bitmap = decodeFilePath(item.filePath, sizePx);
            if (bitmap == null && isCurrent(this)) {
                bitmap = legacyThumbnail(item);
            }
            Drawable decodedDrawable = bitmap == null && isCurrent(this)
                    ? decodeDrawableFallback(item.uri, sizePx) : null;
            if (bitmap != null) {
                cache.put(key, bitmap);
                Bitmap delivered = bitmap;
                mainHandler.post(() -> {
                    if (!isCurrent(this)) return;
                    target.setAlpha(0f);
                    target.setImageBitmap(delivered);
                    target.animate().alpha(1f).setDuration(110).start();
                    finish(this);
                });
            } else if (decodedDrawable != null) {
                mainHandler.post(() -> {
                    if (!isCurrent(this)) return;
                    target.setImageDrawable(decodedDrawable);
                    finish(this);
                });
            } else {
                mainHandler.post(() -> {
                    if (!isCurrent(this)) return;
                    if (attempt < 2) {
                        attempt++;
                        mainHandler.postDelayed(() -> {
                            if (isCurrent(this)) executor.execute(this);
                        }, 350L * attempt);
                    } else {
                        finish(this);
                    }
                });
            }
        }
    }

    private boolean isCurrent(Request request) {
        if (request.signal.isCanceled() || !request.key.equals(request.target.getTag())) return false;
        synchronized (requests) { return requests.get(request.target) == request; }
    }

    private Bitmap legacyThumbnail(MediaItem item) {
        try {
            if (item.isVideo()) {
                return MediaStore.Video.Thumbnails.getThumbnail(resolver, item.id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null);
            }
            return MediaStore.Images.Thumbnails.getThumbnail(resolver, item.id,
                    MediaStore.Images.Thumbnails.MINI_KIND, null);
        } catch (Exception ignored) { return null; }
    }

    private void finish(Request request) {
        synchronized (requests) {
            if (requests.get(request.target) == request) requests.remove(request.target);
        }
    }

    private Bitmap decodeFallback(Uri uri, int sizePx) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int sourceMax = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    int sample = 1;
                    while (sourceMax / sample > sizePx * 2) sample *= 2;
                    decoder.setTargetSampleSize(sample);
                });
            }

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = resolver.openInputStream(uri)) {
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            int sample = 1;
            int sourceMax = Math.max(bounds.outWidth, bounds.outHeight);
            while (sourceMax / sample > sizePx * 2) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream stream = resolver.openInputStream(uri)) {
                return BitmapFactory.decodeStream(stream, null, options);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private Bitmap decodeFileDescriptor(Uri uri, int sizePx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
                if (descriptor == null) return null;
                BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, bounds);
            }
            int sample = 1;
            int sourceMax = Math.max(bounds.outWidth, bounds.outHeight);
            while (sourceMax > 0 && sourceMax / sample > sizePx * 2) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, sample);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
                if (descriptor == null) return null;
                return BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, options);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private Bitmap decodeTypedDescriptor(Uri uri, int sizePx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (AssetFileDescriptor descriptor = resolver.openTypedAssetFileDescriptor(uri, "image/*", null)) {
                if (descriptor == null) return null;
                BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, bounds);
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateSample(bounds.outWidth, bounds.outHeight, sizePx);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (AssetFileDescriptor descriptor = resolver.openTypedAssetFileDescriptor(uri, "image/*", null)) {
                if (descriptor == null) return null;
                return BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, options);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private Bitmap decodeFilePath(String path, int sizePx) {
        if (path == null || path.trim().isEmpty()) return null;
        try {
            File file = new File(path);
            if (!file.isFile() || file.length() <= 0L) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateSample(bounds.outWidth, bounds.outHeight, sizePx);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(path, options);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int calculateSample(int width, int height, int sizePx) {
        int sample = 1;
        int sourceMax = Math.max(width, height);
        while (sourceMax > 0 && sourceMax / sample > sizePx * 2) sample *= 2;
        return Math.max(1, sample);
    }

    private Drawable decodeDrawableFallback(Uri uri, int sizePx) {
        if (Build.VERSION.SDK_INT < 28) return null;
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
            return ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                int sourceMax = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                int sample = 1;
                while (sourceMax / sample > sizePx * 2) sample *= 2;
                decoder.setTargetSampleSize(sample);
            });
        } catch (Exception ignored) {
            return null;
        }
    }

}
