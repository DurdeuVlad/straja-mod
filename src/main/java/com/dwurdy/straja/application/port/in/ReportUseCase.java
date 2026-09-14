package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import java.util.List;

/**
 * §11 activity reports. Implemented by the authoritative report service;
 * adapters (Secretary surface, form routing, test shims) depend only on this
 * inbound port.
 */
public interface ReportUseCase {

    enum Action { SUBMIT, STATUS, REVIEW_LIST, REVIEW }

    record AvailableAction(Action action, String reportId) {}

    /** Read-only projection of the report actions currently available to the player. */
    List<AvailableAction> availableActions(PlayerGateway player);

    /**
     * Files (or re-files a RETURNED report) for the current interval.
     * Idempotent: resubmitting while SUBMITTED updates the same record.
     */
    boolean submit(PlayerGateway player, String activity, String missions,
                   String incidents, String notes);

    /** The member's own report status, next deadline, and any review note. */
    void status(PlayerGateway player);

    /** Comisar-facing list of reports awaiting review. */
    void listForReview(PlayerGateway player);

    /**
     * Comisar review: {@code accept} | {@code return} (note required) |
     * {@code call} (summons the author for an audience).
     */
    boolean review(PlayerGateway player, String id, String decision, String note);
}
