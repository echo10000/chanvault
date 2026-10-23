package dev.chanvault.reader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Device-local thread preferences, distinct from the Favorites boards and media vault.
 * Pinning does not download/archive thread content. Blocking hides thread cards locally.
 */
final class ThreadShelf {
    static final class Item {
        final String board;
        final long id;
        final String title, thumbnail;
        final long savedAt;

        Item(String board, long id, String title, String thumbnail, long savedAt) {
            this.board = board;
            this.id = id;
            this.title = title;
            this.thumbnail = thumbnail;
            this.savedAt = savedAt;
        }

        String label() { return "/" + board + "/ · #" + id; }
    }

    private static final String PINS = "pinned";
    private static final String BLOCKS = "blocked";
    private final SharedPreferences prefs;

    ThreadShelf(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences("thread_shelf_v1", Context.MODE_PRIVATE);
    }

    private static boolean valid(String board, long id) {
        return MediaAddress.isValidBoard(board) && id > 0;
    }

    private JSONArray read(String name) {
        try { return new JSONArray(prefs.getString(name, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void save(String name, JSONArray data) {
        prefs.edit().putString(name, data.toString())
                .putLong("revision", prefs.getLong("revision", 0) + 1).apply();
    }

    long revision() { return prefs.getLong("revision", 0); }

    private static String cleanTitle(String title, long id) {
        String value = title == null ? "" : title.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (value.isEmpty()) value = "Thread #" + id;
        return value.substring(0, Math.min(130, value.length()));
    }

    private static String cleanThumb(String thumbnail) {
        if (thumbnail == null || thumbnail.length() > 170) return "";
        // MediaThumbs.load enforces the same allow-list before issuing a request.
        return thumbnail.startsWith("https://i.4cdn.org/") ? thumbnail : "";
    }

    private static boolean same(JSONObject item, String board, long id) {
        return item != null && board.equals(item.optString("board")) &&
                id == item.optLong("id", -1);
    }

    private synchronized boolean contains(String list, String board, long id) {
        if (!valid(board, id)) return false;
        JSONArray a = read(list);
        for (int i = 0; i < a.length(); i++) if (same(a.optJSONObject(i), board, id)) return true;
        return false;
    }

    boolean isPinned(String board, long id) { return contains(PINS, board, id); }
    boolean isBlocked(String board, long id) { return contains(BLOCKS, board, id); }

    private synchronized boolean add(String key, String board, long id,
                                     String title, String thumbnail, int limit) {
        if (!valid(board, id) || contains(key, board, id)) return false;
        JSONArray source = read(key);
        JSONArray result = new JSONArray();
        try {
            JSONObject row = new JSONObject();
            row.put("board", board);
            row.put("id", id);
            row.put("title", cleanTitle(title, id));
            row.put("thumbnail", cleanThumb(thumbnail));
            row.put("savedAt", System.currentTimeMillis());
            result.put(row);
            // Newest first; bound storage to avoid unlimited JSON expansion.
            for (int i = 0; i < source.length() && result.length() < limit; i++) {
                JSONObject prior = source.optJSONObject(i);
                if (prior != null) result.put(prior);
            }
            save(key, result);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private synchronized boolean remove(String key, String board, long id) {
        if (!valid(board, id)) return false;
        JSONArray source = read(key), result = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (same(item, board, id)) removed = true;
            else if (item != null) result.put(item);
        }
        if (removed) save(key, result);
        return removed;
    }

    synchronized boolean pin(String board, long id, String title, String thumbnail) {
        if (isBlocked(board, id)) return false;
        return add(PINS, board, id, title, thumbnail, 120);
    }

    boolean unpin(String board, long id) { return remove(PINS, board, id); }

    synchronized boolean block(String board, long id, String title, String thumbnail) {
        if (!valid(board, id)) return false;
        remove(PINS, board, id);
        return add(BLOCKS, board, id, title, thumbnail, 600);
    }

    boolean unblock(String board, long id) { return remove(BLOCKS, board, id); }

    private synchronized List<Item> list(String key) {
        JSONArray array = read(key);
        List<Item> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject row = array.optJSONObject(i);
            if (row == null) continue;
            String board = row.optString("board");
            long id = row.optLong("id");
            if (!valid(board, id)) continue;
            result.add(new Item(board, id, row.optString("title"),
                    row.optString("thumbnail"), row.optLong("savedAt")));
        }
        return result;
    }

    List<Item> pinned() { return list(PINS); }
    List<Item> blocked() { return list(BLOCKS); }
}
