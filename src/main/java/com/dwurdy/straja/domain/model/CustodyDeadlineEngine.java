package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves persisted custody deadlines against one server timestamp.
 *
 * <p>The engine deliberately does not own a scheduler or a repository.  The
 * application service calls {@link #resolveDue(CustodyState, long,
 * StrajaPolicies)} from the existing server tick, then persists the enclosing
 * aggregate once.  Absolute deadlines and the transition identity make a
 * restart, reconnect, late callback, or repeated tick safe.</p>
 */
public final class CustodyDeadlineEngine {
    private CustodyDeadlineEngine() {}

    public enum Timer {
        DOWNED_DEATH,
        TRANSPORT,
        RESUSCITATION,
        UNCONSCIOUS_CUSTODY,
        JAIL_DELIVERY,
        JAIL_REVIVAL
    }

    public record Resolution(Timer timer, String transitionId) {}

    /**
     * Resolves every deadline that is due at {@code now}.  A bounded loop is
     * intentional: transport expiry can resume a paused downed timer, and a
     * resuscitation interruption can likewise expose the timer at the same
     * timestamp.  No more than one transition per timer can be produced.
     */
    public static List<Resolution> resolveDue(CustodyState state, long now,
                                               StrajaPolicies policies) {
        if (state == null || policies == null || !state.wellFormed()) return List.of();
        var resolutions = new ArrayList<Resolution>();
        for (int i = 0; i < 8; i++) {
            var candidate = due(state, now, policies);
            if (candidate == null) break;
            var result = CustodyTransitionEngine.apply(state,
                    new CustodyTransition(candidate.transitionId(), candidate.action(), now,
                            "deadline-engine", "", "", StateProvider.SYSTEM,
                            "deadline:" + candidate.timer().name().toLowerCase(), 0),
                    policies);
            if (!result.ok() || result.idempotent()) break;
            resolutions.add(new Resolution(candidate.timer(), candidate.transitionId()));
        }
        return List.copyOf(resolutions);
    }

    /**
     * Applies the configured lifecycle policy on logout or server restart.
     * RETAIN is a no-op; all other policies produce one persisted state change
     * and clear the deadlines that no longer apply.
     */
    public static boolean recover(CustodyState state, RecoveryEvent event, long at,
                                  StrajaPolicies policies) {
        if (state == null || event == null || policies == null || !state.wellFormed()
                || state.condition == PlayerCondition.DEAD) return false;
        RecoveryBehavior behavior = policies.recoveryBehavior(event);
        if (behavior == RecoveryBehavior.RETAIN) return false;

        switch (behavior) {
            case CLEAR_ALL -> {
                state.condition = PlayerCondition.ALIVE;
                state.custody = CustodyStatus.FREE;
                state.restraint = RestraintStatus.NONE;
                state.vision = VisionStatus.NORMAL;
                state.destination = "";
                state.carrierId = "";
                state.restraintActorId = "";
                state.custodyActorId = "";
            }
            case RELEASE_TRANSPORT -> releaseTransport(state, at);
            case RELEASE_RESTRAINTS -> {
                state.condition = PlayerCondition.ALIVE;
                state.custody = CustodyStatus.FREE;
                state.restraint = RestraintStatus.NONE;
                state.vision = VisionStatus.NORMAL;
                state.restraintActorId = "";
                state.custodyActorId = "";
            }
            case WAKE -> {
                state.condition = state.restraint == RestraintStatus.NONE
                        ? PlayerCondition.ALIVE : PlayerCondition.CONSCIOUS_RESTRAINED;
                if (state.restraint == RestraintStatus.NONE) state.custody = CustodyStatus.FREE;
                state.unconsciousCustodyDeadlineAt = null;
                state.resuscitationDeadlineAt = null;
                state.downedDeadlineAt = null;
                state.pausedDownedRemainingMs = null;
                state.resuscitationProgress = 0;
                releaseTransport(state, at);
            }
            case RETAIN -> { return false; }
        }
        clearInactiveDeadlines(state);
        state.transitionId = "recovery:" + event.name().toLowerCase() + ":" + at;
        state.enteredAt = at;
        state.provider = StateProvider.SYSTEM;
        state.source = "recovery:" + event.name().toLowerCase();
        return true;
    }

