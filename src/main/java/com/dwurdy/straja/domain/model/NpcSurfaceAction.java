package com.dwurdy.straja.domain.model;

import java.util.Objects;

/** A provider-neutral action exposed by an NPC surface. */
public record NpcSurfaceAction(
        NpcContentId actionId,
        String label,
        boolean enabled,
        String disabledReason) {

    public NpcSurfaceAction {
        Objects.requireNonNull(actionId, "actionId");
        if (label == null || label.isBlank() || label.length() > 256) {
            throw new IllegalArgumentException("label must be non-blank and at most 256 characters");
        }
        disabledReason = disabledReason == null ? "" : disabledReason;
        if (disabledReason.length() > 512) {
            throw new IllegalArgumentException("disabledReason must be at most 512 characters");
        }
        if (enabled && !disabledReason.isEmpty()) {
            throw new IllegalArgumentException("enabled action cannot have a disabled reason");
        }
    }

    public static NpcSurfaceAction enabled(NpcContentId actionId, String label) {
        return new NpcSurfaceAction(actionId, label, true, "");
    }

    public static NpcSurfaceAction disabled(
            NpcContentId actionId, String label, String disabledReason) {
        return new NpcSurfaceAction(actionId, label, false, disabledReason);
    }
}
