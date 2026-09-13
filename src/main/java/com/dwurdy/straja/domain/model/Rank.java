package com.dwurdy.straja.domain.model;

/**
 * Straja rank ladder per docs/gameplay-decisions.md §2:
 * Stagiar → Străjer → Sergent → Inspector. Comisar is a personnel flag,
 * not a ladder step. Numeric levels match the persisted format.
 */
public enum Rank {
    CIVIL(0, "Civil"),
    STAGIAR(1, "Stagiar"),
    GUARD(2, "Străjer"),
    SERGENT(3, "Sergent"),
    INSPECTOR(4, "Inspector");

    private final int level;
    private final String displayName;

    Rank(int level, String displayName) {
        this.level = level;
        this.displayName = displayName;
    }

    public int level() {
        return level;
    }

    /** Static fallback name — prefer {@code StrajaPolicies.rankName(level)} for display. */
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
