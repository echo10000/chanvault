package dev.chanvault.reader;

import java.net.URI;
import java.util.Locale;

/**
 * Strict source validation for user-requested private downloads.
 *
 * Parse URL components rather than using Android regex interval quantifiers:
 * older Android ICU implementations can reject patterns such as {5,24}.
 * Accept only numeric 4chan CDN media filenames and a plain board segment.
 */
final class MediaAddress {
    static boolean isValidBoard(String board) {
        if (board == null || board.length() < 1 || board.length() > 12) return false;
        for (int i = 0; i < board.length(); i++) {
            char c = board.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')) return false;
        }
        return true;
    }

    static boolean isValidMediaUrl(String source) {
        if (source == null || source.length() > 220) return false;
        try {
            URI uri = new URI(source);
            if (!"https".equals(uri.getScheme())) return false;
            if (!"i.4cdn.org".equals(uri.getHost())) return false;
            if (uri.getPort() != -1 || uri.getRawUserInfo() != null ||
                    uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/")) return false;
            String[] parts = path.substring(1).split("/", -1);
            if (parts.length != 2 || !isValidBoard(parts[0])) return false;
            String name = parts[1];
            int dot = name.lastIndexOf('.');
            if (dot < 5 || dot > 24 || dot == name.length() - 1) return false;
            for (int i = 0; i < dot; i++) {
                char c = name.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            // Case-sensitive filename check to avoid silently changing CDN URLs.
            if (!name.endsWith("." + ext)) return false;
            switch (ext) {
                case "webm":
                case "mp4":
                case "gif":
                case "jpg":
                case "jpeg":
                case "png":
                    return true;
                default:
                    return false;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private MediaAddress() { }
}
