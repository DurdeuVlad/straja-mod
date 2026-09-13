package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import java.util.List;

/**
 * Player-facing complaint use case. Implemented by the authoritative complaint
 * service; adapters (NPC surfaces, form routing, physical item interactions)
 * depend only on this inbound port, never on the concrete service.
 */
public interface ComplaintRoleplayUseCase {

    enum Action { SUBMIT, LIST, CLAIM, JOIN, LEAVE, REPORT, CONFIRM, WITHDRAW, REVIEW }

    record AvailableAction(Action action, String complaintId) {}

    record Limits(int description, int evidence, int withdrawalReason) {}

    /** Read-only projection of the complaint actions currently available to the player. */
    List<AvailableAction> availableActions(PlayerGateway player);

    /** Field length limits exposed to native form sessions. */
    Limits limits();

    boolean submit(PlayerGateway player, String accused, String category, String description);

    void list(PlayerGateway player);

    boolean claim(PlayerGateway player, String id);

    boolean join(PlayerGateway player, String id);

    boolean leave(PlayerGateway player, String id);

    boolean report(PlayerGateway player, String id, String report);

    boolean confirm(PlayerGateway player, String id);

    boolean withdraw(PlayerGateway player, String id, String reason);

    boolean review(PlayerGateway player, String id, String decision, Integer reward);

    /**
     * Login recovery: re-delivers pending investigation rewards to a player
     * that just came online. Receipt-scoped and idempotent.
     */
    void claimPendingRewards(PlayerGateway player);
}
