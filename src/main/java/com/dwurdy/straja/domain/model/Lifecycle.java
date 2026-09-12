package com.dwurdy.straja.domain.model;

/** Derived lifecycle label. Never an authority source on its own. */
public enum Lifecycle {
    CIVIL,
    INVITED,
    ACTIVE_OFF_DUTY,
    ACTIVE_ON_DUTY,
    SUSPENDED,
    RESIGNATION_NOTICE,
    RESIGNED_COOLDOWN,
    FIRED
}
