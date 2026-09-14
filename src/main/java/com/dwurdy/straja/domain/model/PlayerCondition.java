package com.dwurdy.straja.domain.model;

/**
 * The player's physical condition.  This dimension is deliberately separate
 * from legal custody and from physical restraints.
 */
public enum PlayerCondition {
    ALIVE,
    DOWNED,
    RESUSCITATING,
    UNCONSCIOUS_CUSTODY,
    CONSCIOUS_RESTRAINED,
    DEAD
}
