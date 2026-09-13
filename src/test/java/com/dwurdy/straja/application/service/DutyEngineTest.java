package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

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
    private static final List<String> ROUTE = List.of(
            "checkpoint_1", "checkpoint_2", "checkpoint_3", "checkpoint_4");

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
        assertEquals("route_invalid", DutyEngine.startDuty(state,
                List.of("a", "b", "c"), 0, Map.of(), POLICY).code());
    }

    @Test
    void startDutySetsDeadlineFromMissionMinutes() {
        GuardState state = new GuardState();
        state.rank = Rank.JUNIOR.level();
        var result = DutyEngine.startDuty(state, ROUTE, 1_000,
                Map.of("checkpoint_1", 45), POLICY);
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

        Result first = DutyEngine.activateCheckpoint(
                state, "checkpoint_1", 60_000, 20, Map.of(), custom);
        assertTrue(first.ok());
        assertEquals(60_000 + 20 * DutyEngine.MINUTE_MS, state.waitingUntil);
        assertEquals(state.waitingUntil + 60 * DutyEngine.MINUTE_MS, state.deadlineAt);
    }

    @Test
    void configuredSalaryBlockMinutesChangeAccrual() {
        StrajaPolicies custom = new StrajaPolicies();
        custom.salaryBlockMinutes = 5;
        GuardState state = juniorOnDuty();
        DutyEngine.accrue(state, 12 * DutyEngine.MINUTE_MS, 20, custom);
        assertEquals(40, state.unpaidSalary);
        assertEquals(2, state.serviceBlocks);
    }

    // ---------------------------------------------------------------- checkpoints

    @Test
    void checkpointFlowWaitsThenActivatesNextWithoutResettingDeadline() {
        GuardState state = juniorOnDuty();
        Result wrong = DutyEngine.activateCheckpoint(
                state, "checkpoint_2", 60_000, 20, Map.of(), POLICY);
        assertEquals("wrong_checkpoint", wrong.code());

        Result first = DutyEngine.activateCheckpoint(
                state, "checkpoint_1", 60_000, 20, Map.of(), POLICY);
        assertTrue(first.ok());
        assertEquals("WAITING", state.patrolState);
        long waitUntil = 60_000 + POLICY.checkpointUnlockMinutes * DutyEngine.MINUTE_MS;
        long absoluteDeadline = waitUntil + POLICY.checkpointDeadlineMinutes * DutyEngine.MINUTE_MS;
        assertEquals(waitUntil, state.waitingUntil);
        assertEquals(absoluteDeadline, state.deadlineAt);

        DutyEngine.tickDuty(state, 300_000, 20, Map.of(), POLICY, null);
        assertEquals("WAITING", state.patrolState);

        DutyEngine.tickDuty(state, waitUntil + 1, 20, Map.of(), POLICY, null);
        assertEquals("ACTIVE", state.patrolState);
        assertEquals(absoluteDeadline, state.deadlineAt,
                "unlock must not create a fresh deadline");
    }

    @Test
    void logoutCannotFreezeWaitingCheckpointDeadline() {
        GuardState state = juniorOnDuty();
        DutyEngine.activateCheckpoint(state, "checkpoint_1", 60_000, 20, Map.of(), POLICY);
        long deadline = state.deadlineAt;
        assertEquals("WAITING", state.patrolState);

        // Simulates no ticks at all while the player is offline. The first
        // state evaluation after the absolute deadline must close the duty.
        var result = DutyEngine.tickDuty(state, deadline, 20, Map.of(), POLICY, deadline);
        assertFalse(state.duty);
        assertEquals("checkpoint_timeout", state.lastEndReason);
        assertTrue(result.events().stream().anyMatch(e -> e.type().equals("duty_ended")));
    }

    @Test
    void finalCheckpointCompletesRoundAndLoopsInsteadOfEndingDuty() {
        GuardState state = juniorOnDuty();
        long now = 60_000;
        for (int i = 0; i < 3; i++) {
            Result reached = DutyEngine.activateCheckpoint(
                    state, ROUTE.get(i), now, 20, Map.of(), POLICY);
            assertTrue(reached.ok());
            now = state.waitingUntil + 1;
            DutyEngine.tickDuty(state, now, 20, Map.of(), POLICY, now);
            assertEquals("ACTIVE", state.patrolState);
            now += 1_000;
        }

        Result last = DutyEngine.activateCheckpoint(
                state, "checkpoint_4", now, 20, Map.of(), POLICY);
        assertTrue(last.ok());
        assertTrue(state.duty);
        assertEquals("WAITING", state.patrolState);
        assertEquals(0, state.patrolIndex);
        assertEquals(1, state.patrolRoundsCompleted);
        assertTrue(last.events().stream().anyMatch(e -> e.type().equals("patrol_round_complete")));
    }

    @Test
    void deadlineTimeoutEndsDuty() {
        GuardState state = juniorOnDuty();
        long deadline = state.deadlineAt;
        var result = DutyEngine.tickDuty(state, deadline, 20, Map.of(), POLICY, deadline);
        assertFalse(state.duty);
        assertEquals("checkpoint_timeout", state.lastEndReason);
        assertTrue(result.events().stream().anyMatch(e -> e.type().equals("duty_ended")));
    }

    // ---------------------------------------------------------------- salary

    @Test
    void accrualPaysCompleteTwentyMinuteDaysOnly() {
        GuardState state = juniorOnDuty();
        DutyEngine.accrue(state, 19 * DutyEngine.MINUTE_MS, 20, POLICY);
        assertEquals(0, state.unpaidSalary);

        DutyEngine.accrue(state, 25 * DutyEngine.MINUTE_MS, 20, POLICY);
        assertEquals(20, state.unpaidSalary);
        assertEquals(1, state.serviceBlocks);

        DutyEngine.accrue(state, 40 * DutyEngine.MINUTE_MS, 20, POLICY);
        assertEquals(40, state.unpaidSalary);
        assertEquals(2, state.serviceBlocks);
    }

    @Test
    void partialPaidDayProgressSurvivesVoluntaryShiftBoundary() {
        GuardState state = juniorOnDuty();
        DutyEngine.accrue(state, 12 * DutyEngine.MINUTE_MS, 20, POLICY);
        assertEquals(12, state.dutyMinutes);
        assertEquals(0, state.unpaidSalary);

        DutyEngine.endDuty(state, "secretary", 12 * DutyEngine.MINUTE_MS, 20, POLICY);
        assertTrue(DutyEngine.startDuty(state, ROUTE,
                20 * DutyEngine.MINUTE_MS, Map.of(), POLICY).ok());
        DutyEngine.accrue(state, 28 * DutyEngine.MINUTE_MS, 20, POLICY);

        assertEquals(20, state.dutyMinutes);
        assertEquals(20, state.unpaidSalary);
        assertEquals(1, state.serviceBlocks);
    }

    @Test
    void salaryCapSuppressesPayAboveDailyLimit() {
        GuardState state = juniorOnDuty();
        // 520 minutes = 26 x 20-minute paid days; cap is 24 per window.
        DutyEngine.accrue(state, 520 * DutyEngine.MINUTE_MS, 20, POLICY);
        assertEquals(24 * 20, state.unpaidSalary);
        assertEquals(26, state.serviceBlocks);
    }

    @Test
    void accrualNowClampStopsIdlePay() {
        GuardState state = juniorOnDuty();
        DutyEngine.tickDuty(state, 300 * DutyEngine.MINUTE_MS,
                20, Map.of(), POLICY, 60_000L);
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

        assertEquals("resignation_wait",
                DutyEngine.completeResignation(state, 10_000,
                        7L * 24 * 3600 * 1000).code());

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

        DutyEngine.tickDuty(state, 500L * DutyEngine.MINUTE_MS,
                20, Map.of(), POLICY, null);
        assertTrue(state.duty);

        Result resumed = DutyEngine.resumeSpecial(
                state, 600L * DutyEngine.MINUTE_MS, 20, POLICY);
        assertTrue(resumed.ok());
        assertEquals("NORMAL", state.mode);
        assertEquals("ACTIVE", state.patrolState);
        assertNotNull(state.deadlineAt);
    }

    @Test
    void canAuthorizeRules() {
        assertTrue(DutyEngine.canAuthorize(true, "dwurdy", 0,
                "guard1", 2, "special_duty"));
        assertTrue(DutyEngine.canAuthorize(true, "someone", 0,
                "other", 2, "regear"));
        assertTrue(DutyEngine.canAuthorize(false, "captain", 4,
                "guard1", 2, "special_duty"));
        assertFalse(DutyEngine.canAuthorize(false, "captain", 4,
                "captain", 4, "special_duty"));
        assertFalse(DutyEngine.canAuthorize(false, "jr", 1,
                "guard1", 1, "regear"));
        assertFalse(DutyEngine.canAuthorize(false, "dwurdy", 0,
                "guard1", 2, "special_duty"));
    }

    @Test
    void toCoinsUsesSixtyFourBronzeSilverDenomination() {
        var coins = new TreeMap<>(Map.of(
                1, "bronze", 10, "brass", 64, "silver", 1000, "gold"));
        var result = DutyEngine.toCoins(148, coins);
        assertEquals(List.of(64, 2), List.of(result.get(0)[0], result.get(0)[1]));
        assertEquals(List.of(10, 2), List.of(result.get(1)[0], result.get(1)[1]));
        assertTrue(DutyEngine.toCoins(0, coins).isEmpty());
    }
}
