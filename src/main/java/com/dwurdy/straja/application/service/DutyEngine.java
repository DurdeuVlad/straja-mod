package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.DomainEvent;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.Result;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Pure duty state machine. No platform access: callers pass loaded state and
 * receive emitted domain events. Checkpoint deadlines are absolute wall-clock
 * timestamps so logout cannot freeze a patrol.
 */
public final class DutyEngine {
    public static final long MINUTE_MS = 60_000L;
    /** Fallbacks used only when no policies are supplied (e.g. pure unit tests). */
    public static final int DEFAULT_UNLOCK_MINUTES = 10;
    public static final int DEFAULT_DEADLINE_MINUTES = 30;
    public static final int DEFAULT_SALARY_BLOCK_MINUTES = 20;

    private DutyEngine() {}

    public static boolean uniqueRoute(List<String> route) {
        return route != null && route.size() == 4 && new HashSet<>(route).size() == 4;
    }

    private static int missionMinutesFor(java.util.Map<String, Integer> missionMinutes,
                                         String checkpointId, StrajaPolicies policies) {
        Integer configured = missionMinutes == null ? null : missionMinutes.get(checkpointId);
        if (configured != null && configured > 0) return configured;
        return policies != null && policies.checkpointDeadlineMinutes > 0
                ? policies.checkpointDeadlineMinutes : DEFAULT_DEADLINE_MINUTES;
    }

    private static int unlockMinutes(StrajaPolicies policies) {
        return policies != null && policies.checkpointUnlockMinutes > 0
                ? policies.checkpointUnlockMinutes : DEFAULT_UNLOCK_MINUTES;
    }

    private static int blockMinutes(StrajaPolicies policies) {
        return policies != null && policies.salaryBlockMinutes > 0
                ? policies.salaryBlockMinutes : DEFAULT_SALARY_BLOCK_MINUTES;
    }

    /** Accrues salary for complete configured block chunks. Returns emitted events. */
    public static List<DomainEvent> accrue(GuardState state, long now, int salaryPerBlock,
                                           StrajaPolicies policies) {
        List<DomainEvent> events = new ArrayList<>();
        if (!state.duty || state.lastAccrualAt == null || now <= state.lastAccrualAt) return events;

        boolean capped = policies != null && policies.salaryWindowMinutes > 0
                && policies.salaryMaxBlocksPerDay >= 0;
        if (capped) {
            long windowKey = now / (policies.salaryWindowMinutes * MINUTE_MS);
            if (state.salaryWindowKey == null || state.salaryWindowKey != windowKey) {
                state.salaryWindowKey = windowKey;
                state.salaryBlocksWindow = 0;
            }
            if (state.salaryBlocksWindow < 0) state.salaryBlocksWindow = 0;
        }

        long elapsed = now - state.lastAccrualAt;
        long total = state.dutyRemainderMs + elapsed;
        long minutes = total / MINUTE_MS;
        state.dutyRemainderMs = total % MINUTE_MS;
        state.lastAccrualAt = now;
        if (minutes <= 0) return events;

        int block = blockMinutes(policies);
        long before = state.dutyMinutes / block;
        state.dutyMinutes += minutes;
        long after = state.dutyMinutes / block;
        int blocks = (int) (after - before);
        if (blocks <= 0) return events;

        state.dutyBlocksCurrent += blocks;
        state.serviceBlocks += blocks;
        int payableBlocks = capped
                ? Math.max(0, Math.min(blocks, policies.salaryMaxBlocksPerDay - state.salaryBlocksWindow))
                : blocks;
        if (capped) state.salaryBlocksWindow += payableBlocks;
        state.unpaidSalary += payableBlocks * salaryPerBlock;
        if (payableBlocks > 0) {
            events.add(DomainEvent.builder("salary_block")
                    .put("blocks", payableBlocks)
                    .put("amount", payableBlocks * salaryPerBlock)
                    .put("suppressedBlocks", blocks - payableBlocks)
                    .build());
        }
        return events;
    }

