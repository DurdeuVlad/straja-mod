package com.dwurdy.straja.domain.model;

/** Configurable outcome for a damage category while custody state is active. */
public enum DamageBehavior {
    ALLOW,
    CANCEL,
    PRESERVE_DOWNED,
    CONVERT_TO_DOWNED,
    KILL
}
