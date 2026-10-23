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

    private Button button(String title, Runnable action) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTextColor(FG);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void makeScreen() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        mediaPane = new FrameLayout(this);
        root.addView(mediaPane, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(6), dp(10), dp(6));
        top.setBackgroundColor(0xe610141e);
        top.addView(button("‹", this::finish), new LinearLayout.LayoutParams(dp(46), dp(48)));
        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        titleStack.addView(text("chanvault.  /" + board + "/  #" + threadId, 15, FG));
        counter = text("Loading thread...", 12, SUB);
        titleStack.addView(counter);
        top.addView(titleStack, new LinearLayout.LayoutParams(0, -2, 1));
        modeButton = button("All posts", this::toggleMode);
        top.addView(modeButton, new LinearLayout.LayoutParams(dp(98), dp(48)));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(top, topLp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(12), dp(16), dp(12));
        bottom.setBackgroundColor(0xea111723);
        heading = text("Loading thread media…", 17, FG);
        heading.setMaxLines(2);
        bottom.addView(heading);
        caption = text("Swipe up for the next item, down for the previous.", 13, SUB);
        caption.setMaxLines(5);
        caption.setPadding(0, dp(6), 0, dp(9));
        bottom.addView(caption);
        LinearLayout actionRow = new LinearLayout(this);
        saveButton = button("↓ Save privately", this::saveCurrent);
        actionRow.addView(saveButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        actionRow.addView(button("Open posts", this::openPosts), new LinearLayout.LayoutParams(0, dp(48), 1));
        bottom.addView(actionRow);
        LinearLayout navigation = new LinearLayout(this);
        navigation.addView(button("↑ Previous", () -> move(-1)), new LinearLayout.LayoutParams(0, dp(45), 1));
        navigation.addView(button("↓ Next", () -> move(1)), new LinearLayout.LayoutParams(0, dp(45), 1));
        bottom.addView(navigation);
        LinearLayout batchRow = new LinearLayout(this);
        batchButton = button("↓ Download all thread media", this::confirmBatch);
        batchRow.addView(batchButton, new LinearLayout.LayoutParams(-1, dp(46)));
        bottom.addView(batchRow);
        status = text("Previews stream online. Nothing is saved until you choose Save.", 11, SUB);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setPadding(0, dp(4), 0, 0);
        bottom.addView(status);
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bottom, bottomLp);

        setContentView(root);
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
            return;
        }
        position = Math.max(0, Math.min(position, list.size() - 1));
        final Entry current = list.get(position);
        counter.setText((mediaOnly ? "Media " : "Post ") + (position + 1) + " / " + list.size()
                + "  ·  " + media.size() + " attachments");
        heading.setText(current.title);
        caption.setText("#" + current.id + " · " + current.author + "\n"
                + (current.text.isEmpty() ? "No description" : shorten(current.text, 380)));
        saveButton.setEnabled(current.hasMedia());
        saveButton.setText(current.hasMedia() ? "↓ Save privately" : "No attachment");
        status.setText(current.hasMedia()
                ? "Swipe up/down to browse · Tap a video to pause/play"
                : "Text-only post · Swipe up/down for the next post");

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
            TextView message = text(shorten(current.text.isEmpty() ? "Text-only post" : current.text, 1800), 20, FG);
            message.setGravity(Gravity.CENTER);
            message.setPadding(dp(28), dp(80), dp(28), dp(200));
            message.setOnTouchListener(swipe);
            mediaPane.addView(message, new FrameLayout.LayoutParams(-1, -1));
        } else if (current.isVideo()) {
            TextView loading = text("Loading video preview…", 16, SUB);
            loading.setGravity(Gravity.CENTER);
            mediaPane.addView(loading, new FrameLayout.LayoutParams(-1, -1));
            VideoView player = new VideoView(this);
            video = player;
            mediaPane.addView(player, new FrameLayout.LayoutParams(-1, -1));
            player.setOnTouchListener(swipe);
            final int serial = viewSerial;
            player.setOnPreparedListener(mp -> {
                if (serial != viewSerial) return;
                loading.setVisibility(View.GONE);
                mp.setLooping(true);
                player.start();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                if (serial == viewSerial) {
                    loading.setVisibility(View.VISIBLE);
                    loading.setText("This video codec can't be previewed here.\n"
                            + "You can open it in your browser or save it privately.");
                    status.setText("Playback depends on your phone's supported codecs.");
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
            MediaThumbs.load(image, current.url, true);
            if (".gif".equals(current.ext))
                status.setText("Animated GIF preview may show one frame on this device.");
        }
        // A non-downloading fallback for video formats the device cannot decode.
        if (current.hasMedia()) {
            TextView browser = text("↗ Open original preview", 12, PRIMARY);
            browser.setPadding(dp(16), dp(70), dp(16), dp(10));
            browser.setOnClickListener(v -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(current.url))); }
                catch (Exception e) { toast("No browser can open this media"); }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.TOP | Gravity.RIGHT);
            mediaPane.addView(browser, lp);
        }
    }

    private void saveCurrent() {
        List<Entry> list = visible();
        if (position >= list.size()) return;
        Entry item = list.get(position);
        if (!item.hasMedia()) return;
        saveButton.setEnabled(false);
        saveButton.setText("Saving…");
        saves.execute(() -> {
            try {
                if (vault.exists(item.url)) {
                    runOnUiThread(() -> { saveButton.setEnabled(true); saveButton.setText("✓ Already saved"); });
                    return;
                }
                vault.download(item.url, item.title + item.ext, board, threadId);
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    saveButton.setText("✓ Saved");
                    toast("Saved inside ChanVault, not your Gallery");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    saveButton.setText("↓ Retry save");
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
            batchButton.setText("Stopping after current file…");
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
        status.setText("Preparing private batch download…");
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
                batchButton.setText("↓ Download all thread media");
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
