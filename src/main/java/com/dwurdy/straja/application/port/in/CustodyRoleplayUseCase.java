package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.DamageCategory;
import com.dwurdy.straja.domain.model.LethalEventResolver;
import java.util.List;
import java.util.UUID;

/** Player-facing custody surface: cuff requests, restraint, downed and recovery. */
public interface CustodyRoleplayUseCase {
    /** Server-authoritative visual modes; clients never infer gameplay state. */
    enum VisualMode { NORMAL, FAINT, CARRIED, RESTRAINED }

    enum RestraintVisual { NONE, ROPE, CUFFS }

    record VisualState(
            UUID playerId,
            VisualMode mode,
            RestraintVisual restraint,
            boolean blindfolded) {}

    enum Action {
        ACCEPT_REQUEST,
        REFUSE_REQUEST,
        RELEASE_TARGET,
        REMOVE_HEAD_SACK,
        WAKE_DOWNED,
        GIVE_CUFFS
    }

    record AvailableAction(Action action, String recordId) {}

    /** Server-authoritative eligibility result for the later confirmation UI. */
    record GiveUpEligibility(boolean eligible, String code) {}

    enum DamageAction {
        NOT_BATON,
        CANCEL,
        ALLOW_NONLETHAL
    }

    record DamageDecision(DamageAction action, String reason) {}

    List<AvailableAction> availableActions(PlayerGateway player);

    boolean isCuffed(PlayerGateway player);
    boolean isBound(PlayerGateway player);
    boolean isDowned(PlayerGateway player);

    List<VisualState> visualStates();

    boolean requestCuffs(PlayerGateway issuer, PlayerGateway target);
    boolean accept(PlayerGateway player, String id);
    boolean refuse(PlayerGateway player, String id);

    boolean release(PlayerGateway issuer, PlayerGateway target);
    boolean releaseById(PlayerGateway issuer, String targetId);

    boolean applyRope(PlayerGateway issuer, PlayerGateway target);
    boolean applyHeadSack(PlayerGateway issuer, PlayerGateway target);
    boolean removeHeadSack(PlayerGateway player);
    boolean wakeDowned(PlayerGateway player, String reason);

    boolean resolveDowned(PlayerGateway player, String destination);
    boolean giveCuffs(PlayerGateway player);

    /** Read-only guard used before opening the client confirmation prompt. */
    GiveUpEligibility giveUpEligibility(PlayerGateway player);

    /** Applies one confirmed, idempotent give-up transition for the player. */
    boolean giveUp(PlayerGateway player, boolean confirmed);

    boolean startCarry(PlayerGateway carrier, PlayerGateway target);
    boolean dropCarry(PlayerGateway carrier, PlayerGateway target, String reason);

    boolean startResuscitation(PlayerGateway rescuer, PlayerGateway target);
    boolean advanceResuscitation(PlayerGateway rescuer, PlayerGateway target, int progress);

    DamageDecision batonStrike(
            PlayerGateway issuer,
            PlayerGateway target,
            double health,
            double absorption,
            double damage);

    double capBatonDamage(double health, double absorption);

    /** Resolves one potentially lethal event without allowing provider overlap. */
    LethalEventResolver.Decision resolveLethalEvent(
            PlayerGateway target,
            PlayerGateway source,
            DamageCategory category,
            boolean explicitHardKill,
            boolean vampireEligible,
            boolean vampireDbnoActive);

    boolean actionBlocked(PlayerGateway player, String action);

    void cuffStatus(PlayerGateway player);
    void downedStatus(PlayerGateway player);

    void recoverOnLogin(PlayerGateway player);
    void recoverOnLogout(PlayerGateway player);
    void recoverOnDimensionChange(PlayerGateway player);
    void recoverAfterDeath(PlayerGateway player);

    void tick();
}
