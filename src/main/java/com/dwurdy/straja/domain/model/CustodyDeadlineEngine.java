package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure evaluator for the persisted deadlines in {@link CustodyState}.
 *
 * <p>The engine does not own a scheduler and does not call Minecraft. A
 * caller supplies the current server time, and the evaluator atomically
 * applies at most one due transition per state. This makes a late callback
 * harmless: the next evaluation sees the resolved state and has no matching
 * deadline. The caller may run another pass in the same server tick when
 * several independent deadlines are due.</p>
 */
public final class CustodyDeadlineEngine {
    private CustodyDeadlineEngine() {}

    public enum DeadlineType {
        DOWNED_DEATH,
        TRANSPORT,
        RESUSCITATION_TIMEOUT,
        UNCONSCIOUS_CUSTODY,
        JAIL_DELIVERY,
        JAIL_REVIVAL
    }

    public record Evaluation(
            boolean changed,
            boolean idempotent,
            DeadlineType type,
            long deadlineAt,
            String code,
            List<String> violations) {
        public Evaluation {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        static Evaluation noOp() {
            return new Evaluation(false, false, null, 0, "NO_DUE_DEADLINE", List.of());
        }

        static Evaluation malformed(List<String> violations) {
            return new Evaluation(false, false, null, 0, "MALFORMED_STATE", violations);
        }

        static Evaluation invalid(String code) {
            return new Evaluation(false, false, null, 0, code, List.of());
        }
    }

    public record SweepResult(
            int appliedCount,
            int idempotentCount,
            int malformedCount,
            List<Evaluation> evaluations) {
        public SweepResult {
            evaluations = evaluations == null ? List.of() : List.copyOf(evaluations);
        }

        public boolean changed() {
            return appliedCount > 0;
        }
    }

    private record Due(DeadlineType type, long deadlineAt, CustodyTransition.Action action) {}

    /** Evaluates one canonical player state at the supplied server time. */
    public static Evaluation tick(CustodyState state, long now, StrajaPolicies policies) {
        if (state == null || policies == null) return Evaluation.invalid("INVALID_INPUT");
        if (now < 0) return Evaluation.invalid("INVALID_TIME");

        List<String> violations = timerViolations(state, policies);
        if (!violations.isEmpty()) return Evaluation.malformed(violations);

        Due due = nextDue(state, now);
        if (due == null) return Evaluation.noOp();

        // Use the persisted deadline as the transition time. If the server was
        // stopped past the deadline, this preserves the paused duration and
        // avoids granting extra time merely because recovery ran late.
        var transition = new CustodyTransition(
                "deadline:" + due.type().name() + ":" + due.deadlineAt(),
                due.action(),
                due.deadlineAt(),
                "",
                "",
                "",
                StateProvider.SYSTEM,
                "deadline",
                0);
        CustodyTransitionResult result = CustodyTransitionEngine.apply(state, transition, policies);
        if (result.idempotent()) {
            return new Evaluation(false, true, due.type(), due.deadlineAt(), result.code(), List.of());
        }
        if (!result.ok()) {
            return new Evaluation(false, false, due.type(), due.deadlineAt(), result.code(), List.of());
        }
        return new Evaluation(true, false, due.type(), due.deadlineAt(), result.code(), List.of());
    }

    /** Alias that reads naturally at call sites that want an evaluation. */
    public static Evaluation evaluate(CustodyState state, long now, StrajaPolicies policies) {
        return tick(state, now, policies);
    }

    /** Evaluates every canonical state without removing malformed entries. */
    public static SweepResult tick(CustodyStore store, long now, StrajaPolicies policies) {
        if (store == null || store.states == null) {
            return new SweepResult(0, 0, 1,
                    List.of(Evaluation.invalid("MALFORMED_STORE")));
        }
        int applied = 0;
        int idempotent = 0;
        int malformed = 0;
        List<Evaluation> evaluations = new ArrayList<>();
        for (Map.Entry<String, CustodyState> entry : new ArrayList<>(store.states.entrySet())) {
            Evaluation evaluation = entry.getValue() == null
                    ? Evaluation.malformed(List.of("state_missing"))
                    : tick(entry.getValue(), now, policies);
            evaluations.add(evaluation);
            if (evaluation.changed()) applied++;
            if (evaluation.idempotent()) idempotent++;
            if ("MALFORMED_STATE".equals(evaluation.code())) malformed++;
        }
        return new SweepResult(applied, idempotent, malformed, evaluations);
    }

    /**
     * Applies the configured lifecycle recovery policy to one canonical state.
     * The normal default is RETAIN, so logout and restart never reset a
     * persisted deadline. Other configured recovery policies are applied as
     * explicit domain transitions; deadlines are never changed implicitly.
     */
    public static Evaluation recover(CustodyState state, RecoveryEvent event,
                                     long now, StrajaPolicies policies) {
        if (state == null || event == null || policies == null) {
            return Evaluation.invalid("INVALID_INPUT");
        }
        if (now < 0) return Evaluation.invalid("INVALID_TIME");
        List<String> violations = timerViolations(state, policies);
        if (!violations.isEmpty()) return Evaluation.malformed(violations);
        RecoveryBehavior behavior = policies.recoveryBehavior(event);
        if (behavior == RecoveryBehavior.RETAIN) return Evaluation.noOp();
        if (behavior == RecoveryBehavior.RELEASE_TRANSPORT
                && state.transport == TransportStatus.CARRIED) {
            long at = state.transportDeadlineAt == null ? now : Math.min(now, state.transportDeadlineAt);
            return applyRecovery(state, event, at, DeadlineType.TRANSPORT,
                    CustodyTransition.Action.STOP_CARRY, policies);
        }
        CustodyTransition.Action action = switch (behavior) {
            case CLEAR_ALL -> CustodyTransition.Action.RECOVER_CLEAR_ALL;
            case RELEASE_RESTRAINTS -> CustodyTransition.Action.RECOVER_RELEASE_RESTRAINTS;
            case WAKE -> CustodyTransition.Action.RECOVER_WAKE;
            case RELEASE_TRANSPORT, RETAIN -> null;
        };
        if (action == null) return Evaluation.noOp();
        return applyRecovery(state, event, now, null, action, policies);
    }

    private static Evaluation applyRecovery(CustodyState state, RecoveryEvent event, long at,
                                             DeadlineType type, CustodyTransition.Action action,
                                             StrajaPolicies policies) {
        var transition = new CustodyTransition(
                "recovery:" + event.name() + ":" + action.name(),
                action,
                at,
                "",
                "",
                "",
                StateProvider.SYSTEM,
                "recovery",
                0);
        CustodyTransitionResult result = CustodyTransitionEngine.apply(state, transition, policies);
        if (result.idempotent()) {
            return new Evaluation(false, true, type, at, result.code(), List.of());
        }
        if (!result.ok()) {
            return new Evaluation(false, false, type, at, result.code(), List.of());
        }
        return new Evaluation(true, false, type, at, result.code(), List.of());
    }

    private static List<String> timerViolations(CustodyState state, StrajaPolicies policies) {
        var violations = new ArrayList<>(state.violations());
        if (state.custody == CustodyStatus.JAILED
                && state.condition == PlayerCondition.UNCONSCIOUS_CUSTODY
                && policies.jailAutomaticRevivalEnabled
                && !positive(state.jailRevivalAt)) {
            violations.add("jail_revival_deadline_missing");
        }
        return List.copyOf(violations);
    }

    private static Due nextDue(CustodyState state, long now) {
        Due best = null;
        if (state.transport == TransportStatus.CARRIED
                && due(state.transportDeadlineAt, now)) {
            best = choose(best, new Due(DeadlineType.TRANSPORT,
                    state.transportDeadlineAt, CustodyTransition.Action.STOP_CARRY));
        }
        if (state.condition != PlayerCondition.DEAD
                && state.custody == CustodyStatus.ARRESTED
                && due(state.jailDeliveryDeadlineAt, now)) {
            best = choose(best, new Due(DeadlineType.JAIL_DELIVERY,
                    state.jailDeliveryDeadlineAt,
                    CustodyTransition.Action.RESOLVE_JAIL_DELIVERY_DEADLINE));
        }
        switch (state.condition) {
            case DOWNED -> {
                if (state.transport != TransportStatus.CARRIED
                        && due(state.downedDeadlineAt, now)) {
                    best = choose(best, new Due(DeadlineType.DOWNED_DEATH,
                            state.downedDeadlineAt, CustodyTransition.Action.DIE));
                }
            }
            case RESUSCITATING -> {
                if (due(state.resuscitationDeadlineAt, now)) {
                    best = choose(best, new Due(DeadlineType.RESUSCITATION_TIMEOUT,
                            state.resuscitationDeadlineAt,
                            CustodyTransition.Action.INTERRUPT_RESUSCITATION));
                }
            }
            case UNCONSCIOUS_CUSTODY -> {
                if (state.custody == CustodyStatus.JAILED) {
                    if (due(state.jailRevivalAt, now)) {
                        best = choose(best, new Due(DeadlineType.JAIL_REVIVAL,
                                state.jailRevivalAt, CustodyTransition.Action.REVIVE_IN_JAIL));
                    }
                } else if (due(state.unconsciousCustodyDeadlineAt, now)) {
                    best = choose(best, new Due(DeadlineType.UNCONSCIOUS_CUSTODY,
                            state.unconsciousCustodyDeadlineAt,
                            CustodyTransition.Action.RESOLVE_UNCONSCIOUS_DEADLINE));
                }
            }
            default -> { /* stable condition or no timer for this dimension */ }
        }
        return best;
    }

    private static Due choose(Due current, Due candidate) {
        if (current == null) return candidate;
        int byTime = Long.compare(candidate.deadlineAt(), current.deadlineAt());
        if (byTime != 0) return byTime < 0 ? candidate : current;
        return priority(candidate.type()) < priority(current.type()) ? candidate : current;
    }

    private static int priority(DeadlineType type) {
        return switch (type) {
            case TRANSPORT -> 0;
            case DOWNED_DEATH -> 1;
            case RESUSCITATION_TIMEOUT -> 2;
            case UNCONSCIOUS_CUSTODY -> 3;
            case JAIL_DELIVERY -> 4;
            case JAIL_REVIVAL -> 5;
        };
    }

    private static boolean due(Long deadline, long now) {
        return deadline != null && deadline > 0 && now >= deadline;
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }
}