    public static Result startDuty(GuardState state, List<String> route, long now,
                                   java.util.Map<String, Integer> missionMinutes,
                                   StrajaPolicies policies) {
        if (state.resignationPending) return Result.fail("resignation_pending");
        if (state.resigned) return Result.fail("resigned");
        if (state.fired) return Result.fail("fired");
        if (state.rank < Rank.JUNIOR.level()) return Result.fail("rank_required");
        if (state.duty) return Result.fail("already_on_duty");
        if (state.suspended) return Result.fail("suspended");
        if (!uniqueRoute(route)) return Result.fail("route_invalid");

        state.duty = true;
        state.mode = "NORMAL";
        state.patrolState = "ACTIVE";
        state.route = new ArrayList<>(route);
        state.patrolIndex = 0;
        state.waitingUntil = null;
        state.missionMinutes = new java.util.LinkedHashMap<>();
        for (String checkpointId : route) {
            state.missionMinutes.put(checkpointId, missionMinutesFor(missionMinutes, checkpointId, policies));
        }
        state.deadlineAt = now + missionMinutesFor(state.missionMinutes, route.get(0), policies) * MINUTE_MS;
        state.dutyStartedAt = now;
        state.lastAccrualAt = now;
        state.lastDutyActivityAt = now;
        state.lastDutyActivityX = null;
        state.lastDutyActivityY = null;
        state.lastDutyActivityZ = null;
        state.salaryActivityPaused = false;
        // dutyRemainderMs and dutyMinutes intentionally survive shift boundaries:
        // partial paid-day progress must not be lost by ending/restarting duty.
        state.dutyBlocksCurrent = 0;
        state.specialAuthorizedBy = null;
        state.lastEndReason = null;
        return Result.pass(List.of(DomainEvent.builder("duty_started")
                .put("checkpoint", route.get(0))
                .put("deadlineAt", state.deadlineAt)
                .put("missionMinutes", state.missionMinutes.get(route.get(0)))
                .build()));
    }

    public static Result activateCheckpoint(GuardState state, String checkpointId, long now,
                                            int salaryPerBlock, java.util.Map<String, Integer> missionMinutes,
                                            StrajaPolicies policies) {
        if (!state.duty || !"NORMAL".equals(state.mode)) return Result.fail("not_normal_duty");
        if (!"ACTIVE".equals(state.patrolState)) return Result.fail("checkpoint_not_active");
        if (state.patrolIndex >= state.route.size() || !state.route.get(state.patrolIndex).equals(checkpointId)) {
            return Result.fail("wrong_checkpoint");
        }

        List<DomainEvent> events = new ArrayList<>(accrue(state, now, salaryPerBlock, policies));
        boolean completedRound = state.patrolIndex >= state.route.size() - 1;
        if (completedRound) {
            state.patrolRoundsCompleted += 1;
            state.patrolIndex = 0;
            events.add(DomainEvent.builder("patrol_round_complete")
                    .put("rounds", state.patrolRoundsCompleted)
                    .build());
        } else {
            state.patrolIndex += 1;
        }

        String nextCheckpoint = state.route.get(state.patrolIndex);
        int nextMissionMinutes = missionMinutesFor(state.missionMinutes, nextCheckpoint, policies);
        state.patrolState = "WAITING";
        state.waitingUntil = now + unlockMinutes(policies) * MINUTE_MS;
        // Critical anti-abuse invariant: deadline is fixed now, not when the
        // player later logs in or the WAITING state is first ticked as ACTIVE.
        state.deadlineAt = state.waitingUntil + nextMissionMinutes * MINUTE_MS;

        events.add(DomainEvent.builder("checkpoint_activated")
                .put("checkpoint", checkpointId)
                .put("nextCheckpoint", nextCheckpoint)
                .put("waitingUntil", state.waitingUntil)
                .put("deadlineAt", state.deadlineAt)
                .put("missionMinutes", nextMissionMinutes)
                .build());
        return Result.pass(events);
    }

