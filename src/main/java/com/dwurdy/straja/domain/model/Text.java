package com.dwurdy.straja.domain.model;

/**
 * Single home for free-text bounds and casing so a limit or normalization
 * rule is defined once and shared by services and form/NPC surfaces.
 */
public final class Text {
    /** Server + form cap on free-text reasons and reviewer decision notes. */
    public static final int REASON_LIMIT = 500;

    /** The one "[Straja] " brand prefix for chat, menu titles, and log tags. */
    public static final String STRAJA_PREFIX = "[Straja] ";

    private Text() {}

    /**
     * Trims and hard-truncates to {@code limit} chars; {@code null} → "".
     * A trailing high surrogate is dropped rather than persisted unpaired.
     */
    public static String bounded(String value, int limit) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() <= limit) return trimmed;
        int end = Math.max(0, limit);
        if (end > 0 && end < trimmed.length()
                && Character.isHighSurrogate(trimmed.charAt(end - 1))) end--;
        return trimmed.substring(0, end);
    }

    /** Canonical id form: trimmed, uppercase, locale-stable. */
    public static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** Display form for normalized ids: trimmed, lowercase, locale-stable. */
    public static String displayLower(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
