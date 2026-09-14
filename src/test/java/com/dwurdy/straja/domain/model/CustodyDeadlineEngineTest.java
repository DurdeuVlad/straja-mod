package com.dwurdy.straja.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CustodyDeadlineEngineTest {

    @Test
    void downedDeathUsesZeroBeforeExactAndOneAfterBoundaries() {
        var state = state("target");
        var policies = policies();
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("down", CustodyTransition.Action.ENTER_DOWNED, 1_000, "weapon"),
                policies).ok());
        long deadline = state.downedDeadlineAt;

        assertTrue(CustodyDeadlineEngine.resolveDue(state, deadline - 1, policies).isEmpty());
        assertEquals(PlayerCondition.DOWNED, state.condition);
        assertEquals(1, CustodyDeadlineEngine.resolveDue(state, deadline, policies).size());
        assertEquals(PlayerCondition.DEAD, state.condition);
        assertTrue(CustodyDeadlineEngine.resolveDue(state, deadline + 1, policies).isEmpty());
    }

    @Test
    void carryingPausesAndDroppingResumesTheStoredDownedTime() {
        var policies = policies();
        policies.carryTransportDeadlineSeconds = 60;
        var state = downed("target", 1_000, policies);
        long downedDeadline = state.downedDeadlineAt;
        var carry = new CustodyTransition("carry", CustodyTransition.Action.START_CARRY,
                2_000, "carrier", "carrier", "", StateProvider.SYSTEM, "carry", 0);
        assertTrue(CustodyTransitionEngine.apply(state, carry, policies).ok());
        assertNull(state.downedDeadlineAt);
        assertEquals(downedDeadline - 2_000, state.pausedDownedRemainingMs);

        assertTrue(CustodyDeadlineEngine.resolveDue(state, downedDeadline + 10_000,
                policies).isEmpty(), "the paused timer cannot expire while carried");
        var drop = CustodyTransition.of("drop", CustodyTransition.Action.STOP_CARRY,
                downedDeadline + 10_000, "carrier");
        assertTrue(CustodyTransitionEngine.apply(state, drop, policies).ok());
        assertEquals(downedDeadline + 10_000 + (downedDeadline - 2_000),
                state.downedDeadlineAt);
    }

    @Test
    void transportDeadlineDropsCarryAndResumesTimerExactlyOnce() {
        var policies = policies();
        policies.carryTransportDeadlineSeconds = 5;
        var state = downed("target", 1_000, policies);
        assertTrue(CustodyTransitionEngine.apply(state,
                new CustodyTransition("carry", CustodyTransition.Action.START_CARRY, 2_000,
                        "carrier", "carrier", "", StateProvider.SYSTEM, "carry", 0), policies).ok());
        long transportDeadline = state.transportDeadlineAt;

        assertTrue(CustodyDeadlineEngine.resolveDue(state, transportDeadline - 1, policies).isEmpty());
        List<CustodyDeadlineEngine.Resolution> resolved =
                CustodyDeadlineEngine.resolveDue(state, transportDeadline, policies);
        assertEquals(1, resolved.size());
        assertEquals(TransportStatus.NONE, state.transport);
        assertNotNull(state.downedDeadlineAt);
        assertTrue(CustodyDeadlineEngine.resolveDue(state, transportDeadline + 1, policies).isEmpty());
    }

    @Test
    void resuscitationPausesDownedTimerAndTimeoutReturnsToDowned() {
        var policies = policies();
        policies.resuscitationTimeoutSeconds = 5;
        var state = downed("target", 1_000, policies);
        long originalDeadline = state.downedDeadlineAt;
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("resus", CustodyTransition.Action.START_RESUSCITATION,
                        2_000, "medic"), policies).ok());
        long resuscitationDeadline = state.resuscitationDeadlineAt;
        assertEquals(originalDeadline - 2_000, state.pausedDownedRemainingMs);
        assertTrue(CustodyDeadlineEngine.resolveDue(state, resuscitationDeadline - 1, policies).isEmpty());
        assertEquals(1, CustodyDeadlineEngine.resolveDue(state, resuscitationDeadline, policies).size());
        assertEquals(PlayerCondition.DOWNED, state.condition);
        assertEquals(resuscitationDeadline + (originalDeadline - 2_000), state.downedDeadlineAt);
    }

    @Test
    void unconsciousCustodyTimeoutRestoresWalkingWithoutRemovingRestraint() {
        var policies = policies();
        var state = downed("target", 1_000, policies);
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("cuffs", CustodyTransition.Action.APPLY_CUFFS,
                        2_000, "guard"), policies).ok());
        long deadline = state.unconsciousCustodyDeadlineAt;
        assertFalse(CustodyDeadlineEngine.resolveDue(state, deadline, policies).isEmpty());
        assertEquals(PlayerCondition.CONSCIOUS_RESTRAINED, state.condition);
        assertEquals(RestraintStatus.CUFFED, state.restraint);
        assertEquals(CustodyStatus.ARRESTED, state.custody);
        assertEquals(TransportStatus.NONE, state.transport);
    }

    @Test
    void jailRevivalIsResolvedAtExactDeadlineAndLateResolutionIsAStillNoOp() {
        var policies = policies();
        policies.jailAutomaticRevivalDelaySeconds = 5;
        var state = downed("target", 1_000, policies);
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("cuffs", CustodyTransition.Action.APPLY_CUFFS,
                        2_000, "guard"), policies).ok());
        assertTrue(CustodyTransitionEngine.apply(state,
                new CustodyTransition("jail", CustodyTransition.Action.DELIVER_TO_JAIL,
                        3_000, "guard", "", "cell-a", StateProvider.SYSTEM, "jail", 0),
                policies).ok());
        long revival = state.jailRevivalAt;
        assertEquals(1, CustodyDeadlineEngine.resolveDue(state, revival, policies).size());
        assertEquals(PlayerCondition.CONSCIOUS_RESTRAINED, state.condition);
        assertTrue(CustodyDeadlineEngine.resolveDue(state, revival + 1, policies).isEmpty());
    }

    @Test
    void malformedOrAlreadyReleasedStatesCannotBeResolvedByLateCallbacks() {
        var state = state("target");
        assertTrue(CustodyDeadlineEngine.resolveDue(state, 999_999, policies()).isEmpty());
        state.condition = PlayerCondition.DOWNED;
        state.downedDeadlineAt = 2_000L;
        assertEquals(1, CustodyDeadlineEngine.resolveDue(state, 2_000L, policies()).size());
        assertEquals(PlayerCondition.DEAD, state.condition);
        assertTrue(CustodyDeadlineEngine.resolveDue(state, 3_000L, policies()).isEmpty());
    }

    private static CustodyState downed(String id, long at, StrajaPolicies policies) {
        var state = state(id);
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("down-" + at, CustodyTransition.Action.ENTER_DOWNED, at, "weapon"),
                policies).ok());
        return state;
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
        policies.unconsciousCustodyDurationSeconds = 10;
        policies.carryTransportDeadlineSeconds = 10;
        policies.jailDeliveryDeadlineSeconds = 10;
        policies.jailAutomaticRevivalEnabled = true;
        return policies;
    }
}
