package dev.chanvault.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Opt-in update check. Only GitHub Releases are considered installable stable
 * updates; CI debug artifacts are intentionally not offered as updates.
 */
final class UpdateChecker {
    static void check(Activity activity) {
        Toast.makeText(activity, "Checking GitHub Releases...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(
                        "https://api.github.com/repos/echo10000/chanvault/releases/latest").openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(13000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "ChanVault-Android-UpdateCheck");
                int code = conn.getResponseCode();
                if (code == 404) {
                    activity.runOnUiThread(() -> showNoRelease(activity));
                    return;
                }
                if (code != 200) throw new Exception("GitHub returned HTTP " + code);
                JSONObject release;
                try (InputStream in = conn.getInputStream();
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (out.size() + n > 1024 * 1024) throw new Exception("Unexpected response size");
                        out.write(buf, 0, n);
                    }
                    release = new JSONObject(out.toString("UTF-8"));
                }
                String tag = release.optString("tag_name");
                if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+")) throw new Exception("Invalid version");
                String current = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionName;
                String version = tag.substring(1);
                boolean newer = compare(version, current) > 0;
                activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                        .setTitle(newer ? "ChanVault " + version + " is available" : "You're up to date")
                        .setMessage(newer ? "A signed GitHub release is available. "
                                + "Installing it requires the SAME signing certificate as your current app. "
                                + "Back up your vault before switching from experimental debug builds."
                                : "Installed: " + current + "\nLatest signed release: " + version)
                        .setNegativeButton("Close", null)
                        .setPositiveButton("View release", (d,w) -> activity.startActivity(
                                new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/echo10000/chanvault/releases/latest"))))
                        .show());
            } catch (Exception ex) {
                activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                        .setTitle("Update check unavailable")
                        .setMessage("Couldn't check GitHub right now: " + ex.getMessage())
                        .setPositiveButton("OK", null).show());
            } finally { if (conn != null) conn.disconnect(); }
        }).start();
    }

    private static int compare(String a, String b) {
        try {
            String[] aa = a.split("\\."), bb = b.split("\\.");
            for (int i = 0; i < 3; i++) {
                int x = Integer.parseInt(aa[i]);
                int y = i < bb.length ? Integer.parseInt(bb[i]) : 0;
                if (x != y) return Integer.compare(x, y);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static void showNoRelease(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("Signed releases aren't configured yet")
                .setMessage("ChanVault is currently distributed as experimental debug builds. "
                        + "The public release workflow is prepared, but a permanent private signing key "
                        + "must be configured before safe in-place updates are possible.")
                .setNegativeButton("OK", null)
                .setPositiveButton("GitHub Actions", (d,w) -> activity.startActivity(
                        new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/echo10000/chanvault/actions"))))
                .show();
    }

    private UpdateChecker() {}
}
