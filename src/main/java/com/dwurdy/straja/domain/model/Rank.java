package com.dwurdy.straja.domain.model;

/**
 * Straja rank ladder. Numeric levels match the persisted/reference format.
 *
 * <p>The level-4 enum constant intentionally keeps its historical internal
 * name for persistence/source compatibility, but the player-facing rank is
 * Căpitan.</p>
 */
public enum Rank {
    CIVIL(0, "Civil"),
    JUNIOR(1, "Străjer Junior"),
    GUARD(2, "Străjer"),
    SENIOR(3, "Străjer Senior"),
    LIEUTENANT(4, "Căpitan");

    private final int level;
    private final String displayName;

    Rank(int level, String displayName) {
        this.level = level;
        this.displayName = displayName;
    }

    public int level() {
        return level;
    }

    public String displayName() {
        return displayName;
    }

    public static Rank of(int level) {
        for (Rank rank : values()) {
            if (rank.level == level) return rank;
        }
        return CIVIL;
    }

    public boolean atLeast(Rank other) {
        return level >= other.level;
    }
}
