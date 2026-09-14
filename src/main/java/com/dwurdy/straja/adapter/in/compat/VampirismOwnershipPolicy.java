package com.dwurdy.straja.adapter.in.compat;

import com.dwurdy.straja.domain.model.LethalEventResolver;

/** Pure ownership and event-order rules for the Vampirism boundary. */
public final class VampirismOwnershipPolicy {
    public enum IncomingDamageAction {
        PASS_THROUGH,
        ALLOW_PROVIDER_DBNO_HANDOFF,
        CANCEL_PROVIDER_DBNO_FOLLOW_UP
    }

    private VampirismOwnershipPolicy() {}

    /** Mirrors the public Vampirism player API without importing it here. */
    public static OptionalCustodyProvider.ProviderState classify(
            boolean vampirePresent, int vampireLevel, boolean dbnoActive) {
        if (!vampirePresent) return OptionalCustodyProvider.ProviderState.NONE;
        if (dbnoActive) return new OptionalCustodyProvider.ProviderState(false, true);
        return vampireLevel > 0
                ? new OptionalCustodyProvider.ProviderState(true, false)
                : OptionalCustodyProvider.ProviderState.NONE;
    }

    /**
     * The initial lethal hit must reach Vampirism's later LivingDeath hook;
     * only an already-owned DBNO follow-up is canceled here. An explicit
     * finisher always passes through to the provider/vanilla death path.
     */
    public static IncomingDamageAction incomingDamageAction(
            OptionalCustodyProvider.ProviderState state, boolean explicitHardKill) {
        if (explicitHardKill) return IncomingDamageAction.PASS_THROUGH;
        if (state != null && state.dbnoActive()) {
            return IncomingDamageAction.CANCEL_PROVIDER_DBNO_FOLLOW_UP;
        }
        if (state != null && state.eligibleForDbno()) {
            return IncomingDamageAction.ALLOW_PROVIDER_DBNO_HANDOFF;
        }
        return IncomingDamageAction.PASS_THROUGH;
    }

    /** Maps a domain decision to the current NeoForge incoming-damage event. */
    public static boolean cancelIncomingDamage(LethalEventResolver.Outcome outcome) {
        return switch (outcome) {
            case PROTECTED_BY_CUSTODY, VAMPIRISM_PRESERVE,
                    STRAJA_DOWNED, IGNORED_TERMINAL -> true;
            case VAMPIRISM_DBNO, HARD_KILL, STRAJA_DEATH, VANILLA_DEATH -> false;
        };
    }
}
