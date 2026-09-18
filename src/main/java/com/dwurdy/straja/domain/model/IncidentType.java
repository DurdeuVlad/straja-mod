package com.dwurdy.straja.domain.model;

/** Operational event categories; incidents are not reward-bearing missions. */
public enum IncidentType {
    CITIZEN_REPORT,
    GUARD_ASSISTANCE,
    JAILER_ASSAULT,
    ESCAPED_PRISONER,
    ARREST_REQUEST,
    DISTURBANCE,
    MANUAL
}
