package dev.chanvault.reader;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Explicit user-initiated transfers. The foreground notification remains active
 * after leaving the UI. Pending tasks are persisted and replayed after a service
 * restart. Android may still stop transfers under battery/network restrictions.
 */
public final class DownloadService extends Service {
    private static final String CHANNEL = "private_downloads";
    private static final int NOTIFICATION_ID = 4117;
    private static final String ACTION_ADD = "dev.chanvault.download.ADD";
    private static final String ACTION_CANCEL = "dev.chanvault.download.CANCEL";
    private static final String KEY_ITEMS = "items";
    private final Object lock = new Object();
    private final List<JSONObject> pending = new ArrayList<>();
    private final ExecutorService runner = Executors.newSingleThreadExecutor();
    private volatile boolean processing = false;
    private volatile boolean stopAfterCurrent = false;
    private int completed, failed, skipped, initialTotal;
    private SharedPreferences prefs;
    private MainActivity.Vault vault;
    private NotificationManager notifications;

    public static void enqueue(Context context, JSONArray items) throws Exception {
        if (items == null || items.length() == 0) throw new Exception("Nothing selected to download");
        Intent request = new Intent(context, DownloadService.class);
        request.setAction(ACTION_ADD);
        request.putExtra(KEY_ITEMS, items.toString());
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(request);
        else context.startService(request);
    }

    public static void cancel(Context context) {
        Intent request = new Intent(context, DownloadService.class);
        request.setAction(ACTION_CANCEL);
        context.startService(request);
    }

    public static JSONObject item(String url, String title, String board, long threadId) throws Exception {
        if (!MediaAddress.isValidMediaUrl(url)) {
            throw new Exception("Invalid media URL");
        }
        if (!MediaAddress.isValidBoard(board) || threadId <= 0)
            throw new Exception("Invalid thread");
        // Reject URLs where the actual CDN board differs from requested metadata.
        if (!new java.net.URI(url).getRawPath().startsWith("/" + board + "/"))
            throw new Exception("Media board mismatch");
        JSONObject row = new JSONObject();
        row.put("url", url);
        row.put("title", title == null ? "Saved media" : title.substring(0, Math.min(title.length(), 120)));
        row.put("board", board);
        row.put("thread", threadId);
        return row;
    }

    public static String progress(Context context) {
        return context.getSharedPreferences("download_queue_v1", MODE_PRIVATE)
                .getString("status", "No downloads running");
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("download_queue_v1", MODE_PRIVATE);
        vault = new MainActivity.Vault(new File(getFilesDir(), "vault"));
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "ChanVault downloads",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Background progress for user-requested private media downloads");
            notifications.createNotificationChannel(channel);
        }
        restoreQueue();
        initialTotal = pending.size();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // Android requires a promptly posted notification for startForegroundService().
        try {
            startForeground(NOTIFICATION_ID, notification("Preparing downloads", 0, 0));
        } catch (RuntimeException e) {
            prefs.edit().putString("status", "Background transfer could not start: " + e.getMessage()).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            stopAfterCurrent = true;
            synchronized (lock) {
                pending.clear();
                persistQueue();
            }
            setStatus("Stopping when current media completes", 0, 0);
        } else if (intent != null && ACTION_ADD.equals(intent.getAction())) {
            String serialized = intent.getStringExtra(KEY_ITEMS);
            try {
                JSONArray input = new JSONArray(serialized);
                int accepted = 0;
                synchronized (lock) {
                    Set<String> known = new HashSet<>();
                    for (JSONObject old : pending) known.add(old.optString("url"));
                    for (int i = 0; i < input.length(); i++) {
                        JSONObject row = input.optJSONObject(i);
                        if (row == null) continue;
                        try {
                            JSONObject clean = item(row.optString("url"), row.optString("title"),
                                    row.optString("board"), row.optLong("thread"));
                            if (known.add(clean.optString("url"))) {
                                pending.add(clean);
                                accepted++;
                            }
                        } catch (Exception ignored) {}
                    }
                    persistQueue();
                    initialTotal = completed + failed + skipped + pending.size();
                }
                if (accepted > 0) stopAfterCurrent = false;
            } catch (Exception ex) {
                setStatus("Could not queue downloads", 0, 0);
            }
        }
        begin();
        return START_STICKY;
    }

    private void restoreQueue() {
        try {
            JSONArray saved = new JSONArray(prefs.getString("queue", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.optJSONObject(i);
                if (item == null) continue;
                try {
                    pending.add(item(item.optString("url"), item.optString("title"),
                            item.optString("board"), item.optLong("thread")));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void persistQueue() {
        JSONArray data = new JSONArray();
        for (JSONObject obj : pending) data.put(obj);
        // commit() ensures queue state is safely written before moving to the next item.
        prefs.edit().putString("queue", data.toString()).commit();
    }

    private void begin() {
        synchronized (lock) {
            if (processing) return;
            if (pending.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return;
            }
            processing = true;
        }
        runner.execute(() -> {
            while (true) {
                JSONObject next;
                int count;
                synchronized (lock) {
                    if (pending.isEmpty() || stopAfterCurrent) break;
                    next = pending.get(0);
                    count = pending.size();
                }
                int alreadyDone = completed + failed + skipped;
                int displayTotal = Math.max(alreadyDone + count, initialTotal);
                setStatus("Downloading " + (alreadyDone + 1) + " of " + displayTotal,
                        alreadyDone, displayTotal);
                try {
                    String src = next.optString("url");
                    if (vault.exists(src)) skipped++;
                    else {
                        long free = new File(getFilesDir(), "vault").getUsableSpace();
                        if (free < 30L * 1024 * 1024) {
                            failed++;
                            stopAfterCurrent = true;
                            setStatus("Low storage: queue paused", 0, 0);
                        } else {
                            vault.download(src, next.optString("title"), next.optString("board"),
                                    next.optLong("thread"));
                            completed++;
                        }
                    }
                } catch (Exception ex) {
                    failed++;
                    // A broken URL cannot block the remainder of the download queue.
                }
                synchronized (lock) {
                    if (!pending.isEmpty() && pending.get(0).optString("url").equals(next.optString("url")))
                        pending.remove(0);
                    persistQueue();
                    int n = completed + skipped + failed;
                    setStatus("Processed " + n + " of " + Math.max(n, initialTotal),
                            n, Math.max(n, initialTotal));
                }
            }
            synchronized (lock) {
                if (stopAfterCurrent) {
                    pending.clear();
                    persistQueue();
                }
                processing = false;
            }
            String result = "Downloads finished: " + completed + " saved, " + skipped +
                    " already saved, " + failed + " failed";
            setStatus(stopAfterCurrent ? "Downloads stopped: " + result : result, 0, 0);
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        });
    }

    private void setStatus(String message, int position, int total) {
        prefs.edit().putString("status", message).apply();
        notifications.notify(NOTIFICATION_ID, notification(message, position, total));
    }

    private Notification notification(String message, int position, int total) {
        Intent open = new Intent(this, MainActivity.class);
        open.putExtra("openSaved", true);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancel = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelAction = PendingIntent.getService(this, 1, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ?
                new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("ChanVault private downloads")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(processing);
        if (position > 0 && total > 0) b.setProgress(total, position, false);
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop remaining", cancelAction).build());
        return b.build();
    }

    @Override public void onDestroy() {
        runner.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
