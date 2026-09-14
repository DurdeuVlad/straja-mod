package com.dwurdy.straja.domain.model;

/**
 * Pure precedence table for a lethal event.
 *
 * <p>The resolver deliberately consumes facts supplied by an adapter. It
 * does not inspect Minecraft damage sources or optional-provider internals;
 * the adapter/provider boundary must classify those facts before the state
 * transition is applied.</p>
 */
public final class LethalEventResolver {
    private LethalEventResolver() {}

    /** Source classification performed by the server-side event adapter. */
    public enum SourceKind {
        WEAPON,
        NON_WEAPON,
        EXCEPTIONAL
    }

    /** Exactly one terminal/control outcome is selected for one event. */
    public enum Outcome {
        NOT_LETHAL,
        STRAJA_CUSTODY_PROTECTION,
        VAMPIRISM_DBNO,
        EXPLICIT_PROVIDER,
        STRAJA_DOWNED,
        HARD_DEATH,
        VANILLA_DEATH,
        DUPLICATE,
        INVALID
    }

    /** Optional provider claim; absent optional mods are represented by none(). */
    public record ProviderSelection(StateProvider provider, boolean eligible) {
        public ProviderSelection {
            provider = provider == null ? StateProvider.SYSTEM : provider;
        }

        public static ProviderSelection none() {
            return new ProviderSelection(StateProvider.SYSTEM, false);
        }

        public boolean selected() {
            return eligible && provider != StateProvider.SYSTEM
                    && provider != StateProvider.NATIVE
                    && provider != StateProvider.UNKNOWN;
        }
    }

    /** Facts needed to classify a server-side event before mutating state. */
    public record Request(
            String eventId,
            String sourceId,
            String actorId,
            SourceKind sourceKind,
            boolean lethal,
            boolean hardKillRequested,
            ProviderSelection provider) {
        public Request {
            eventId = eventId == null ? "" : eventId.trim();
            sourceId = sourceId == null ? "" : sourceId.trim();
            actorId = actorId == null ? "" : actorId.trim();
            provider = provider == null ? ProviderSelection.none() : provider;
        }

        public Request withProvider(ProviderSelection selection) {
            return new Request(eventId, sourceId, actorId, sourceKind, lethal,
                    hardKillRequested, selection);
        }

        public DamageCategory policyCategory() {
            if (sourceKind == null) return null;
            return switch (sourceKind) {
                case WEAPON -> DamageCategory.ORDINARY;
                case NON_WEAPON -> DamageCategory.NON_WEAPON;
                case EXCEPTIONAL -> DamageCategory.EXCEPTIONAL;
            };
        }
    }

    /** Resolver result; cancel means the selected Straja/provider owns the event. */
    public record Decision(
            Outcome outcome,
            SourceKind sourceKind,
            DamageCategory policyCategory,
            StateProvider provider,
            String code,
            boolean cancelVanillaDeath,
            boolean idempotent) {
        public Decision {
            outcome = outcome == null ? Outcome.INVALID : outcome;
            provider = provider == null ? StateProvider.SYSTEM : provider;
            code = code == null ? "" : code;
        }

        public boolean ownsEvent() {
            return cancelVanillaDeath;
        }

        public boolean terminal() {
            return outcome == Outcome.STRAJA_DOWNED
                    || outcome == Outcome.VAMPIRISM_DBNO
                    || outcome == Outcome.EXPLICIT_PROVIDER
                    || outcome == Outcome.HARD_DEATH
                    || outcome == Outcome.VANILLA_DEATH;
        }
    }

