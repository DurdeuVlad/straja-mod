package com.dwurdy.straja.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CustodyTransitionEngineTest {

    @Test
    void ropeAndCuffsUseDifferentCustodyDimensions() {
        var hostage = state("hostage");
        var rope = CustodyTransition.of("rope-1", CustodyTransition.Action.APPLY_ROPE, 1_000, "criminal");
        assertTrue(CustodyTransitionEngine.apply(hostage, rope, policies()).ok());
        assertEquals(PlayerCondition.CONSCIOUS_RESTRAINED, hostage.condition);
        assertEquals(CustodyStatus.HOSTAGE, hostage.custody);
        assertEquals(RestraintStatus.ROPE_BOUND, hostage.restraint);

        var arrested = state("arrested");
        var cuffs = CustodyTransition.of("cuffs-1", CustodyTransition.Action.APPLY_CUFFS, 1_000, "guard");
        assertTrue(CustodyTransitionEngine.apply(arrested, cuffs, policies()).ok());
        assertEquals(PlayerCondition.CONSCIOUS_RESTRAINED, arrested.condition);
        assertEquals(CustodyStatus.ARRESTED, arrested.custody);
        assertEquals(RestraintStatus.CUFFED, arrested.restraint);
    }

    @Test
    void defeatedTargetEntersUnconsciousCustodyOnlyWhenRestrained() {
        var state = state("target");
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED, 1_000, "weapon"),
                policies()).ok());

        var result = CustodyTransitionEngine.apply(state,
                CustodyTransition.of("cuffs-1", CustodyTransition.Action.APPLY_CUFFS, 2_000, "guard"),
                policies());
        assertTrue(result.ok());
        assertEquals(PlayerCondition.UNCONSCIOUS_CUSTODY, state.condition);
        assertEquals(CustodyStatus.ARRESTED, state.custody);
        assertNotNull(state.unconsciousCustodyDeadlineAt);
        assertNull(state.downedDeadlineAt);
    }

    @Test
    void carryingPreservesConditionCustodyAndRestraint() {
        var state = state("target");
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("rope-1", CustodyTransition.Action.APPLY_ROPE, 1_000, "criminal"),
                policies()).ok());
        var carry = new CustodyTransition("carry-1", CustodyTransition.Action.START_CARRY, 2_000,
                "carrier", "carrier", "", StateProvider.NATIVE, "grab", 0);
        assertTrue(CustodyTransitionEngine.apply(state, carry, policies()).ok());
        assertEquals(PlayerCondition.CONSCIOUS_RESTRAINED, state.condition);
        assertEquals(CustodyStatus.HOSTAGE, state.custody);
        assertEquals(RestraintStatus.ROPE_BOUND, state.restraint);
        assertEquals(TransportStatus.CARRIED, state.transport);
        assertEquals("carrier", state.carrierId);
    }

    @Test
    void resuscitationBindsARescuerAndCanBeInterruptedOrCompleted() {
        var state = state("target");
        var policies = policies();
        policies.downedDurationSeconds = 10;
        policies.resuscitationTimeoutSeconds = 5;
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED, 1_000, "weapon"),
                policies).ok());
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("resus-1", CustodyTransition.Action.START_RESUSCITATION,
                        2_000, "medic"), policies).ok());
        assertEquals("medic", state.resuscitatorId);
        assertEquals(PlayerCondition.RESUSCITATING, state.condition);

        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("interrupt-1", CustodyTransition.Action.INTERRUPT_RESUSCITATION,
                        3_000, "system"), policies).ok());
        assertEquals(PlayerCondition.DOWNED, state.condition);
        assertEquals("", state.resuscitatorId);
        assertEquals(12_000L, state.downedDeadlineAt);
    }

    @Test
    void resuscitationRejectsCarryAndSelfRescuer() {
        var state = state("target");
        var policies = policies();
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED, 1_000, "weapon"),
                policies).ok());
        assertTrue(CustodyTransitionEngine.apply(state,
                new CustodyTransition("carry-1", CustodyTransition.Action.START_CARRY, 1_500,
                        "carrier", "carrier", "", StateProvider.NATIVE, "grab", 0), policies).ok());
        assertEquals("RESUSCITATION_REQUIRES_DOWNED_FREE", CustodyTransitionEngine.apply(state,
                CustodyTransition.of("resus-1", CustodyTransition.Action.START_RESUSCITATION,
                        2_000, "medic"), policies).code());
    }

    @Test
    void invalidTransitionsFailClosed() {
        var state = state("target");
        assertEquals("RESTRAINT_ACTOR_INVALID", CustodyTransitionEngine.apply(state,
                CustodyTransition.of("rope-self", CustodyTransition.Action.APPLY_ROPE, 1_000, "target"),
                policies()).code());
        assertEquals("CARRIER_INVALID", CustodyTransitionEngine.apply(state,
                CustodyTransition.of("carry-missing", CustodyTransition.Action.START_CARRY, 1_000, ""),
                policies()).code());
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED, 1_000, "weapon"),
                policies()).ok());
        assertEquals("DOWNED_DEADLINE_ACTIVE", CustodyTransitionEngine.apply(state,
                CustodyTransition.of("wake-early", CustodyTransition.Action.WAKE, 1_000, "guard"),
                policies()).code());

        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("cuffs-1", CustodyTransition.Action.APPLY_CUFFS, 2_000, "guard"),
                policies()).ok());
        assertEquals("SELF_RESTRAINT_REMOVAL_FORBIDDEN", CustodyTransitionEngine.apply(state,
                CustodyTransition.of("release-self", CustodyTransition.Action.RELEASE_RESTRAINT, 3_000, "target"),
                policies()).code());
    }

    @Test
    void malformedPersistedDimensionCombinationIsRejected() {
        var state = state("target");
        state.restraint = RestraintStatus.ROPE_BOUND;
        assertFalse(state.wellFormed());
        assertEquals("MALFORMED_STATE", CustodyTransitionEngine.apply(state,
                CustodyTransition.of("any", CustodyTransition.Action.START_CARRY, 1_000, "carrier"),
                policies()).code());
    }

    @Test
    void unstablePersistedStatesRequireTheirOwnDeadline() {
        var state = state("target");
        state.condition = PlayerCondition.DOWNED;
        assertTrue(state.violations().contains("downed_deadline_missing"));

        state.condition = PlayerCondition.RESUSCITATING;
        assertTrue(state.violations().contains("resuscitation_deadline_missing"));

        state.condition = PlayerCondition.UNCONSCIOUS_CUSTODY;
        state.custody = CustodyStatus.ARRESTED;
        state.restraint = RestraintStatus.CUFFED;
        assertTrue(state.violations().contains("unconscious_custody_deadline_missing"));

        state.condition = PlayerCondition.DOWNED;
        state.downedDeadlineAt = 2_000L;
        state.transport = TransportStatus.CARRIED;
        state.carrierId = "carrier";
        assertTrue(state.violations().contains("transport_deadline_missing"));
    }

    @Test
    void transitionIdentityMakesRetriesIdempotent() {
        var state = state("target");
        var transition = CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED, 1_000, "weapon");
        assertTrue(CustodyTransitionEngine.apply(state, transition, policies()).ok());
        assertEquals("down-1", state.transitionId);
        var replay = CustodyTransitionEngine.apply(state, transition, policies());
        assertTrue(replay.ok());
        assertTrue(replay.idempotent());
        assertEquals("IDEMPOTENT_REPLAY", replay.code());
    }

    @Test
    void unconsciousDeadlineRestoresWalkingButKeepsPhysicalRestraint() {
        var state = state("target");
        var p = policies();
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED, 1_000, "weapon"), p).ok());
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("rope-1", CustodyTransition.Action.APPLY_ROPE, 2_000, "criminal"), p).ok());
        long deadline = state.unconsciousCustodyDeadlineAt;
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("wake-1", CustodyTransition.Action.WAKE, deadline, "guard"), p).ok());
        assertEquals(PlayerCondition.CONSCIOUS_RESTRAINED, state.condition);
        assertEquals(CustodyStatus.HOSTAGE, state.custody);
        assertEquals(RestraintStatus.ROPE_BOUND, state.restraint);
        assertEquals(TransportStatus.NONE, state.transport);
    }

    @Test
    void policyOverridesChangeDeadlinesAndPermissions() {
        var p = policies();
        p.downedDurationSeconds = 9;
        p.carryTransportDeadlineSeconds = 17;
        p.criminalRopeEnabled = false;
        var state = state("target");
        assertEquals("CRIMINAL_ROPE_DISABLED", CustodyTransitionEngine.apply(state,
                CustodyTransition.of("rope-1", CustodyTransition.Action.APPLY_ROPE, 1_000, "criminal"), p).code());
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED, 1_000, "weapon"), p).ok());
        assertEquals(10_000L, state.downedDeadlineAt);
    }

    @Test
    void policyRegistryExposesTheNewConfigurationBoundary() {
        var p = new StrajaPolicies();
        assertTrue(PolicyRegistry.apply(p, "downed.durationSeconds", "17").ok());
        assertEquals(17, p.downedDurationSeconds);
        assertEquals(17, p.downedCooldownSeconds,
                "the legacy RP-007 alias must stay in sync with the canonical timer");
        assertTrue(PolicyRegistry.apply(p, "downed.cooldownSeconds", "19").ok());
        assertEquals(19, p.downedDurationSeconds);
        assertTrue(PolicyRegistry.apply(p, "custody.carryTransportDeadlineSeconds", "17").ok());
        assertEquals(17, p.carryTransportDeadlineSeconds);
        assertTrue(PolicyRegistry.apply(p, "custody.secondWeaponHitBehavior", "cancel").ok());
        assertEquals("CANCEL", p.secondWeaponHitBehavior);
        assertFalse(PolicyRegistry.apply(p, "custody.secondWeaponHitBehavior", "not-a-rule").ok());
        assertTrue(PolicyRegistry.apply(p, "recovery.death", "RETAIN").ok());
        assertEquals("RETAIN", p.deathRecoveryBehavior);
    }

    private static CustodyState state(String id) {
        var state = new CustodyState();
        state.playerId = id;
        state.playerUuid = id;
        state.playerName = id;
        return state;
    }

    private static StrajaPolicies policies() {
        var policies = new StrajaPolicies();
        policies.downedDurationSeconds = 60;
        policies.unconsciousCustodyDurationSeconds = 60;
        return policies;
    }
}
