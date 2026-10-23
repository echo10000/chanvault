package dev.chanvault.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** A read-only, swipeable thread feed. Streaming a preview does not save it. */
public final class ThreadFeedActivity extends Activity {
    private static final int BG = 0xff0d1017, FG = 0xfff5f5fa, SUB = 0xffb6bdcb;
    private static final int PRIMARY = 0xff9faefb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final ExecutorService saves = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelBatch = new AtomicBoolean(false);
    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> media = new ArrayList<>();
    private MainActivity.Vault vault;
    private String board;
    private long threadId;
    private boolean mediaOnly = true, batchRunning = false;
    private int position = 0, viewSerial = 0;
    private float touchStartX, touchStartY;
    private FrameLayout root, mediaPane;
    private TextView counter, heading, caption, prompt, status;
    private Button saveButton, modeButton, batchButton;
    private VideoView video;
    private LinearLayout upNextRow;

    static final class Entry {
        long id, size;
        String title, text, author, url, ext, thumb;
        boolean hasMedia() { return url != null; }
        boolean isVideo() { return ".webm".equals(ext) || ".mp4".equals(ext); }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        board = getIntent().getStringExtra("board");
        threadId = getIntent().getLongExtra("thread", 0);
        if (board == null || !board.matches("[a-z0-9]{1,12}") || threadId <= 0) {
            finish(); return;
        }
        vault = new MainActivity.Vault(new File(getFilesDir(), "vault"));
        makeScreen();
        loadThread();
    }

