package com.dwurdy.straja.domain.model;

/** Persisted lifecycle for an identity card. Expiry is derived from expiresAt. */
public enum IdentityCardStatus {
    VALID,
    REVOKED
}
