package com.dwurdy.straja.application.port.out;

import java.util.Map;

/**
 * Persisted runtime policy overrides — flat {@code key → raw value} map.
 * The file format (YAML) is an adapter detail; the application sees strings.
 */
public interface PolicyOverrideStore {
    Map<String, String> read();

    /** @return false when the write could not be persisted. */
    boolean write(Map<String, String> overrides);

    /** Human-readable source for admin tells (e.g. the file name). */
    String describe();
}
