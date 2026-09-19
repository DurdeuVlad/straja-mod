package com.dwurdy.straja.domain.model;

import java.util.Objects;

/** Result returned by the authoritative Straja action boundary. */
public record NpcActionResult(Status status, String code, String message) {
    public NpcActionResult {
        Objects.requireNonNull(status, "status");
        if (code == null || code.isBlank() || code.length() > 64) {
            throw new IllegalArgumentException("code must be non-blank and at most 64 characters");
        }
        if (message == null || message.isBlank() || message.length() > 1_024) {
            throw new IllegalArgumentException("message must be non-blank and at most 1024 characters");
        }
    }

    public enum Status {
        ACCEPTED,
        REJECTED,
        EXPIRED,
        UNAUTHORIZED,
        UNAVAILABLE
    }
}
