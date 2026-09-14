package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import java.util.List;

/**
 * §14 Comisar administrative interface on the Secretary surface: personnel
 * roster and dossiers, direct authorization at rank, rank changes, suspension,
 * revocation and reinstatement, the active-duty roster, plus runtime policy
 * and emergency management. Every action revalidates Comisar (or op/console)
 * authority server-side.
 */
public interface AdminRoleplayUseCase {

    enum Action {
        PERSONNEL, ROSTER_ACTIVE, DOSSIER, AUTHORIZE,
        PROMOTE, DEMOTE, SUSPEND, FIRE, REINSTATE,
        POLICIES, POLICY_SET,
        EMERGENCY_STATUS, EMERGENCY_ALERT, EMERGENCY_START, EMERGENCY_END
    }

    /** Member-scoped actions carry the member's UUID string in memberId. */
    record AvailableAction(Action action, String memberId, String memberName) {}

    /** Admin actions currently available — empty unless the actor is authorized. */
    List<AvailableAction> availableActions(PlayerGateway player);

    /** Revalidates a parameterized admin action before dispatch (stale-click guard). */
    boolean isStillValid(PlayerGateway player, Action action, String memberId);

    void personnel(PlayerGateway actor);
    void activeRoster(PlayerGateway actor);
    void dossier(PlayerGateway actor, String memberId);
    void authorize(PlayerGateway actor, String name, int rank);
    void promote(PlayerGateway actor, String memberId);
    void demote(PlayerGateway actor, String memberId);
    void suspend(PlayerGateway actor, String memberId);
    void fire(PlayerGateway actor, String memberId);
    void reinstate(PlayerGateway actor, String memberId);
    void policyList(PlayerGateway actor);
    void policySet(PlayerGateway actor, String key, String value);
    void emergencyStatus(PlayerGateway actor);
    void emergencyAlert(PlayerGateway actor, String message);
    void emergencyStart(PlayerGateway actor, Double multiplier, Integer rounds, String reason);
    void emergencyEnd(PlayerGateway actor);
}
