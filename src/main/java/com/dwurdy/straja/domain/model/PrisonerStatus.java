package com.dwurdy.straja.domain.model;

/** Custody position of a registered prisoner. */
public enum PrisonerStatus {
    IN_CELL,
    IN_CAMP,
    ESCORTED,
    FUGITIVE,
    RELEASED,
    /** Released by reaching the labor-camp freedom price (a served release). */
    SERVED_LABOR;

    /** Terminal states — the prisoner is out of custody entirely. */
    public boolean isReleased() {
        return this == RELEASED || this == SERVED_LABOR;
    }
}
