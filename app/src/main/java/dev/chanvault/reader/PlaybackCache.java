package dev.chanvault.reader;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.database.StandaloneDatabaseProvider;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Short-lived playback cache, NOT the private downloaded media vault.
 * Cached bytes are LRU-evicted at 96 MiB. At most one 1 MiB next-video
 * prefetch is running. Cache survives feed transitions so navigating backward
 * typically doesn't re-download the same already-buffered bytes.
 */
@UnstableApi
final class PlaybackCache {
    private static final long MAX_CACHE_BYTES = 96L * 1024L * 1024L;
    private static final long PREFETCH_BYTES = 1024L * 1024L;
    private static PlaybackCache instance;

    private final SimpleCache cache;
    private final CacheDataSource.Factory factory;
    private final ExecutorService preloadExecutor = Executors.newSingleThreadExecutor();
    private volatile CacheWriter activeWriter;
    private volatile String queuedUrl = "";

    static synchronized PlaybackCache get(Context context) {
        if (instance == null) instance = new PlaybackCache(context.getApplicationContext());
        return instance;
    }

    private PlaybackCache(Context context) {
        File cacheDir = new File(context.getCacheDir(), "stream-cache-v1");
        cache = new SimpleCache(cacheDir,
                new LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
                new StandaloneDatabaseProvider(context));
        DefaultHttpDataSource.Factory upstream = new DefaultHttpDataSource.Factory()
                .setUserAgent("ChanVault/0.5.1 Android (media cache)")
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(22000)
                .setAllowCrossProtocolRedirects(false);
        factory = new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    CacheDataSource.Factory sourceFactory() {
        return factory;
    }

    long cachedBytes(String url, long knownLength) {
        if (url == null || knownLength <= 0) return 0;
        synchronized (cache) {
            return Math.max(0, cache.getCachedBytes(url, 0, knownLength));
        }
    }

    void cancelPrefetch() {
        queuedUrl = "";
        CacheWriter prior = activeWriter;
        if (prior != null) prior.cancel();
    }

    void prefetch(String url, long fileSize) {
        if (url == null || fileSize <= 0 || !url.startsWith("https://i.4cdn.org/")) return;
        if (url.equals(queuedUrl)) return;
        cancelPrefetch();
        queuedUrl = url;
        preloadExecutor.execute(() -> {
            if (!url.equals(queuedUrl)) return;
            try {
                long length = Math.min(PREFETCH_BYTES, fileSize);
                if (cachedBytes(url, length) >= length) return;
                DataSpec spec = new DataSpec.Builder()
                        .setUri(Uri.parse(url))
                        .setPosition(0)
                        .setLength(length)
                        .build();
                CacheWriter writer = new CacheWriter(factory.createDataSource(),
                        spec, null, null);
                activeWriter = writer;
                if (!url.equals(queuedUrl)) writer.cancel();
                else writer.cache();
            } catch (Exception ignored) {
                // Prefetch is best effort; playback and scrolling must still work.
            } finally {
                activeWriter = null;
            }
        });
    }
}
