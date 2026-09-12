package com.dwurdy.straja.domain.model;

/** Server-side authority levels. Numeric ordering matches the reference. */
public enum PermissionLevel {
    PUBLIC(0),
    GUARD(1),
    LIEUTENANT(2),
    COMMISSIONER(3);

    private final int level;

    PermissionLevel(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    public boolean atLeast(PermissionLevel required) {
        return level >= required.level;
    }
}
