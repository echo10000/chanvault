package dev.chanvault.reader;

import java.util.Locale;

/** Persist readable thread titles without treating numeric post IDs as titles. */
final class ThreadTitles {
    private ThreadTitles() {}

    static String clean(String original) {
        if (original == null) return "";
        String value = original.replaceAll("[\\p{Cntrl}]+", " ")
                .replaceAll("\\s+", " ").trim();
        if (value.matches("(?i)^(?:post|thread)\\s*#?\\d+$") ||
                value.matches("^#\\d+$")) return "";
        return value.length() > 120 ? value.substring(0, 119) + "…" : value;
    }

    static String select(String subject, String caption, long id) {
        String title = clean(subject);
        if (!title.isEmpty()) return title;
        String text = clean(caption);
        if (!text.isEmpty()) return text;
        return "Untitled thread";
    }

    static String display(String title, String fallback) {
        String value = clean(title);
        if (value.isEmpty()) value = clean(fallback);
        return value.isEmpty() ? "Untitled thread" : value;
    }

    static String groupKey(String board, long threadId) {
        return board + ":" + threadId;
    }
}
