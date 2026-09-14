package com.dwurdy.straja.domain.model;

/** Lifecycle interruptions with explicit custody recovery policy. */
public enum RecoveryEvent {
    LOGOUT,
    RESTART,
    DEATH,
    DIMENSION_CHANGE,
    MISSING_DESTINATION
}
