package dev.chanvault.reader;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Thumbnails and inline still-image previews. Never writes media to public storage. */
final class MediaThumbs {
    private static final ExecutorService pool = Executors.newFixedThreadPool(4);
    private static final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };

    static void load(ImageView view, String url, boolean full) {
        load(view, url, full, null);
    }

    static void load(ImageView view, String url, boolean full,
                     java.util.function.Consumer<Boolean> completed) {
        load(view, url, full, completed, null);
    }

    static void load(ImageView view, String url, boolean full,
                     java.util.function.Consumer<Boolean> completed,
                     java.util.function.IntConsumer onPercent) {
        view.setTag(url);
        view.setImageDrawable(null);
        if (url == null || !url.matches(
                "https://i\\.4cdn\\.org/[a-z0-9]{1,12}/[0-9]{5,24}(s\\.jpg|\\.(jpg|jpeg|png|gif))")) {
            return;
        }
        Bitmap cached = bitmaps.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            if (onPercent != null) onPercent.accept(100);
            if (completed != null) completed.accept(true);
            return;
        }
        pool.execute(() -> {
            Bitmap loaded = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(false);
                int limit = full ? 16 * 1024 * 1024 : 1024 * 1024;
                if (connection.getResponseCode() != 200 ||
                    connection.getContentLengthLong() > limit) return;
                try (InputStream stream = connection.getInputStream();
                     ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] bytes = new byte[8192];
                    int n;
                    int lastPercent = -1;
                    long transferSize = connection.getContentLengthLong();
                    while ((n = stream.read(bytes)) != -1) {
                        if (buffer.size() + n > limit) return;
                        buffer.write(bytes, 0, n);
                        if (onPercent != null && transferSize > 0) {
                            int percent = Math.min(100, (int) (buffer.size() * 100L / transferSize));
                            if (percent >= lastPercent + 4 || percent == 100) {
                                lastPercent = percent;
                                final int loadedPercent = percent;
                                view.post(() -> {
                                    if (url.equals(view.getTag())) onPercent.accept(loadedPercent);
                                });
                            }
                        }
                    }
                    byte[] raw = buffer.toByteArray();
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
                    int target = full ? 1600 : 400;
                    opts.inSampleSize = 1;
                    while (opts.outWidth / opts.inSampleSize > target * 2 ||
                           opts.outHeight / opts.inSampleSize > target * 2) {
                        opts.inSampleSize *= 2;
                    }
                    opts.inJustDecodeBounds = false;
                    loaded = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
                }
                if (loaded != null) bitmaps.put(url, loaded);
            } catch (Exception ignored) {
                // Bad or removed thumbnails are common; keep the rest of the feed responsive.
            } finally {
                if (connection != null) connection.disconnect();
                final Bitmap result = loaded;
                view.post(() -> {
                    if (!url.equals(view.getTag())) return;
                    if (result != null) view.setImageBitmap(result);
                    if (completed != null) completed.accept(result != null);
                });
            }
        });
    }

    private MediaThumbs() {}
}
