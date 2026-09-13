package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import java.util.List;

/**
 * Player-facing fine use case. Implemented by the authoritative fine service;
 * adapters (NPC surfaces, form routing, physical item interactions) depend
 * only on this inbound port, never on the concrete service.
 */
public interface FineRoleplayUseCase {

    enum Action { DRAFT_WRITE, DRAFT_STATUS, PAY, REFUSE, APPEAL, LIST_APPEALS, REVIEW_APPEAL,
                  LIST_TASKS, ACCEPT_TASK, COMPLETE_TASK, ARREST_TASK, CLAIM_TASK_REWARD,
                  HEARING_WARRANT }

    record AvailableAction(Action action, String recordId) {}

    record Limits(int law, int description, int appealReason, int reviewReason, int warrantReason) {}

    /** Read-only projection of the fine actions currently available to the player. */
    List<AvailableAction> availableActions(PlayerGateway player);

    /** Field length limits exposed to native form sessions. */
    Limits limits();

    boolean writeDraft(PlayerGateway issuer, String targetName, int amount, String law, String description);

    String draftText(PlayerGateway issuer);

    boolean issueFromDraft(PlayerGateway issuer, PlayerGateway target);

    boolean pay(PlayerGateway player, String id);

    boolean refusePayment(PlayerGateway player, String taskId);

    boolean appeal(PlayerGateway player, String id, String reason);

    void listAppeals(PlayerGateway player);

    boolean reviewAppeal(PlayerGateway player, String id, String decision, Integer reducedAmount, String reason);

    void listTasks(PlayerGateway player);

    boolean acceptTask(PlayerGateway player, String id);

    boolean completeTask(PlayerGateway player, String id);

    boolean arrest(PlayerGateway player, String id, Integer commissionerDays);

    boolean claimTaskReward(PlayerGateway player, String id);

    boolean issueHearingWarrant(PlayerGateway player, String targetName, String details);

    void listFines(PlayerGateway player);

    /**
     * Login recovery: retries arrest bounties whose delivery failed or was
     * interrupted. The payout receipt makes the retry idempotent.
     */
    void recoverOnLogin(PlayerGateway player);

    /** Per-tick lifecycle: payment reminders and fine escalation. */
    void tick();

    /** Opens a JAILER_ASSAULT arrest task after a jailer is hurt or killed. */
    com.dwurdy.straja.domain.model.FineTask createJailerAssaultMission(
            PlayerGateway attacker, String jailerName, String outcome);

    /** Resolves open arrest tasks against a suspect killed by an empowered guard. */
    void suspectKilled(PlayerGateway killer, PlayerGateway victim);
}
