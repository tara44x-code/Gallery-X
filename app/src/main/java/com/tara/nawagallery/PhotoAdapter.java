package com.tara.nawagallery;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;
import java.util.Set;

public final class PhotoAdapter extends BaseAdapter {
    private final Context context;
    private final List<MediaItem> photos;
    private final Set<String> selected;
    private final int columns;

    public PhotoAdapter(Context context, List<MediaItem> photos, Set<String> selected, int columns) {
        this.context = context;
        this.photos = photos;
        this.selected = selected;
        this.columns = columns;
    }

    @Override public int getCount() { return photos.size(); }
    @Override public MediaItem getItem(int position) { return photos.get(position); }
    @Override public long getItemId(int position) { return photos.get(position).id; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        FrameLayout cell;
        Holder holder;
        if (convertView instanceof FrameLayout && convertView.getTag() instanceof Holder) {
            cell = (FrameLayout) convertView;
            holder = (Holder) cell.getTag();
        } else {
            cell = new FrameLayout(context);
            holder = new Holder();
            holder.image = new ImageView(context);
            holder.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            holder.image.setBackgroundColor(ThemePrefs.getStyle(context).surfaceAlt);
            cell.addView(holder.image, new FrameLayout.LayoutParams(-1, -1));

            holder.selection = new TextView(context);
            holder.selection.setText("✓");
            holder.selection.setTextColor(Color.WHITE);
            holder.selection.setTextSize(Ui.textSize(context, 21));
            holder.selection.setGravity(android.view.Gravity.CENTER);
            holder.selection.setBackground(Ui.rounded(ThemePrefs.getStyle(context).primary, 22, context));
            FrameLayout.LayoutParams check = new FrameLayout.LayoutParams(Ui.dp(context, 42), Ui.dp(context, 42),
                    android.view.Gravity.TOP | android.view.Gravity.END);
            check.setMargins(Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6));
            cell.addView(holder.selection, check);

            holder.mediaBadge = new TextView(context);
            holder.mediaBadge.setTextColor(Color.WHITE);
            holder.mediaBadge.setTextSize(Ui.textSize(context, 12));
            holder.mediaBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            holder.mediaBadge.setGravity(android.view.Gravity.CENTER);
            holder.mediaBadge.setPadding(Ui.dp(context, 8), Ui.dp(context, 3), Ui.dp(context, 8), Ui.dp(context, 3));
            holder.mediaBadge.setBackground(Ui.rounded(0xCC000000, 12, context));
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, Ui.dp(context, 30),
                    android.view.Gravity.BOTTOM | android.view.Gravity.START);
            badge.setMargins(Ui.dp(context, 7), Ui.dp(context, 7), Ui.dp(context, 7), Ui.dp(context, 7));
            cell.addView(holder.mediaBadge, badge);
            cell.setTag(holder);
        }
        int available = Math.max(parent.getWidth(), context.getResources().getDisplayMetrics().widthPixels);
        int size = Math.max(available / columns, Ui.dp(context, 76));
        cell.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, size));
        MediaItem item = getItem(position);
        boolean checked = selected.contains(item.uri.toString());
        holder.selection.setVisibility(checked ? View.VISIBLE : View.GONE);
        holder.mediaBadge.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);
        if (item.isVideo()) holder.mediaBadge.setText("▶  " + duration(item.durationMs));
        cell.setForeground(checked ? Ui.rounded(0x447B2CFF, 0, context) : null);
        holder.image.setImageResource(R.drawable.ic_photo_placeholder);
        ThumbnailLoader.get(context).load(item, holder.image, size);
        return cell;
    }

    private static final class Holder {
        ImageView image;
        TextView selection;
        TextView mediaBadge;
    }

    private String duration(long durationMs) {
        long total = Math.max(0L, durationMs / 1000L);
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;
        return hours > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(java.util.Locale.US, "%d:%02d", minutes, seconds);
    }
}
