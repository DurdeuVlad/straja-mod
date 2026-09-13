package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.domain.model.DutyEngine;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.Result;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class DutyEngineTest {
    private static final StrajaPolicies POLICY = new StrajaPolicies();
    private static final List<String> ROUTE = List.of("checkpoint_1", "checkpoint_2", "checkpoint_3", "checkpoint_4");

    private GuardState juniorOnDuty() {
        GuardState state = new GuardState();
        state.rank = Rank.JUNIOR.level();
        state.invited = true;
        state.quizPassed = true;
        Result result = DutyEngine.startDuty(state, ROUTE, 0, Map.of(), POLICY);
        assertTrue(result.ok(), "startDuty refused: " + result.code());
        return state;
    }

    // ---------------------------------------------------------------- start

    @Test
    void startDutyRequiresRankAndCleanStatus() {
        GuardState state = new GuardState();
        assertEquals("rank_required", DutyEngine.startDuty(state, ROUTE, 0, Map.of(), POLICY).code());

        state.rank = Rank.JUNIOR.level();
        state.suspended = true;
        assertEquals("suspended", DutyEngine.startDuty(state, ROUTE, 0, Map.of(), POLICY).code());

        state.suspended = false;
        state.resignationPending = true;
        assertEquals("resignation_pending", DutyEngine.startDuty(state, ROUTE, 0, Map.of(), POLICY).code());
    }

    @Test
    void startDutyRejectsDuplicateOrShortRoutes() {
        GuardState state = new GuardState();
        state.rank = Rank.JUNIOR.level();
        assertEquals("route_invalid", DutyEngine.startDuty(state,
                List.of("a", "a", "b", "c"), 0, Map.of(), POLICY).code());
        assertEquals("route_invalid", DutyEngine.startDuty(state, List.of("a", "b", "c"), 0, Map.of(), POLICY).code());
    }

    @Test
    void startDutySetsDeadlineFromMissionMinutes() {
        GuardState state = new GuardState();
        state.rank = Rank.JUNIOR.level();
        var result = DutyEngine.startDuty(state, ROUTE, 1_000, Map.of("checkpoint_1", 45), POLICY);
        assertTrue(result.ok());
        assertEquals(1_000 + 45 * DutyEngine.MINUTE_MS, state.deadlineAt);
        assertEquals("ACTIVE", state.patrolState);
        assertEquals(0, state.patrolIndex);
    }

    @Test
    void configuredTimersOverrideDefaults() {
        StrajaPolicies custom = new StrajaPolicies();
        custom.checkpointUnlockMinutes = 20;
        custom.checkpointDeadlineMinutes = 60;
        custom.salaryBlockMinutes = 5;
        GuardState state = new GuardState();
        state.rank = Rank.JUNIOR.level();
        var result = DutyEngine.startDuty(state, ROUTE, 0, Map.of(), custom);
        assertTrue(result.ok());
        assertEquals(60 * DutyEngine.MINUTE_MS, state.deadlineAt);

        Result first = DutyEngine.activateCheckpoint(state, "checkpoint_1", 60_000, 20, Map.of(), custom);
        assertTrue(first.ok());
        assertEquals(60_000 + 20 * DutyEngine.MINUTE_MS, state.waitingUntil);
    }

    @Test
    void configuredSalaryBlockMinutesChangeAccrual() {
        StrajaPolicies custom = new StrajaPolicies();
        custom.salaryBlockMinutes = 5;
        GuardState state = juniorOnDuty();
        // 12 minutes -> 2 blocks of 5 (would be 1 block under the default 10)
        DutyEngine.accrue(state, 12 * DutyEngine.MINUTE_MS, 20, custom);
        assertEquals(40, state.unpaidSalary);
        assertEquals(2, state.serviceBlocks);
    }

    // ---------------------------------------------------------------- checkpoints

    @Test
    void checkpointFlowWaitsThenActivatesNext() {
        GuardState state = juniorOnDuty();
        // Wrong checkpoint rejected.
        Result wrong = DutyEngine.activateCheckpoint(state, "checkpoint_2", 60_000, 20, Map.of(), POLICY);
        assertEquals("wrong_checkpoint", wrong.code());

        // First checkpoint at t=60s -> WAITING until t=60s+10min.
        Result first = DutyEngine.activateCheckpoint(state, "checkpoint_1", 60_000, 20, Map.of(), POLICY);
        assertTrue(first.ok());
        assertEquals("WAITING", state.patrolState);
        assertEquals(60_000 + DutyEngine.DEFAULT_UNLOCK_MINUTES * DutyEngine.MINUTE_MS, state.waitingUntil);
        assertNull(state.deadlineAt);

        // During the wait, ticking is inert for the patrol.
        var duringWait = DutyEngine.tickDuty(state, 300_000, 20, Map.of(), POLICY, null);
        assertEquals("WAITING", state.patrolState);

        // Wait expires -> next checkpoint ACTIVE with a fresh deadline.
        var after = DutyEngine.tickDuty(state, 700_000, 20, Map.of(), POLICY, null);
        assertEquals("ACTIVE", state.patrolState);
        assertEquals(700_000 + 30 * DutyEngine.MINUTE_MS, state.deadlineAt);
    }

    @Test
    void finalCheckpointCompletesPatrol() {
        GuardState state = juniorOnDuty();
        for (int i = 0; i < 3; i++) {
            DutyEngine.activateCheckpoint(state, ROUTE.get(i), 60_000, 20, Map.of(), POLICY);
            DutyEngine.tickDuty(state, 60_000 + DutyEngine.DEFAULT_UNLOCK_MINUTES * DutyEngine.MINUTE_MS + 1, 20, Map.of(), POLICY, null);
        }
        Result last = DutyEngine.activateCheckpoint(state, "checkpoint_4", 800_000, 20, Map.of(), POLICY);
        assertTrue(last.ok());
        assertFalse(state.duty);
        assertEquals("OFF", state.patrolState);
        assertEquals("patrol_complete", state.lastEndReason);
    }

    @Test
    void deadlineTimeoutEndsDuty() {
        GuardState state = juniorOnDuty();
        long deadline = state.deadlineAt;
        var result = DutyEngine.tickDuty(state, deadline, 20, Map.of(), POLICY, null);
        assertFalse(state.duty);
        assertEquals("checkpoint_timeout", state.lastEndReason);
        assertTrue(result.events().stream().anyMatch(e -> e.type().equals("duty_ended")));
    }

    // ---------------------------------------------------------------- salary

    @Test
    void accrualPaysCompleteBlocksOnly() {
        GuardState state = juniorOnDuty();
        // 9 minutes -> no block
        DutyEngine.accrue(state, 9 * DutyEngine.MINUTE_MS, 20, POLICY);
        assertEquals(0, state.unpaidSalary);
        // 25 minutes total -> 2 blocks of 10
        DutyEngine.accrue(state, 25 * DutyEngine.MINUTE_MS, 20, POLICY);
        assertEquals(40, state.unpaidSalary);
        assertEquals(2, state.serviceBlocks);
        // remainder carries over
        DutyEngine.accrue(state, 30 * DutyEngine.MINUTE_MS, 20, POLICY);
        assertEquals(3, state.serviceBlocks);
    }

    @Test
    void salaryCapSuppressesPayAboveDailyLimit() {
        GuardState state = juniorOnDuty();
        // 260 minutes = 26 blocks; cap is 24 blocks/day
        DutyEngine.accrue(state, 260 * DutyEngine.MINUTE_MS, 20, POLICY);
        assertEquals(24 * 20, state.unpaidSalary);
        assertEquals(26, state.serviceBlocks);
    }

    @Test
    void accrualNowClampStopsIdlePay() {
        GuardState state = juniorOnDuty();
        // accrual timestamp clamped at grace boundary — later wall time doesn't pay
        DutyEngine.tickDuty(state, 300 * DutyEngine.MINUTE_MS, 20, Map.of(), POLICY, 60_000L);
        assertEquals(0, state.unpaidSalary);
    }

    // ---------------------------------------------------------------- resignation

    @Test
    void resignationLifecycle() {
        GuardState state = juniorOnDuty();
        DutyEngine.endDuty(state, "test", 10_000, 20, POLICY);

        Result start = DutyEngine.beginResignation(state, 10_000, 15);
        assertTrue(start.ok());
        assertEquals(10_000 + 15 * DutyEngine.MINUTE_MS, state.resignationDeadlineAt);

        // too early to confirm
        assertEquals("resignation_wait",
                DutyEngine.completeResignation(state, 10_000, 7L * 24 * 3600 * 1000).code());

        Result done = DutyEngine.completeResignation(state,
                10_000 + 16 * DutyEngine.MINUTE_MS, 7L * 24 * 3600 * 1000);
        assertTrue(done.ok());
        assertTrue(state.resigned);
        assertEquals(0, state.rank);
        assertFalse(state.invited);
        assertEquals(Rank.JUNIOR.level(), state.formerRank);
    }

    @Test
    void cancelResignationRestoresState() {
        GuardState state = new GuardState();
        state.rank = Rank.GUARD.level();
        DutyEngine.beginResignation(state, 0, 15);
        Result cancelled = DutyEngine.cancelResignation(state);
        assertTrue(cancelled.ok());
        assertFalse(state.resignationPending);
        assertNull(state.resignationDeadlineAt);
    }

    // ---------------------------------------------------------------- special duty

    @Test
    void specialDutySuspendsPatrolAndResumes() {
        GuardState state = juniorOnDuty();
        Result started = DutyEngine.startSpecial(state, "dwurdy", 5_000, 20, POLICY);
        assertTrue(started.ok());
        assertEquals("SPECIAL", state.mode);
        assertNull(state.deadlineAt);

        // ticking during special duty must not time out the patrol
        DutyEngine.tickDuty(state, 500L * DutyEngine.MINUTE_MS, 20, Map.of(), POLICY, null);
        assertTrue(state.duty);

        Result resumed = DutyEngine.resumeSpecial(state, 600L * DutyEngine.MINUTE_MS, 20, POLICY);
        assertTrue(resumed.ok());
        assertEquals("NORMAL", state.mode);
        assertEquals("ACTIVE", state.patrolState);
        assertNotNull(state.deadlineAt);
    }

    @Test
    void canAuthorizeRules() {
        // commissioner flag (computed by PlayerService) bypasses rank rules
        assertTrue(DutyEngine.canAuthorize(true, "dwurdy", 0, "guard1", 2, "special_duty"));
        assertTrue(DutyEngine.canAuthorize(true, "someone", 0, "other", 2, "regear"));
        // lieutenant for a lower-ranked different target
        assertTrue(DutyEngine.canAuthorize(false, "lt", 4, "guard1", 2, "special_duty"));
        // lieutenant cannot authorize self
        assertFalse(DutyEngine.canAuthorize(false, "lt", 4, "lt", 4, "special_duty"));
        // junior cannot authorize
        assertFalse(DutyEngine.canAuthorize(false, "jr", 1, "guard1", 1, "regear"));
        // names alone never confer commissioner authority
        assertFalse(DutyEngine.canAuthorize(false, "dwurdy", 0, "guard1", 2, "special_duty"));
    }

    @Test
    void toCoinsBreaksDownLargestFirst() {
        var coins = new TreeMap<>(Map.of(1, "b", 10, "r", 100, "s", 1000, "g"));
        var result = DutyEngine.toCoins(2543, coins);
        assertEquals(List.of(1000, 2), List.of(result.get(0)[0], result.get(0)[1]));
        assertEquals(List.of(100, 5), List.of(result.get(1)[0], result.get(1)[1]));
        assertEquals(List.of(10, 4), List.of(result.get(2)[0], result.get(2)[1]));
        assertEquals(List.of(1, 3), List.of(result.get(3)[0], result.get(3)[1]));
        assertTrue(DutyEngine.toCoins(0, coins).isEmpty());
    }
}
