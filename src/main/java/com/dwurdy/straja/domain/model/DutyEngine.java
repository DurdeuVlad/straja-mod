package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Pure port of the reference 01_straja_core.js. No platform access: callers
 * pass the loaded state and get events back. State objects are mutated in
 * place (the JS built copies; persistence is the repository's job).
 */
public final class DutyEngine {
    public static final long MINUTE_MS = 60_000L;
    /** Fallbacks used only when no policies are supplied (e.g. pure unit tests). */
    public static final int DEFAULT_UNLOCK_MINUTES = 10;
    public static final int DEFAULT_DEADLINE_MINUTES = 30;
    public static final int DEFAULT_SERVICE_BLOCK_MINUTES = 10;

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

    private static int serviceBlockMinutes(StrajaPolicies policies) {
        return policies != null && policies.serviceBlockMinutes > 0
                ? policies.serviceBlockMinutes : DEFAULT_SERVICE_BLOCK_MINUTES;
    }

    private static long granularityMs(StrajaPolicies policies) {
        return Math.max(1, policies != null ? policies.salaryGranularitySeconds : 60) * 1000L;
    }

    /**
     * Hourly-wage accrual (§10): duty time is quantized to
     * {@code salaryGranularitySeconds}; each chunk earns
     * {@code wage × seconds / 3600} Bronze and the sub-coin fraction carries
     * in {@code salaryCarryWork}, so promotion mid-shift only changes the
     * forward rate and nothing is ever truncated away. Service points still
     * tick per {@code serviceBlockMinutes} and are never suppressed by the
     * paid-time cap — duty is duty.
     */
    public static List<DomainEvent> accrue(GuardState state, long now, int salaryPerHour,
                                           StrajaPolicies policies) {
        List<DomainEvent> events = new ArrayList<>();
        if (!state.duty || state.lastAccrualAt == null || now <= state.lastAccrualAt) return events;

        boolean capped = policies != null && policies.salaryWindowMinutes > 0
                && policies.salaryMaxPaidMinutesPerDay >= 0;
        if (capped) {
            long windowKey = now / (policies.salaryWindowMinutes * MINUTE_MS);
            if (state.salaryWindowKey == null || state.salaryWindowKey != windowKey) {
                state.salaryWindowKey = windowKey;
                state.salaryPaidSecondsWindow = 0;
            }
            if (state.salaryPaidSecondsWindow < 0) state.salaryPaidSecondsWindow = 0;
        }

        long granMs = granularityMs(policies);
        long elapsed = now - state.lastAccrualAt;
        long total = state.dutyRemainderMs + elapsed;
        long chunks = total / granMs;
        state.dutyRemainderMs = total % granMs;
        state.lastAccrualAt = now;
        if (chunks <= 0) return events;

        long consumedMs = chunks * granMs;

        // Service points (promotion credit) tick on their own interval.
        long blockMs = serviceBlockMinutes(policies) * MINUTE_MS;
        long beforeBlocks = state.dutyServiceMs / blockMs;
        state.dutyServiceMs += consumedMs;
        int blocks = (int) (state.dutyServiceMs / blockMs - beforeBlocks);
        if (blocks > 0) {
            state.dutyBlocksCurrent += blocks;
            state.serviceBlocks += blocks;
        }

        // Paid seconds are capped per window; suppressed time still earns
        // service credit but no wage.
        long consumedSeconds = consumedMs / 1000;
        long payableSeconds = consumedSeconds;
        if (capped) {
            long remaining = policies.salaryMaxPaidMinutesPerDay * 60L - state.salaryPaidSecondsWindow;
            payableSeconds = Math.max(0, Math.min(consumedSeconds, remaining));
            state.salaryPaidSecondsWindow += payableSeconds;
        }

        long work = state.salaryCarryWork + payableSeconds * Math.max(0, (long) salaryPerHour);
        int coins = (int) (work / 3600L);
        state.salaryCarryWork = work % 3600L;
        if (coins > 0) {
            state.unpaidSalary += coins;
            events.add(DomainEvent.builder("salary_accrual")
                    .put("amount", coins)
                    .put("paidMinutes", payableSeconds / 60)
                    .put("suppressedSeconds", consumedSeconds - payableSeconds)
                    .put("hourlyWage", salaryPerHour)
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
        if (state.rank < Rank.STAGIAR.level()) return Result.fail("rank_required");
        if (state.duty) return Result.fail("already_on_duty");
        if (state.suspended) return Result.fail("suspended");
        if (!uniqueRoute(route)) return Result.fail("route_invalid");

        state.duty = true;
        state.mode = "NORMAL";
        state.patrolState = "ACTIVE";
        state.route = new ArrayList<>(route);
        state.patrolIndex = 0;
        state.patrolRounds = 0;
        state.requiredRounds = 0;
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
        state.dutyRemainderMs = 0;
        state.dutyServiceMs = 0;
        state.dutyBlocksCurrent = 0;
        state.specialAuthorizedBy = null;
        state.lastEndReason = null;
        return Result.pass(List.of(DomainEvent.builder("duty_started")
                .put("checkpoint", route.get(0))
                .put("deadlineAt", state.deadlineAt)
                .put("missionMinutes", state.missionMinutes.get(route.get(0)))
                .build()));
    }

    /**
     * Sergent+ ranks and the commissioner start shifts at will: no patrol route,
     * no checkpoint deadlines. Salary still accrues (per Minecraft day) and the
     * same lifecycle guards apply.
     */
    public static Result startFreeDuty(GuardState state, long now, StrajaPolicies policies,
                                       boolean commissioner) {
        if (state.resignationPending) return Result.fail("resignation_pending");
        if (state.resigned) return Result.fail("resigned");
        if (state.fired) return Result.fail("fired");
        if (state.duty) return Result.fail("already_on_duty");
        if (state.suspended) return Result.fail("suspended");
        int minRank = policies != null ? policies.freeDutyMinRank : Rank.SERGENT.level();
        if (!commissioner && state.rank < minRank) return Result.fail("free_duty_rank_required");

        state.duty = true;
        state.mode = "FREE";
        state.patrolState = "OFF";
        state.route = new ArrayList<>();
        state.patrolIndex = 0;
        state.patrolRounds = 0;
        state.requiredRounds = 0;
        state.waitingUntil = null;
        state.deadlineAt = null;
        state.missionMinutes = new java.util.LinkedHashMap<>();
        state.dutyStartedAt = now;
        state.lastAccrualAt = now;
        state.lastDutyActivityAt = now;
        state.lastDutyActivityX = null;
        state.lastDutyActivityY = null;
        state.lastDutyActivityZ = null;
        state.salaryActivityPaused = false;
        state.dutyRemainderMs = 0;
        state.dutyServiceMs = 0;
        state.dutyBlocksCurrent = 0;
        state.specialAuthorizedBy = null;
        state.lastEndReason = null;
        return Result.pass(List.of(DomainEvent.of("duty_started_free")));
    }

    public static Result activateCheckpoint(GuardState state, String checkpointId, long now,
                                            int salaryPerHour, java.util.Map<String, Integer> missionMinutes,
                                            StrajaPolicies policies) {
        if (!state.duty || !"NORMAL".equals(state.mode)) return Result.fail("not_normal_duty");
        if (!"ACTIVE".equals(state.patrolState)) return Result.fail("checkpoint_not_active");
        if (state.patrolIndex >= state.route.size() || !state.route.get(state.patrolIndex).equals(checkpointId)) {
            return Result.fail("wrong_checkpoint");
        }

        List<DomainEvent> events = new ArrayList<>(accrue(state, now, salaryPerHour, policies));
        state.deadlineAt = null;

        if (state.patrolIndex >= state.route.size() - 1) {
            // §8: the route loops — a completed round returns to checkpoint 1
            // after the standard unlock pause; duty ends only on stop or a
            // missed deadline. §25: an emergency snapshot of requiredRounds
            // turns the loop into a finite patrol that ends on the last lap.
            state.patrolRounds += 1;
            if (state.requiredRounds > 0 && state.patrolRounds >= state.requiredRounds) {
                events.add(DomainEvent.builder("patrol_complete")
                        .put("rounds", state.patrolRounds)
                        .build());
                events.addAll(endDuty(state, "patrol_complete", now, salaryPerHour, policies).events());
                return Result.pass(events);
            }
            state.patrolIndex = 0;
            state.patrolState = "WAITING";
            state.waitingUntil = now + unlockMinutes(policies) * MINUTE_MS;
            events.add(DomainEvent.builder("patrol_round_complete")
                    .put("round", state.patrolRounds)
                    .put("checkpoint", state.route.get(0))
                    .put("waitingUntil", state.waitingUntil)
                    .build());
            return Result.pass(events);
        }

        state.patrolIndex += 1;
        state.patrolState = "WAITING";
        state.waitingUntil = now + unlockMinutes(policies) * MINUTE_MS;
        events.add(DomainEvent.builder("checkpoint_activated")
                .put("checkpoint", checkpointId)
                .put("waitingUntil", state.waitingUntil)
                .build());
        return Result.pass(events);
    }

    /**
     * @param accrualNow timestamp used for salary accrual (allows anti-AFK to
     *                   clamp accrual independently of the deadline clock)
     */
    public static TickResult tickDuty(GuardState state, long now, int salaryPerHour,
                                      java.util.Map<String, Integer> missionMinutes,
                                      StrajaPolicies policies, Long accrualNow) {
        List<DomainEvent> events = new ArrayList<>();
        if (!state.duty) return new TickResult(events);
        long salaryTimestamp = accrualNow != null ? Math.min(now, accrualNow) : now;
        events.addAll(accrue(state, salaryTimestamp, salaryPerHour, policies));

        // Only NORMAL patrols have checkpoint deadlines; SPECIAL and FREE
        // shifts accrue salary without a route.
        if (!"NORMAL".equals(state.mode)) return new TickResult(events);

        if ("WAITING".equals(state.patrolState) && state.waitingUntil != null && now >= state.waitingUntil) {
            state.patrolState = "ACTIVE";
            state.waitingUntil = null;
            String activeCheckpoint = state.route.get(state.patrolIndex);
            int minutes = missionMinutesFor(state.missionMinutes, activeCheckpoint, policies);
            state.deadlineAt = now + minutes * MINUTE_MS;
            events.add(DomainEvent.builder("checkpoint_available")
                    .put("checkpoint", activeCheckpoint)
                    .put("deadlineAt", state.deadlineAt)
                    .put("missionMinutes", minutes)
                    .build());
        } else if ("ACTIVE".equals(state.patrolState) && state.deadlineAt != null && now >= state.deadlineAt) {
            state.duty = false;
            state.mode = "OFF_DUTY";
            state.patrolState = "OFF";
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
        return new TickResult(events);
    }

    public static Result endDuty(GuardState state, String reason, long now, int salaryPerHour,
                                 StrajaPolicies policies) {
        List<DomainEvent> events = new ArrayList<>(accrue(state, now, salaryPerHour, policies));
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
        state.requiredRounds = 0;
        state.lastEndReason = reason;
        events.add(DomainEvent.builder("duty_ended").put("reason", reason).build());
        return Result.pass(events);
    }

    public static Result beginResignation(GuardState state, long now, int noticeMinutes) {
        if (state.rank < Rank.STAGIAR.level()) return Result.fail("rank_required");
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

    public static Result startSpecial(GuardState state, String actor, long now, int salaryPerHour,
                                      StrajaPolicies policies) {
        if (!state.duty || !"NORMAL".equals(state.mode)) return Result.fail("normal_duty_required");
        List<DomainEvent> events = new ArrayList<>(accrue(state, now, salaryPerHour, policies));
        state.mode = "SPECIAL";
        state.patrolState = "SUSPENDED";
        state.waitingUntil = null;
        state.deadlineAt = null;
        state.specialAuthorizedBy = actor;
        events.add(DomainEvent.builder("special_started").put("authorizedBy", actor).build());
        return Result.pass(events);
    }

    public static Result resumeSpecial(GuardState state, long now, int salaryPerHour,
                                       StrajaPolicies policies) {
        if (!state.duty || !"SPECIAL".equals(state.mode)) return Result.fail("special_duty_required");
        List<DomainEvent> events = new ArrayList<>(accrue(state, now, salaryPerHour, policies));
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

    public static Result completeSpecial(GuardState state, long now, int salaryPerHour,
                                         StrajaPolicies policies) {
        if (!state.duty || !"SPECIAL".equals(state.mode)) return Result.fail("special_duty_required");
        return endDuty(state, "special_complete", now, salaryPerHour, policies);
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
     * Commissioner / lieutenant authorization for special duty and regear.
     * Commissioner identity is decided by the caller via
     * {@link PlayerService#isCommissioner} — this engine never re-derives it
     * from configured names, which would bypass the UUID-pinning policies.
     */
    public static boolean canAuthorize(boolean commissioner, String actorName, int actorRank,
                                       String targetName, int targetRank, String action) {
        if (commissioner) return true;
        if ("special_duty".equals(action) || "resume_special".equals(action)
                || "complete_special".equals(action) || "regear".equals(action)) {
            return actorRank == Rank.INSPECTOR.level()
                    && !canon(targetName).equals(canon(actorName))
                    && targetRank < Rank.INSPECTOR.level();
        }
        return false;
    }

    private static String canon(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    public record TickResult(List<DomainEvent> events) {}
}