    @Override public void onBackPressed() {
        if (batchRunning) {
            new AlertDialog.Builder(this).setTitle("Leave the feed?")
                    .setMessage("The current download will finish, but the remaining queue will stop.")
                    .setNegativeButton("Stay", null)
                    .setPositiveButton("Leave", (d,w) -> { cancelBatch.set(true); finish(); }).show();
        } else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        cancelBatch.set(true);
        if (video != null) video.stopPlayback();
        io.shutdownNow();
        saves.shutdown();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private TextView text(String content, int size, int color) {
        TextView t = new TextView(this);
        t.setText(content);
        t.setTextColor(color);
        t.setTextSize(size);
        return t;
    }

    private android.graphics.drawable.GradientDrawable rounded(int color, int radius, int border) {
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (border != 0) background.setStroke(dp(1), border);
        return background;
    }

    private Button button(String title, Runnable action) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextSize(13);
        boolean primary = title.equals("Save to vault") || title.equals("Download thread");
        b.setTextColor(primary ? BG : FG);
        b.setBackground(rounded(primary ? PRIMARY : 0xff293044, 13,
                primary ? 0 : 0xff394359));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        });
        int icon = 0;
        if (title.equals("Save to vault") || title.equals("Download thread"))
            icon = R.drawable.ic_download;
        else if (title.equals("Posts")) icon = R.drawable.ic_posts;
        else if (title.equals("Back")) icon = R.drawable.ic_back;
        if (icon != 0) {
            android.graphics.drawable.Drawable d = getDrawable(icon);
            if (d != null) {
                d = d.mutate();
                d.setTint(primary ? BG : FG);
                d.setBounds(0, 0, dp(18), dp(18));
                b.setCompoundDrawables(d, null, null, null);
                b.setCompoundDrawablePadding(dp(5));
            }
        }
        return b;
    }

    private void makeScreen() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        mediaPane = new FrameLayout(this);
        root.addView(mediaPane, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(12), dp(6), dp(12), dp(7));
        top.setBackgroundColor(0xe610141e);
        top.addView(button("Back", this::finish),
                new LinearLayout.LayoutParams(dp(78), dp(44)));
        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        titleStack.setPadding(dp(10), 0, dp(3), 0);
        titleStack.addView(text("/" + board + "/  •  Thread #" + threadId, 14, FG));
        counter = text("Loading thread...", 12, SUB);
        titleStack.addView(counter);
        top.addView(titleStack, new LinearLayout.LayoutParams(0, -2, 1));
        modeButton = button("All posts", this::toggleMode);
        top.addView(modeButton, new LinearLayout.LayoutParams(dp(94), dp(44)));
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(14), dp(9), dp(14), dp(10));
        bottom.setBackground(rounded(0xf3121825, 20, 0));

        LinearLayout nextHead = new LinearLayout(this);
        nextHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView nextLabel = text("UP NEXT", 11, PRIMARY);
        nextLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        nextHead.addView(nextLabel, new LinearLayout.LayoutParams(0, -2, 1));
        nextHead.addView(text("Tap to jump", 11, SUB));
        bottom.addView(nextHead);
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        upNextRow = new LinearLayout(this);
        upNextRow.setOrientation(LinearLayout.HORIZONTAL);
        upNextRow.setPadding(0, dp(7), 0, dp(7));
        scroller.addView(upNextRow);
        bottom.addView(scroller, new LinearLayout.LayoutParams(-1, dp(95)));

        heading = text("Loading thread media...", 17, FG);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setMaxLines(2);
        bottom.addView(heading);
        caption = text("Swipe vertically to browse. Tap to pause.", 12, SUB);
        caption.setMaxLines(2);
        caption.setPadding(0, dp(5), 0, dp(8));
        bottom.addView(caption);

        LinearLayout actions = new LinearLayout(this);
        saveButton = button("Save to vault", this::saveCurrent);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        saveLp.rightMargin = dp(9);
        actions.addView(saveButton, saveLp);
        actions.addView(button("Posts", this::openPosts),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        bottom.addView(actions);

        batchButton = button("Download thread", this::confirmBatch);
        LinearLayout.LayoutParams batchLp = new LinearLayout.LayoutParams(-1, dp(42));
        batchLp.topMargin = dp(7);
        bottom.addView(batchButton, batchLp);

        status = text("Previews stream online • Nothing saves without your tap", 11, SUB);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, dp(6), 0, 0);
        bottom.addView(status);

        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bottomLp.setMargins(dp(6), 0, dp(6), dp(5));
        root.addView(bottom, bottomLp);
        setContentView(root);
    }

    private void populateUpNext() {
        upNextRow.removeAllViews();
        List<Entry> source = visible();
        if (source.isEmpty()) return;
        List<Entry> upcoming = new ArrayList<>();
        // Prefer future videos, then fill any remaining spaces with still media.
        for (int i = position + 1; i < source.size() && upcoming.size() < 3; i++) {
            Entry item = source.get(i);
            if (item.hasMedia() && item.isVideo()) upcoming.add(item);
        }
        for (int i = position + 1; i < source.size() && upcoming.size() < 3; i++) {
            Entry item = source.get(i);
            if (item.hasMedia() && !upcoming.contains(item)) upcoming.add(item);
        }
        if (upcoming.isEmpty()) {
            TextView done = text("You've reached the end of this thread", 12, SUB);
            done.setGravity(Gravity.CENTER_VERTICAL);
            upNextRow.addView(done, new LinearLayout.LayoutParams(-1, dp(70)));
        }
        for (Entry item : upcoming) {
            final int target = source.indexOf(item);
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(4), dp(3), dp(4), dp(3));
            tile.setBackground(rounded(0xff252c3d, 10, 0xff374259));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(109), dp(79));
            lp.rightMargin = dp(8);
            upNextRow.addView(tile, lp);
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setBackground(rounded(0xff353d50, 7, 0));
            tile.addView(thumb, new LinearLayout.LayoutParams(-1, dp(54)));
            if (item.thumb != null) MediaThumbs.load(thumb, item.thumb, false);
            TextView title = text((item.isVideo() ? "VIDEO" : "IMAGE") + "  •  #" + item.id,
                    10, SUB);
            title.setSingleLine(true);
            title.setGravity(Gravity.CENTER);
            tile.addView(title);
            tile.setContentDescription("Preview post " + item.id);
            tile.setClickable(true);
            tile.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                position = target;
                showEntry();
            });
        }
    }

    private FrameLayout loadingOverlay(String label, View.OnTouchListener swipe) {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x8c0d1017);
        android.widget.LinearLayout indicator = new android.widget.LinearLayout(this);
        indicator.setOrientation(android.widget.LinearLayout.VERTICAL);
        indicator.setGravity(Gravity.CENTER);
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(PRIMARY));
        indicator.addView(spinner, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView labelView = text(label, 13, FG);
        labelView.setPadding(dp(12), dp(12), dp(12), dp(4));
        labelView.setGravity(Gravity.CENTER);
        indicator.addView(labelView);
        FrameLayout.LayoutParams center = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        overlay.addView(indicator, center);
        overlay.setOnTouchListener(swipe);
        mediaPane.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        return overlay;
    }

    private void loadThread() {
        final String url = "https://a.4cdn.org/" + board + "/thread/" + threadId + ".json";
        io.execute(() -> {
            try {
                JSONArray posts = new JSONObject(fetchText(url)).getJSONArray("posts");
                for (int i = 0; i < posts.length(); i++) {
                    JSONObject post = posts.optJSONObject(i);
                    if (post == null) continue;
                    Entry e = new Entry();
                    e.id = post.optLong("no");
                    e.author = clean(post.optString("name", "Anonymous"));
                    e.title = clean(post.optString("sub", "Post #" + e.id));
                    if (e.title.isEmpty()) e.title = "Post #" + e.id;
                    e.text = clean(post.optString("com", ""));
                    e.ext = post.optString("ext", "").toLowerCase(Locale.US);
                    String tim = post.optString("tim", "");
                    e.size = Math.max(0, post.optLong("fsize", 0));
                    if (tim.matches("[0-9]{5,24}") && e.ext.matches("\\.(webm|mp4|gif|jpg|jpeg|png)")) {
                        e.url = "https://i.4cdn.org/" + board + "/" + tim + e.ext;
                        e.thumb = "https://i.4cdn.org/" + board + "/" + tim + "s.jpg";
                    }
                    all.add(e);
                    if (e.hasMedia()) media.add(e);
                }
                runOnUiThread(() -> {
                    int focus = (int) getIntent().getLongExtra("focusPost", 0);
                    if (focus > 0) {
                        for (int i = 0; i < media.size(); i++)
                            if (media.get(i).id == focus) { position = i; break; }
                    }
                    showEntry();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    heading.setText("Couldn't load this thread");
                    caption.setText(ex.getMessage());
                    counter.setText("Load failed");
                    status.setText("Check your internet connection and try again.");
                });
            }
        });
    }

    private List<Entry> visible() { return mediaOnly ? media : all; }

    private void toggleMode() {
        Entry old = null;
        List<Entry> list = visible();
        if (position < list.size()) old = list.get(position);
        mediaOnly = !mediaOnly;
        modeButton.setText(mediaOnly ? "All posts" : "Media only");
        position = 0;
        if (old != null) {
            List<Entry> next = visible();
            for (int i = 0; i < next.size(); i++) {
                if (next.get(i).id == old.id) { position = i; break; }
            }
        }
        showEntry();
    }

    private void move(int step) {
        List<Entry> list = visible();
        if (list.isEmpty()) return;
        int next = position + step;
        if (next < 0 || next >= list.size()) {
            Toast.makeText(this, "End of thread feed", Toast.LENGTH_SHORT).show();
            return;
        }
        position = next;
        showEntry();
    }

    private void dismissLoading(FrameLayout loading, int serial) {
        if (serial != viewSerial || loading == null) return;
        loading.animate().alpha(0f).setDuration(170).withEndAction(() -> {
            if (serial == viewSerial) mediaPane.removeView(loading);
        }).start();
    }

    private void showLoadingFailure(FrameLayout overlay, String message, int serial) {
        if (serial != viewSerial) return;
        overlay.animate().cancel();
        overlay.setAlpha(1f);
        overlay.removeAllViews();
        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(15), dp(20), dp(15));
        TextView label = text(message, 14, FG);
        label.setGravity(Gravity.CENTER);
        content.addView(label);
        Button retry = button("Retry preview", this::showEntry);
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(dp(160), dp(46));
        retryLp.topMargin = dp(14);
        content.addView(retry, retryLp);
        overlay.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
    }

    private void showEntry() {
        ++viewSerial;
        if (video != null) {
            video.stopPlayback();
            video = null;
        }
        mediaPane.removeAllViews();
        List<Entry> list = visible();
        if (list.isEmpty()) {
            heading.setText("No media in this thread");
            caption.setText("Switch to All posts to read the discussion.");
            counter.setText("0 items");
            saveButton.setEnabled(false);
            upNextRow.removeAllViews();
            return;
        }
        position = Math.max(0, Math.min(position, list.size() - 1));
        populateUpNext();
        final int serial = viewSerial;
        final Entry current = list.get(position);
        counter.setText((mediaOnly ? "Media " : "Post ") + (position + 1) + " / " + list.size()
                + "  •  " + media.size() + " attachments");
        heading.setText(current.title);
        caption.setText("#" + current.id + " · " + current.author + "\n"
                + (current.text.isEmpty() ? "No description" : shorten(current.text, 120)));
        saveButton.setEnabled(current.hasMedia());
        saveButton.setText(current.hasMedia() ? "Save to vault" : "Text only");
        status.setText(current.hasMedia()
                ? "Swipe to browse · Tap to pause or play"
                : "Text-only post · Swipe up for the next post");

        View.OnTouchListener swipe = (v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchStartY = event.getRawY();
                    touchStartX = event.getRawX();
                    return true;
                case MotionEvent.ACTION_UP:
                    float dy = event.getRawY() - touchStartY;
                    float dx = event.getRawX() - touchStartX;
                    if (Math.abs(dy) > dp(65) && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                        move(dy < 0 ? 1 : -1);
                    } else if (video != null && Math.abs(dy) < dp(30)) {
                        if (video.isPlaying()) video.pause();
                        else video.start();
                    }
                    return true;
                default:
                    return true;
            }
        };
        mediaPane.setOnTouchListener(swipe);

        if (!current.hasMedia()) {
            TextView message = text(shorten(current.text.isEmpty() ? "Text-only post" : current.text, 1800), 19, FG);
            message.setGravity(Gravity.CENTER);
            message.setPadding(dp(28), dp(80), dp(28), dp(260));
            message.setOnTouchListener(swipe);
            mediaPane.addView(message, new FrameLayout.LayoutParams(-1, -1));
        } else if (current.isVideo()) {
            VideoView player = new VideoView(this);
            video = player;
            mediaPane.addView(player, new FrameLayout.LayoutParams(-1, -1));
            player.setOnTouchListener(swipe);
            FrameLayout loading = loadingOverlay("Loading video preview...", swipe);
            player.setOnPreparedListener(mp -> {
                if (serial != viewSerial) return;
                mp.setLooping(true);
                player.start();
                ui.postDelayed(() -> dismissLoading(loading, serial), 350);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (serial == viewSerial) {
                    showLoadingFailure(loading, "This video couldn't play on your device.\n"
                            + "Use Open original to try an external player.", serial);
                    status.setText("Playback depends on your Android video codecs.");
                }
                return true;
            });
            player.setVideoURI(Uri.parse(current.url));
        } else {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackgroundColor(BG);
            image.setOnTouchListener(swipe);
            mediaPane.addView(image, new FrameLayout.LayoutParams(-1, -1));
            FrameLayout loading = loadingOverlay("Loading image preview...", swipe);
            MediaThumbs.load(image, current.url, true, ok -> {
                if (serial != viewSerial) return;
                if (ok) dismissLoading(loading, serial);
                else showLoadingFailure(loading, "Image preview is unavailable.\nTry again or open the original.", serial);
            });
            if (".gif".equals(current.ext))
                status.setText("GIF previews may show one frame on some devices.");
        }
        if (current.hasMedia()) {
            TextView browser = text("Open original", 12, PRIMARY);
            browser.setTypeface(null, android.graphics.Typeface.BOLD);
            browser.setBackground(rounded(0xd9121825, 12, 0xff374259));
            browser.setPadding(dp(12), dp(11), dp(12), dp(11));
            browser.setOnClickListener(v -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(current.url))); }
                catch (Exception e) { toast("No external viewer available"); }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
            lp.topMargin = dp(67);
            lp.rightMargin = dp(10);
            mediaPane.addView(browser, lp);
        }
    }

    private void saveCurrent() {
        List<Entry> list = visible();
        if (position >= list.size()) return;
        Entry item = list.get(position);
        if (!item.hasMedia()) return;
        final int serial = viewSerial;
        saveButton.setEnabled(false);
        saveButton.setText("Saving...");
        saves.execute(() -> {
            try {
                boolean alreadySaved = vault.exists(item.url);
                if (!alreadySaved) vault.download(item.url, item.title + item.ext, board, threadId);
                runOnUiThread(() -> {
                    if (serial == viewSerial) {
                        saveButton.setEnabled(true);
                        saveButton.setText(alreadySaved ? "Already saved" : "Saved to vault");
                    }
                    if (!alreadySaved) toast("Saved privately inside ChanVault");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (serial == viewSerial) {
                        saveButton.setEnabled(true);
                        saveButton.setText("Retry save");
                    }
                    toast("Save failed: " + e.getMessage());
                });
            }
        });
    }

    private void openPosts() {
        Intent view = new Intent(this, MainActivity.class);
        view.putExtra("board", board);
        view.putExtra("thread", threadId);
        view.putExtra("sourceFeed", true);
        startActivity(view);
    }

    private void confirmBatch() {
        if (batchRunning) {
            cancelBatch.set(true);
            batchButton.setText("Finishing current file...");
            batchButton.setEnabled(false);
            return;
        }
        if (media.isEmpty()) { toast("This thread has no supported media"); return; }
        long estimate = 0;
        boolean hasUnknownSizes = false;
        for (Entry e : media) {
            estimate += e.size;
            if (e.size <= 0) hasUnknownSizes = true;
        }
        String detail = "Save all " + media.size() + " supported attachments (videos, WebMs, GIFs and images)"
                + " into ChanVault's private storage.\n\n"
                + "Estimated total: " + formatSize(estimate) + (hasUnknownSizes ? " or more" : "")
                + "\n\nExisting downloads will be skipped. Files larger than 250 MB are skipped."
                + " Keep ChanVault open until completion. This may use substantial data and storage.";
        new AlertDialog.Builder(this)
                .setTitle("Download entire thread?")
                .setMessage(detail)
                .setNegativeButton("Not now", null)
                .setPositiveButton("Download all", (d, w) -> startBatch())
                .show();
    }

    private void startBatch() {
        if (batchRunning) return;
        batchRunning = true;
        cancelBatch.set(false);
        batchButton.setText("Cancel remaining downloads");
        status.setText("Preparing downloads...");
        saves.execute(() -> {
            int saved = 0, skipped = 0, failed = 0;
            int total = media.size();
            for (int i = 0; i < total && !cancelBatch.get(); i++) {
                Entry e = media.get(i);
                final int current = i + 1;
                runOnUiThread(() -> status.setText("Downloading " + current + " / " + total + " · #" + e.id));
                try {
                    if (vault.exists(e.url)) { skipped++; continue; }
                    if (e.size > 250L * 1024 * 1024) { failed++; continue; }
                    if (e.size > 0 && new File(getFilesDir(), "vault").getUsableSpace() < e.size + 16L*1024*1024) {
                        failed++;
                        cancelBatch.set(true);
                        runOnUiThread(() -> toast("Low phone storage. Batch stopped."));
                        break;
                    }
                    vault.download(e.url, e.title + e.ext, board, threadId);
                    saved++;
                } catch (Exception ex) { failed++; }
            }
            final String result = "Saved " + saved + " · already saved " + skipped
                    + " · failed " + failed + (cancelBatch.get() ? " · stopped" : " · finished");
            runOnUiThread(() -> {
                batchRunning = false;
                batchButton.setEnabled(true);
                batchButton.setText("Download thread");
                status.setText(result);
                new AlertDialog.Builder(this).setTitle("Thread download summary")
                        .setMessage(result + "\n\nFiles are inside Saved Media, not your Gallery.")
                        .setPositiveButton("OK", null).show();
            });
        });
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / (1024d * 1024));
    }

    private static String clean(String html) {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString()
                .replaceAll("\n{3,}", "\n\n").trim();
    }

    private static String shorten(String text, int length) {
        return text.length() <= length ? text : text.substring(0, length) + "…";
    }

    private static String fetchText(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "ChanVault/0.2 Android unofficial");
        try {
            if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] data = new byte[8192];
                int n;
                while ((n = in.read(data)) != -1) {
                    if (out.size() + n > 12 * 1024 * 1024) throw new Exception("Thread is too large");
                    out.write(data, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally { conn.disconnect(); }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
