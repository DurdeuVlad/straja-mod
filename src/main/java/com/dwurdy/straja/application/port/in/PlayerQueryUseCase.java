package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;

/**
 * Read-only authority/capability queries for inbound adapters. Adapters use
 * these to decide whether a physical surface applies; every mutating use case
 * still revalidates the same rules inside the services.
 */
public interface PlayerQueryUseCase {

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
     */
    String setupHintFor(PlayerGateway player);
}
