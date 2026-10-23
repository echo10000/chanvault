package dev.chanvault.reader;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Html;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * A quiet, touch-first board discovery screen.
 * Favorites are private SharedPreferences.  Only one preview API request is
 * issued at a time (>= 1.15 seconds apart) and responses are cached briefly.
 */
final class BoardsHome {
    interface Navigator {
        void board(String board);
        void thread(String board, long threadId);
    }

    private static final int BG = 0xff101116;
    private static final int CARD = 0xff1e2330;
    private static final int BORDER = 0xff343b4c;
    private static final int TEXT = 0xfff1f3ff;
    private static final int MUTED = 0xffaab3cb;
    private static final int ACCENT = 0xffb6bdff;
    private static final int CHIP = 0xff2a3041;
    private static final String[] DEFAULTS = {"g", "wg", "w", "a", "v", "gif"};
    private static final ExecutorService PREVIEW_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Map<String, Spotlight> CACHE =
            Collections.synchronizedMap(new HashMap<>());
    private static long lastRequestAt;

    private final Activity activity;
    private final LinearLayout parent;
    private final Navigator navigation;
    private final BooleanSupplier stillVisible;
    private final SharedPreferences preferences;
    private final Set<String> favorites = new LinkedHashSet<>();
    private LinearLayout favoriteContent;
    private String currentBoard;

    private static final class Spotlight {
        long id, fetchedAt;
        int replies;
        String title, summary, thumb;
    }

    BoardsHome(Activity activity, LinearLayout parent, Navigator navigation,
               BooleanSupplier stillVisible) {
        this.activity = activity;
        this.parent = parent;
        this.navigation = navigation;
        this.stillVisible = stillVisible;
        this.preferences = activity.getSharedPreferences("boards_home_v1", Context.MODE_PRIVATE);
        for (String board : preferences.getStringSet("favorites", Collections.emptySet())) {
            if (valid(board)) favorites.add(board);
        }
    }

    void render(String selectedBoard) {
        currentBoard = valid(selectedBoard) ? selectedBoard : "g";

        TextView heading = text("Your boards, your way.", 24, TEXT, true);
        heading.setPadding(dp(3), dp(4), dp(3), dp(1));
        parent.addView(heading);

        TextView tagline = text("A quick look at what's happening before you dive in.", 13, MUTED, false);
        tagline.setPadding(dp(3), 0, dp(3), dp(16));
        parent.addView(tagline);

        LinearLayout search = row();
        search.setPadding(dp(10), dp(4), dp(5), dp(4));
        search.setBackground(round(CARD, 16, BORDER));
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setText("/" + currentBoard + "/");
        input.setSelectAllOnFocus(true);
        input.setHint("Enter a board, e.g. /g/");
        input.setTextSize(15);
        input.setBackgroundColor(0x00000000);
        search.addView(input, new LinearLayout.LayoutParams(0, dp(51), 1));

        TextView go = pill("Explore", 13, ACCENT, CHIP);
        go.setOnClickListener(v -> {
            String board = input.getText().toString().toLowerCase(java.util.Locale.ROOT).trim().replace("/", "");
            if (!valid(board)) {
                Toast.makeText(activity, "Use a board name like /g/ or /wg/", Toast.LENGTH_SHORT).show();
                return;
            }
            rememberRecent(board);
            InputMethodManager keyboard =
                    (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
            navigation.board(board);
        });
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(dp(88), dp(44));
        search.addView(go, goLp);
        parent.addView(search, new LinearLayout.LayoutParams(-1, -2));
        addSpace(new View(activity), 16);

        LinearLayout favoritesHeader = row();
        favoritesHeader.setGravity(Gravity.CENTER_VERTICAL);
        favoritesHeader.addView(text("Favorites", 18, TEXT, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView hint = text("Tap a heart to pin", 12, MUTED, false);
        favoritesHeader.addView(hint);
        parent.addView(favoritesHeader);
        addSpace(new View(activity), 10);

        favoriteContent = new LinearLayout(activity);
        favoriteContent.setOrientation(LinearLayout.VERTICAL);
        favoriteContent.setPadding(dp(13), dp(11), dp(13), dp(11));
        favoriteContent.setBackground(round(CARD, 16, BORDER));
        parent.addView(favoriteContent);
        updateFavorites();
        addSpace(new View(activity), 21);

        parent.addView(text("Discover boards", 18, TEXT, true));
        TextView subtitle = text("A highlight from each board, refreshed as you browse.", 12, MUTED, false);
        subtitle.setPadding(0, dp(3), 0, dp(9));
        parent.addView(subtitle);

        Set<String> boards = new LinkedHashSet<>(Arrays.asList(DEFAULTS));
        boards.addAll(favorites);
        for (String recent : recentBoards()) boards.add(recent);
        int shown = 0;
        for (String board : boards) {
            if (shown++ >= 14) break;
            renderBoard(board);
        }

        TextView foot = text("Thread highlights come from the 4chan read-only API. "
                + "Boards may contain mature content. Saved media stays inside ChanVault.", 11, MUTED, false);
        foot.setPadding(dp(3), dp(14), dp(3), dp(10));
        parent.addView(foot);
    }

    private void renderBoard(String board) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(11), dp(13), dp(12));
        card.setBackground(round(CARD, 18, BORDER));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.bottomMargin = dp(12);
        parent.addView(card, cardLp);

        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBlock = new LinearLayout(activity);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.addView(text("/" + board + "/  " + boardName(board), 17, TEXT, true));
        titleBlock.addView(text(boardAbout(board), 12, MUTED, false));
        titleRow.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));

