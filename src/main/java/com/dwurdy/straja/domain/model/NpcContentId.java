package com.dwurdy.straja.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/** Provider-neutral identifier for a surface, dialogue node, quest, or action. */
public record NpcContentId(String value) {
    private static final Pattern VALID = Pattern.compile(
            "(?:[a-z][a-z0-9._-]{0,127}|[a-z][a-z0-9._-]{0,63}:[A-Za-z0-9_-]{1,80})");

    public NpcContentId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "content id must be a lowercase identifier or a lowercase namespace "
                            + "followed by ':' and a safe key: " + value);
        }
    }

    public static NpcContentId of(String value) {
        return new NpcContentId(value);
    }
}
