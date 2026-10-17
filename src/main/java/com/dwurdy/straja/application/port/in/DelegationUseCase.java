package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.DelegableAction;
import com.dwurdy.straja.domain.model.DelegationMode;
import java.util.List;

/**
 * Player-facing delegation surface: the authority sees the batched pending
 * digest and manages modes, windows, and standing orders per request queue.
 * All mutations require delegation authority and are audited.
 */
public interface DelegationUseCase {

    /** One pending request rendered in the digest. {@code reviewActionId} is
     *  the native review action that opens the queue's own decision form —
     *  the digest batches, it does not replace the per-queue review. */
    record DigestEntry(DelegableAction action, String requestId, String subjectName,
                       String summary, String reviewActionId, long submittedAt) {}

    /** The live policy for one queue as rendered on the surface. */
    record PolicyView(DelegableAction action, DelegationMode mode, long timeoutMillis,
                      List<OrderView> orders) {}

    record OrderView(String orderId, String authorName, String filter, boolean active) {}

    /** One issuable standing-order option: the queue plus the concrete filter
     *  value (or "*") it would match, with a display label. */
    record OrderOption(String action, String filter, String label) {}

    /** Everything the Secretary surface needs for the delegation section —
     *  digest rows, live policies, and the issuable order vocabulary. Empty
     *  for viewers without delegation authority. */
    record SurfaceView(List<DigestEntry> digest, List<PolicyView> policies,
                       List<OrderOption> orderOptions) {
        public static SurfaceView empty() {
            return new SurfaceView(List.of(), List.of(), List.of());
        }
    }

    /** The aggregated surface view for one viewer. */
    SurfaceView surface(PlayerGateway viewer);

    /** Cycles the queue's mode across its allowed values. */
    void cycleMode(PlayerGateway actor, String actionName);

    /** Cycles the queue's timeout window across the configured steps. */
    void cycleTimeout(PlayerGateway actor, String actionName);

    /** Publishes a standing order for the queue ({@code filter} or "*"). */
    void issueOrder(PlayerGateway actor, String actionName, String filter);

    /** Revokes a standing order; author or any delegation authority. */
    void revokeOrder(PlayerGateway actor, String orderId);

    /** Periodic sweep: applies standing orders and timeout presumption to the
     *  real pending queues. Safe to call often — internally rate-limited. */
    void tick();
}
