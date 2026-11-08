package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;

/**
 * Player-facing parole submissions (FUN-11): filing a case and designating
 * an advocate. Every gate re-verifies inside the service at consume time —
 * the port exposes verbs, never the raw store.
 */
public interface ParoleUseCase {

    /** One transparent eligibility check — what the prisoner can read. */
    record Check(String key, boolean ok, String detail) {}

    /**
     * The prisoner's parole projection for menus: custody and eligibility,
     * the pending case, advocate consent, the open bail window and the
     * released-parolee flag — everything a desk renders without touching
     * the store.
     */
    record ParoleSurface(
            boolean prisoner,
            boolean eligible,
            String pendingId,
            String pendingKind,
            String advocateStatus,
            boolean parolee,
            String conditionalId,
            int bailAmount) {}

    /**
     * Files a parole or appeal case. {@code kind} arrives bound to the desk
     * button the prisoner clicked; the service re-checks custody, standing,
     * served time, debts, escape record, cooldown and the no-open-case rule.
     */
    boolean file(PlayerGateway prisoner, String kind, String reason);

    /**
     * Designates an advocate for the prisoner's pending case; the named
     * player must consent before they count as representation.
     */
    boolean advocate(PlayerGateway prisoner, String advocateName);
}
