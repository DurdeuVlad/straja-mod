package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import java.util.List;

/**
 * §12 audience requests to the Comisar via the Secretary. Implemented by the
 * authoritative audience service; adapters depend only on this port.
 */
public interface AudienceUseCase {

    enum Action { REQUEST, STATUS, REVIEW_LIST, REVIEW }

    record AvailableAction(Action action, String requestId) {}

    /** Read-only projection of the audience actions currently available to the player. */
    List<AvailableAction> availableActions(PlayerGateway player);

    /**
     * Files an audience request. One open request per member: while PENDING,
     * re-requesting updates the reason instead of creating a second record.
     */
    boolean request(PlayerGateway player, String reason);

    /** The member's pending request and latest outcome. */
    void status(PlayerGateway player);

    /** Comisar-facing list of pending requests. */
    void listForReview(PlayerGateway player);

    /** Comisar decision: {@code resolve} or {@code dismiss} (optional note). */
    boolean resolve(PlayerGateway player, String id, String decision, String note);

    /** Login recovery: delivers a decided-but-untold outcome to the requester. */
    void deliverOutcome(PlayerGateway player);
}
