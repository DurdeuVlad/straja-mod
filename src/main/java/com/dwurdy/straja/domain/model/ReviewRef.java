package com.dwurdy.straja.domain.model;

/**
 * Composite "<id>_<version>" reference a review form carries so the service
 * can reject decisions made against a petition version the reviewer never
 * read. One codec owns the format for both producers and the router.
 */
public record ReviewRef(String id, Long version) {

    public static String encode(String id, long version) {
        return id + "_" + version;
    }

    /** Bare ids parse as {@code (raw, null)} — provider surfaces pass no version. */
    public static ReviewRef parse(String raw) {
        String value = raw == null ? "" : raw;
        int cut = value.lastIndexOf('_');
        if (cut <= 0) return new ReviewRef(value, null);
        try {
            return new ReviewRef(value.substring(0, cut), Long.parseLong(value.substring(cut + 1)));
        } catch (NumberFormatException ignored) {
            return new ReviewRef(value, null);
        }
    }
}
