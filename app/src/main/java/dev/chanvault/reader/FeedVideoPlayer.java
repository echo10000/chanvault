package dev.chanvault.reader;

import android.app.Activity;
import android.graphics.Matrix;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

/** A player per visible video; byte cache survives player switches. */
@UnstableApi
final class FeedVideoPlayer {
    interface Events {
        void onReady();
        void onBuffering(int timelinePercent, long cachedBytes, long fileBytes);
        void onError(String problem);
    }

    private final ExoPlayer engine;
    private final PlayerView view;
    private final PlaybackCache cache;
    private final String url;
    private final long size;
    private final Events events;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean released = false, fill = false;
    private int rotation = 0;
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (released) return;
            events.onBuffering(engine.getBufferedPercentage(), cache.cachedBytes(url, size), size);
            handler.postDelayed(this, 550);
        }
    };

    FeedVideoPlayer(Activity activity, FrameLayout parent, String url, long size, Events events) {
        this.url = url;
        this.size = size;
        this.events = events;
        cache = PlaybackCache.get(activity);
        view = (PlayerView) LayoutInflater.from(activity)
                .inflate(R.layout.feed_video_player, parent, false);
        view.setUseController(false);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        parent.addView(view, new FrameLayout.LayoutParams(-1, -1));

        DefaultMediaSourceFactory mediaFactory = new DefaultMediaSourceFactory(cache.sourceFactory());
        engine = new ExoPlayer.Builder(activity)
                .setMediaSourceFactory(mediaFactory)
                .build();
        engine.setRepeatMode(Player.REPEAT_MODE_ONE);
        view.setPlayer(engine);
        engine.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (released) return;
                if (state == Player.STATE_READY) events.onReady();
                events.onBuffering(engine.getBufferedPercentage(), cache.cachedBytes(url, size), size);
            }
            @Override public void onPlayerError(PlaybackException e) {
                if (!released) events.onError("Video playback failed on this device.");
            }
        });
        engine.setMediaItem(MediaItem.fromUri(url));
        engine.prepare();
        engine.play();
        handler.post(progress);
    }

    PlayerView view() { return view; }

    boolean isPlaying() { return !released && engine.isPlaying(); }
    void togglePlay() {
        if (released) return;
        if (engine.isPlaying()) engine.pause();
        else engine.play();
    }
    long positionMs() { return released ? 0 : Math.max(0, engine.getCurrentPosition()); }
    long durationMs() {
        if (released) return 0;
        long duration = engine.getDuration();
        return duration > 0 && duration != androidx.media3.common.C.TIME_UNSET ? duration : 0;
    }
    boolean isSeekable() { return !released && engine.isCurrentMediaItemSeekable() && durationMs() > 0; }
    void seekTo(long targetMs) {
        if (!isSeekable()) return;
        engine.seekTo(Math.min(Math.max(0, targetMs), durationMs()));
    }
    void skipBy(long offsetMs) { seekTo(positionMs() + offsetMs); }

    void pause() { if (!released) engine.pause(); }
    void resume() { if (!released) engine.play(); }

    boolean toggleFill() {
        fill = !fill;
        view.setResizeMode(fill ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        applyRotation();
        return fill;
    }

    int rotate90() {
        rotation = (rotation + 90) % 360;
        view.post(this::applyRotation);
        return rotation;
    }

    private void applyRotation() {
        if (released) return;
        View surface = view.getVideoSurfaceView();
        if (!(surface instanceof TextureView)) return;
        TextureView texture = (TextureView) surface;
        int width = texture.getWidth();
        int height = texture.getHeight();
        if (width <= 0 || height <= 0) return;
        Matrix matrix = new Matrix();
        float midX = width / 2f, midY = height / 2f;
        matrix.setRotate(rotation, midX, midY);
        if (rotation == 90 || rotation == 270) {
            // Uniform scaling keeps rotated footage inside the player bounds.
            float scale = Math.min((float) width / height, (float) height / width);
            if (fill) scale = Math.max((float) width / height, (float) height / width);
            matrix.postScale(scale, scale, midX, midY);
        }
        texture.setTransform(matrix);
        texture.invalidate();
    }

    void release() {
        if (released) return;
        released = true;
        handler.removeCallbacks(progress);
        view.setPlayer(null);
        engine.release();
    }
}
