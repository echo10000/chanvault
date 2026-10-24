package dev.chanvault.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import android.widget.MediaController;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Experimental, unofficial, read-only imageboard reader.
 * Media is stored ONLY inside Android's private application files directory.
 */
public final class MainActivity extends Activity {
    private static int BG = 0xff102019, PANEL = 0xff172b21;
    private static int TEXT = 0xfff8f5e6, MUTED = 0xffb6ceb9, ACCENT = 0xffb5e077;
    private ThemePalette theme;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout body;
    private LinearLayout catalogPins, catalogRegular;
    private TextView catalogPinnedHeader;
    private Vault vault;
    private ThreadShelf threadShelf;
    private long shownShelfRevision;
    private String board = "g", page = "home";
    private int generation = 0;
    private long lastRequestAt = 0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        theme = ThemePalette.get(this);
        BG = theme.background;
        PANEL = theme.card;
        TEXT = theme.text;
        MUTED = theme.muted;
        ACCENT = theme.accent;
        theme.applyBars(this);
        vault = new Vault(new File(getFilesDir(), "vault"));
        threadShelf = new ThreadShelf(this);
        shownShelfRevision = threadShelf.revision();
        if (getIntent().hasExtra("thread") && getIntent().hasExtra("board")) {
            String openBoard = getIntent().getStringExtra("board");
            long openThread = getIntent().getLongExtra("thread", 0);
            if (openBoard != null && openBoard.matches("[a-z0-9]{1,12}") && openThread > 0) {
                board = openBoard;
                thread(openBoard, openThread);
            } else home();
        } else if (getIntent().getBooleanExtra("openSaved", false)) saved();
        else home();
    }

    @Override protected void onResume() {
        super.onResume();
        if (threadShelf == null || shownShelfRevision == threadShelf.revision()) return;
        shownShelfRevision = threadShelf.revision();
        if ("catalog".equals(page)) catalog(board);
        else if ("home".equals(page)) home();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if ("thread".equals(page) && getIntent().getBooleanExtra("sourceFeed", false)) finish();
        else if ("thread".equals(page)) catalog(board);
        else if ("catalog".equals(page) || "saved".equals(page)) home();
        else super.onBackPressed();
    }

    private int dp(int x) {
        return (int) (getResources().getDisplayMetrics().density * x + .5f);
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        boolean active = ("Browse".equals(text) && !"saved".equals(page))
                || ("Saved Media".equals(text) && "saved".equals(page))
                || "Save privately".equals(text) || "Open board".equals(text);
        b.setTextColor(active ? theme.onAccent : TEXT);
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(active ? ACCENT : theme.chip);
        background.setCornerRadius(dp(14));
        if (!active) background.setStroke(dp(1), theme.border);
        b.setBackground(background);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(11), 0, dp(11), 0);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        });
        if ("Browse".equals(text) || "Saved Media".equals(text)) {
            android.graphics.drawable.Drawable d = getDrawable(
                    "Browse".equals(text) ? R.drawable.ic_posts : R.drawable.ic_download);
            if (d != null) {
                d = d.mutate();
                d.setTint(active ? theme.onAccent : TEXT);
                d.setBounds(0, 0, dp(17), dp(17));
                b.setCompoundDrawables(d, null, null, null);
                b.setCompoundDrawablePadding(dp(6));
            }
        }
        return b;
    }

    private void spacing(int dp) {
        View blank = new View(this);
        body.addView(blank, new LinearLayout.LayoutParams(1, dp(dp)));
    }

    private void card(String title, String detail, Runnable action) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(13), dp(10), dp(13), dp(10));
        android.graphics.drawable.GradientDrawable surface = new android.graphics.drawable.GradientDrawable();
        surface.setColor(PANEL);
        surface.setCornerRadius(dp(15));
        panel.setBackground(surface);
        panel.addView(label(title, 17, TEXT));
        if (detail != null && !detail.isEmpty()) panel.addView(label(detail, 13, MUTED));
        if (action != null) panel.addView(button("Open", action));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        body.addView(panel, lp);
    }


    /** A compact, touchable catalog tile with a real thread thumbnail. */
    private void catalogCard(JSONObject obj, String b, long id, String title, String preview) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(11), dp(11), dp(11), dp(11));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(theme.card);
        bg.setCornerRadius(dp(17));
        bg.setStroke(dp(1), theme.border);
        tile.setBackground(bg);
        tile.setClickable(true);
        tile.setOnClickListener(v -> openFeed(b, id, 0));

        ImageView thumbnail = new ImageView(this);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackgroundColor(theme.chip);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(106), dp(114));
        thumbLp.rightMargin = dp(14);
        tile.addView(thumbnail, thumbLp);
        String tim = obj.optString("tim", "");
        if (tim.matches("[0-9]{5,24}"))
            MediaThumbs.load(thumbnail, "https://i.4cdn.org/" + b + "/" + tim + "s.jpg", false);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView heading = label(title, 16, TEXT);
        heading.setMaxLines(2);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(heading);
        TextView meta = label(obj.optInt("replies") + " replies  •  " +
                (obj.has("ext") ? "Media thread" : "Discussion"), 12, ACCENT);
        info.addView(meta);
        TextView desc = label(preview.isEmpty() ? "Tap to view the thread" : preview, 12, MUTED);
        desc.setMaxLines(3);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(desc);
        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        TextView pin = label(threadShelf.isPinned(b, id) ? "Unpin" : "Pin", 13, ACCENT);
        pin.setPadding(dp(8), dp(6), dp(8), dp(6));
        pin.setBackgroundColor(theme.chip);
        pin.setClickable(true);
        pin.setOnClickListener(v -> {
            if (threadShelf.isPinned(b, id)) {
                threadShelf.unpin(b, id);
                pin.setText("Pin");
                moveCatalogCard(tile, false);
                toast("Thread unpinned from /" + b + "/");
            } else if (threadShelf.pin(b, id, title,
                    tim.matches("[0-9]{5,24}")
                        ? "https://i.4cdn.org/" + b + "/" + tim + "s.jpg" : "")) {
                pin.setText("Unpin");
                moveCatalogCard(tile, true);
                toast("Thread pinned in /" + b + "/");
            }
        });
        quick.addView(pin);
        TextView hide = label("Block", 13, MUTED);
        hide.setPadding(dp(13), dp(6), dp(8), dp(6));
        hide.setClickable(true);
        hide.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Block this thread?")
                .setMessage("Hide /" + b + "/ thread #" + id + " from your catalogs and Discover. "
                        + "You can unblock it later from Discover. No downloaded files are deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Block thread", (dialog, which) -> {
                    String thumb = tim.matches("[0-9]{5,24}")
                            ? "https://i.4cdn.org/" + b + "/" + tim + "s.jpg" : "";
                    threadShelf.block(b, id, title, thumb);
                    if (tile.getParent() instanceof android.view.ViewGroup)
                        ((android.view.ViewGroup) tile.getParent()).removeView(tile);
                    if (catalogPinnedHeader != null && catalogPins != null)
                        catalogPinnedHeader.setVisibility(
                                catalogPins.getChildCount() > 0 ? View.VISIBLE : View.GONE);
                    toast("Thread blocked locally");
                }).show());
        quick.addView(hide);
        info.addView(quick);
        tile.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        LinearLayout destination = threadShelf.isPinned(b, id) ? catalogPins : catalogRegular;
        if (destination != null) destination.addView(tile, lp);
        else body.addView(tile, lp);
    }

    private void moveCatalogCard(View tile, boolean pin) {
        if (catalogPins == null || catalogRegular == null) return;
        if (tile.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup) tile.getParent()).removeView(tile);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        (pin ? catalogPins : catalogRegular).addView(tile, 0, lp);
        if (catalogPinnedHeader != null) catalogPinnedHeader.setVisibility(
                catalogPins.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void showPinnedOutsideCatalog(ThreadShelf.Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(theme.card);
        bg.setCornerRadius(dp(13));
        bg.setStroke(dp(1), theme.border);
        row.setBackground(bg);
        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView name = label("✿  " + item.title, 15, TEXT);
        name.setMaxLines(2);
        textCol.addView(name);
        textCol.addView(label("Thread #" + item.id + " · Not in current board catalog", 11, MUTED));
        LinearLayout buttons = new LinearLayout(this);
        TextView open = label("Open", 13, ACCENT);
        open.setPadding(dp(6), dp(6), dp(12), dp(6));
        open.setOnClickListener(v -> openFeed(item.board, item.id, 0));
        buttons.addView(open);
        TextView unpin = label("Unpin", 13, MUTED);
        unpin.setPadding(dp(10), dp(6), dp(10), dp(6));
        unpin.setOnClickListener(v -> {
            threadShelf.unpin(item.board, item.id);
            if (row.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) row.getParent()).removeView(row);
            catalogPinnedHeader.setVisibility(catalogPins.getChildCount() > 0 ?
                    View.VISIBLE : View.GONE);
            toast("Unpinned from /" + item.board + "/");
        });
        buttons.addView(unpin);
        textCol.addView(buttons);
        row.addView(textCol, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        catalogPins.addView(row, lp);
    }

    private void openFeed(String b, long id, long focusPost) {
        Intent intent = new Intent(this, ThreadFeedActivity.class);
        intent.putExtra("board", b);
        intent.putExtra("thread", id);
        if (focusPost > 0) intent.putExtra("focusPost", focusPost);
        startActivity(intent);
    }

    private void shell(String title, String nextPage) {
        page = nextPage;
        generation++;
        if (threadShelf != null) shownShelfRevision = threadShelf.revision();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        TextView header = label("chanvault.  ·  " + title, 19, TEXT);
        header.setPadding(dp(16), dp(17), dp(16), dp(13));
        root.addView(header);
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(9), dp(14), dp(20));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(12), dp(8), dp(12), dp(11));
        LinearLayout.LayoutParams browseLp = new LinearLayout.LayoutParams(0, dp(53), 1);
        browseLp.rightMargin = dp(6);
        nav.addView(button("Browse", this::home), browseLp);
        LinearLayout.LayoutParams savedLp = new LinearLayout.LayoutParams(0, dp(53), 1);
        savedLp.leftMargin = dp(6);
        nav.addView(button("Saved Media", this::saved), savedLp);
        root.addView(nav);
        setContentView(root);
    }

    private void home() {
        shell("Discover", "home");
        TextView look = label("✿  Appearance", 18, TEXT);
        look.setPadding(dp(3), dp(7), dp(3), dp(8));
        body.addView(look);
        LinearLayout themes = new LinearLayout(this);
        LinearLayout.LayoutParams themeLp = new LinearLayout.LayoutParams(0, dp(49), 1);
        themeLp.rightMargin = dp(6);
        themes.addView(button("Theme: " + (theme.index < 2 ? "Yotsuba" :
                        theme.index == 2 ? "Midnight" : "Sakura"), this::pickTheme), themeLp);
        LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(0, dp(49), 1);
        accentLp.leftMargin = dp(6);
        themes.addView(button("Accent: " + (theme.accentIndex == 0 ? "Default" :
                        ThemePalette.ACCENTS[theme.accentIndex]), this::pickAccent), accentLp);
        body.addView(themes);
        spacing(10);
        body.addView(button("Check for signed updates", () -> UpdateChecker.check(this)));
        spacing(12);
        // Pins are deliberately shown only inside their own board catalog.
        body.addView(button("Manage blocked threads (" +
                threadShelf.blocked().size() + ")", this::showBlockedThreads));
        spacing(14);
        android.content.SharedPreferences resumePrefs = getSharedPreferences("watch_resume_v1", MODE_PRIVATE);
        String lastBoard = resumePrefs.getString("latest_board", "");
        long lastThread = resumePrefs.getLong("latest_thread", 0);
        long lastPost = resumePrefs.getLong("latest_post", 0);
        if (lastBoard.matches("[a-z0-9]{1,12}") && lastThread > 0
                && !threadShelf.isBlocked(lastBoard, lastThread)) {
            card("Continue watching", "Return to /" + lastBoard + "/  ·  thread #" + lastThread,
                    () -> openFeed(lastBoard, lastThread, lastPost));
        }
        final int visibleGeneration = generation;
        BoardsHome home = new BoardsHome(this, body, new BoardsHome.Navigator() {
            @Override public void board(String requestedBoard) { catalog(requestedBoard); }
            @Override public void thread(String requestedBoard, long threadId) {
                openFeed(requestedBoard, threadId, 0);
            }
        }, () -> generation == visibleGeneration && !isFinishing() && !isDestroyed());
        home.render(board);
    }

    private void pickTheme() {
        new AlertDialog.Builder(this)
                .setTitle("Choose ChanVault theme")
                .setSingleChoiceItems(ThemePalette.THEMES, theme.index, (dialog, choice) -> {
                    ThemePalette.chooseTheme(this, choice);
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pickAccent() {
        new AlertDialog.Builder(this)
                .setTitle("Choose highlight color")
                .setSingleChoiceItems(ThemePalette.ACCENTS, theme.accentIndex, (dialog, choice) -> {
                    ThemePalette.chooseAccent(this, choice);
                    dialog.dismiss();
                    recreate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showPinnedThreads() {
        List<ThreadShelf.Item> pins = threadShelf.pinned();
        TextView heading = label("Pinned threads", 19, TEXT);
        body.addView(heading);
        if (pins.isEmpty()) {
            body.addView(label("Pin any thread from its catalog or player to find it here.", 13, MUTED));
        } else {
            for (ThreadShelf.Item item : pins) {
                LinearLayout panel = new LinearLayout(this);
                panel.setOrientation(LinearLayout.HORIZONTAL);
                panel.setGravity(Gravity.CENTER_VERTICAL);
                panel.setPadding(dp(11), dp(11), dp(11), dp(11));
                android.graphics.drawable.GradientDrawable bg =
                        new android.graphics.drawable.GradientDrawable();
                bg.setColor(theme.card);
                bg.setCornerRadius(dp(16));
                panel.setBackground(bg);
                if (!item.thumbnail.isEmpty()) {
                    ImageView image = new ImageView(this);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(dp(66), dp(72));
                    imgLp.rightMargin = dp(10);
                    panel.addView(image, imgLp);
                    MediaThumbs.load(image, item.thumbnail, false);
                }
                LinearLayout words = new LinearLayout(this);
                words.setOrientation(LinearLayout.VERTICAL);
                TextView title = label(item.title, 15, TEXT);
                title.setMaxLines(2);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                words.addView(title);
                words.addView(label(item.label(), 12, MUTED));
                LinearLayout controls = new LinearLayout(this);
                TextView open = label("Open", 13, ACCENT);
                open.setPadding(dp(7), dp(5), dp(10), dp(5));
                open.setOnClickListener(v -> openFeed(item.board, item.id, 0));
                controls.addView(open);
                TextView unpin = label("Unpin", 13, MUTED);
                unpin.setPadding(dp(7), dp(5), dp(10), dp(5));
                unpin.setOnClickListener(v -> {
                    threadShelf.unpin(item.board, item.id);
                    home();
                });
                controls.addView(unpin);
                words.addView(controls);
                panel.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(9);
                body.addView(panel, lp);
            }
        }
        List<ThreadShelf.Item> hidden = threadShelf.blocked();
        body.addView(button("Manage blocked threads (" + hidden.size() + ")",
                this::showBlockedThreads));
        spacing(15);
    }

    private void showBlockedThreads() {
        List<ThreadShelf.Item> items = threadShelf.blocked();
        if (items.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Blocked threads")
                    .setMessage("No threads are blocked. Blocking hides a thread only inside ChanVault.")
                    .setPositiveButton("Done", null).show();
            return;
        }
        String[] names = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            ThreadShelf.Item item = items.get(i);
            names[i] = item.label() + " — " + item.title;
        }
        new AlertDialog.Builder(this).setTitle("Blocked threads · Tap to unblock")
                .setItems(names, (dialog, which) -> {
                    ThreadShelf.Item item = items.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle("Unblock thread?")
                            .setMessage(item.title + "\n" + item.label())
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Unblock", (d, w) -> {
                                threadShelf.unblock(item.board, item.id);
                                home();
                                toast("Thread unblocked");
                            }).show();
                }).setNegativeButton("Done", null).show();
    }

    private static String validateBoard(String raw) {
        String s = raw.toLowerCase(Locale.US).trim().replace("/", "");
        if (!s.matches("[a-z0-9]{1,12}")) throw new IllegalArgumentException("Invalid board name");
        return s;
    }

    private void catalog(String requested) {
        try { board = validateBoard(requested); }
        catch (Exception e) { toast(e.getMessage()); return; }
        shell("/" + board + "/ catalog", "catalog");
        int ticket = generation;
        final String b = board;
        catalogPinnedHeader = label("✿  Pinned in /" + b + "/", 19, ACCENT);
        catalogPinnedHeader.setPadding(dp(3), dp(8), dp(3), dp(8));
        catalogPinnedHeader.setVisibility(View.GONE);
        body.addView(catalogPinnedHeader);
        catalogPins = new LinearLayout(this);
        catalogPins.setOrientation(LinearLayout.VERTICAL);
        body.addView(catalogPins);
        TextView regularHeading = label("Threads in /" + b + "/", 17, TEXT);
        regularHeading.setPadding(dp(3), dp(8), dp(3), dp(8));
        body.addView(regularHeading);
        catalogRegular = new LinearLayout(this);
        catalogRegular.setOrientation(LinearLayout.VERTICAL);
        body.addView(catalogRegular);
        catalogRegular.addView(label("Loading threads and thumbnails…", 14, MUTED));
        worker.execute(() -> {
            try {
                JSONArray pages = new JSONArray(fetch("https://a.4cdn.org/" + b + "/catalog.json"));
                List<JSONObject> threads = new ArrayList<>();
                for (int i = 0; i < pages.length(); i++) {
                    JSONArray rows = pages.getJSONObject(i).getJSONArray("threads");
                    for (int j = 0; j < rows.length(); j++) threads.add(rows.getJSONObject(j));
                }
                ui.post(() -> {
                    if (generation != ticket) return;
                    catalogPins.removeAllViews();
                    catalogRegular.removeAllViews();
                    List<ThreadShelf.Item> pins = new ArrayList<>();
                    for (ThreadShelf.Item pin : threadShelf.pinned())
                        if (b.equals(pin.board) && !threadShelf.isBlocked(b, pin.id)) pins.add(pin);
                    java.util.Map<Long, JSONObject> live = new java.util.HashMap<>();
                    for (JSONObject obj : threads) live.put(obj.optLong("no"), obj);
                    for (ThreadShelf.Item pin : pins) {
                        JSONObject obj = live.get(pin.id);
                        if (obj == null) showPinnedOutsideCatalog(pin);
                        else {
                            String title = clean(obj.optString("sub", ""));
                            if (title.isEmpty()) title = pin.title;
                            String preview = clean(obj.optString("com", ""));
                            if (preview.length() > 180) preview = preview.substring(0, 180) + "…";
                            catalogCard(obj, b, pin.id, title, preview);
                        }
                    }
                    catalogPinnedHeader.setVisibility(
                            catalogPins.getChildCount() > 0 ? View.VISIBLE : View.GONE);
                    int shown = 0;
                    for (JSONObject obj : threads) {
                        if (shown >= 100) break;
                        long id = obj.optLong("no");
                        if (threadShelf.isBlocked(b, id) || threadShelf.isPinned(b, id)) continue;
                        shown++;
                        String title = clean(obj.optString("sub", ""));
                        if (title.isEmpty()) title = "Thread #" + id;
                        String preview = clean(obj.optString("com", ""));
                        if (preview.length() > 180) preview = preview.substring(0, 180) + "…";
                        catalogCard(obj, b, id, title, preview);
                    }
                    if (shown == 0) catalogRegular.addView(label("No other visible threads.", 13, MUTED));
                    if (shown < threads.size()) catalogRegular.addView(
                            label("Showing up to 100 non-pinned threads.", 12, MUTED));
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (generation != ticket) return;
                    catalogRegular.removeAllViews();
                    catalogRegular.addView(label("Board load failed: " + e.getMessage(), 13, MUTED));
                    catalogRegular.addView(button("Retry catalog", () -> catalog(b)));
                });
            }
        });
    }

    private void thread(String b, long id) {
        shell("Thread #" + id, "thread");
        int ticket = generation;
        body.addView(label("Loading posts…", 14, MUTED));
        worker.execute(() -> {
            try {
                JSONObject response = new JSONObject(fetch("https://a.4cdn.org/" + b + "/thread/" + id + ".json"));
                JSONArray posts = response.getJSONArray("posts");
                JSONObject firstPost = posts.optJSONObject(0);
                final String threadCaption = firstPost == null ? "Untitled thread" :
                        ThreadTitles.select(clean(firstPost.optString("sub", "")),
                                clean(firstPost.optString("com", "")), id);
                ui.post(() -> {
                    if (ticket != generation) return;
                    body.removeAllViews();
                    body.addView(button("Back to /" + b + "/", () -> catalog(b)));
                    for (int i = 0; i < posts.length(); i++) {
                        JSONObject post = posts.optJSONObject(i);
                        if (post == null) continue;
                        long postId = post.optLong("no");
                        String text = clean(post.optString("com", ""));
                        if (text.length() > 1500) text = text.substring(0, 1500) + "…";
                        card("Post #" + postId + "  ·  " + clean(post.optString("name", "Anonymous")), text, null);
                        String ext = post.optString("ext", "").toLowerCase(Locale.US);
                        String tim = post.optString("tim", "");
                        if (!ext.matches("\\.(webm|mp4|gif|jpg|jpeg|png)") || !tim.matches("[0-9]{5,24}")) continue;
                        String mediaUrl = "https://i.4cdn.org/" + b + "/" + tim + ext;
                        String display = post.optString("filename", "attachment") + ext;
                        if (display.length() > 100) display = display.substring(0, 100);
                        String name = display;
                        body.addView(label("Attachment: " + display, 13, MUTED));
                        Button download = button("Save privately", () -> download(mediaUrl, name, b, id, threadCaption));
                        body.addView(download);
                        body.addView(button("Preview in swipe feed", () -> openFeed(b, id, postId)));
                        body.addView(button("Open original media in browser", () -> {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mediaUrl));
                            startActivity(intent);
                        }));
                        spacing(9);
                    }
                });
            } catch (Exception e) { error(ticket, "Thread error: " + e.getMessage()); }
        });
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 51);
    }

    private void download(String url, String name, String sourceBoard, long sourceThread,
                          String threadCaption) {
        askNotifications();
        try {
            JSONArray list = new JSONArray();
            list.put(DownloadService.item(url, name, sourceBoard, sourceThread, threadCaption));
            DownloadService.enqueue(this, list);
            toast("Download queued in the background");
        } catch (Exception ex) {
            toast("Couldn't queue: " + ex.getMessage());
        }
    }

    private void saved() {
        shell("Saved Media", "saved");
        int ticket = generation;
        TextView queue = label(DownloadService.progress(this), 13, MUTED);
        queue.setPadding(dp(14), dp(12), dp(14), dp(12));
        android.graphics.drawable.GradientDrawable queueBg = new android.graphics.drawable.GradientDrawable();
        queueBg.setColor(theme.card);
        queueBg.setCornerRadius(dp(13));
        queue.setBackground(queueBg);
        body.addView(queue);
        body.addView(button("Refresh downloads", this::saved));
        body.addView(button("Stop queued downloads", () -> {
            DownloadService.cancel(this);
            toast("Stop requested. Current file may finish before the queue stops.");
        }));
        body.addView(label("Loading saved media…", 14, MUTED));
        worker.execute(() -> {
            try {
                List<JSONObject> items = vault.list();
                ui.post(() -> {
                    if (ticket != generation) return;
                    body.removeAllViews();
                    TextView progress = label(DownloadService.progress(this), 12, MUTED);
                    progress.setPadding(dp(12), dp(12), dp(12), dp(12));
                    progress.setBackgroundColor(theme.card);
                    body.addView(progress);
                    LinearLayout actions = new LinearLayout(this);
                    LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(0, dp(46), 1);
                    refreshLp.rightMargin = dp(6);
                    actions.addView(button("Refresh", this::saved), refreshLp);
                    LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(0, dp(46), 1);
                    stopLp.leftMargin = dp(6);
                    actions.addView(button("Stop downloads", () -> {
                        DownloadService.cancel(this);
                        toast("Stopping after current download");
                    }), stopLp);
                    LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
                    actionsLp.topMargin = dp(9);
                    body.addView(actions, actionsLp);
                    LinearLayout backups = new LinearLayout(this);
                    LinearLayout.LayoutParams exportLp = new LinearLayout.LayoutParams(0, dp(46), 1);
                    exportLp.rightMargin = dp(6);
                    backups.addView(button("Export vault ZIP", this::exportVault), exportLp);
                    LinearLayout.LayoutParams restoreLp = new LinearLayout.LayoutParams(0, dp(46), 1);
                    restoreLp.leftMargin = dp(6);
                    backups.addView(button("Restore ZIP", this::importVault), restoreLp);
                    LinearLayout.LayoutParams backupsLp = new LinearLayout.LayoutParams(-1, -2);
                    backupsLp.topMargin = dp(9);
                    body.addView(backups, backupsLp);
                    spacing(10);
                    LinearLayout gallery = new LinearLayout(this);
                    gallery.setOrientation(LinearLayout.VERTICAL);
                    body.addView(gallery);
                    new SavedLibrary(this, gallery, items, this::saved).draw();
                });
            } catch (Exception e) { error(ticket, "Saved media error: " + e.getMessage()); }
        });
    }

    private void exportVault() {
        Intent pick = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("application/zip");
        pick.putExtra(Intent.EXTRA_TITLE, "ChanVault-vault-backup.zip");
        startActivityForResult(pick, 201);
    }

    private void importVault() {
        new AlertDialog.Builder(this)
                .setTitle("Restore private vault?")
                .setMessage("Restoring is supported into an EMPTY vault. Existing private files "
                        + "will not be overwritten. Select a ZIP previously exported by ChanVault.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose backup", (d,w) -> {
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.setType("application/zip");
                    startActivityForResult(pick, 202);
                }).show();
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (resultCode != RESULT_OK || result == null || result.getData() == null) return;
        Uri uri = result.getData();
        if (requestCode == 201) {
            toast("Preparing the private vault ZIP backup...");
            VaultBackup.exportTo(this, uri, this::saved);
        } else if (requestCode == 202) {
            toast("Restoring private vault from ZIP...");
            VaultBackup.importFrom(this, uri, this::saved);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void error(int ticket, String message) {
        ui.post(() -> {
            if (ticket != generation) return;
            body.removeAllViews();
            card("Unable to load", message, null);
        });
    }

    private static String clean(String html) {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
                .toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    private String fetch(String address) throws Exception {
        long delay = 1100 - (System.currentTimeMillis() - lastRequestAt);
        if (delay > 0) Thread.sleep(delay);
        lastRequestAt = System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "ChanVault/0.1 Android unofficial reader");
        try {
            if (conn.getResponseCode() != 200)
                throw new Exception("HTTP " + conn.getResponseCode());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = conn.getInputStream()) {
                byte[] bytes = new byte[8192];
                int n;
                while ((n = input.read(bytes)) != -1) {
                    if (output.size() + n > 12 * 1024 * 1024)
                        throw new Exception("Response too large");
                    output.write(bytes, 0, n);
                }
            }
            return output.toString("UTF-8");
        } finally { conn.disconnect(); }
    }

    /** Saved files and metadata are only inside getFilesDir()/vault/. */
    static final class Vault {
        private final File directory, index;
        private static final long LIMIT = 250L * 1024 * 1024;
        Vault(File directory) {
            this.directory = directory;
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Vault unavailable");
            this.index = new File(directory, "index.json");
        }

        synchronized List<JSONObject> list() throws Exception {
            JSONArray arr = load();
            List<JSONObject> result = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                String name = item.optString("file", "");
                if (name.matches("[a-f0-9-]{36}\\.(webm|mp4|gif|jpg|jpeg|png)")
                        && new File(directory, name).isFile()) result.add(item);
            }
            return result;
        }


        synchronized boolean exists(String source) throws Exception {
            for (JSONObject row : list()) {
                if (source.equals(row.optString("source"))) return true;
            }
            return false;
        }

        synchronized void delete(String id) throws Exception {
            JSONArray old = load(), updated = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.getJSONObject(i);
                if (!id.equals(item.optString("id"))) updated.put(item);
                else {
                    String name = item.optString("file", "");
                    if (name.matches("[a-f0-9-]{36}\\.(webm|mp4|gif|jpg|jpeg|png)"))
                        new File(directory, name).delete();
                }
            }
            persist(updated);
        }

        /**
         * Remove selected saved items in one index transaction.
         * Persist the new index before deleting private files, so failed
         * persistence cannot silently discard the user’s library.
         */
        synchronized int deleteMany(java.util.Set<String> selectedIds) throws Exception {
            if (selectedIds == null || selectedIds.isEmpty()) return 0;
            JSONArray old = load(), updated = new JSONArray();
            java.util.ArrayList<File> removedFiles = new java.util.ArrayList<>();
            int removed = 0;
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.getJSONObject(i);
                if (!selectedIds.contains(item.optString("id"))) {
                    updated.put(item);
                    continue;
                }
                String name = item.optString("file", "");
                if (!name.matches("[a-f0-9-]{36}\\.(webm|mp4|gif|jpg|jpeg|png)")) {
                    updated.put(item);
                    continue;
                }
                removedFiles.add(new File(directory, name));
                removed++;
            }
            if (removed == 0) return 0;
            persist(updated);
            for (File mediaFile : removedFiles) {
                if (mediaFile.isFile()) mediaFile.delete();
            }
            return removed;
        }

        void download(String source, String title, String board, long threadId) throws Exception {
            download(source, title, board, threadId, "");
        }

        void download(String source, String title, String board, long threadId,
                      String threadTitle) throws Exception {
            if (exists(source)) return;
            if (!MediaAddress.isValidMediaUrl(source))
                throw new Exception("Unsupported HTTPS media");
            String ext = source.substring(source.lastIndexOf('.') + 1).toLowerCase(Locale.US);
            String id = UUID.randomUUID().toString();
            String name = id + "." + ext;
            File tmp = new File(directory, name + ".part");
            File target = new File(directory, name);
            HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            long size = 0;
            try {
                if (connection.getResponseCode() != 200) throw new Exception("HTTP " + connection.getResponseCode());
                if (connection.getContentLengthLong() > LIMIT) throw new Exception("Larger than 250 MB");
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = input.read(buf)) != -1) {
                        size += n;
                        if (size > LIMIT) throw new Exception("Larger than 250 MB");
                        output.write(buf, 0, n);
                    }
                    output.getFD().sync();
                }
                if (size == 0 || !tmp.renameTo(target)) throw new Exception("Couldn't save media");
                JSONObject row = new JSONObject();
                row.put("id", id); row.put("file", name); row.put("source", source);
                row.put("title", title.substring(0, Math.min(title.length(), 120)));
                row.put("board", board); row.put("thread", threadId);
                row.put("threadTitle", ThreadTitles.clean(threadTitle));
                row.put("size", size); row.put("savedAt", System.currentTimeMillis());
                synchronized (this) {
                    JSONArray data = load();
                    data.put(row);
                    try { persist(data); }
                    catch (Exception e) { target.delete(); throw e; }
                }
            } finally {
                connection.disconnect();
                if (tmp.exists()) tmp.delete();
            }
        }

        private JSONArray load() throws Exception {
            if (!index.isFile()) return new JSONArray();
            return new JSONArray(new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8));
        }

        private void persist(JSONArray data) throws Exception {
            File tmp = new File(directory, "index.json.tmp");
            try (FileOutputStream output = new FileOutputStream(tmp)) {
                output.write(data.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            if (!tmp.renameTo(index)) throw new Exception("Couldn't save library index");
        }
    }

    /** Fully internal playback, without granting other apps access to private media. */
    public static final class Player extends Activity {
        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            getWindow().setStatusBarColor(BG);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(BG);
            Button back = new Button(this);
            back.setText("Back");
            back.setOnClickListener(v -> finish());
            root.addView(back);
            String name = getIntent().getStringExtra("file");
            if (name == null || !name.matches("[a-f0-9-]{36}\\.(webm|mp4|gif|png|jpg|jpeg)")) {
                TextView message = new TextView(this);
                message.setText("Invalid private media file.");
                root.addView(message);
                setContentView(root);
                return;
            }
            File file = new File(new File(getFilesDir(), "vault"), name);
            if (!file.isFile()) {
                TextView message = new TextView(this);
                message.setText("Media no longer exists.");
                root.addView(message);
                setContentView(root);
                return;
            }
            if (name.endsWith(".webm") || name.endsWith(".mp4")) {
                VideoView video = new VideoView(this);
                root.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
                MediaController controller = new MediaController(this);
                controller.setAnchorView(video);
                video.setMediaController(controller);
                video.setVideoURI(Uri.fromFile(file));
                video.setOnPreparedListener(player -> {
                    player.setLooping(true);
                    video.start();
                });
            } else {
                ImageView image = new ImageView(this);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                if (name.endsWith(".gif") && Build.VERSION.SDK_INT >= 28) {
                    try {
                        android.graphics.ImageDecoder.Source source =
                                android.graphics.ImageDecoder.createSource(file);
                        android.graphics.drawable.Drawable drawable =
                                android.graphics.ImageDecoder.decodeDrawable(source);
                        image.setImageDrawable(drawable);
                        if (drawable instanceof android.graphics.drawable.AnimatedImageDrawable)
                            ((android.graphics.drawable.AnimatedImageDrawable) drawable).start();
                    } catch (Exception ex) {
                        image.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
                    }
                } else image.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
                root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
            }
            setContentView(root);
        }
    }
}
