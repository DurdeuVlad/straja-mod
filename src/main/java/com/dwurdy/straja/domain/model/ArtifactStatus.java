package com.dwurdy.straja.domain.model;

/** Legal state of a registered artifact. PENDING is computed, never stored. */
public enum ArtifactStatus {
    PENDING,
    ACTIVE,
    REVOKED
}
