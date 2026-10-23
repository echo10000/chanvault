package dev.chanvault.reader;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline gallery with a board / thread / recent switcher; all reads remain private. */
final class SavedLibrary {
    private static final int TEXT = 0xfff4f5ff, SUB = 0xffaab3c8, ACCENT = 0xffaab7ff;
    private static final int CARD = 0xff202635;
    private static final ExecutorService THUMBS = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bmp) { return bmp.getByteCount(); }
    };

    private final MainActivity host;
    private final LinearLayout container;
    private final List<JSONObject> items;
    private final Runnable reload;
    private final File vaultDir;
    private int grouping;

    SavedLibrary(MainActivity host, LinearLayout container, List<JSONObject> source, Runnable reload) {
        this.host = host;
        this.container = container;
        this.items = new ArrayList<>(source);
        this.reload = reload;
        this.vaultDir = new File(host.getFilesDir(), "vault");
        this.grouping = host.getSharedPreferences("saved_view", 0).getInt("group", 0);
        Collections.sort(items, (a, b) -> Long.compare(b.optLong("savedAt"), a.optLong("savedAt")));
    }

    private int dp(int px) { return (int)(host.getResources().getDisplayMetrics().density * px + .5f); }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(host);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    void draw() {
        container.removeAllViews();
        TextView heading = text("Your offline collection", 22, TEXT, true);
        heading.setPadding(dp(2), dp(6), dp(2), dp(4));
        container.addView(heading);
        container.addView(text(items.size() + " saved files · Private to this device", 13, SUB, false));
        addGap(container, 12);

        LinearLayout chooser = new LinearLayout(host);
        String[] options = {"By thread", "By board", "Recent"};
        for (int i = 0; i < options.length; i++) {
            final int target = i;
            TextView control = text(options[i], 13, i == grouping ? 0xff101421 : TEXT, true);
            control.setGravity(Gravity.CENTER);
            control.setBackground(bg(i == grouping ? ACCENT : 0xff293044, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1);
            if (i != options.length - 1) lp.rightMargin = dp(7);
            chooser.addView(control, lp);
            control.setOnClickListener(v -> {
                grouping = target;
                host.getSharedPreferences("saved_view", 0).edit().putInt("group", grouping).apply();
                draw();
            });
        }
        container.addView(chooser);
        addGap(container, 10);
        if (!items.isEmpty()) {
            TextView watchAll = text("▶  Swipe through all saved media", 14, 0xff101421, true);
            watchAll.setGravity(Gravity.CENTER);
            watchAll.setBackground(bg(ACCENT, 12));
            container.addView(watchAll, new LinearLayout.LayoutParams(-1, dp(48)));
            watchAll.setOnClickListener(v -> {
                Intent intent = new Intent(host, SavedFeedActivity.class);
                intent.putExtra("group", "recent");
                intent.putExtra("file", items.get(0).optString("file"));
                host.startActivity(intent);
            });
        }
        addGap(container, 16);

        if (items.isEmpty()) {
            TextView message = text("No saved media yet.\n\nBrowse a thread, preview a video, then tap Save to vault.", 15, SUB, false);
            message.setGravity(Gravity.CENTER);
            message.setPadding(dp(20), dp(35), dp(20), dp(35));
            message.setBackground(bg(CARD, 18));
            container.addView(message);
            return;
        }

        Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
        for (JSONObject item : items) {
            String board = item.optString("board", "?");
            long thread = item.optLong("thread", 0);
            String key = grouping == 0 ? "/" + board + "/  ·  Thread #" + thread :
                    grouping == 1 ? "/" + board + "/  ·  Board" : "Recently saved";
            if (!groups.containsKey(key)) groups.put(key, new ArrayList<>());
            groups.get(key).add(item);
        }
        for (Map.Entry<String, List<JSONObject>> group : groups.entrySet()) {
            LinearLayout sectionHeader = new LinearLayout(host);
            sectionHeader.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(group.getKey(), 16, TEXT, true);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            sectionHeader.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            sectionHeader.addView(text(group.getValue().size() + " files", 12, ACCENT, false));
            container.addView(sectionHeader);
            addGap(container, 8);

            HorizontalScrollView horizontal = new HorizontalScrollView(host);
            horizontal.setHorizontalScrollBarEnabled(false);
            LinearLayout strip = new LinearLayout(host);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(dp(1), dp(2), dp(1), dp(6));
            for (JSONObject row : group.getValue()) {
                addMediaTile(strip, row);
            }
            horizontal.addView(strip);
            container.addView(horizontal);
            addGap(container, 18);
        }
        TextView hint = text("Tap to swipe through this group · Long-press to delete", 12, SUB, false);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(dp(4), dp(8), dp(4), dp(24));
        container.addView(hint);
    }

    private void addMediaTile(LinearLayout strip, JSONObject row) {
        String fileName = row.optString("file", "");
        if (!fileName.matches("[a-f0-9-]{36}\\.(webm|mp4|gif|jpg|jpeg|png)")) return;
        File file = new File(vaultDir, fileName);
        if (!file.isFile()) return;
        boolean isVideo = fileName.endsWith(".mp4") || fileName.endsWith(".webm");
        LinearLayout tile = new LinearLayout(host);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(6), dp(6), dp(6), dp(8));
        tile.setBackground(bg(CARD, 14));
        LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(dp(141), dp(183));
        tileLp.rightMargin = dp(10);
        strip.addView(tile, tileLp);

        FrameLayout imageHolder = new FrameLayout(host);
        imageHolder.setBackground(bg(0xff323b50, 11));
        tile.addView(imageHolder, new LinearLayout.LayoutParams(-1, dp(131)));
        ImageView image = new ImageView(host);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageHolder.addView(image, new FrameLayout.LayoutParams(-1, -1));
        TextView type = text(isVideo ? "VIDEO" : (fileName.endsWith(".gif") ? "GIF" : "IMAGE"),
                10, TEXT, true);
        type.setPadding(dp(7), dp(4), dp(7), dp(4));
        type.setBackground(bg(0xd9232b40, 7));
        FrameLayout.LayoutParams typeLp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.BOTTOM | Gravity.LEFT);
        typeLp.leftMargin = dp(6);
        typeLp.bottomMargin = dp(6);
        imageHolder.addView(type, typeLp);

        TextView label = text(row.optString("title", "Saved media"), 11, TEXT, false);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(3), dp(5), dp(3), 0);
        tile.addView(label);
        imageHolder.setContentDescription("Open " + row.optString("title", "saved media"));
        tile.setOnClickListener(v -> {
            Intent intent = new Intent(host, SavedFeedActivity.class);
            intent.putExtra("file", fileName);
            intent.putExtra("group", grouping == 0 ? "thread" : grouping == 1 ? "board" : "recent");
            if (grouping != 2) intent.putExtra("board", row.optString("board"));
            if (grouping == 0) intent.putExtra("thread", row.optLong("thread"));
            host.startActivity(intent);
        });
        tile.setOnLongClickListener(v -> {
            new AlertDialog.Builder(host)
                    .setTitle("Delete saved media?")
                    .setMessage(row.optString("title") + "\n\nThis only removes the private local copy.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete", (dialog, which) -> {
                        THUMBS.execute(() -> {
                            try {
                                new MainActivity.Vault(vaultDir).delete(row.optString("id"));
                                host.runOnUiThread(reload);
                            } catch (Exception e) {
                                host.runOnUiThread(() ->
                                        android.widget.Toast.makeText(host, "Delete failed", 0).show());
                            }
                        });
                    }).show();
            return true;
        });
        loadThumbnail(file, image, isVideo);
    }

    private void loadThumbnail(File file, ImageView image, boolean video) {
        String key = file.getAbsolutePath() + ":" + file.lastModified();
        image.setTag(key);
        Bitmap cached = CACHE.get(key);
        if (cached != null) { image.setImageBitmap(cached); return; }
        THUMBS.execute(() -> {
            Bitmap bmp = null;
            try {
                if (video) {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    try {
                        retriever.setDataSource(file.getAbsolutePath());
                        bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    } finally { retriever.release(); }
                } else {
                    BitmapFactory.Options opt = new BitmapFactory.Options();
                    opt.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(file.getAbsolutePath(), opt);
                    opt.inSampleSize = 1;
                    while ((opt.outHeight / opt.inSampleSize) > 512 ||
                           (opt.outWidth / opt.inSampleSize) > 512) opt.inSampleSize *= 2;
                    opt.inJustDecodeBounds = false;
                    bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opt);
                }
                if (bmp != null) CACHE.put(key, bmp);
            } catch (Exception ignored) {}
            final Bitmap result = bmp;
            image.post(() -> {
                if (result != null && key.equals(image.getTag())) image.setImageBitmap(result);
            });
        });
    }

    private void addGap(LinearLayout layout, int dp) {
        layout.addView(new View(host), new LinearLayout.LayoutParams(1, dp(dp)));
    }
}
