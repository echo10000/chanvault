package dev.chanvault.reader;

import android.app.AlertDialog;
import android.content.Intent;
import android.widget.EditText;
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
import android.widget.Button;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline gallery with a board / thread / recent switcher; all reads remain private. */
final class SavedLibrary {
    private static int TEXT = 0xfff8f5e6, SUB = 0xffb6ceb9, ACCENT = 0xffb5e077;
    private static int CARD = 0xff203629;
    private final ThemePalette theme;
    private static final ExecutorService THUMBS = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bmp) { return bmp.getByteCount(); }
    };

    private final MainActivity host;
    private final LinearLayout container;
    private final List<JSONObject> items;
    private final Runnable reload;
    private final File vaultDir;
    private final android.content.SharedPreferences renamePrefs;
    private int grouping;
    private boolean selecting = false, deleting = false;
    private final Set<String> selectedIds = new LinkedHashSet<>();

    SavedLibrary(MainActivity host, LinearLayout container, List<JSONObject> source, Runnable reload) {
        this.theme = ThemePalette.get(host);
        TEXT = theme.text;
        SUB = theme.muted;
        ACCENT = theme.accent;
        CARD = theme.card;
        this.host = host;
        this.container = container;
        this.items = new ArrayList<>(source);
        this.reload = reload;
        this.vaultDir = new File(host.getFilesDir(), "vault");
        this.renamePrefs = host.getSharedPreferences("saved_thread_titles_v1", 0);
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

    // Keep the user's place when selecting files in a long saved-media library.
    private void redrawPreservingScroll() {
        android.widget.ScrollView scroll = container.getParent() instanceof android.widget.ScrollView
                ? (android.widget.ScrollView) container.getParent() : null;
        int y = scroll == null ? 0 : scroll.getScrollY();
        draw();
        if (scroll != null) scroll.post(() -> scroll.scrollTo(0, y));
    }

    void draw() {
        container.removeAllViews();
        TextView heading = text("Your offline collection", 22, TEXT, true);
        heading.setPadding(dp(2), dp(6), dp(2), dp(4));
        container.addView(heading);
        container.addView(text(items.size() + " saved files · Private to this device", 13, SUB, false));
        addGap(container, 9);
        LinearLayout manage = new LinearLayout(host);
        manage.setGravity(Gravity.CENTER_VERTICAL);
        TextView selectMode = text(selecting ? "Cancel selection" : "Select files", 14, selecting ? TEXT : theme.onAccent, true);
        selectMode.setGravity(Gravity.CENTER);
        selectMode.setPadding(dp(12), 0, dp(12), 0);
        selectMode.setBackground(bg(selecting ? theme.chip : ACCENT, 12));
        LinearLayout.LayoutParams selectLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        manage.addView(selectMode, selectLp);
        selectMode.setOnClickListener(v -> {
            selecting = !selecting;
            selectedIds.clear();
            redrawPreservingScroll();
        });
        if (selecting) {
            TextView all = text(selectedIds.size() == items.size() ? "Clear all" : "Select all",
                    14, TEXT, true);
            all.setGravity(Gravity.CENTER);
            all.setBackground(bg(theme.chip, 12));
            LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(0, dp(46), 1);
            allLp.leftMargin = dp(9);
            manage.addView(all, allLp);
            all.setOnClickListener(v -> {
                if (selectedIds.size() == items.size()) selectedIds.clear();
                else {
                    selectedIds.clear();
                    for (JSONObject row : items) selectedIds.add(row.optString("id"));
                }
                redrawPreservingScroll();
            });
        }
        container.addView(manage);
        if (selecting) {
            LinearLayout selectedActions = new LinearLayout(host);
            selectedActions.setGravity(Gravity.CENTER_VERTICAL);
            TextView count = text(selectedIds.size() + " selected", 13, SUB, true);
            selectedActions.addView(count, new LinearLayout.LayoutParams(0, dp(49), 1));
            TextView delete = text("Delete selected (" + selectedIds.size() + ")", 14,
                    selectedIds.isEmpty() ? SUB : 0xffffe2e2, true);
            delete.setGravity(Gravity.CENTER);
            delete.setPadding(dp(10), 0, dp(10), 0);
            delete.setBackground(bg(selectedIds.isEmpty() ? theme.chip : 0xff8e3748, 12));
            selectedActions.addView(delete, new LinearLayout.LayoutParams(0, dp(46), 2));
            delete.setEnabled(!selectedIds.isEmpty() && !deleting);
            delete.setOnClickListener(v -> confirmBulkDelete());
            LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
            actionsLp.topMargin = dp(9);
            container.addView(selectedActions, actionsLp);
        }
        addGap(container, 12);

        LinearLayout chooser = new LinearLayout(host);
        String[] options = {"By thread", "By board", "Recent"};
        for (int i = 0; i < options.length; i++) {
            final int target = i;
            TextView control = text(options[i], 13, i == grouping ? theme.onAccent : TEXT, true);
            control.setGravity(Gravity.CENTER);
            control.setBackground(bg(i == grouping ? ACCENT : theme.chip, 12));
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
        if (!items.isEmpty() && !selecting) {
            TextView watchAll = text("▶  Swipe through all saved media", 14, theme.onAccent, true);
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
        Map<String, String> groupNames = new LinkedHashMap<>();
        for (JSONObject item : items) {
            String board = item.optString("board", "?");
            long thread = item.optLong("thread", 0);
            String key = grouping == 0 ? ThreadTitles.groupKey(board, thread) :
                    grouping == 1 ? "board:" + board : "recent";
            if (!groups.containsKey(key)) groups.put(key, new ArrayList<>());
            groups.get(key).add(item);
            if (grouping == 0) {
                String candidate = ThreadTitles.clean(item.optString("threadTitle", ""));
                if (!candidate.isEmpty() && !groupNames.containsKey(key)) groupNames.put(key, candidate);
            }
        }
        for (Map.Entry<String, List<JSONObject>> group : groups.entrySet()) {
            LinearLayout sectionHeader = new LinearLayout(host);
            sectionHeader.setGravity(Gravity.CENTER_VERTICAL);
            String titleString;
            JSONObject first = group.getValue().get(0);
            if (grouping == 0) {
                String discovered = groupNames.get(group.getKey());
                String chosen = renamePrefs.getString(group.getKey(), "");
                titleString = ThreadTitles.display(chosen, discovered);
            } else if (grouping == 1) {
                titleString = "/" + first.optString("board", "?") + "/ · Saved media";
            } else titleString = "Recently saved";

            LinearLayout textGroup = new LinearLayout(host);
            textGroup.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(titleString, 16, TEXT, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textGroup.addView(title);
            if (grouping == 0)
                textGroup.addView(text("/" + first.optString("board", "?") + "/  ·  Thread #"
                        + first.optLong("thread", 0), 11, SUB, false));
            sectionHeader.addView(textGroup, new LinearLayout.LayoutParams(0, -2, 1));

            LinearLayout groupTools = new LinearLayout(host);
            groupTools.setOrientation(LinearLayout.VERTICAL);
            TextView filesCount = text(group.getValue().size() + " files", 12, ACCENT, false);
            filesCount.setGravity(Gravity.RIGHT);
            groupTools.addView(filesCount);
            TextView groupAction = text(selecting ? "Select group" : "Rename", 12, ACCENT, true);
            groupAction.setPadding(dp(9), dp(8), 0, dp(6));
            groupAction.setGravity(Gravity.RIGHT);
            if (!selecting && grouping != 0) groupAction.setVisibility(View.GONE);
            groupAction.setOnClickListener(v -> {
                if (selecting) {
                    boolean entirelySelected = true;
                    for (JSONObject row : group.getValue())
                        if (!selectedIds.contains(row.optString("id"))) entirelySelected = false;
                    for (JSONObject row : group.getValue()) {
                        String selectedId = row.optString("id");
                        if (entirelySelected) selectedIds.remove(selectedId);
                        else selectedIds.add(selectedId);
                    }
                    redrawPreservingScroll();
                } else if (grouping == 0) {
                    askRenameThread(group.getKey(), title.getText().toString());
                }
            });
            groupTools.addView(groupAction);
            sectionHeader.addView(groupTools);
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
        TextView hint = text(selecting
                ? "Tap thumbnails or Select group · Confirm before deleting"
                : "Tap to swipe through a group · Long-press to delete or Select files above", 12, SUB, false);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(dp(4), dp(8), dp(4), dp(24));
        container.addView(hint);
    }

    private void askRenameThread(String groupKey, String existingTitle) {
        EditText input = new EditText(host);
        String saved = renamePrefs.getString(groupKey, "");
        if (!saved.isEmpty()) input.setText(saved);
        else if (!"Untitled thread".equals(existingTitle)) input.setText(existingTitle);
        input.setHint("Thread subject or caption");
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        int padding = dp(17);
        LinearLayout wrapper = new LinearLayout(host);
        wrapper.setPadding(padding, dp(8), padding, 0);
        wrapper.addView(input, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(host).setTitle("Rename saved thread")
                .setMessage("This changes only the name in your private library, not any downloaded files.")
                .setView(wrapper)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset name", (d, w) -> {
                    renamePrefs.edit().remove(groupKey).apply();
                    draw();
                })
                .setPositiveButton("Save", (d, w) -> {
                    String chosen = ThreadTitles.clean(input.getText().toString());
                    if (chosen.isEmpty()) {
                        android.widget.Toast.makeText(host, "Please enter a thread title",
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    renamePrefs.edit().putString(groupKey, chosen).apply();
                    draw();
                }).show();
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
        imageHolder.setBackground(bg(theme.chip, 11));
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
        TextView marked = text("✓ Selected", 11, 0xff172618, true);
        marked.setGravity(Gravity.CENTER);
        marked.setBackground(bg(ACCENT, 8));
        FrameLayout.LayoutParams markLp = new FrameLayout.LayoutParams(-2, dp(26),
                Gravity.TOP | Gravity.RIGHT);
        markLp.topMargin = dp(6);
        markLp.rightMargin = dp(6);
        marked.setPadding(dp(7), 0, dp(7), 0);
        marked.setVisibility(selecting && selectedIds.contains(row.optString("id"))
                ? View.VISIBLE : View.GONE);
        imageHolder.addView(marked, markLp);
        if (selecting && selectedIds.contains(row.optString("id")))
            tile.setBackground(bg(theme.chip, 14));

        TextView label = text(row.optString("title", "Saved media"), 11, TEXT, false);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(3), dp(5), dp(3), 0);
        tile.addView(label);
        imageHolder.setContentDescription("Open " + row.optString("title", "saved media"));
        tile.setOnClickListener(v -> {
            if (selecting) {
                String id = row.optString("id", "");
                if (selectedIds.contains(id)) selectedIds.remove(id);
                else if (!id.isEmpty()) selectedIds.add(id);
                redrawPreservingScroll();
                return;
            }
            Intent intent = new Intent(host, SavedFeedActivity.class);
            intent.putExtra("file", fileName);
            intent.putExtra("group", grouping == 0 ? "thread" : grouping == 1 ? "board" : "recent");
            if (grouping != 2) intent.putExtra("board", row.optString("board"));
            if (grouping == 0) intent.putExtra("thread", row.optLong("thread"));
            host.startActivity(intent);
        });
        tile.setOnLongClickListener(v -> {
            if (selecting) {
                tile.performClick();
                return true;
            }
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

    private void confirmBulkDelete() {
        if (selectedIds.isEmpty() || deleting) return;
        final Set<String> targets = new LinkedHashSet<>(selectedIds);
        long bytes = 0;
        for (JSONObject row : items) {
            if (!targets.contains(row.optString("id"))) continue;
            File file = new File(vaultDir, row.optString("file"));
            if (file.isFile()) bytes += file.length();
        }
        String total = bytes >= 1024 * 1024 ?
                String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0)) :
                String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        new AlertDialog.Builder(host)
                .setTitle("Delete " + targets.size() + " saved files?")
                .setMessage("This will permanently remove " + targets.size()
                        + " selected files (" + total + ") from the private vault. "
                        + "Unselected downloads are preserved. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete files", (d, w) -> {
                    deleting = true;
                    THUMBS.execute(() -> {
                        try {
                            int removed = new MainActivity.Vault(vaultDir).deleteMany(targets);
                            host.runOnUiThread(() -> {
                                deleting = false;
                                selectedIds.clear();
                                selecting = false;
                                android.widget.Toast.makeText(host,
                                        "Deleted " + removed + " saved files",
                                        android.widget.Toast.LENGTH_LONG).show();
                                reload.run();
                            });
                        } catch (Exception ex) {
                            host.runOnUiThread(() -> {
                                deleting = false;
                                android.widget.Toast.makeText(host,
                                        "Could not delete files. Your library is unchanged.",
                                        android.widget.Toast.LENGTH_LONG).show();
                                draw();
                            });
                        }
                    });
                }).show();
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
