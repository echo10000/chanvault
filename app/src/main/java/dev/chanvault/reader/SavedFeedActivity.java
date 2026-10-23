package dev.chanvault.reader;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local-first vertical swipe feed for downloaded media.
 * Reads only files in getFilesDir()/vault; never streams from the internet.
 * Can be scoped to the selected thread, board, or all recent saved media.
 */
public final class SavedFeedActivity extends Activity {
    private static final int BG = 0xff0d1017, FG = 0xfff5f5fa, MUTED = 0xffb6bdcb, ACCENT = 0xff9faefb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ArrayList<JSONObject> media = new ArrayList<>();
    private final Runnable hideControls = () -> {
        if (controls != null && !scrubbing) controls.setVisibility(View.GONE);
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateTimeline();
            ui.postDelayed(this, 400);
        }
    };
    private LinearLayout root;
    private FrameLayout mediaPane;
    private TextView count, title, details, timelineText;
    private LinearLayout controls;
    private SeekBar timeline;
    private Button playButton, fitButton;
    private ExoPlayer player;
    private PlayerView playerView;
    private AnimatedImageDrawable animated;
    private ProgressBar loading;
    private File vaultDir;
    private int index = 0, rotation = 0, serial = 0;
    private boolean scrubbing = false, zoom = false;
    private float downX, downY;

    private int dp(int px) { return Math.round(getResources().getDisplayMetrics().density * px); }

    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(size);
        return v;
    }

    private Button button(String value, Runnable action) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(FG);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0xff2b3245);
        background.setCornerRadius(dp(12));
        background.setStroke(dp(1), 0xff46516a);
        b.setBackground(background);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        });
        return b;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        vaultDir = new File(getFilesDir(), "vault");
        buildScreen();
        String group = getIntent().getStringExtra("group");
        String board = getIntent().getStringExtra("board");
        long thread = getIntent().getLongExtra("thread", 0);
        String startFile = getIntent().getStringExtra("file");
        if (startFile == null || !startFile.matches("[a-f0-9-]{36}\\.(webm|mp4|gif|jpg|jpeg|png)")) {
            finish();
            return;
        }
        loader.execute(() -> {
            try {
                List<JSONObject> rows = new MainActivity.Vault(vaultDir).list();
                rows.sort((a, b) -> Long.compare(b.optLong("savedAt"), a.optLong("savedAt")));
                ArrayList<JSONObject> selection = new ArrayList<>();
                for (JSONObject row : rows) {
                    if ("thread".equals(group) && (!row.optString("board").equals(board) ||
                            row.optLong("thread") != thread)) continue;
                    if ("board".equals(group) && !row.optString("board").equals(board)) continue;
                    selection.add(row);
                }
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    media.addAll(selection);
                    for (int i = 0; i < media.size(); i++) {
                        if (startFile.equals(media.get(i).optString("file"))) { index = i; break; }
                    }
                    display();
                });
            } catch (Exception ex) {
                ui.post(() -> {
                    if (!isFinishing()) Toast.makeText(this, "Cannot open saved library", Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    private void buildScreen() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(this);
        top.setPadding(dp(12), dp(7), dp(12), dp(7));
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("Back", this::finish);
        top.addView(back, new LinearLayout.LayoutParams(dp(74), dp(46)));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(13), 0, 0, 0);
        header.addView(text("Saved Media · Offline", 14, FG));
        count = text("Opening saved files...", 12, MUTED);
        header.addView(count);
        top.addView(header, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(62)));

        mediaPane = new FrameLayout(this);
        root.addView(mediaPane, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(16), dp(9), dp(16), dp(17));
        bottom.setBackgroundColor(0xff171d2a);
        title = text("Your offline videos and images", 15, FG);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bottom.addView(title);
        details = text("Swipe up or down to browse saved files", 12, MUTED);
        details.setPadding(0, dp(6), 0, dp(11));
        bottom.addView(details);

        LinearLayout buttons = new LinearLayout(this);
        fitButton = button("Fit / Fill", () -> {
            zoom = !zoom;
            if (playerView != null) playerView.setResizeMode(zoom ?
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            fitButton.setText(zoom ? "Fill / Fit" : "Fit / Fill");
        });
        LinearLayout.LayoutParams fitLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        fitLp.rightMargin = dp(7);
        buttons.addView(fitButton, fitLp);
        Button rotate = button("Rotate 90°", () -> {
            rotation = (rotation + 90) % 360;
            applyRotation();
        });
        LinearLayout.LayoutParams rotateLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        rotateLp.leftMargin = dp(7);
        buttons.addView(rotate, rotateLp);
        bottom.addView(buttons);
        root.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void clearCurrent() {
        ui.removeCallbacks(hideControls);
        scrubbing = false;
        controls = null;
        timeline = null;
        timelineText = null;
        playButton = null;
        rotation = 0;
        zoom = false;
        if (player != null) {
            if (playerView != null) playerView.setPlayer(null);
            player.release();
            player = null;
        }
        playerView = null;
        if (animated != null) {
            animated.stop();
            animated = null;
        }
        mediaPane.removeAllViews();
    }

    private void display() {
        ++serial;
        clearCurrent();
        if (media.isEmpty()) {
            title.setText("No saved media");
            details.setText("Return to Saved Media to browse your library.");
            count.setText("0 files");
            return;
        }
        index = Math.max(0, Math.min(index, media.size() - 1));
        JSONObject row = media.get(index);
        String name = row.optString("file");
        File file = new File(vaultDir, name);
        count.setText((index + 1) + " / " + media.size());
        title.setText(row.optString("title", "Saved file"));
        details.setText("/" + row.optString("board", "?") + "/ · Thread #" +
                row.optLong("thread") + " · " + size(file.length()) + " · Swipe ↑↓");
        fitButton.setText("Fit / Fill");
        if (!file.isFile()) {
            TextView missing = text("This saved file no longer exists.", 15, FG);
            missing.setGravity(Gravity.CENTER);
            mediaPane.addView(missing, new FrameLayout.LayoutParams(-1, -1));
            missing.setOnTouchListener(swipes());
            return;
        }
        boolean isVideo = name.endsWith(".webm") || name.endsWith(".mp4");
        if (isVideo) {
            playLocalVideo(file, serial);
        } else {
            showLocalImage(file);
        }
    }

    private View.OnTouchListener swipes() {
        return (v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    if (Math.abs(dy) > dp(60) && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                        move(dy < 0 ? 1 : -1);
                    } else if (player != null && Math.abs(dx) < dp(25) && Math.abs(dy) < dp(25)) {
                        revealControls();
                    }
                    return true;
                default:
                    return true;
            }
        };
    }

    private void move(int step) {
        int target = index + step;
        if (target < 0 || target >= media.size()) {
            Toast.makeText(this, "End of saved media", Toast.LENGTH_SHORT).show();
            return;
        }
        index = target;
        display();
    }

    private void playLocalVideo(File file, int issuedSerial) {
        FrameLayout holder = new FrameLayout(this);
        mediaPane.addView(holder, new FrameLayout.LayoutParams(-1, -1));
        PlayerView surface = (PlayerView) getLayoutInflater().inflate(R.layout.feed_video_player, holder, false);
        playerView = surface;
        holder.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        surface.setOnTouchListener(swipes());
        loading = new ProgressBar(this);
        loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        holder.addView(loading, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));

        ExoPlayer engine = new ExoPlayer.Builder(this).build();
        player = engine;
        engine.setRepeatMode(Player.REPEAT_MODE_ONE);
        surface.setUseController(false);
        surface.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        surface.setPlayer(engine);
        engine.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (issuedSerial != serial) return;
                if (loading != null) loading.setVisibility(state == Player.STATE_READY ? View.GONE : View.VISIBLE);
            }
            @Override public void onPlayerError(PlaybackException ex) {
                if (issuedSerial != serial) return;
                if (loading != null) loading.setVisibility(View.GONE);
                Toast.makeText(SavedFeedActivity.this, "Video cannot be played on this device", Toast.LENGTH_LONG).show();
            }
        });
        engine.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        engine.prepare();
        engine.play();
        addControls(holder);
    }

    private void showLocalImage(File file) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(BG);
        mediaPane.addView(image, new FrameLayout.LayoutParams(-1, -1));
        image.setOnTouchListener(swipes());
        if (file.getName().endsWith(".gif") && Build.VERSION.SDK_INT >= 28) {
            try {
                Drawable drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file));
                image.setImageDrawable(drawable);
                if (drawable instanceof AnimatedImageDrawable) {
                    animated = (AnimatedImageDrawable) drawable;
                    animated.start();
                }
            } catch (Exception ex) {
                image.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
            }
        } else {
            image.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
        }
    }

    private static String time(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void addControls(FrameLayout holder) {
        LinearLayout panel = new LinearLayout(this);
        controls = panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(11), dp(9), dp(11), dp(9));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xe91b2233);
        bg.setCornerRadius(dp(15));
        panel.setBackground(bg);

        timelineText = text("0:00 / --:--", 12, FG);
        panel.addView(timelineText);
        timeline = new SeekBar(this);
        timeline.setMax(1000);
        timeline.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        timeline.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        panel.addView(timeline, new LinearLayout.LayoutParams(-1, dp(41)));
        timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {
                scrubbing = true;
                ui.removeCallbacks(hideControls);
            }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean byUser) {
                if (byUser && player != null && player.getDuration() > 0) {
                    long duration = player.getDuration();
                    timelineText.setText(time(duration * progress / 1000) + " / " + time(duration));
                }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (player != null && player.getDuration() > 0 && player.isCurrentMediaItemSeekable())
                    player.seekTo(player.getDuration() * bar.getProgress() / 1000);
                scrubbing = false;
                revealControls();
            }
        });

        LinearLayout buttons = new LinearLayout(this);
        Button back = button("-10 sec", () -> { skip(-10000); revealControls(); });
        buttons.addView(back, new LinearLayout.LayoutParams(0, dp(44), 1));
        playButton = button("Pause", () -> {
            if (player != null) {
                if (player.isPlaying()) player.pause(); else player.play();
            }
            revealControls();
        });
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        playLp.leftMargin = dp(8);
        playLp.rightMargin = dp(8);
        buttons.addView(playButton, playLp);
        Button next = button("+10 sec", () -> { skip(10000); revealControls(); });
        buttons.addView(next, new LinearLayout.LayoutParams(0, dp(44), 1));
        panel.addView(buttons);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(dp(9), 0, dp(9), dp(10));
        holder.addView(panel, lp);
        panel.setVisibility(View.GONE);
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void skip(long offset) {
        if (player == null || !player.isCurrentMediaItemSeekable() || player.getDuration() <= 0) return;
        player.seekTo(Math.min(Math.max(0, player.getCurrentPosition() + offset), player.getDuration()));
    }

    private void updateTimeline() {
        if (player == null || timeline == null) return;
        long duration = player.getDuration(), position = player.getCurrentPosition();
        boolean canSeek = duration > 0 && player.isCurrentMediaItemSeekable();
        timeline.setEnabled(canSeek);
        if (!scrubbing) {
            timeline.setProgress(canSeek ? (int) (Math.min(position, duration) * 1000 / duration) : 0);
            timelineText.setText(time(position) + " / " + (duration > 0 ? time(duration) : "--:--"));
        }
        if (playButton != null) playButton.setText(player.isPlaying() ? "Pause" : "Play");
    }

    private void revealControls() {
        if (controls == null) return;
        controls.setVisibility(View.VISIBLE);
        controls.bringToFront();
        updateTimeline();
        ui.removeCallbacks(hideControls);
        if (!scrubbing) ui.postDelayed(hideControls, 5200);
    }

    private void applyRotation() {
        if (playerView != null) {
            View surface = playerView.getVideoSurfaceView();
            if (surface instanceof TextureView) {
                TextureView texture = (TextureView) surface;
                float w = texture.getWidth(), h = texture.getHeight();
                if (w <= 0 || h <= 0) return;
                Matrix matrix = new Matrix();
                matrix.setRotate(rotation, w / 2, h / 2);
                if (rotation % 180 != 0) {
                    float ratio = zoom ? Math.max(w / h, h / w) : Math.min(w / h, h / w);
                    matrix.postScale(ratio, ratio, w / 2, h / 2);
                }
                texture.setTransform(matrix);
            }
        }
    }

    private static String size(long bytes) {
        if (bytes <= 0) return "unknown size";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024f);
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    @Override protected void onStop() {
        if (player != null) player.pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(tick);
        ui.removeCallbacks(hideControls);
        clearCurrent();
        loader.shutdownNow();
        super.onDestroy();
    }
}