    private static Candidate due(CustodyState state, long now, StrajaPolicies policies) {
        if (state.transport == TransportStatus.CARRIED && expired(state.transportDeadlineAt, now)) {
            return candidate(Timer.TRANSPORT, CustodyTransition.Action.RESOLVE_TRANSPORT_DEADLINE,
                    state.transportDeadlineAt, state);
        }
        if (state.condition == PlayerCondition.RESUSCITATING
                && expired(state.resuscitationDeadlineAt, now)) {
            return candidate(Timer.RESUSCITATION,
                    CustodyTransition.Action.RESOLVE_RESUSCITATION_DEADLINE,
                    state.resuscitationDeadlineAt, state);
        }
        if (state.condition == PlayerCondition.DOWNED
                && state.transport != TransportStatus.CARRIED
                && expired(state.downedDeadlineAt, now)) {
            return candidate(Timer.DOWNED_DEATH, CustodyTransition.Action.RESOLVE_DOWNED_DEADLINE,
                    state.downedDeadlineAt, state);
        }
        if (state.condition == PlayerCondition.UNCONSCIOUS_CUSTODY
                && state.custody == CustodyStatus.JAILED
                && expired(state.jailRevivalAt, now)) {
            return candidate(Timer.JAIL_REVIVAL, CustodyTransition.Action.REVIVE_IN_JAIL,
                    state.jailRevivalAt, state);
        }
        if (state.condition == PlayerCondition.UNCONSCIOUS_CUSTODY
                && state.custody != CustodyStatus.JAILED
                && expired(state.unconsciousCustodyDeadlineAt, now)) {
            return candidate(Timer.UNCONSCIOUS_CUSTODY,
                    CustodyTransition.Action.RESOLVE_UNCONSCIOUS_DEADLINE,
                    state.unconsciousCustodyDeadlineAt, state);
        }
        if (state.custody == CustodyStatus.ARRESTED
                && expired(state.jailDeliveryDeadlineAt, now)) {
            return candidate(Timer.JAIL_DELIVERY,
                    CustodyTransition.Action.RESOLVE_JAIL_DELIVERY_DEADLINE,
                    state.jailDeliveryDeadlineAt, state);
        }
        return null;
    }

    private static Candidate candidate(Timer timer, CustodyTransition.Action action,
                                       Long deadline, CustodyState state) {
        String identity = !blank(state.playerUuid) ? state.playerUuid : state.playerId;
        String id = "deadline:" + timer.name().toLowerCase() + ":" + identity + ":" + deadline;
        return new Candidate(timer, action, id);
    }

    private static void releaseTransport(CustodyState state, long at) {
        if (state.transport != TransportStatus.CARRIED) return;
        state.transport = TransportStatus.NONE;
        state.carrierId = "";
        state.transportDeadlineAt = null;
        if (state.condition == PlayerCondition.DOWNED && state.downedDeadlineAt == null
                && positive(state.pausedDownedRemainingMs)) {
            state.downedDeadlineAt = at + Math.max(1L, state.pausedDownedRemainingMs);
            state.pausedDownedRemainingMs = null;
        }
    }

    private static void clearInactiveDeadlines(CustodyState state) {
        if (state.condition != PlayerCondition.DOWNED
                && state.condition != PlayerCondition.RESUSCITATING) {
            state.downedDeadlineAt = null;
            state.pausedDownedRemainingMs = null;
        }
        if (state.condition != PlayerCondition.RESUSCITATING) state.resuscitationDeadlineAt = null;
        if (state.condition != PlayerCondition.UNCONSCIOUS_CUSTODY) {
            state.unconsciousCustodyDeadlineAt = null;
            state.jailRevivalAt = null;
        }
        if (state.custody != CustodyStatus.ARRESTED) state.jailDeliveryDeadlineAt = null;
        if (state.transport != TransportStatus.CARRIED) state.transportDeadlineAt = null;
    }

    private static boolean expired(Long deadline, long now) {
        return deadline != null && now >= deadline;
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Candidate(Timer timer, CustodyTransition.Action action,
                             String transitionId) {}
}
