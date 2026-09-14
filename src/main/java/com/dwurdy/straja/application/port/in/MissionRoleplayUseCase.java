package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import java.util.List;

/**
 * Focused inbound port for the mission/order roleplay journey: native NPC and
 * physical-item surfaces call these methods instead of the concrete service.
 * {@link #availableActions} is a read-only projection adapters map to
 * clickable actions; every mutating method revalidates authoritative state.
 */
public interface MissionRoleplayUseCase {
    enum Action { GET_CARNET, DRAFT_WRITE, DRAFT_STATUS, DRAFT_SCOPE, DRAFT_SIGN, DRAFT_PACKAGE,
                  TEMPLATE_LIST, ISSUE_TEMPLATE, ADJUST_BUDGET,
                  JOIN, ACCEPT, DECLINE, REPORT, FAIL, COMPLETE, CLAIM_REWARD, RECOVER_REWARD }

    record AvailableAction(Action action, String missionId) {}

    List<AvailableAction> availableActions(PlayerGateway player);
    void list(PlayerGateway player);
    void giveCarnet(PlayerGateway player);
    void draftStatus(PlayerGateway player);
    void draftWrite(PlayerGateway player, int minutes, String start, int reward, String objective);
    void draftScope(PlayerGateway player, String minimumRank, int maxAssignees);
    void draftSign(PlayerGateway player);
    void draftPackage(PlayerGateway player);
    boolean issueDraft(PlayerGateway issuer, PlayerGateway target);
    /** §13 issuer preview: enabled templates with budgets re-derived from the current wage table. */
    void templateList(PlayerGateway player);
    /** §13 creates a work draft from a template; reward is calculated, never hand-typed. */
    void draftFromTemplate(PlayerGateway player, String templateId);
    /**
     * §13 adjusts a template draft's hours/risk and optionally overrides the
     * calculated reward. Overrides above the configured margin require a
     * reason and are audited.
     */
    void draftAdjust(PlayerGateway player, String hours, String risk, String reward, String reason);
    /** §13 commissioner administration: list every template including disabled. */
    void templateListAll(PlayerGateway player);
    void templateCreate(PlayerGateway player, String name, int minRank, double hours,
                        double risk, int maxPaid, int deadlineMinutes, String objective,
                        boolean supersedesPatrol);
    void templateSet(PlayerGateway player, String id, String field, String value);
    void templateDuplicate(PlayerGateway player, String id);
    void templateSetEnabled(PlayerGateway player, String id, boolean enabled);
    boolean join(PlayerGateway player, String id);
    void accept(PlayerGateway player, String id);
    boolean decline(PlayerGateway player, String id);
    boolean report(PlayerGateway player, String id, String report);
    boolean fail(PlayerGateway player, String id, String reason);
    boolean complete(PlayerGateway player, String id);
    boolean claimReward(PlayerGateway player, String id);
    boolean recoverReward(PlayerGateway player, String id);
    /**
     * Login recovery: reconciles the player's reward claims on completed
     * missions against payout receipts and retries pending or interrupted
     * payments while the recipient is online. Claims under commissioner review
     * are left untouched.
     */
    void deliverPendingRewards(PlayerGateway player);
    /** Per-tick lifecycle: deadline expiry/failure and retention pruning. */
    void tick();
}
