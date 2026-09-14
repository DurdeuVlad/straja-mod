package com.dwurdy.straja.domain.model;

/** Origin of a state assertion, retained for provenance and recovery. */
public enum StateProvider {
    SYSTEM,
    NATIVE,
    MIGRATION,
    ADMIN,
    VAMPIRISM,
    UNKNOWN
}