        TextView heart = pill(favorites.contains(board) ? "\u2665" : "\u2661",
                23, favorites.contains(board) ? ACCENT : MUTED, CHIP);
        heart.setContentDescription("Toggle favorite for /" + board + "/");
        titleRow.addView(heart, new LinearLayout.LayoutParams(dp(50), dp(48)));
        heart.setOnClickListener(v -> {
            if (favorites.contains(board)) favorites.remove(board);
            else favorites.add(board);
            preferences.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
            heart.setText(favorites.contains(board) ? "\u2665" : "\u2661");
            heart.setTextColor(favorites.contains(board) ? ACCENT : MUTED);
            updateFavorites();
        });
        card.addView(titleRow);
        addGap(card, 12);

        LinearLayout previewRow = row();
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(round(CHIP, 12, 0));
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(dp(102), dp(106));
        imgLp.rightMargin = dp(12);
        previewRow.addView(image, imgLp);

        LinearLayout previewWords = new LinearLayout(activity);
        previewWords.setGravity(Gravity.CENTER_VERTICAL);
        previewWords.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrow = text("LOADING HIGHLIGHT", 10, ACCENT, true);
        previewWords.addView(eyebrow);
        TextView threadTitle = text("Looking for an active thread...", 14, TEXT, true);
        threadTitle.setMaxLines(2);
        threadTitle.setEllipsize(TextUtils.TruncateAt.END);
        threadTitle.setPadding(0, dp(4), 0, dp(3));
        previewWords.addView(threadTitle);
        TextView threadMeta = text("Board preview", 12, MUTED, false);
        threadMeta.setMaxLines(2);
        previewWords.addView(threadMeta);
        previewRow.addView(previewWords, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(previewRow);

        addGap(card, 11);
        LinearLayout actions = row();
        TextView browse = pill("Browse board", 13, TEXT, CHIP);
        actions.addView(browse, new LinearLayout.LayoutParams(0, dp(46), 1));
        browse.setOnClickListener(v -> { rememberRecent(board); navigation.board(board); });

        TextView watch = pill("View thread", 13, 0xff171a2b, ACCENT);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        actionLp.leftMargin = dp(8);
        actions.addView(watch, actionLp);
        watch.setAlpha(.5f);
        watch.setEnabled(false);
        card.addView(actions);

        Spotlight cached;
        synchronized (CACHE) { cached = CACHE.get(board); }
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < 180000) {
            applySpotlight(board, cached, eyebrow, threadTitle, threadMeta, image, watch);
            return;
        }
        PREVIEW_EXECUTOR.execute(() -> {
            if (!stillVisible.getAsBoolean()) return;
            try {
                Spotlight highlight = loadSpotlight(board);
                if (highlight != null) {
                    synchronized (CACHE) { CACHE.put(board, highlight); }
                }
                activity.runOnUiThread(() -> {
                    if (!stillVisible.getAsBoolean() || activity.isFinishing()) return;
                    if (highlight == null) {
                        eyebrow.setText("NO RECENT MEDIA");
                        threadTitle.setText("Explore this board");
                        threadMeta.setText("Tap Browse board to see all its threads");
                    } else applySpotlight(board, highlight, eyebrow, threadTitle, threadMeta, image, watch);
                });
            } catch (Exception ex) {
                activity.runOnUiThread(() -> {
                    if (!stillVisible.getAsBoolean() || activity.isFinishing()) return;
                    eyebrow.setText("PREVIEW UNAVAILABLE");
                    threadTitle.setText("Tap to explore /" + board + "/");
                    threadMeta.setText("This board may be unavailable or the network is offline");
                });
            }
        });
    }

    private void applySpotlight(String board, Spotlight s,
                                TextView eyebrow, TextView threadTitle, TextView meta,
                                ImageView thumbnail, TextView watch) {
        eyebrow.setText("ACTIVE THREAD  \u00b7  " + s.replies + " REPLIES");
        threadTitle.setText(s.title);
        meta.setText(s.summary.isEmpty() ? "Tap to preview its posts and media" : s.summary);
        if (s.thumb != null) MediaThumbs.load(thumbnail, s.thumb, false);
        watch.setEnabled(true);
        watch.setAlpha(1f);
        watch.setOnClickListener(v -> { rememberRecent(board); navigation.thread(board, s.id); });
    }

    private static Spotlight loadSpotlight(String board) throws Exception {
        long pause = 1150 - (System.currentTimeMillis() - lastRequestAt);
        if (pause > 0) Thread.sleep(pause);
        lastRequestAt = System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://a.4cdn.org/" + board + "/catalog.json").openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(22000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "ChanVault/0.3 (unofficial imageboard reader)");
        try {
            if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
            JSONArray pages;
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream data = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (data.size() + read > 12 * 1024 * 1024)
                        throw new Exception("Catalog response too large");
                    data.write(buffer, 0, read);
                }
                pages = new JSONArray(data.toString("UTF-8"));
            }
            JSONObject choice = null;
            double best = -1;
            // Prefer lively threads on the first few pages, never pinned rules threads.
            for (int p = 0; p < Math.min(3, pages.length()); p++) {
                JSONArray threads = pages.getJSONObject(p).optJSONArray("threads");
                if (threads == null) continue;
                for (int i = 0; i < threads.length(); i++) {
                    JSONObject item = threads.optJSONObject(i);
                    if (item == null || item.optInt("sticky") == 1 ||
                        item.optInt("closed") == 1 || item.optLong("no") <= 0) continue;
                    String tim = item.optString("tim", "");
                    if (!tim.matches("[0-9]{5,24}")) continue;
                    int replies = item.optInt("replies", 0);
                    int images = item.optInt("images", 0);
                    double score = Math.log1p(replies) * 7 + Math.log1p(images) * 3 - p * 1.5;
                    if (score > best) { best = score; choice = item; }
                }
            }
            if (choice == null) return null;
            Spotlight s = new Spotlight();
            s.id = choice.optLong("no");
            s.replies = choice.optInt("replies");
            String subject = clean(choice.optString("sub", ""));
            String content = clean(choice.optString("com", ""));
            s.title = subject.isEmpty() ? firstText(content, "Thread #" + s.id, 88) : firstText(subject, "Thread #" + s.id, 88);
            s.summary = firstText(content, "", 106);
            s.thumb = "https://i.4cdn.org/" + board + "/" + choice.optString("tim") + "s.jpg";
            s.fetchedAt = System.currentTimeMillis();
            return s;
        } finally { conn.disconnect(); }
    }

    private static String firstText(String str, String fallback, int max) {
        String s = str.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? fallback : s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String clean(String html) {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
                .toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    private void updateFavorites() {
        favoriteContent.removeAllViews();
        if (favorites.isEmpty()) {
            TextView empty = text("No favorites yet. Heart a board below to keep it close.", 13, MUTED, false);
            empty.setPadding(dp(3), dp(5), dp(3), dp(5));
            favoriteContent.addView(empty);
            return;
        }
        android.widget.HorizontalScrollView scroller = new android.widget.HorizontalScrollView(activity);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row();
        chips.setPadding(dp(2), dp(1), dp(2), dp(1));
        List<String> sorted = new ArrayList<>(favorites);
        Collections.sort(sorted);
        for (String favorite : sorted) {
            TextView chip = pill("\u2665  /" + favorite + "/", 14, ACCENT, CHIP);
            chip.setPadding(dp(15), 0, dp(15), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(46));
            lp.rightMargin = dp(9);
            chips.addView(chip, lp);
            chip.setOnClickListener(v -> { rememberRecent(favorite); navigation.board(favorite); });
        }
        scroller.addView(chips);
        favoriteContent.addView(scroller);
    }

    private List<String> recentBoards() {
        List<String> result = new ArrayList<>();
        for (String b : preferences.getString("recent", "").split(",")) {
            if (valid(b) && !result.contains(b)) result.add(b);
        }
        return result;
    }

    private void rememberRecent(String board) {
        List<String> boards = recentBoards();
        boards.remove(board);
        boards.add(0, board);
        if (boards.size() > 8) boards = boards.subList(0, 8);
        preferences.edit().putString("recent", TextUtils.join(",", boards)).apply();
    }

    private static boolean valid(String name) {
        return name != null && name.matches("[a-z0-9]{1,12}");
    }

    private static String boardName(String b) {
        switch (b) {
            case "g": return "Technology";
            case "wg": return "Wallpapers";
            case "w": return "Anime wallpapers";
            case "a": return "Anime & manga";
            case "v": return "Video games";
            case "gif": return "GIFs & WebMs";
            default: return "Imageboard";
        }
    }

    private static String boardAbout(String b) {
        if ("gif".equals(b)) return "Video loops and animated media \u00b7 mature";
        if ("wg".equals(b) || "w".equals(b)) return "Visual threads and wallpapers";
        return "Preview a lively conversation";
    }

    private int dp(int value) { return Math.round(activity.getResources().getDisplayMetrics().density * value); }

    private GradientDrawable round(int color, int radius, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (stroke != 0) background.setStroke(dp(1), stroke);
        return background;
    }

    private TextView text(String title, int size, int color, boolean bold) {
        TextView tv = new TextView(activity);
        tv.setText(title);
        tv.setTextSize(size);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }

    private TextView pill(String title, int size, int fg, int bg) {
        TextView v = text(title, size, fg, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(round(bg, 12, 0));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private void addGap(LinearLayout layout, int height) {
        layout.addView(new View(activity), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private void addSpace(View v, int height) {
        parent.addView(v, new LinearLayout.LayoutParams(-1, dp(height)));
    }
}
