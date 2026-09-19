package com.dwurdy.straja.domain.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable identifier for an NPC presentation provider. */
public record NpcProviderId(String value) {
    private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    public static final NpcProviderId CUSTOM_NPCS = new NpcProviderId("customnpcs");
    public static final NpcProviderId DEBUG_TEXT = new NpcProviderId("debug-text");

    public NpcProviderId {
        Objects.requireNonNull(value, "value");
        value = value.toLowerCase(Locale.ROOT);
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "provider id must match [a-z][a-z0-9-]{0,31}: " + value);
        }
    }

    public static NpcProviderId of(String value) {
        return new NpcProviderId(value);
    }
}
