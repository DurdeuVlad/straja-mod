package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import java.util.List;

/** Player-facing custody surface: cuff requests, restraint, downed and recovery. */
public interface CustodyRoleplayUseCase {
    enum Action {
        ACCEPT_REQUEST,
        REFUSE_REQUEST,
        RELEASE_TARGET,
        REMOVE_HEAD_SACK,
        WAKE_DOWNED,
        GIVE_CUFFS
    }

    record AvailableAction(Action action, String recordId) {}

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
    boolean enterJail(PlayerGateway player, String destination);
    boolean releaseFromJail(PlayerGateway player, String reason);
    boolean giveCuffs(PlayerGateway player);

    DamageDecision batonStrike(
            PlayerGateway issuer,
            PlayerGateway target,
            double health,
            double absorption,
            double damage);

    double capBatonDamage(double health, double absorption);

    boolean actionBlocked(PlayerGateway player, String action);

    void cuffStatus(PlayerGateway player);
    void downedStatus(PlayerGateway player);

    void recoverOnLogin(PlayerGateway player);
    void recoverOnLogout(PlayerGateway player);
    void recoverAfterDeath(PlayerGateway player);

    void tick();
}
