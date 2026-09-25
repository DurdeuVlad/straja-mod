package com.dwurdy.straja.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/** Provider-neutral last-known realm and block position for an NPC host. */
public record NpcHostLocation(String realmId, int blockX, int blockY, int blockZ) {
    private static final Pattern REALM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public NpcHostLocation {
        Objects.requireNonNull(realmId, "realmId");
        if (!REALM_ID.matcher(realmId).matches()) {
            throw new IllegalArgumentException("realmId must be a namespaced identifier");
        }
    }

    public String displayValue() {
        return realmId + " @ " + blockX + ", " + blockY + ", " + blockZ;
    }
}
