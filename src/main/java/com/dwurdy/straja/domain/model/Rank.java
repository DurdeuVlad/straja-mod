package com.dwurdy.straja.domain.model;

/** Straja rank ladder. Numeric levels match the reference persistence format. */
public enum Rank {
    CIVIL(0, "Civil"),
    JUNIOR(1, "Străjer Junior"),
    GUARD(2, "Străjer"),
    SENIOR(3, "Străjer Senior"),
    LIEUTENANT(4, "Locotenent");

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
