package com.dwurdy.straja.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stable public identity for an authored Straja NPC profile. */
public record NpcProfileId(String value) {
    private static final Pattern VALID = Pattern.compile(
            "[a-z0-9._-]{1,64}:[a-z0-9/._-]{1,64}");

    public NpcProfileId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("profile id must be a lowercase namespaced identifier: " + value);
        }
    }

    public static NpcProfileId of(String value) {
        return new NpcProfileId(value);
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    /** Stable compatibility mapping for pre-catalog dotted content IDs. */
    public static NpcProfileId fromContentId(NpcContentId contentId) {
        Objects.requireNonNull(contentId, "contentId");
        String value = contentId.value();
        if (isValid(value)) return of(value);
        String suffix = UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
        // Legacy dotted IDs have no public namespace boundary. Hash the entire
        // value so punctuation or prefix normalization cannot alias two IDs.
        return of("straja:legacy-" + suffix);
    }
}
