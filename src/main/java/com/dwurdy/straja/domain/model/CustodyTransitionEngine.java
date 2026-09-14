package com.dwurdy.straja.domain.model;

/**
 * Server-independent custody state machine.  It owns only domain invariants;
 * adapters remain responsible for capability checks, inventory, entities,
 * effects, messaging, and persistence.
 */
public final class CustodyTransitionEngine {
    private CustodyTransitionEngine() {}

    public static CustodyTransitionResult apply(CustodyState state,
                                                 CustodyTransition transition,
                                                 StrajaPolicies policies) {
        if (state == null || transition == null || policies == null) {
            return CustodyTransitionResult.rejected("INVALID_INPUT");
        }
        if (transition.id().isEmpty()) {
            return CustodyTransitionResult.rejected("TRANSITION_ID_REQUIRED");
        }
        if (transition.action() == null || transition.at() < 0) {
            return CustodyTransitionResult.rejected("INVALID_TRANSITION");
        }
        if (!state.wellFormed()) {
            return CustodyTransitionResult.rejected("MALFORMED_STATE");
        }
        if (transition.id().equals(state.transitionId)) {
            return CustodyTransitionResult.replay();
        }

        CustodyTransitionResult result = switch (transition.action()) {
            case ENTER_DOWNED -> enterDowned(state, transition, policies);
            case ENTER_UNCONSCIOUS_CUSTODY -> enterUnconsciousCustody(state, transition, policies);
            case START_RESUSCITATION -> startResuscitation(state, transition, policies);
            case ADVANCE_RESUSCITATION -> advanceResuscitation(state, transition, policies);
            case INTERRUPT_RESUSCITATION -> interruptResuscitation(state, transition);
            case APPLY_ROPE -> applyRestraint(state, transition, policies, RestraintStatus.ROPE_BOUND,
                    CustodyStatus.HOSTAGE, "CRIMINAL_ROPE_DISABLED");
            case APPLY_CUFFS -> applyRestraint(state, transition, policies, RestraintStatus.CUFFED,
                    CustodyStatus.ARRESTED, "POLICE_CUFFS_DISABLED");
            case START_CARRY -> startCarry(state, transition, policies);
            case STOP_CARRY -> stopCarry(state, transition);
            case APPLY_BLINDFOLD -> applyBlindfold(state, transition, policies);
            case REMOVE_BLINDFOLD -> removeBlindfold(state, transition, policies);
            case WAKE -> wake(state, transition, policies);
            case RESOLVE_UNCONSCIOUS_DEADLINE -> resolveUnconsciousDeadline(state, transition);
            case RESOLVE_JAIL_DELIVERY_DEADLINE ->
                    resolveJailDeliveryDeadline(state, transition);
            case DELIVER_TO_JAIL -> deliverToJail(state, transition, policies);
            case REVIVE_IN_JAIL -> reviveInJail(state, transition, policies);
            case RELEASE_RESTRAINT -> releaseRestraint(state, transition);
            case RELEASE_CUSTODY -> releaseCustody(state, transition, policies);
            case RECOVER_CLEAR_ALL -> recoverClearAll(state);
            case RECOVER_RELEASE_RESTRAINTS -> recoverReleaseRestraints(state);
            case RECOVER_WAKE -> recoverWake(state);
            case GIVE_UP -> giveUp(state, transition, policies);
            case DIE -> die(state, transition, policies);
        };
        if (!result.ok()) return result;

        state.transitionId = transition.id();
        state.enteredAt = transition.at();
        state.provider = transition.provider();
        if (!transition.source().isEmpty()) state.source = transition.source();
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult enterDowned(CustodyState state,
                                                         CustodyTransition transition,
                                                         StrajaPolicies policies) {
        if (state.condition != PlayerCondition.ALIVE
                || state.custody != CustodyStatus.FREE
                || state.restraint != RestraintStatus.NONE
                || state.transport != TransportStatus.NONE) {
            return reject("DOWNED_REQUIRES_FREE_ALIVE");
        }
        state.condition = PlayerCondition.DOWNED;
        state.downedDeadlineAt = deadline(transition.at(), policies.downedDurationSeconds);
        state.pausedDownedRemainingMs = 0;
        state.resuscitationDeadlineAt = null;
        state.unconsciousCustodyDeadlineAt = null;
        state.transportDeadlineAt = null;
        state.jailDeliveryDeadlineAt = null;
        state.jailRevivalAt = null;
        state.resuscitationProgress = 0;
        state.resuscitatorId = "";
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult startResuscitation(CustodyState state,
                                                                CustodyTransition transition,
                                                                StrajaPolicies policies) {
        if (state.condition != PlayerCondition.DOWNED
                || state.custody != CustodyStatus.FREE
                || state.restraint != RestraintStatus.NONE
                || state.transport != TransportStatus.NONE) {
            return reject("RESUSCITATION_REQUIRES_DOWNED_FREE");
        }
        if (blank(transition.actorId()) || samePlayer(state, transition.actorId())) {
            return reject("RESUSCITATOR_INVALID");
        }
        if (expired(state.downedDeadlineAt, transition.at())) {
            return reject("DOWNED_DEADLINE_EXPIRED");
        }
        long remaining = remaining(state.downedDeadlineAt, transition.at());
        if (remaining <= 0) return reject("DOWNED_DEADLINE_EXPIRED");
        state.condition = PlayerCondition.RESUSCITATING;
        state.downedDeadlineAt = null;
        state.pausedDownedRemainingMs = remaining;
        state.resuscitationDeadlineAt = deadline(transition.at(), policies.resuscitationTimeoutSeconds);
        state.resuscitationProgress = 0;
        state.resuscitatorId = transition.actorId();
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult advanceResuscitation(CustodyState state,
                                                                  CustodyTransition transition,
                                                                  StrajaPolicies policies) {
        if (state.condition != PlayerCondition.RESUSCITATING) {
            return reject("RESUSCITATION_NOT_ACTIVE");
        }
        if (blank(transition.actorId()) || !transition.actorId().equals(state.resuscitatorId)) {
            return reject("RESUSCITATOR_MISMATCH");
        }
        if (expired(state.resuscitationDeadlineAt, transition.at())) {
            return reject("RESUSCITATION_DEADLINE_EXPIRED");
        }
        if (transition.progress() < state.resuscitationProgress || transition.progress() > 100) {
            return reject("RESUSCITATION_PROGRESS_INVALID");
        }
        state.resuscitationProgress = transition.progress();
        int required = Math.max(1, Math.min(100, policies.resuscitationProgressPercent));
        if (state.resuscitationProgress >= required) {
            state.condition = PlayerCondition.ALIVE;
            state.downedDeadlineAt = null;
            state.pausedDownedRemainingMs = 0;
            state.resuscitationDeadlineAt = null;
            state.resuscitationProgress = 0;
            state.resuscitatorId = "";
        }
        return CustodyTransitionResult.applied();
    }

    /** Converts an already restrained conscious target into custody unconsciousness. */
    private static CustodyTransitionResult enterUnconsciousCustody(
            CustodyState state, CustodyTransition transition, StrajaPolicies policies) {
        if (state.condition != PlayerCondition.CONSCIOUS_RESTRAINED
                || state.restraint == RestraintStatus.NONE
                || state.custody == CustodyStatus.FREE) {
            return reject("UNCONSCIOUS_CUSTODY_REQUIRES_RESTRAINT");
        }
        state.condition = PlayerCondition.UNCONSCIOUS_CUSTODY;
        state.unconsciousCustodyDeadlineAt = deadline(
                transition.at(), policies.unconsciousCustodyDurationSeconds);
        state.jailDeliveryDeadlineAt = state.custody == CustodyStatus.ARRESTED
                ? deadline(transition.at(), policies.jailDeliveryDeadlineSeconds) : null;
        state.resuscitationDeadlineAt = null;
        state.resuscitationProgress = 0;
        state.resuscitatorId = "";
        state.downedDeadlineAt = null;
        state.pausedDownedRemainingMs = 0;
        return CustodyTransitionResult.applied();
    }

    /** Interrupts resuscitation and resumes the paused normal downed clock. */
    private static CustodyTransitionResult interruptResuscitation(CustodyState state,
                                                                    CustodyTransition transition) {
        if (state.condition != PlayerCondition.RESUSCITATING) {
            return reject("RESUSCITATION_NOT_ACTIVE");
        }
        if (state.pausedDownedRemainingMs <= 0) {
            return reject("PAUSED_DOWNED_TIME_MISSING");
        }
        state.condition = PlayerCondition.DOWNED;
        state.downedDeadlineAt = deadlineFromRemaining(
                transition.at(), state.pausedDownedRemainingMs);
        state.pausedDownedRemainingMs = 0;
        state.resuscitationDeadlineAt = null;
        state.resuscitationProgress = 0;
        state.resuscitatorId = "";
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult applyRestraint(CustodyState state,
                                                            CustodyTransition transition,
                                                            StrajaPolicies policies,
                                                            RestraintStatus restraint,
                                                            CustodyStatus custody,
                                                            String disabledCode) {
        boolean enabled = restraint == RestraintStatus.ROPE_BOUND
                ? policies.criminalRopeEnabled : policies.policeCuffsEnabled;
        if (!enabled) return reject(disabledCode);
        if (blank(transition.actorId()) || samePlayer(state, transition.actorId())) {
            return reject("RESTRAINT_ACTOR_INVALID");
        }
        if ((state.condition != PlayerCondition.ALIVE && state.condition != PlayerCondition.DOWNED)
                || state.custody != CustodyStatus.FREE
                || state.restraint != RestraintStatus.NONE) {
            return reject("RESTRAINT_REQUIRES_FREE_TARGET");
        }
        state.restraint = restraint;
        state.custody = custody;
        state.restraintActorId = transition.actorId();
        state.custodyActorId = transition.actorId();
        state.vision = VisionStatus.NORMAL;
        state.resuscitationDeadlineAt = null;
        state.resuscitationProgress = 0;
        state.resuscitatorId = "";
        state.pausedDownedRemainingMs = 0;
        if (state.condition == PlayerCondition.DOWNED) {
            state.condition = PlayerCondition.UNCONSCIOUS_CUSTODY;
            state.downedDeadlineAt = null;
            state.unconsciousCustodyDeadlineAt = deadline(
                    transition.at(), policies.unconsciousCustodyDurationSeconds);
        } else {
            state.condition = PlayerCondition.CONSCIOUS_RESTRAINED;
            state.unconsciousCustodyDeadlineAt = null;
        }
        if (custody == CustodyStatus.ARRESTED) {
            state.jailDeliveryDeadlineAt = deadline(transition.at(), policies.jailDeliveryDeadlineSeconds);
        }
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult startCarry(CustodyState state,
                                                        CustodyTransition transition,
                                                        StrajaPolicies policies) {
        String carrier = transition.carrierId().isEmpty() ? transition.actorId() : transition.carrierId();
        if (blank(carrier) || samePlayer(state, carrier)) return reject("CARRIER_INVALID");
        if (state.condition == PlayerCondition.DEAD || state.transport != TransportStatus.NONE) {
            return reject("CARRY_NOT_AVAILABLE");
        }
        if (state.condition == PlayerCondition.DOWNED) {
            long remaining = remaining(state.downedDeadlineAt, transition.at());
            if (remaining <= 0) return reject("DOWNED_DEADLINE_EXPIRED");
            state.downedDeadlineAt = null;
            state.pausedDownedRemainingMs = remaining;
        }
        state.transport = TransportStatus.CARRIED;
        state.carrierId = carrier;
        state.transportDeadlineAt = deadline(transition.at(), policies.carryTransportDeadlineSeconds);
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult stopCarry(CustodyState state,
                                                       CustodyTransition transition) {
        if (state.transport != TransportStatus.CARRIED) return reject("NOT_CARRIED");
        if (state.condition == PlayerCondition.DOWNED) {
            if (state.pausedDownedRemainingMs <= 0) return reject("PAUSED_DOWNED_TIME_MISSING");
            state.downedDeadlineAt = deadlineFromRemaining(
                    transition.at(), state.pausedDownedRemainingMs);
            state.pausedDownedRemainingMs = 0;
        }
        state.transport = TransportStatus.NONE;
        state.carrierId = "";
        state.transportDeadlineAt = null;
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult applyBlindfold(CustodyState state,
                                                           CustodyTransition transition,
                                                           StrajaPolicies policies) {
        if (!policies.blackSackApplicationEnabled) return reject("BLACK_SACK_APPLICATION_DISABLED");
        if (blank(transition.actorId()) || samePlayer(state, transition.actorId())) {
            return reject("BLINDFOLD_ACTOR_INVALID");
        }
        if (state.restraint == RestraintStatus.NONE || state.vision == VisionStatus.BLINDFOLDED) {
            return reject("BLINDFOLD_REQUIRES_UNBLINDFOLDED_RESTRAINT");
        }
        state.vision = VisionStatus.BLINDFOLDED;
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult removeBlindfold(CustodyState state,
                                                             CustodyTransition transition,
                                                             StrajaPolicies policies) {
        if (!policies.blackSackRemovalEnabled) return reject("BLACK_SACK_REMOVAL_DISABLED");
        if (state.vision != VisionStatus.BLINDFOLDED) return reject("NOT_BLINDFOLDED");
        if (blank(transition.actorId())) return reject("BLINDFOLD_ACTOR_REQUIRED");
        if (samePlayer(state, transition.actorId()) && !policies.blackSackSelfRemoval) {
            return reject("SELF_BLINDFOLD_REMOVAL_FORBIDDEN");
        }
        state.vision = VisionStatus.NORMAL;
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult wake(CustodyState state,
                                                 CustodyTransition transition,
                                                 StrajaPolicies policies) {
        if (state.condition == PlayerCondition.DOWNED) {
            if (!expired(state.downedDeadlineAt, transition.at())) return reject("DOWNED_DEADLINE_ACTIVE");
            state.condition = PlayerCondition.ALIVE;
            state.custody = CustodyStatus.FREE;
            state.downedDeadlineAt = null;
            state.pausedDownedRemainingMs = 0;
            state.resuscitatorId = "";
            return CustodyTransitionResult.applied();
        }
        if (state.condition == PlayerCondition.UNCONSCIOUS_CUSTODY) {
            if (!expired(state.unconsciousCustodyDeadlineAt, transition.at())) {
                return reject("UNCONSCIOUS_CUSTODY_DEADLINE_ACTIVE");
            }
            state.condition = state.restraint == RestraintStatus.NONE
                    ? PlayerCondition.ALIVE : PlayerCondition.CONSCIOUS_RESTRAINED;
            state.unconsciousCustodyDeadlineAt = null;
            state.pausedDownedRemainingMs = 0;
            state.resuscitatorId = "";
            return CustodyTransitionResult.applied();
        }
        return reject("WAKE_REQUIRES_CONTROL_LOSS");
    }

    private static CustodyTransitionResult resolveUnconsciousDeadline(CustodyState state,
                                                                        CustodyTransition transition) {
        if (state.condition != PlayerCondition.UNCONSCIOUS_CUSTODY
                || !expired(state.unconsciousCustodyDeadlineAt, transition.at())) {
            return reject("UNCONSCIOUS_CUSTODY_DEADLINE_ACTIVE");
        }
        state.condition = state.restraint == RestraintStatus.NONE
                ? PlayerCondition.ALIVE : PlayerCondition.CONSCIOUS_RESTRAINED;
        state.unconsciousCustodyDeadlineAt = null;
        state.pausedDownedRemainingMs = 0;
        state.resuscitatorId = "";
        return CustodyTransitionResult.applied();
    }

    /**
     * Resolves the finite pre-jail obligation without killing the target. The
     * physical restraint remains in place; only the unconscious control loss
     * and transport deadline are resolved.
     */
    private static CustodyTransitionResult resolveJailDeliveryDeadline(
            CustodyState state, CustodyTransition transition) {
        if (state.custody != CustodyStatus.ARRESTED
                || !expired(state.jailDeliveryDeadlineAt, transition.at())) {
            return reject("JAIL_DELIVERY_DEADLINE_ACTIVE");
        }
        // Missing the delivery deadline is a failed custody resolution. It
        // releases the temporary arrest completely; physical restraint is
        // intentionally retained only for the separate unconscious-custody
        // timeout path.
        state.condition = PlayerCondition.ALIVE;
        state.jailDeliveryDeadlineAt = null;
        state.transport = TransportStatus.NONE;
        state.carrierId = "";
        state.transportDeadlineAt = null;
        state.unconsciousCustodyDeadlineAt = null;
        state.pausedDownedRemainingMs = 0;
        clearAll(state);
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult deliverToJail(CustodyState state,
                                                          CustodyTransition transition,
                                                          StrajaPolicies policies) {
        if (state.custody != CustodyStatus.ARRESTED || blank(transition.destination())) {
            return reject("JAIL_DELIVERY_REQUIRES_ARREST_AND_DESTINATION");
        }
        state.custody = CustodyStatus.JAILED;
        state.destination = transition.destination();
        state.transport = TransportStatus.NONE;
        state.carrierId = "";
        state.transportDeadlineAt = null;
        state.pausedDownedRemainingMs = 0;
        state.jailDeliveryDeadlineAt = null;
        state.resuscitatorId = "";
        if (state.condition == PlayerCondition.UNCONSCIOUS_CUSTODY) {
            state.unconsciousCustodyDeadlineAt = null;
            state.jailRevivalAt = policies.jailAutomaticRevivalEnabled
                    ? deadline(transition.at(), policies.jailAutomaticRevivalDelaySeconds) : null;
        } else {
            state.jailRevivalAt = null;
        }
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult reviveInJail(CustodyState state,
                                                         CustodyTransition transition,
                                                         StrajaPolicies policies) {
        if (state.custody != CustodyStatus.JAILED
                || state.condition != PlayerCondition.UNCONSCIOUS_CUSTODY) {
            return reject("JAIL_REVIVAL_REQUIRES_UNCONSCIOUS_PRISONER");
        }
        if (policies.jailAutomaticRevivalEnabled && !expired(state.jailRevivalAt, transition.at())) {
            return reject("JAIL_REVIVAL_NOT_DUE");
        }
        state.condition = state.restraint == RestraintStatus.NONE
                ? PlayerCondition.ALIVE : PlayerCondition.CONSCIOUS_RESTRAINED;
        state.unconsciousCustodyDeadlineAt = null;
        state.jailRevivalAt = null;
        state.pausedDownedRemainingMs = 0;
        state.resuscitatorId = "";
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult releaseRestraint(CustodyState state,
                                                             CustodyTransition transition) {
        if (state.restraint == RestraintStatus.NONE) return reject("NOT_RESTRAINED");
        if (blank(transition.actorId()) || samePlayer(state, transition.actorId())) {
            return reject("SELF_RESTRAINT_REMOVAL_FORBIDDEN");
        }
        if (state.custody == CustodyStatus.JAILED) return reject("JAILED_RESTRAINT_REQUIRES_RELEASE");
        state.restraint = RestraintStatus.NONE;
        state.restraintActorId = "";
        state.vision = VisionStatus.NORMAL;
        if (state.condition == PlayerCondition.UNCONSCIOUS_CUSTODY) {
            state.condition = PlayerCondition.ALIVE;
            state.custody = CustodyStatus.FREE;
            state.unconsciousCustodyDeadlineAt = null;
            state.pausedDownedRemainingMs = 0;
        } else {
            state.condition = PlayerCondition.ALIVE;
            state.custody = CustodyStatus.FREE;
        }
        state.custodyActorId = "";
        state.jailDeliveryDeadlineAt = null;
        state.resuscitatorId = "";
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult releaseCustody(CustodyState state,
                                                           CustodyTransition transition,
                                                           StrajaPolicies policies) {
        if (state.custody == CustodyStatus.FREE || state.custody == CustodyStatus.JAILED) {
            return reject("CUSTODY_RELEASE_NOT_AVAILABLE");
        }
        if (blank(transition.actorId()) || samePlayer(state, transition.actorId())) {
            return reject("SELF_CUSTODY_RELEASE_FORBIDDEN");
        }
        if (state.restraint != RestraintStatus.NONE) return reject("RESTRAINT_MUST_BE_RELEASED_FIRST");
        state.custody = CustodyStatus.FREE;
        state.custodyActorId = "";
        if (state.condition == PlayerCondition.UNCONSCIOUS_CUSTODY) {
            state.condition = PlayerCondition.DOWNED;
            state.unconsciousCustodyDeadlineAt = null;
            if (state.transport == TransportStatus.CARRIED) {
                state.downedDeadlineAt = null;
                state.pausedDownedRemainingMs = Math.max(1, policies.downedDurationSeconds) * 1000L;
            } else {
                state.downedDeadlineAt = deadline(transition.at(), policies.downedDurationSeconds);
                state.pausedDownedRemainingMs = 0;
            }
        }
        state.resuscitatorId = "";
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult recoverClearAll(CustodyState state) {
        if (state.condition != PlayerCondition.DEAD) state.condition = PlayerCondition.ALIVE;
        clearDeadlines(state);
        clearAll(state);
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult recoverReleaseRestraints(CustodyState state) {
        if (state.condition != PlayerCondition.DEAD) state.condition = PlayerCondition.ALIVE;
        clearDeadlines(state);
        state.restraint = RestraintStatus.NONE;
        state.custody = CustodyStatus.FREE;
        state.vision = VisionStatus.NORMAL;
        state.carrierId = "";
        state.restraintActorId = "";
        state.custodyActorId = "";
        state.transport = TransportStatus.NONE;
        state.transportDeadlineAt = null;
        state.destination = "";
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult recoverWake(CustodyState state) {
        if (state.condition == PlayerCondition.DEAD) return CustodyTransitionResult.applied();
        Long jailDeliveryDeadline = state.jailDeliveryDeadlineAt;
        state.condition = state.restraint == RestraintStatus.NONE
                ? PlayerCondition.ALIVE : PlayerCondition.CONSCIOUS_RESTRAINED;
        clearDeadlines(state);
        if (state.custody == CustodyStatus.ARRESTED) {
            state.jailDeliveryDeadlineAt = jailDeliveryDeadline;
        }
        state.transport = TransportStatus.NONE;
        state.carrierId = "";
        state.destination = "";
        state.resuscitatorId = "";
        return CustodyTransitionResult.applied();
    }

    private static CustodyTransitionResult die(CustodyState state,
                                                CustodyTransition transition,
                                                StrajaPolicies policies) {
        if (state.condition == PlayerCondition.DEAD) return reject("ALREADY_DEAD");
        state.condition = PlayerCondition.DEAD;
        state.downedDeadlineAt = null;
        state.resuscitationDeadlineAt = null;
        state.unconsciousCustodyDeadlineAt = null;
        state.transportDeadlineAt = null;
        state.jailDeliveryDeadlineAt = null;
        state.jailRevivalAt = null;
        state.transport = TransportStatus.NONE;
        state.carrierId = "";
        state.pausedDownedRemainingMs = 0;
        state.resuscitationProgress = 0;
        state.resuscitatorId = "";
        switch (policies.recoveryBehavior(RecoveryEvent.DEATH)) {
            case CLEAR_ALL, WAKE -> clearAll(state);
            case RELEASE_RESTRAINTS -> {
                state.restraint = RestraintStatus.NONE;
                state.vision = VisionStatus.NORMAL;
                state.custody = CustodyStatus.FREE;
                state.custodyActorId = "";
                state.restraintActorId = "";
            }
            case RELEASE_TRANSPORT -> {
                state.transport = TransportStatus.NONE;
                state.carrierId = "";
            }
            case RETAIN -> { /* death is itself an explicit resolution */ }
        }
        return CustodyTransitionResult.applied();
    }

    /**
     * Read-only eligibility check for the application confirmation surface.
     * It performs the same guards as {@code GIVE_UP} without mutating state.
     */
    public static CustodyTransitionResult giveUpEligibility(CustodyState state,
                                                              String actorId) {
        if (state == null) return CustodyTransitionResult.rejected("INVALID_INPUT");
        if (!state.wellFormed()) return CustodyTransitionResult.rejected("MALFORMED_STATE");
        return giveUpGuards(state, actorId);
    }

    /**
     * Ends only an ordinary Straja-owned downed state. This is deliberately a
     * distinct transition from {@link CustodyTransition.Action#DIE}: give-up
     * is a player-authored action and must never become a way around transport,
     * rescue, restraint, custody, jail, or another provider's ownership.
     */
    private static CustodyTransitionResult giveUp(CustodyState state,
                                                    CustodyTransition transition,
                                                    StrajaPolicies policies) {
        CustodyTransitionResult eligibility = giveUpGuards(state, transition.actorId());
        if (!eligibility.ok()) return eligibility;

        // Reuse the configured death lifecycle policy. The action itself does
        // not cause damage or invoke a server; callers perform that boundary
        // work after this pure transition succeeds.
        return die(state, transition, policies);
    }

    private static CustodyTransitionResult giveUpGuards(CustodyState state, String actorId) {
        if (state.condition == PlayerCondition.DEAD) return reject("ALREADY_DEAD");
        if (state.provider == StateProvider.VAMPIRISM) {
            return reject("EXTERNAL_PROVIDER_OWNS_STATE");
        }
        if (state.provider == StateProvider.UNKNOWN) {
            return reject("STATE_PROVIDER_UNKNOWN");
        }
        if (state.condition != PlayerCondition.DOWNED
                || state.custody != CustodyStatus.FREE
                || state.transport != TransportStatus.NONE
                || state.restraint != RestraintStatus.NONE) {
            return reject("GIVE_UP_REQUIRES_ORDINARY_DOWNED");
        }
        if (blank(actorId) || !samePlayer(state, actorId)) {
            return reject("GIVE_UP_ACTOR_INVALID");
        }
        return CustodyTransitionResult.applied();
    }

    private static void clearAll(CustodyState state) {
        state.custody = CustodyStatus.FREE;
        state.transport = TransportStatus.NONE;
        state.restraint = RestraintStatus.NONE;
        state.vision = VisionStatus.NORMAL;
        state.carrierId = "";
        state.restraintActorId = "";
        state.custodyActorId = "";
        state.destination = "";
        state.pausedDownedRemainingMs = 0;
        state.resuscitatorId = "";
    }

    private static void clearDeadlines(CustodyState state) {
        state.downedDeadlineAt = null;
        state.resuscitationDeadlineAt = null;
        state.unconsciousCustodyDeadlineAt = null;
        state.transportDeadlineAt = null;
        state.jailDeliveryDeadlineAt = null;
        state.jailRevivalAt = null;
        state.resuscitationProgress = 0;
        state.pausedDownedRemainingMs = 0;
        state.resuscitatorId = "";
    }

    private static boolean samePlayer(CustodyState state, String actorId) {
        return !blank(actorId) && (actorId.equals(state.playerId) || actorId.equals(state.playerUuid));
    }

    private static boolean expired(Long deadline, long now) {
        return deadline != null && now >= deadline;
    }

    private static long deadline(long at, int seconds) {
        long duration = Math.max(1, seconds) * 1000L;
        return Long.MAX_VALUE - at < duration ? Long.MAX_VALUE : at + duration;
    }

    private static long deadlineFromRemaining(long at, long remainingMs) {
        if (remainingMs <= 0) return 0;
        if (Long.MAX_VALUE - at < remainingMs) return Long.MAX_VALUE;
        return at + remainingMs;
    }

    private static long remaining(Long deadline, long at) {
        if (deadline == null || deadline <= at) return 0;
        return deadline - at;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static CustodyTransitionResult reject(String code) {
        return CustodyTransitionResult.rejected(code);
    }
}
