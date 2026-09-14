package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;

/**
 * Focused inbound port for guard self-service duty operations: native NPC
 * surfaces call these methods and never reach concrete services directly.
 * The read-only {@link DutyView} derives display affordances; every mutating
 * method re-loads and revalidates authoritative state itself.
 */
public interface GuardDutyUseCase {
    record DutyView(boolean activeGuard, boolean canStart, String checkpointId, boolean canStop,
                    boolean canClaimSalary, boolean canViewCoins, boolean canClaimFood,
                    boolean canClaimKit, boolean canBeginResignation,
                    boolean canConfirmResignation, boolean canCancelResignation, boolean canRejoin) {}

    DutyView dutyView(PlayerGateway player);
    void startDuty(PlayerGateway player);
    void checkpoint(PlayerGateway player, String checkpointId);
    void stopDuty(PlayerGateway player);
    void salary(PlayerGateway player);
    void coins(PlayerGateway player);
    void food(PlayerGateway player);
    void kit(PlayerGateway player);
    void beginResignation(PlayerGateway player);
    void confirmResignation(PlayerGateway player);
    void cancelResignation(PlayerGateway player);
    void rejoin(PlayerGateway player);
    /**
     * Login recovery: closes a duty left active by a previous runtime boot and
     * stamps the current boot id so relogins inside one runtime keep the duty.
     */
    void recoverOnLogin(PlayerGateway player);
    /** Per-tick duty maintenance for one player (patrol cap, salary accrual). */
    com.dwurdy.straja.domain.model.DutyEngine.TickResult tickPlayerDuty(PlayerGateway player);
    void showRules(PlayerGateway player);
    void showStatus(PlayerGateway player);
}
