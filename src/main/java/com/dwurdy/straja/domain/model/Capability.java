package com.dwurdy.straja.domain.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Fine-grained capabilities, stricter than the generic permission levels.
 * The matrix mirrors the reference rankCapabilities table exactly.
 */
public enum Capability {
    ISSUE_FINES,
    USE_CUFFS,
    USE_BATON,
    EXECUTE_ARRESTS,
    ASSIST_COMPLAINTS,
    INVESTIGATE_COMPLAINTS,
    MOBILIZE_PLAYERS,
    CREATE_MISSIONS,
    APPROVE_REWARDS,
    REVIEW_APPEALS;

    private static final Map<Rank, Set<Capability>> MATRIX = new EnumMap<>(Rank.class);

    static {
        MATRIX.put(Rank.CIVIL, Set.of());
        MATRIX.put(Rank.JUNIOR, Set.of(ISSUE_FINES));
        MATRIX.put(Rank.GUARD, Set.of(
                ISSUE_FINES, USE_CUFFS, USE_BATON, EXECUTE_ARRESTS, ASSIST_COMPLAINTS));
        MATRIX.put(Rank.SENIOR, Set.of(
                ISSUE_FINES, USE_CUFFS, USE_BATON, EXECUTE_ARRESTS, ASSIST_COMPLAINTS,
                INVESTIGATE_COMPLAINTS, MOBILIZE_PLAYERS));
        MATRIX.put(Rank.LIEUTENANT, Set.of(
                ISSUE_FINES, USE_CUFFS, USE_BATON, EXECUTE_ARRESTS, ASSIST_COMPLAINTS,
                INVESTIGATE_COMPLAINTS, MOBILIZE_PLAYERS, CREATE_MISSIONS,
                APPROVE_REWARDS, REVIEW_APPEALS));
    }

    public static Set<Capability> of(Rank rank) {
        return MATRIX.getOrDefault(rank, Set.of());
    }

    public static boolean allowed(Rank rank, Capability capability) {
        return of(rank).contains(capability);
    }
}