    /**
     * @param accrualNow timestamp used for salary accrual (allows anti-AFK to
     *                   clamp accrual independently of the deadline clock)
     */
    public static TickResult tickDuty(GuardState state, long now, int salaryPerBlock,
                                      java.util.Map<String, Integer> missionMinutes,
                                      StrajaPolicies policies, Long accrualNow) {
        List<DomainEvent> events = new ArrayList<>();
        if (!state.duty) return new TickResult(events);
        long salaryTimestamp = accrualNow != null ? Math.min(now, accrualNow) : now;
        events.addAll(accrue(state, salaryTimestamp, salaryPerBlock, policies));

        if ("SPECIAL".equals(state.mode)) return new TickResult(events);

        if ("WAITING".equals(state.patrolState)) {
            // A player who logs out during the unlock window still has the same
            // absolute deadline. If both unlock and deadline pass offline, the
            // duty ends immediately when the state is processed again.
            if (state.deadlineAt != null && now >= state.deadlineAt) {
                endForTimeout(state, events);
                return new TickResult(events);
            }
            if (state.waitingUntil != null && now >= state.waitingUntil) {
                state.patrolState = "ACTIVE";
                state.waitingUntil = null;
                String activeCheckpoint = state.route.get(state.patrolIndex);
                int minutes = missionMinutesFor(state.missionMinutes, activeCheckpoint, policies);
                events.add(DomainEvent.builder("checkpoint_available")
                        .put("checkpoint", activeCheckpoint)
                        .put("deadlineAt", state.deadlineAt)
                        .put("missionMinutes", minutes)
                        .build());
            }
        } else if ("ACTIVE".equals(state.patrolState)
                && state.deadlineAt != null && now >= state.deadlineAt) {
            endForTimeout(state, events);
        }
        return new TickResult(events);
    }

    private static void endForTimeout(GuardState state, List<DomainEvent> events) {
        state.duty = false;
        state.mode = "OFF_DUTY";
        state.patrolState = "OFF";
        state.waitingUntil = null;
        state.deadlineAt = null;
        state.lastAccrualAt = null;
        state.lastDutyActivityAt = null;
        state.lastDutyActivityX = null;
        state.lastDutyActivityY = null;
        state.lastDutyActivityZ = null;
        state.salaryActivityPaused = false;
        state.lastEndReason = "checkpoint_timeout";
        events.add(DomainEvent.builder("duty_ended").put("reason", "checkpoint_timeout").build());
    }

    public static Result endDuty(GuardState state, String reason, long now, int salaryPerBlock,
                                 StrajaPolicies policies) {
        List<DomainEvent> events = new ArrayList<>(accrue(state, now, salaryPerBlock, policies));
        state.duty = false;
        state.mode = "OFF_DUTY";
        state.patrolState = "OFF";
        state.waitingUntil = null;
        state.deadlineAt = null;
        state.missionMinutes = new java.util.LinkedHashMap<>();
        state.lastAccrualAt = null;
        state.lastDutyActivityAt = null;
        state.lastDutyActivityX = null;
        state.lastDutyActivityY = null;
        state.lastDutyActivityZ = null;
        state.salaryActivityPaused = false;
        state.lastEndReason = reason;
        events.add(DomainEvent.builder("duty_ended").put("reason", reason).build());
        return Result.pass(events);
    }

    public static Result beginResignation(GuardState state, long now, int noticeMinutes) {
        if (state.rank < Rank.JUNIOR.level()) return Result.fail("rank_required");
        if (state.resigned) return Result.fail("already_resigned");
        if (state.resignationPending) return Result.fail("resignation_pending");
        state.resignationPending = true;
        state.resignationDeadlineAt = now + (long) noticeMinutes * MINUTE_MS;
        state.resignationRank = state.rank;
        return Result.pass(List.of(DomainEvent.builder("resignation_started")
                .put("deadlineAt", state.resignationDeadlineAt)
                .put("noticeMinutes", noticeMinutes)
                .build()));
    }

    public static Result cancelResignation(GuardState state) {
        if (!state.resignationPending) return Result.fail("resignation_not_pending");
        state.resignationPending = false;
        state.resignationDeadlineAt = null;
        state.resignationRank = null;
        return Result.pass(List.of(DomainEvent.of("resignation_cancelled")));
    }

