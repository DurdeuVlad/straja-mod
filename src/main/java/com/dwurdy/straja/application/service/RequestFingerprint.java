package com.dwurdy.straja.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Canonical, length-delimited request identity for durable replay keys. */
final class RequestFingerprint {
    private RequestFingerprint() {}

    static String of(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            String text = canonical(value);
            canonical.append(text.length()).append(':').append(text).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static String canonical(Object value) {
        if (value == null) return "<null>";
        if (value instanceof java.util.Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> canonical(entry.getKey()) + "=" + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Iterable<?> iterable) {
            StringBuilder result = new StringBuilder("[");
            for (Object item : iterable) result.append(canonical(item)).append(',');
            return result.append(']').toString();
        }
        return String.valueOf(value);
    }
}