    /** Applies issue-41 precedence without touching or mutating state. */
    public static Decision resolve(CustodyState state, Request request,
                                   StrajaPolicies policies) {
        if (state == null || request == null || policies == null
                || request.sourceKind() == null || request.eventId().isEmpty()) {
            return invalid(request, "INVALID_INPUT");
        }
        if (!state.wellFormed()) {
            return invalid(request, "MALFORMED_STATE");
        }
        if (!request.lethal()) {
            return decision(Outcome.NOT_LETHAL, request, StateProvider.SYSTEM,
                    "NOT_LETHAL", false, false);
        }
        if (request.eventId().equals(state.transitionId)
                || request.eventId().equals(state.lastLethalEventId)
                || state.hasLethalEvent(request.eventId())) {
            boolean owns = state.ownsLethalEvent(request.eventId())
                    || request.eventId().equals(state.lastLethalEventId)
                    && ownsPreviousEvent(state);
            return decision(Outcome.DUPLICATE, request, state.lastLethalProvider,
                    "DUPLICATE_EVENT", owns, true);
        }
        if (state.condition == PlayerCondition.DEAD) {
            return decision(Outcome.DUPLICATE, request, state.provider,
                    "ALREADY_DEAD", false, true);
        }

        // Existing Straja custody is the first protection boundary. Explicit
        // hard-kill policy is evaluated immediately after that boundary.
        boolean establishedCustody = state.restraint != RestraintStatus.NONE
                || state.condition == PlayerCondition.CONSCIOUS_RESTRAINED
                || state.condition == PlayerCondition.UNCONSCIOUS_CUSTODY
                || state.custody != CustodyStatus.FREE;
        if (establishedCustody && request.hardKillRequested()) {
            return decision(Outcome.STRAJA_CUSTODY_PROTECTION, request, StateProvider.NATIVE,
                    "CUSTODY_PRECEDENCE_PROTECTION", true, false);
        }
        if (establishedCustody) {
            return policyDecision(request, policies.damageBehavior(request.policyCategory()),
                    "CUSTODY_POLICY");
        }
        if (request.hardKillRequested() && policies.hardKillEnabled) {
            return decision(Outcome.HARD_DEATH, request, StateProvider.SYSTEM,
                    "EXPLICIT_HARD_KILL", false, false);
        }

        // An optional provider can claim this event only after hard-kill
        // precedence and before Straja's own downed provider.
        if (request.provider().selected()) {
            Outcome providerOutcome = request.provider().provider() == StateProvider.VAMPIRISM
                    ? Outcome.VAMPIRISM_DBNO : Outcome.EXPLICIT_PROVIDER;
            return decision(providerOutcome, request, request.provider().provider(),
                    "PROVIDER_SELECTED", true, false);
        }

        if (state.condition == PlayerCondition.DOWNED
                && state.restraint == RestraintStatus.NONE
                && request.sourceKind() == SourceKind.WEAPON) {
            return policyDecision(request,
                    policies.damageBehavior(DamageCategory.SECOND_WEAPON_HIT),
                    "SECOND_WEAPON_HIT_POLICY");
        }

        // An active resuscitation is a protected hand-off: ordinary damage
        // cannot finish the target while a rescuer is actively bringing them
        // back. Interruption is handled by the application lifecycle flow.
        if (state.condition == PlayerCondition.RESUSCITATING) {
            return decision(Outcome.STRAJA_CUSTODY_PROTECTION, request, StateProvider.NATIVE,
                    "RESUSCITATION_DAMAGE_PRESERVED", true, false);
        }

        DamageBehavior behavior = policies.damageBehavior(request.policyCategory());
        if (state.condition == PlayerCondition.ALIVE && policies.downedEnabled
                && (behavior == DamageBehavior.PRESERVE_DOWNED
                || behavior == DamageBehavior.CONVERT_TO_DOWNED)) {
            return decision(Outcome.STRAJA_DOWNED, request, StateProvider.NATIVE,
                    "DOWNED_POLICY", true, false);
        }
        return policyDecision(request, behavior, "DAMAGE_POLICY");
    }

    private static Decision policyDecision(Request request, DamageBehavior behavior,
                                           String prefix) {
        if (behavior == null) behavior = DamageBehavior.CANCEL;
        return switch (behavior) {
            case PRESERVE_DOWNED, CANCEL -> decision(
                    Outcome.STRAJA_CUSTODY_PROTECTION, request, StateProvider.NATIVE,
                    prefix + "_PRESERVE", true, false);
            case CONVERT_TO_DOWNED -> decision(
                    Outcome.STRAJA_DOWNED, request, StateProvider.NATIVE,
                    prefix + "_DOWNED", true, false);
            case KILL -> decision(
                    Outcome.HARD_DEATH, request, StateProvider.SYSTEM,
                    prefix + "_KILL", false, false);
            case ALLOW -> decision(
                    Outcome.VANILLA_DEATH, request, StateProvider.SYSTEM,
                    prefix + "_ALLOW", false, false);
        };
    }

    private static Decision decision(Outcome outcome, Request request,
                                     StateProvider provider, String code,
                                     boolean cancel, boolean idempotent) {
        return new Decision(outcome, request == null ? null : request.sourceKind(),
                request == null ? null : request.policyCategory(), provider,
                code, cancel, idempotent);
    }

    private static Decision invalid(Request request, String code) {
        return decision(Outcome.INVALID, request, StateProvider.SYSTEM, code, true, false);
    }

    private static boolean ownsPreviousEvent(CustodyState state) {
        return Outcome.STRAJA_DOWNED.name().equals(state.lastLethalOutcome)
                || Outcome.STRAJA_CUSTODY_PROTECTION.name().equals(state.lastLethalOutcome)
                || Outcome.VAMPIRISM_DBNO.name().equals(state.lastLethalOutcome)
                || Outcome.EXPLICIT_PROVIDER.name().equals(state.lastLethalOutcome);
    }
}