    public static Result completeResignation(GuardState state, long now, long cooldownMs) {
        if (state.resigned) return Result.fail("already_resigned");
        if (!state.resignationPending) return Result.fail("resignation_not_pending");
        if (state.resignationDeadlineAt == null || now < state.resignationDeadlineAt) {
            return Result.fail("resignation_wait");
        }
        if (state.duty) return Result.fail("duty_active");
        int formerRank = state.resignationRank != null ? state.resignationRank : state.rank;
        state.resigned = true;
        state.resignedAt = now;
        state.rejoinAvailableAt = now + cooldownMs;
        state.formerRank = formerRank;
        state.rank = 0;
        state.invited = false;
        state.quizPassed = false;
        state.quizOrder = new ArrayList<>();
        state.quizIndex = 0;
        state.regearPending = false;
        state.kitClaimedRank = 0;
        state.resignationPending = false;
        state.resignationDeadlineAt = null;
        state.resignationRank = null;
        return Result.pass(List.of(DomainEvent.builder("resignation_completed")
                .put("formerRank", formerRank)
                .build()));
    }

    public static Result startSpecial(GuardState state, String actor, long now, int salaryPerBlock,
                                      StrajaPolicies policies) {
        if (!state.duty || !"NORMAL".equals(state.mode)) return Result.fail("normal_duty_required");
        List<DomainEvent> events = new ArrayList<>(accrue(state, now, salaryPerBlock, policies));
        state.mode = "SPECIAL";
        state.patrolState = "SUSPENDED";
        state.waitingUntil = null;
        state.deadlineAt = null;
        state.specialAuthorizedBy = actor;
        events.add(DomainEvent.builder("special_started").put("authorizedBy", actor).build());
        return Result.pass(events);
    }

    public static Result resumeSpecial(GuardState state, long now, int salaryPerBlock,
                                       StrajaPolicies policies) {
        if (!state.duty || !"SPECIAL".equals(state.mode)) return Result.fail("special_duty_required");
        List<DomainEvent> events = new ArrayList<>(accrue(state, now, salaryPerBlock, policies));
        state.mode = "NORMAL";
        state.patrolState = "ACTIVE";
        String checkpoint = state.patrolIndex < state.route.size() ? state.route.get(state.patrolIndex) : "";
        int minutes = missionMinutesFor(state.missionMinutes, checkpoint, policies);
        state.deadlineAt = now + minutes * MINUTE_MS;
        state.waitingUntil = null;
        events.add(DomainEvent.builder("patrol_resumed")
                .put("checkpoint", checkpoint)
                .put("deadlineAt", state.deadlineAt)
                .put("missionMinutes", minutes)
                .build());
        return Result.pass(events);
    }

    public static Result completeSpecial(GuardState state, long now, int salaryPerBlock,
                                         StrajaPolicies policies) {
        if (!state.duty || !"SPECIAL".equals(state.mode)) return Result.fail("special_duty_required");
        return endDuty(state, "special_complete", now, salaryPerBlock, policies);
    }

    /** Splits an amount into denominations, largest first. */
    public static List<int[]> toCoins(int amount, java.util.NavigableMap<Integer, String> coinMap) {
        if (amount < 0) throw new IllegalArgumentException("amount must be a non-negative integer");
        List<int[]> result = new ArrayList<>();
        for (var entry : coinMap.descendingMap().entrySet()) {
            int value = entry.getKey();
            int count = amount / value;
            if (count > 0) result.add(new int[]{value, count});
            amount %= value;
        }
        return result;
    }

    /**
     * Commissioner / captain authorization for special duty and regear.
     * Commissioner identity is decided by the caller via
     * {@link PlayerService#isCommissioner}; this engine never re-derives it
     * from configured names, which would bypass UUID-pinning policies.
     */
    public static boolean canAuthorize(boolean commissioner, String actorName, int actorRank,
                                       String targetName, int targetRank, String action) {
        if (commissioner) return true;
        if ("special_duty".equals(action) || "resume_special".equals(action)
                || "complete_special".equals(action) || "regear".equals(action)) {
            return actorRank == Rank.LIEUTENANT.level()
                    && !canon(targetName).equals(canon(actorName))
                    && targetRank < Rank.LIEUTENANT.level();
        }
        return false;
    }

    private static String canon(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    public record TickResult(List<DomainEvent> events) {}
}
