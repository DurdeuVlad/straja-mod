package com.dwurdy.straja.domain.model;

import java.util.Objects;

/** Explicit result for provider operations; failures must not look successful. */
public record NpcProviderResult(Status status, String code, String message) {
    public NpcProviderResult {
        Objects.requireNonNull(status, "status");
        if (code == null || code.isBlank() || code.length() > 64) {
            throw new IllegalArgumentException("code must be non-blank and at most 64 characters");
        }
        if (message == null || message.isBlank() || message.length() > 1_024) {
            throw new IllegalArgumentException("message must be non-blank and at most 1024 characters");
        }
    }

    public static NpcProviderResult accepted(String message) {
        return new NpcProviderResult(Status.ACCEPTED, "ok", message);
    }

    public static NpcProviderResult rejected(String code, String message) {
        return new NpcProviderResult(Status.REJECTED, code, message);
    }

    public static NpcProviderResult unavailable(String message) {
        return new NpcProviderResult(Status.UNAVAILABLE, "provider-unavailable", message);
    }

    public static NpcProviderResult unknown(String message) {
        return new NpcProviderResult(Status.UNKNOWN, "provider-state-unknown", message);
    }

    public static NpcProviderResult reconciled(String message) {
        return new NpcProviderResult(Status.RECONCILED, "provider-state-reconciled", message);
    }

    public enum Status {
        ACCEPTED,
        REJECTED,
        UNAVAILABLE,
        UNKNOWN,
        RECONCILED
    }
}
