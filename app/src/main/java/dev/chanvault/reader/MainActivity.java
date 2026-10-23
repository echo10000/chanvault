package dev.chanvault.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
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
    private static final int BG = 0xff101116, PANEL = 0xff1e2028;
    private static final int TEXT = 0xfff1f2f7, MUTED = 0xffa7acbe, ACCENT = 0xff9dabff;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout body;
    private Vault vault;
    private String board = "g", page = "home";
    private int generation = 0;
    private long lastRequestAt = 0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        vault = new Vault(new File(getFilesDir(), "vault"));
        home();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if ("thread".equals(page)) catalog(board);
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
        b.setTextColor(TEXT);
        b.setTextSize(13);
        b.setOnClickListener(v -> action.run());
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
        panel.setBackgroundColor(PANEL);
        panel.addView(label(title, 17, TEXT));
        if (detail != null && !detail.isEmpty()) panel.addView(label(detail, 13, MUTED));
        if (action != null) panel.addView(button("Open →", action));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        body.addView(panel, lp);
    }

    private void shell(String title, String nextPage) {
        page = nextPage;
        generation++;
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
        nav.setPadding(dp(10), dp(4), dp(10), dp(5));
        nav.addView(button("Browse", this::home), new LinearLayout.LayoutParams(0, dp(52), 1));
        nav.addView(button("Saved Media", this::saved), new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(nav);
        setContentView(root);
    }

    private void home() {
        shell("Browse", "home");
        card("Private media vault", "Save WebM, MP4, GIF and images without adding them to your Gallery.", null);
        EditText entry = new EditText(this);
        entry.setSingleLine(true);
        entry.setText("/" + board + "/");
        entry.setHint("Board name, e.g. /g/");
        entry.setTextColor(TEXT);
        body.addView(entry);
        body.addView(button("Open board", () -> {
            try { catalog(validateBoard(entry.getText().toString())); }
            catch (Exception e) { toast(e.getMessage()); }
        }));
        spacing(8);
        body.addView(label("Quick boards", 17, MUTED));
        for (String name : new String[]{"g", "wg", "w", "a", "v", "gif"}) {
            body.addView(button("/" + name + "/  →", () -> catalog(name)));
        }
        spacing(20);
        body.addView(label("Unofficial, read-only reader. Board content may be sensitive.", 12, MUTED));
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
        body.addView(label("Loading board…", 14, MUTED));
        String b = board;
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
                    body.removeAllViews();
                    int limit = Math.min(150, threads.size());
                    for (int i = 0; i < limit; i++) {
                        JSONObject obj = threads.get(i);
                        long id = obj.optLong("no");
                        String title = clean(obj.optString("sub", ""));
                        if (title.isEmpty()) title = "Thread #" + id;
                        String preview = clean(obj.optString("com", ""));
                        if (preview.length() > 180) preview = preview.substring(0, 180) + "…";
                        card(title, obj.optInt("replies") + " replies · " + preview, () -> thread(b, id));
                    }
                    if (limit < threads.size()) body.addView(label("Showing the first 150 threads.", 13, MUTED));
                });
            } catch (Exception e) { error(ticket, "Board error: " + e.getMessage()); }
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
                ui.post(() -> {
                    if (ticket != generation) return;
                    body.removeAllViews();
                    body.addView(button("← Back to /" + b + "/", () -> catalog(b)));
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
                        Button download = button("↓ Save privately", () -> download(mediaUrl, name, b, id));
                        body.addView(download);
                        body.addView(button("View original media", () -> {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mediaUrl));
                            startActivity(intent);
                        }));
                        spacing(9);
                    }
                });
            } catch (Exception e) { error(ticket, "Thread error: " + e.getMessage()); }
        });
    }

    private void download(String url, String name, String sourceBoard, long sourceThread) {
        toast("Saving to private vault…");
        worker.execute(() -> {
            try {
                vault.download(url, name, sourceBoard, sourceThread);
                ui.post(() -> toast("Saved — open Saved Media to play offline."));
            } catch (Exception e) { ui.post(() -> toast("Download failed: " + e.getMessage())); }
        });
    }

    private void saved() {
        shell("Saved Media", "saved");
        int ticket = generation;
        body.addView(label("Opening vault…", 14, MUTED));
        worker.execute(() -> {
            try {
                List<JSONObject> items = vault.list();
                ui.post(() -> {
                    if (ticket != generation) return;
                    body.removeAllViews();
                    if (items.isEmpty()) card("Nothing saved yet", "Browse a board and save an attachment first.", null);
                    for (JSONObject item : items) {
                        String id = item.optString("id");
                        String file = item.optString("file");
                        String title = item.optString("title", "Saved media");
                        card(title, "/" + item.optString("board") + "/ · " + (item.optLong("size") / 1024) + " KB", null);
                        body.addView(button("▶ Play offline", () -> {
                            Intent intent = new Intent(this, Player.class);
                            intent.putExtra("file", file);
                            startActivity(intent);
                        }));
                        body.addView(button("Delete saved file", () -> {
                            new AlertDialog.Builder(this).setTitle("Delete from private vault?")
                                    .setMessage(title)
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton("Delete", (d, w) -> worker.execute(() -> {
                                        try { vault.delete(id); ui.post(this::saved); }
                                        catch (Exception e) { ui.post(() -> toast(e.getMessage())); }
                                    })).show();
                        }));
                        spacing(12);
                    }
                });
            } catch (Exception e) { error(ticket, "Vault error: " + e.getMessage()); }
        });
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
    private static final class Vault {
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

        void download(String source, String title, String board, long threadId) throws Exception {
            if (!source.matches("https://i\\.4cdn\\.org/[a-z0-9]{1,12}/[0-9]{5,24}\\.(webm|mp4|gif|png|jpg|jpeg)"))
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
                row.put("id", id); row.put("file", name);
                row.put("title", title.substring(0, Math.min(title.length(), 120)));
                row.put("board", board); row.put("thread", threadId);
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
            back.setText("← Back");
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
                image.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
            }
            setContentView(root);
        }
    }
}
