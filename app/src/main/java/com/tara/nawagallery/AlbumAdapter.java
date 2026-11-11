package com.tara.nawagallery;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public final class AlbumAdapter extends BaseAdapter {
    private final Context context;
    private final List<Album> albums;

    public AlbumAdapter(Context context, List<Album> albums) {
        this.context = context;
        this.albums = albums;
    }

    @Override public int getCount() { return albums.size(); }
    @Override public Album getItem(int position) { return albums.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            FrameLayout root = new FrameLayout(context);
            root.setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4));
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(ThemePrefs.getStyle(context).surfaceAlt);
            root.addView(image, new FrameLayout.LayoutParams(-1, -1));

            View shade = new View(context);
            GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Color.TRANSPARENT, 0xCC000000});
            shade.setBackground(gradient);
            FrameLayout.LayoutParams shadeLp = new FrameLayout.LayoutParams(-1, Ui.dp(context, 76), Gravity.BOTTOM);
            root.addView(shade, shadeLp);

            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setGravity(Gravity.BOTTOM | Gravity.START);
            labels.setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), Ui.dp(context, 12));
            TextView title = new TextView(context);
            title.setTextColor(Color.WHITE);
            title.setTextSize(Ui.textSize(context, 19));
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            title.setShadowLayer(4f, 0f, 1f, Color.BLACK);
            TextView count = new TextView(context);
            count.setTextColor(0xFFD7D7D7);
            count.setTextSize(Ui.textSize(context, 15));
            count.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            count.setShadowLayer(4f, 0f, 1f, Color.BLACK);
            labels.addView(title);
            labels.addView(count);
            root.addView(labels, new FrameLayout.LayoutParams(-1, -1));
            convertView = root;
            holder = new Holder(image, title, count);
            convertView.setTag(holder);
        } else {
            holder = (Holder) convertView.getTag();
        }
        int width = Math.max(parent.getWidth() / 2, Ui.dp(context, 160));
        convertView.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, width));
        Album album = getItem(position);
        holder.title.setText(album.name);
        holder.count.setText(context.getString(R.string.photos_count, album.items.size()));
        holder.image.setImageResource(R.drawable.ic_photo_placeholder);
        if (album.cover() != null) ThumbnailLoader.get(context).load(album.cover(), holder.image, width);
        return convertView;
    }

    private static final class Holder {
        final ImageView image;
        final TextView title;
        final TextView count;
        Holder(ImageView image, TextView title, TextView count) {
            this.image = image; this.title = title; this.count = count;
        }
    }
}
