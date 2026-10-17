package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.SetupChecklist;

/**
 * Read-only authority/capability queries for inbound adapters. Adapters use
 * these to decide whether a physical surface applies; every mutating use case
 * still revalidates the same rules inside the services.
 */
public interface PlayerQueryUseCase {

    /** Read-only projection source for contextual player-facing surfaces. */
    GuardState readState(PlayerGateway player);

    /**
     * Read-only state by raw uuid — for offline subjects (e.g. the Comisar
     * reading a petitioner's name/service data while they are offline).
     */
    GuardState readState(java.util.UUID uuid);

    /**
     * Display name for a subject uuid string: the recorded last-known name
     * when the state has one, else the raw identifier (non-uuid subject ids
     * pass through unchanged). The single name-resolution rule for surfaces.
     */
    default String displayName(String subjectUuid) {
        try {
            GuardState state = readState(java.util.UUID.fromString(subjectUuid));
            if (state != null && state.lastKnownName != null && !state.lastKnownName.isBlank()) {
                return state.lastKnownName;
            }
        } catch (IllegalArgumentException ignored) {}
        return subjectUuid;
    }

    boolean isCommissioner(PlayerGateway player);

    boolean hasCapability(PlayerGateway player, Capability capability);

    /** On-duty guard (rank at least Stagiar) — the jailer-assault exemption rule. */
    boolean isOnDutyGuard(PlayerGateway player);

    /** Resolves a player by name or UUID through the server view (includes test virtuals). */
    PlayerGateway findPlayer(String nameOrUuid);

    /**
     * Guided-setup nudge for the commissioner at login: the next missing
     * installation step, or null when setup is complete (or the player is not
     * the commissioner). Read-only; never reveals setup state to others.
     * {@code probes} carries the install-state the domain cannot reach itself.
     */
    String setupHintFor(PlayerGateway player, SetupChecklist.Probes probes);

    /**
     * §4 display composition: the bracketed rank prefix ("[Căpitan]") for
     * chat/TAB/nameplate surfaces, or null for civilians and former members.
     * Uses the configured rank display names (#15); faction membership never
     * suppresses it while the member is authorized.
     */
    String rankPrefixFor(PlayerGateway player);
}
