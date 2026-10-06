package com.dwurdy.straja.domain.model;

/** Legal state of a registered artifact. PENDING is computed, never stored.
 * FORGED marks a shadow record: a registry-known fake, never legal, kept for
 * audit and post-hoc forger tracking (#247). */
public enum ArtifactStatus {
    PENDING,
    ACTIVE,
    REVOKED,
    FORGED
}
