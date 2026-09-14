package com.dwurdy.straja.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Boundary and persistence tests for the canonical server-time evaluator. */
class CustodyDeadlineEngineTest {

    @Test
    void downedExpiryUsesExactBoundaryAndIsIdempotent() {
        var state = state("downed");
        var policies = policies();
        policies.downedDurationSeconds = 1;
        apply(state, CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED, 0, "weapon"), policies);

        assertEquals(PlayerCondition.DOWNED, state.condition);
        assertFalse(CustodyDeadlineEngine.tick(state, 0, policies).changed());
        assertFalse(CustodyDeadlineEngine.tick(state, 999, policies).changed());

        var expired = CustodyDeadlineEngine.tick(state, 1_000, policies);
        assertTrue(expired.changed());
        assertEquals(CustodyDeadlineEngine.DeadlineType.DOWNED_DEATH, expired.type());
        assertEquals(PlayerCondition.DEAD, state.condition);

        // A late/repeated callback cannot kill or otherwise mutate a resolved state.
        var late = CustodyDeadlineEngine.tick(state, 1_001, policies);
        assertFalse(late.changed());
        assertEquals(PlayerCondition.DEAD, state.condition);
    }

    @Test
    void carryPausesDownedTimeButTransportStillExpires() {
        var state = state("carried");
        var policies = policies();
        policies.downedDurationSeconds = 1;
        policies.carryTransportDeadlineSeconds = 5;
        apply(state, CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED, 0, "weapon"), policies);
        apply(state, new CustodyTransition("carry-1", CustodyTransition.Action.START_CARRY, 400,
                "carrier", "carrier", "", StateProvider.NATIVE, "grab", 0), policies);

        assertNull(state.downedDeadlineAt);
        assertEquals(600, state.pausedDownedRemainingMs);
        assertEquals(5_400, state.transportDeadlineAt);
        assertFalse(CustodyDeadlineEngine.tick(state, 1_000, policies).changed(),
                "the paused downed deadline must not expire while carried");

        var dropped = CustodyDeadlineEngine.tick(state, 5_400, policies);
        assertTrue(dropped.changed());
        assertEquals(CustodyDeadlineEngine.DeadlineType.TRANSPORT, dropped.type());
        assertEquals(TransportStatus.NONE, state.transport);
        assertEquals(6_000, state.downedDeadlineAt,
                "transport expiry resumes the stored downed duration from its deadline");
        assertFalse(CustodyDeadlineEngine.tick(state, 5_999, policies).changed());
        assertTrue(CustodyDeadlineEngine.tick(state, 6_000, policies).changed());
        assertEquals(PlayerCondition.DEAD, state.condition);
    }

    @Test
    void resuscitationPausesAndTimeoutResumesDownedTime() {
        var state = state("resuscitating");
        var policies = policies();
        policies.downedDurationSeconds = 10;
        policies.resuscitationTimeoutSeconds = 3;
        apply(state, CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED, 0, "weapon"), policies);
        apply(state, CustodyTransition.of("resus-1", CustodyTransition.Action.START_RESUSCITATION,
                4_000, "medic"), policies);

        assertEquals(PlayerCondition.RESUSCITATING, state.condition);
        assertNull(state.downedDeadlineAt);
        assertEquals(6_000, state.pausedDownedRemainingMs);
        assertEquals(7_000, state.resuscitationDeadlineAt);
        assertFalse(CustodyDeadlineEngine.tick(state, 6_999, policies).changed());

        var timeout = CustodyDeadlineEngine.tick(state, 7_000, policies);
        assertTrue(timeout.changed());
        assertEquals(CustodyDeadlineEngine.DeadlineType.RESUSCITATION_TIMEOUT, timeout.type());
        assertEquals(PlayerCondition.DOWNED, state.condition);
        assertEquals(13_000, state.downedDeadlineAt);
        assertEquals(0, state.pausedDownedRemainingMs);
    }

    @Test
    void resuscitationProgressCanResolveBeforeTimeout() {
        var state = state("rescued");
        var policies = policies();
        policies.downedDurationSeconds = 10;
        policies.resuscitationTimeoutSeconds = 3;
        apply(state, CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED, 0, "weapon"), policies);
        apply(state, CustodyTransition.of("resus-1", CustodyTransition.Action.START_RESUSCITATION,
                4_000, "medic"), policies);
        apply(state, new CustodyTransition("progress-1", CustodyTransition.Action.ADVANCE_RESUSCITATION,
                6_999, "medic", "", "", StateProvider.NATIVE, "rescue", 100), policies);

        assertEquals(PlayerCondition.ALIVE, state.condition);
        assertNull(state.downedDeadlineAt);
        assertNull(state.resuscitationDeadlineAt);
        assertFalse(CustodyDeadlineEngine.tick(state, 7_000, policies).changed());
    }

    @Test
    void unconsciousCustodyExpiryWakesWithoutRemovingPhysicalRestraint() {
        var state = state("hostage");
        var policies = policies();
        policies.downedDurationSeconds = 10;
        policies.unconsciousCustodyDurationSeconds = 2;
        apply(state, CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED, 0, "weapon"), policies);
        apply(state, CustodyTransition.of("rope-1", CustodyTransition.Action.APPLY_ROPE,
                1_000, "captor"), policies);

        assertEquals(3_000, state.unconsciousCustodyDeadlineAt);
        var result = CustodyDeadlineEngine.tick(state, 3_000, policies);

        assertTrue(result.changed());
        assertEquals(PlayerCondition.CONSCIOUS_RESTRAINED, state.condition);
        assertEquals(CustodyStatus.HOSTAGE, state.custody);
        assertEquals(RestraintStatus.ROPE_BOUND, state.restraint);
        assertNull(state.unconsciousCustodyDeadlineAt);
    }

    @Test
    void custodyExpiryWhileCarriedKeepsFiniteTransportDeadline() {
        var state = state("carried-hostage");
        var policies = policies();
        policies.unconsciousCustodyDurationSeconds = 2;
        policies.carryTransportDeadlineSeconds = 10;
        apply(state, CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED, 0, "weapon"), policies);
        apply(state, CustodyTransition.of("rope-1", CustodyTransition.Action.APPLY_ROPE,
                1_000, "captor"), policies);
        apply(state, new CustodyTransition("carry-1", CustodyTransition.Action.START_CARRY, 1_500,
                "carrier", "carrier", "", StateProvider.NATIVE, "grab", 0), policies);

        assertEquals(11_500, state.transportDeadlineAt);
        assertTrue(CustodyDeadlineEngine.tick(state, 3_000, policies).changed());
        assertEquals(PlayerCondition.CONSCIOUS_RESTRAINED, state.condition);
        assertEquals(TransportStatus.CARRIED, state.transport,
                "custody wake does not silently cancel the separate transport deadline");
        assertEquals(11_500, state.transportDeadlineAt);
        assertEquals(RestraintStatus.ROPE_BOUND, state.restraint);
    }

    @Test
    void jailDeliveryAndRevivalDeadlinesAreEvaluated() {
        var state = state("prisoner");
        var policies = policies();
        policies.unconsciousCustodyDurationSeconds = 20;
        policies.jailAutomaticRevivalDelaySeconds = 1;
        apply(state, CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED, 0, "weapon"), policies);
        apply(state, CustodyTransition.of("cuffs-1", CustodyTransition.Action.APPLY_CUFFS,
                1_000, "guard"), policies);
        apply(state, new CustodyTransition("jail-1", CustodyTransition.Action.DELIVER_TO_JAIL,
                2_000, "guard", "", "jail", StateProvider.NATIVE, "delivery", 0), policies);

        assertEquals(3_000, state.jailRevivalAt);
        assertFalse(CustodyDeadlineEngine.tick(state, 2_999, policies).changed());
        assertTrue(CustodyDeadlineEngine.tick(state, 3_000, policies).changed());
        assertEquals(PlayerCondition.CONSCIOUS_RESTRAINED, state.condition);
        assertEquals(CustodyStatus.JAILED, state.custody);
        assertEquals(RestraintStatus.CUFFED, state.restraint);
        assertNull(state.jailRevivalAt);
    }

    @Test
    void missedJailDeliveryDeadlineFailsClosedToReleasedAliveState() {
        var state = state("late-prisoner");
        var policies = policies();
        policies.jailDeliveryDeadlineSeconds = 1;
        apply(state, CustodyTransition.of("cuffs-1", CustodyTransition.Action.APPLY_CUFFS,
                0, "guard"), policies);

        assertTrue(CustodyDeadlineEngine.tick(state, 1_000, policies).changed());
        assertEquals(PlayerCondition.ALIVE, state.condition);
        assertEquals(CustodyStatus.FREE, state.custody);
        assertEquals(RestraintStatus.NONE, state.restraint);
        assertFalse(CustodyDeadlineEngine.tick(state, 2_000, policies).changed());
    }

    @Test
    void persistedAbsoluteDeadlineSurvivesRestartAndResolvesOnce() {
        var original = new CustodyStore();
        var state = state("restart");
        var policies = policies();
        policies.downedDurationSeconds = 1;
        apply(state, CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED,
                10_000, "weapon"), policies);
        original.states.put(state.playerId, state);

        Gson gson = new GsonBuilder().serializeNulls().create();
        var restarted = gson.fromJson(gson.toJson(original), CustodyStore.class);
        var loaded = restarted.states.get("restart");
        assertEquals(11_000, loaded.downedDeadlineAt);
        assertFalse(CustodyDeadlineEngine.tick(loaded, 10_999, policies).changed());
        assertTrue(CustodyDeadlineEngine.tick(loaded, 11_000, policies).changed());
        assertEquals(PlayerCondition.DEAD, loaded.condition);
        assertFalse(CustodyDeadlineEngine.tick(loaded, 11_001, policies).changed());
    }

    @Test
    void malformedStateFailsClosedWithoutMutation() {
        var state = state("malformed");
        state.condition = PlayerCondition.DOWNED;
        String before = new Gson().toJson(state);

        var result = CustodyDeadlineEngine.tick(state, 5_000, policies());

        assertFalse(result.changed());
        assertEquals("MALFORMED_STATE", result.code());
        assertEquals(before, new Gson().toJson(state));
    }

    @Test
    void configuredRecoveryResolvesOnlyTheRequestedLifecyclePolicy() {
        var state = state("recovery");
        var policies = policies();
        apply(state, CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED,
                0, "weapon"), policies);
        policies.logoutRecoveryBehavior = "WAKE";

        var result = CustodyDeadlineEngine.recover(state, RecoveryEvent.LOGOUT, 250,
                policies);

        assertTrue(result.changed());
        assertEquals(PlayerCondition.ALIVE, state.condition);
        assertEquals(CustodyStatus.FREE, state.custody);
        assertNull(state.downedDeadlineAt);
        assertFalse(CustodyDeadlineEngine.tick(state, 1_000, policies).changed());
    }

    @Test
    void nonRetainRecoveryIsIdempotentAcrossRepeatedCalls() {
        for (String behavior : List.of("WAKE", "CLEAR_ALL", "RELEASE_RESTRAINTS")) {
            var state = state("recovery-" + behavior);
            var policies = policies();
            policies.logoutRecoveryBehavior = behavior;
            apply(state, CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED,
                    0, "weapon"), policies);

            var first = CustodyDeadlineEngine.recover(state, RecoveryEvent.LOGOUT, 250, policies);
            var transitionId = state.transitionId;
            var enteredAt = state.enteredAt;
            var second = CustodyDeadlineEngine.recover(state, RecoveryEvent.LOGOUT, 500, policies);

            assertTrue(first.changed(), behavior);
            assertFalse(second.changed(), behavior);
            assertTrue(second.idempotent(), behavior);
            assertEquals(transitionId, state.transitionId, behavior);
            assertEquals(enteredAt, state.enteredAt, behavior);
        }
    }

    @Test
    void deadStateWithStaleArrestDeadlineFailsClosed() {
        var state = state("dead-malformed");
        state.condition = PlayerCondition.DEAD;
        state.custody = CustodyStatus.ARRESTED;
        state.restraint = RestraintStatus.CUFFED;
        state.jailDeliveryDeadlineAt = 1_000L;
        String before = new Gson().toJson(state);

        var result = CustodyDeadlineEngine.tick(state, 2_000, policies());

        assertFalse(result.changed());
        assertEquals("MALFORMED_STATE", result.code());
        assertEquals(before, new Gson().toJson(state));
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
        policies.downedDurationSeconds = 10;
        policies.carryTransportDeadlineSeconds = 10;
        policies.resuscitationTimeoutSeconds = 5;
        policies.unconsciousCustodyDurationSeconds = 10;
        policies.jailDeliveryDeadlineSeconds = 20;
        policies.jailAutomaticRevivalDelaySeconds = 5;
        return policies;
    }

    private static void apply(CustodyState state, CustodyTransition transition, StrajaPolicies policies) {
        var result = CustodyTransitionEngine.apply(state, transition, policies);
        assertTrue(result.ok(), () -> "transition failed: " + result.code());
    }
}
