package com.dwurdy.straja.domain.model;

/** Explicit recovery outcome for lifecycle interruptions. */
public enum RecoveryBehavior {
    RETAIN,
    CLEAR_ALL,
    RELEASE_TRANSPORT,
    RELEASE_RESTRAINTS,
    WAKE
}
