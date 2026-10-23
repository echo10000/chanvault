package dev.chanvault.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
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
import java.util.HashSet;
import java.util.Set;

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
    private ThreadShelf threadShelf;
    private String board;
    private long threadId;
    private boolean mediaOnly = true, batchRunning = false;
    private int position = 0, viewSerial = 0;
    private float touchStartX, touchStartY;
    private LinearLayout root;
    private FrameLayout mediaPane;
    private android.widget.HorizontalScrollView nextScroller;
    private TextView nextToggle;
    private boolean nextExpanded = false;
    private TextView counter, heading, caption, prompt, status;
    private Button saveButton, modeButton, batchButton;
    private FeedVideoPlayer feedPlayer;
    private PlaybackCache streamCache;
    private boolean preloadEnabled = true;
    private LinearLayout upNextRow, videoTools;
    private Button fitButton, preloadButton;
    private final List<LinearLayout> carouselTiles = new ArrayList<>();
    private final List<ImageView> carouselImages = new ArrayList<>();
    private final Set<Integer> requestedThumbs = new HashSet<>();
    private TextView bufferingLabel;

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
        threadShelf = new ThreadShelf(this);
        streamCache = PlaybackCache.get(this);
        preloadEnabled = getSharedPreferences("feed_settings", MODE_PRIVATE)
                .getBoolean("prefetch_next", true);
        mediaOnly = getSharedPreferences("watch_resume_v1", MODE_PRIVATE)
            .getBoolean("mode_" + board + "_" + threadId, true);
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
        if (feedPlayer != null) feedPlayer.release();
        if (streamCache != null) streamCache.cancelPrefetch();
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
        // The video and bottom controls occupy separate regions. Nothing overlays the player.
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(5), dp(10), dp(5));
        top.setBackgroundColor(0xff161c29);
        top.addView(button("Back", this::finish), new LinearLayout.LayoutParams(dp(76), dp(42)));
        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);
        titleStack.setPadding(dp(9), 0, dp(4), 0);
        TextView name = text("/" + board + "/  •  Thread #" + threadId, 13, FG);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleStack.addView(name);
        counter = text("Loading thread...", 11, SUB);
        titleStack.addView(counter);
        top.addView(titleStack, new LinearLayout.LayoutParams(0, -2, 1));
        modeButton = button(mediaOnly ? "All posts" : "Media only", this::toggleMode);
        top.addView(modeButton, new LinearLayout.LayoutParams(dp(82), dp(42)));
        Button more = button("•••", () -> showThreadMenu());
        more.setContentDescription("Thread actions: pin or block");
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(dp(44), dp(42));
        moreLp.leftMargin = dp(6);
        top.addView(more, moreLp);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(58)));

        mediaPane = new FrameLayout(this);
        root.addView(mediaPane, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(15), dp(9), dp(15), dp(12));
        bottom.setBackground(rounded(0xff171d2a, 17, 0));

        // A collapsed Up Next bar is shown by default; expanding the filmstrip
        // makes the player a little shorter instead of covering its pixels.
        nextToggle = text("Browse all media    ▾", 12, PRIMARY);
        nextToggle.setTypeface(null, android.graphics.Typeface.BOLD);
        nextToggle.setGravity(Gravity.CENTER_VERTICAL);
        nextToggle.setPadding(dp(14), dp(6), dp(12), dp(6));
        nextToggle.setBackground(rounded(0xff292f43, 10, 0));
        nextToggle.setOnClickListener(v -> setNextExpanded(!nextExpanded));
        bottom.addView(nextToggle, new LinearLayout.LayoutParams(-1, dp(39)));

        nextScroller = new android.widget.HorizontalScrollView(this);
        nextScroller.setHorizontalScrollBarEnabled(false);
        nextScroller.setOnScrollChangeListener((v, x, y, oldX, oldY) -> loadVisibleCarouselImages());
        upNextRow = new LinearLayout(this);
        upNextRow.setOrientation(LinearLayout.HORIZONTAL);
        upNextRow.setPadding(0, dp(5), 0, dp(4));
        nextScroller.addView(upNextRow);
        nextScroller.setVisibility(View.GONE);
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(-1, dp(108));
        nextLp.topMargin = dp(3);
        bottom.addView(nextScroller, nextLp);

        // Two spacious rows are easier to hit than four edge-to-edge small buttons.
        // All controls remain beneath the player and outside its picture area.
        videoTools = new LinearLayout(this);
        videoTools.setOrientation(LinearLayout.VERTICAL);
        videoTools.setVisibility(View.GONE);
        LinearLayout videoRow1 = new LinearLayout(this);
        videoRow1.setOrientation(LinearLayout.HORIZONTAL);
        fitButton = button("Fit / Fill", () -> {
            if (feedPlayer == null) return;
            boolean filled = feedPlayer.toggleFill();
            fitButton.setText(filled ? "Fill / Fit" : "Fit / Fill");
            status.setText(filled ? "Zoomed to fill (edges may crop)" : "Fit entire video");
        });
        LinearLayout.LayoutParams fitLp = new LinearLayout.LayoutParams(0, dp(43), 1);
        fitLp.rightMargin = dp(6);
        videoRow1.addView(fitButton, fitLp);
        Button rotate = button("Rotate 90°", () -> {
            if (feedPlayer != null) {
                int degrees = feedPlayer.rotate90();
                status.setText("Rotation " + degrees + "°");
            }
        });
        LinearLayout.LayoutParams rotateLp = new LinearLayout.LayoutParams(0, dp(43), 1);
        rotateLp.leftMargin = dp(6);
        videoRow1.addView(rotate, rotateLp);
        videoTools.addView(videoRow1, new LinearLayout.LayoutParams(-1, dp(43)));

        LinearLayout videoRow2 = new LinearLayout(this);
        videoRow2.setOrientation(LinearLayout.HORIZONTAL);
        Button original = button("Open original", () -> {
            Entry e = currentEntry();
            if (e == null || e.url == null) return;
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(e.url))); }
            catch (Exception ex) { toast("No external player installed"); }
        });
        LinearLayout.LayoutParams originalLp = new LinearLayout.LayoutParams(0, dp(43), 1);
        originalLp.rightMargin = dp(6);
        videoRow2.addView(original, originalLp);
        preloadButton = button(preloadEnabled ? "Preload: On" : "Preload: Off", () -> {
            preloadEnabled = !preloadEnabled;
            getSharedPreferences("feed_settings", MODE_PRIVATE).edit()
                    .putBoolean("prefetch_next", preloadEnabled).apply();
            preloadButton.setText(preloadEnabled ? "Preload: On" : "Preload: Off");
            if (!preloadEnabled) streamCache.cancelPrefetch();
            else prepareNextVideo();
        });
        LinearLayout.LayoutParams preloadLp = new LinearLayout.LayoutParams(0, dp(43), 1);
        preloadLp.leftMargin = dp(6);
        videoRow2.addView(preloadButton, preloadLp);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(-1, dp(43));
        row2Lp.topMargin = dp(7);
        videoTools.addView(videoRow2, row2Lp);

        LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(-1, -2);
        toolsLp.topMargin = dp(8);
        toolsLp.bottomMargin = dp(5);
        bottom.addView(videoTools, toolsLp);

        heading = text("Loading media...", 15, FG);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setMaxLines(1);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(-1, -2);
        headingLp.topMargin = dp(7);
        bottom.addView(heading, headingLp);

        caption = text("Swipe vertically to browse", 11, SUB);
        caption.setMaxLines(1);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setPadding(0, dp(2), 0, dp(7));
        bottom.addView(caption);

        LinearLayout actions = new LinearLayout(this);
        saveButton = button("Save to vault", this::saveCurrent);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        saveLp.rightMargin = dp(7);
        actions.addView(saveButton, saveLp);
        LinearLayout.LayoutParams postsLp = new LinearLayout.LayoutParams(0, dp(48), 1);
        postsLp.leftMargin = dp(7);
        actions.addView(button("Posts", this::openPosts), postsLp);
        bottom.addView(actions);

        batchButton = button("Download thread", this::confirmBatch);
        LinearLayout.LayoutParams batchLp = new LinearLayout.LayoutParams(-1, dp(47));
        batchLp.topMargin = dp(10);
        bottom.addView(batchButton, batchLp);

        status = text("Swipe to browse • Tap video to pause", 10, SUB);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        status.setMaxLines(1);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setPadding(0, dp(5), 0, 0);
        bottom.addView(status);

        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void setNextExpanded(boolean expand) {
        nextExpanded = expand;
        nextScroller.setVisibility(expand ? View.VISIBLE : View.GONE);
        nextToggle.setText(expand ? "Browse " + media.size() + " media · Hide  ▴"
                : "Browse all " + media.size() + " media  ▾");
        if (expand) nextScroller.post(() -> {
            Entry active = currentEntry();
            nextScroller.scrollTo(Math.max(0, media.indexOf(active) * dp(106) - dp(60)), 0);
            loadVisibleCarouselImages();
        });
    }

    private Entry currentEntry() {
        List<Entry> items = visible();
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    // Full thread carousel, not just the next three videos.
    // Download only the thumbnails close to what is visible on the screen.
    private void populateUpNext() {
        if (carouselTiles.isEmpty() && !media.isEmpty()) {
            upNextRow.removeAllViews();
            carouselImages.clear();
            requestedThumbs.clear();
            for (int i = 0; i < media.size(); i++) {
                final Entry target = media.get(i);
                final int index = i;
                LinearLayout tile = new LinearLayout(this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setPadding(dp(4), dp(4), dp(4), dp(4));
                tile.setBackground(rounded(0xff262d40, 10, 0xff3c465c));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(99), dp(96));
                lp.rightMargin = dp(7);
                upNextRow.addView(tile, lp);
                ImageView thumb = new ImageView(this);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumb.setBackground(rounded(0xff323b50, 8, 0));
                tile.addView(thumb, new LinearLayout.LayoutParams(-1, dp(68)));
                TextView number = text((i + 1) + " · " + (target.isVideo() ? "Video" : "Image"), 10, SUB);
                number.setSingleLine(true);
                number.setGravity(Gravity.CENTER);
                tile.addView(number);
                tile.setContentDescription("Jump to media " + (i + 1) + " of " + media.size());
                tile.setClickable(true);
                tile.setOnClickListener(v -> {
                    int found = visible().indexOf(target);
                    if (found < 0) {
                        mediaOnly = true;
                        modeButton.setText("All posts");
                        found = index;
                    }
                    position = found;
                    showEntry();
                });
                carouselImages.add(thumb);
                carouselTiles.add(tile);
            }
        }
        Entry active = currentEntry();
        for (int i = 0; i < carouselTiles.size(); i++) {
            boolean selected = active == media.get(i);
            carouselTiles.get(i).setBackground(rounded(
                    selected ? 0xff414f80 : 0xff262d40,
                    10, selected ? PRIMARY : 0xff3c465c));
        }
        nextToggle.setText(nextExpanded ? "Browse " + media.size() + " media · Hide  ▴"
                : "Browse all " + media.size() + " media  ▾");
        if (nextExpanded) nextScroller.post(() -> {
            int where = media.indexOf(active);
            if (where >= 0) nextScroller.smoothScrollTo(Math.max(0, where * dp(106) - dp(60)), 0);
            loadVisibleCarouselImages();
        });
    }

    private void loadVisibleCarouselImages() {
        if (!nextExpanded || carouselImages.isEmpty()) return;
        int start = Math.max(0, nextScroller.getScrollX() / dp(106) - 2);
        int end = Math.min(media.size(), start + 11);
        for (int i = start; i < end; i++) {
            if (!requestedThumbs.add(i)) continue;
            Entry item = media.get(i);
            if (item.thumb != null) MediaThumbs.load(carouselImages.get(i), item.thumb, false);
        }
    }

    private void prepareNextVideo() {
        if (streamCache == null) return;
        if (!preloadEnabled) { streamCache.cancelPrefetch(); return; }
        Entry active = currentEntry();
        int from = active == null ? -1 : media.indexOf(active);
        for (int i = from + 1; i < media.size(); i++) {
            Entry next = media.get(i);
            if (next.isVideo()) {
                streamCache.prefetch(next.url, next.size);
                return;
            }
        }
        streamCache.cancelPrefetch();
    }

    private FrameLayout loadingOverlay(String label, View.OnTouchListener swipe) {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x8c0d1017);
        android.widget.LinearLayout indicator = new android.widget.LinearLayout(this);
        indicator.setOrientation(android.widget.LinearLayout.VERTICAL);
        indicator.setGravity(Gravity.CENTER);
        indicator.setPadding(dp(10),dp(10),dp(10),dp(10));
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(PRIMARY));
        indicator.addView(spinner, new LinearLayout.LayoutParams(dp(46), dp(46)));
        TextView labelView = text(label, 13, FG);
        labelView.setPadding(dp(12), dp(12), dp(12), dp(4));
        labelView.setGravity(Gravity.CENTER);
        labelView.setMaxWidth(dp(260));
        indicator.addView(labelView);
        bufferingLabel = labelView;
        FrameLayout.LayoutParams center = new FrameLayout.LayoutParams(dp(280), -2, Gravity.CENTER);
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
                    long focus = getIntent().getLongExtra("focusPost", 0);
                    if (focus == 0) focus = getSharedPreferences("watch_resume_v1", MODE_PRIVATE)
                            .getLong("post_" + board + "_" + threadId, 0);
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
        getSharedPreferences("watch_resume_v1", MODE_PRIVATE).edit()
                .putBoolean("mode_" + board + "_" + threadId, mediaOnly).apply();
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
        if (feedPlayer != null) {
            feedPlayer.release();
            feedPlayer = null;
        }
        bufferingLabel = null;
        mediaPane.removeAllViews();
        List<Entry> list = visible();
        if (list.isEmpty()) {
            heading.setText("No media in this thread");
            caption.setText("Switch to All posts to read the discussion.");
            counter.setText("0 items");
            saveButton.setEnabled(false);
            upNextRow.removeAllViews();
            videoTools.setVisibility(View.GONE);
            return;
        }
        position = Math.max(0, Math.min(position, list.size() - 1));
        populateUpNext();
        prepareNextVideo();
        final int serial = viewSerial;
        final Entry current = list.get(position);
        getSharedPreferences("watch_resume_v1", MODE_PRIVATE).edit()
                .putString("latest_board", board)
                .putLong("latest_thread", threadId)
                .putLong("latest_post", current.id)
                .putLong("post_" + board + "_" + threadId, current.id)
                .putBoolean("mode_" + board + "_" + threadId, mediaOnly)
                .apply();
        counter.setText((mediaOnly ? "Media " : "Post ") + (position + 1) + " / " + list.size()
                + "  •  " + media.size() + " attachments");
        heading.setText(current.title);
        caption.setText("#" + current.id + " · " + current.author + "\n"
                + (current.text.isEmpty() ? "No description" : shorten(current.text, 120)));
        saveButton.setEnabled(current.hasMedia());
        saveButton.setText(current.hasMedia() ? "Save to vault" : "Text only");
        videoTools.setVisibility(current.isVideo() ? View.VISIBLE : View.GONE);
        fitButton.setText("Fit / Fill");
        status.setText(current.hasMedia()
                ? "Size: " + formatSize(current.size) + " · Swipe to browse"
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
                    } else if (feedPlayer != null && Math.abs(dy) < dp(30)) {
                        feedPlayer.togglePlay();
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
            FrameLayout loading = loadingOverlay("Buffering video...", swipe);
            feedPlayer = new FeedVideoPlayer(this, mediaPane, current.url, current.size,
                    new FeedVideoPlayer.Events() {
                @Override public void onReady() {
                    if (serial == viewSerial) dismissLoading(loading, serial);
                }
                @Override public void onBuffering(int percent, long cached, long total) {
                    if (serial != viewSerial) return;
                    String sizeText = total > 0 ? formatSize(total) : "size unknown";
                    String cachedText = total > 0 ? " · Cached "
                            + Math.min(100, Math.round(cached * 100f / total)) + "%" : "";
                    status.setText("Playback buffered " + percent + "% · File " + sizeText + cachedText);
                    if (bufferingLabel != null && loading.getParent() != null)
                        bufferingLabel.setText("Buffering " + percent + "% of playback\n"
                                + sizeText + " total" + cachedText);
                }
                @Override public void onError(String problem) {
                    if (serial == viewSerial) {
                        showLoadingFailure(loading, problem +
                                "\nUse Original below to open another player.", serial);
                        status.setText("Video playback unavailable · File " + formatSize(current.size));
                    }
                }
            });
            feedPlayer.view().setOnTouchListener(swipe);
            // Keep loading above the video, but not above the separate controls.
            loading.bringToFront();
        } else {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackgroundColor(BG);
            image.setOnTouchListener(swipe);
            mediaPane.addView(image, new FrameLayout.LayoutParams(-1, -1));
            FrameLayout loading = loadingOverlay("Loading image...", swipe);
            MediaThumbs.load(image, current.url, true, ok -> {
                if (serial != viewSerial) return;
                if (ok) dismissLoading(loading, serial);
                else showLoadingFailure(loading, "Image preview is unavailable.\nTry again or use Original below.", serial);
            }, percent -> {
                if (serial != viewSerial) return;
                if (bufferingLabel != null && loading.getParent() != null) {
                    bufferingLabel.setText("Loading image " + percent + "%\n"
                            + formatSize(current.size) + " total");
                }
                status.setText("Image loaded " + percent + "% · File " + formatSize(current.size));
            });
            if (".gif".equals(current.ext))
                status.setText("GIF previews may show one frame on some devices.");
        }
    }

    private void showThreadMenu() {
        Entry first = all.isEmpty() ? null : all.get(0);
        Entry current = currentEntry();
        String title = first != null ? first.title :
                current != null ? current.title : "Thread #" + threadId;
        if (title.startsWith("Post #") && first != null && !first.text.isEmpty())
            title = first.text.substring(0, Math.min(95, first.text.length()));
        final String caption = title;
        final String thumbnail = first != null && first.thumb != null ? first.thumb
                : current != null && current.thumb != null ? current.thumb : "";
        String pinLabel = threadShelf.isPinned(board, threadId) ? "Unpin thread" : "Pin thread";
        new AlertDialog.Builder(this)
                .setTitle("/" + board + "/ · #" + threadId)
                .setItems(new String[]{pinLabel, "Block thread"}, (dialog, which) -> {
                    if (which == 0) {
                        if (threadShelf.isPinned(board, threadId)) {
                            threadShelf.unpin(board, threadId);
                            toast("Thread removed from Pinned");
                        } else if (threadShelf.pin(board, threadId, caption, thumbnail)) {
                            toast("Thread pinned to Discover");
                        } else {
                            toast("This thread is blocked. Unblock it from Discover first.");
                        }
                    } else {
                        new AlertDialog.Builder(this).setTitle("Block thread?")
                                .setMessage("This thread will disappear from ChanVault board catalogs "
                                        + "and Discover. Blocking doesn't remove saved videos. "
                                        + "Unblock it later in Discover → Manage blocked threads.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Block", (d, w) -> {
                                    threadShelf.block(board, threadId, caption, thumbnail);
                                    toast("Thread blocked locally");
                                    finish();
                                }).show();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 50);
        }
    }

    private void saveCurrent() {
        List<Entry> list = visible();
        if (position >= list.size()) return;
        Entry current = list.get(position);
        if (!current.hasMedia()) return;
        askNotificationPermission();
        try {
            JSONArray batch = new JSONArray();
            batch.put(DownloadService.item(current.url, current.title + current.ext, board, threadId));
            DownloadService.enqueue(this, batch);
            status.setText("Download queued • See your notification");
            toast("Download added to the background queue");
        } catch (Exception e) {
            toast("Could not queue download: " + e.getMessage());
        }
    }

    private void openPosts() {
        Intent view = new Intent(this, MainActivity.class);
        view.putExtra("board", board);
        view.putExtra("thread", threadId);
        view.putExtra("sourceFeed", true);
        startActivity(view);
    }

    private void confirmBatch() {
        if (media.isEmpty()) {
            toast("No supported media in this thread");
            return;
        }
        long estimate = 0;
        boolean unknown = false;
        for (Entry e : media) {
            estimate += e.size;
            if (e.size <= 0) unknown = true;
        }
        String message = "Save " + media.size() + " supported attachments to ChanVault's private storage?"
                + "\n\nEstimated size: " + formatSize(estimate) + (unknown ? " or more" : "")
                + "\n\nDownloads can continue when you leave ChanVault. Progress and cancellation appear"
                + " in Android's notification shade. Existing files are skipped.";
        new AlertDialog.Builder(this).setTitle("Download entire thread?")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Queue downloads", (d, w) -> startBatch())
                .show();
    }

    private void startBatch() {
        askNotificationPermission();
        try {
            JSONArray batch = new JSONArray();
            for (Entry e : media) if (e.hasMedia()) {
                batch.put(DownloadService.item(e.url, e.title + e.ext, board, threadId));
            }
            DownloadService.enqueue(this, batch);
            status.setText("Queued " + batch.length() + " files • You can leave the app");
            toast("Downloading in the background — check notifications");
        } catch (Exception e) {
            toast("Could not start download: " + e.getMessage());
        }
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
