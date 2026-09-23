package com.dwurdy.straja.domain.model;

/** Durable phase of an explicit provider reassignment transaction. */
public enum NpcRebindPhase {
    UNBIND,
    BIND,
    PUBLISH,
    RESTORE,
    UNASSIGN
}
