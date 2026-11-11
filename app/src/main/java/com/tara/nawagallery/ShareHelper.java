package com.tara.nawagallery;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * One place for every share button. Each file is sent with its exact type (video/mp4, image/jpeg…)
 * so receiving apps treat videos as videos. Before this, a video could go out as "image/*" and
 * apps like Telegram turned it into a .jpg photo.
 */
final class ShareHelper {
    private ShareHelper() {}

    static void share(Activity activity, List<MediaItem> items, String title) {
        if (items == null || items.isEmpty()) return;
        ArrayList<Uri> uris = new ArrayList<>();
        LinkedHashSet<String> types = new LinkedHashSet<>();
        boolean allVideo = true, allImage = true;
        for (MediaItem item : items) {
            String type = resolveType(activity, item);
            uris.add(item.uri);
            types.add(type);
            if (!MediaTypes.isVideo(type)) allVideo = false;
            if (!MediaTypes.isImage(type)) allImage = false;
        }
        String intentType;
        if (types.size() == 1 && MediaTypes.isSpecific(types.iterator().next())) intentType = types.iterator().next();
        else if (allVideo) intentType = "video/*";
        else if (allImage) intentType = "image/*";
        else intentType = "*/*";

        Intent share;
        if (uris.size() == 1) {
            share = new Intent(Intent.ACTION_SEND);
            share.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            share = new Intent(Intent.ACTION_SEND_MULTIPLE);
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        share.setType(intentType);
        ClipData clip = new ClipData(new ClipDescription(title, types.toArray(new String[0])), new ClipData.Item(uris.get(0)));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        share.setClipData(clip);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(share, title));
    }

    /** Exact type: what the system reports, else what we stored, else the file's own bytes and name. */
    static String resolveType(Context context, MediaItem item) {
        String reported = null;
        try { reported = context.getContentResolver().getType(item.uri); } catch (Exception ignored) { }
        if (MediaTypes.isSpecific(reported)) return reported;
        if (MediaTypes.isSpecific(item.mimeType)) return item.mimeType;
        byte[] head = new byte[256];
        int length = 0;
        try (InputStream in = context.getContentResolver().openInputStream(item.uri)) {
            if (in != null) {
                int read;
                while (length < head.length && (read = in.read(head, length, head.length - length)) > 0) length += read;
            }
        } catch (Exception ignored) { }
        String hint = item.mimeType != null ? item.mimeType : reported;
        String best = MediaTypes.best(hint, head, length, item.name);
        return MediaTypes.UNKNOWN.equals(best) ? (item.isVideo() ? "video/*" : "*/*") : best;
    }
}
