package com.dwurdy.straja.domain.model;

/**
 * Pure precedence resolver for a single potentially lethal damage event.
 *
 * <p>The resolver deliberately knows nothing about NeoForge, Vampirism, or
 * player entities.  The adapter supplies the already-classified source and
 * the provider eligibility flags; the returned outcome tells the application
 * layer which owner may resolve the event.</p>
 */
public final class LethalEventResolver {
    private LethalEventResolver() {}

    public enum Outcome {
        /** A normal hit is absorbed by established Straja custody. */
        PROTECTED_BY_CUSTODY,
        /** The configured hard-kill source owns the terminal result. */
        HARD_KILL,
        /** The optional Vampirism provider owns the DBNO transition. */
        VAMPIRISM_DBNO,
        /** Vampirism already owns DBNO; the hit must not finish it normally. */
        VAMPIRISM_PRESERVE,
        /** Straja owns the temporary downed transition. */
        STRAJA_DOWNED,
        /** Straja owns a terminal result for an already downed target. */
        STRAJA_DEATH,
        /** No provider claimed the event, so vanilla death may proceed. */
        VANILLA_DEATH,
        /** The target is already terminal; the duplicate event is ignored. */
        IGNORED_TERMINAL
    }

    public record Context(
            PlayerCondition condition,
            CustodyStatus custody,
            RestraintStatus restraint,
            DamageCategory category,
            boolean explicitHardKill,
            boolean vampireEligible,
            boolean vampireDbnoActive) {
        public Context {
            condition = condition == null ? PlayerCondition.ALIVE : condition;
            custody = custody == null ? CustodyStatus.FREE : custody;
            restraint = restraint == null ? RestraintStatus.NONE : restraint;
            category = category == null ? DamageCategory.ORDINARY : category;
        }
    }

    public record Decision(Outcome outcome, String code, boolean cancelVanillaDeath) {
        public Decision {
            outcome = outcome == null ? Outcome.VANILLA_DEATH : outcome;
            code = code == null ? "" : code;
        }

        public boolean terminal() {
            return outcome == Outcome.HARD_KILL
                    || outcome == Outcome.STRAJA_DEATH
                    || outcome == Outcome.VANILLA_DEATH;
        }
    }

    /** Resolves one event.  Callers must not apply more than this outcome. */
    public static Decision resolve(Context event, StrajaPolicies policies) {
        if (event == null || policies == null) {
            return new Decision(Outcome.VANILLA_DEATH, "INVALID_INPUT", false);
        }
        if (event.condition() == PlayerCondition.DEAD) {
            return new Decision(Outcome.IGNORED_TERMINAL, "ALREADY_DEAD", true);
        }

        boolean custodyEstablished = event.custody() != CustodyStatus.FREE
                || event.restraint() != RestraintStatus.NONE;

        // Custody protects against ordinary damage.  An explicitly classified
        // execution remains available as a separate, auditable exception.
        if (custodyEstablished && !event.explicitHardKill()) {
            return new Decision(Outcome.PROTECTED_BY_CUSTODY,
                    "CUSTODY_PROTECTS_FROM_ORDINARY_DAMAGE", true);
        }

        if (event.explicitHardKill()) {
            return policies.damageBehavior(DamageCategory.EXCEPTIONAL) == DamageBehavior.CANCEL
                    ? new Decision(Outcome.PROTECTED_BY_CUSTODY, "HARD_KILL_DISABLED", true)
                    : new Decision(Outcome.HARD_KILL, "EXPLICIT_HARD_KILL", false);
        }

        // A provider that already owns DBNO must receive normal follow-up hits
        // without Straja creating a second downed state.
        if (event.vampireDbnoActive()) {
            return new Decision(Outcome.VAMPIRISM_PRESERVE,
                    "VAMPIRISM_DBNO_ALREADY_ACTIVE", true);
        }
        if (event.vampireEligible()) {
            return new Decision(Outcome.VAMPIRISM_DBNO,
                    // The initial lethal hit must reach Vampirism's later
                    // LivingDeath hook, which creates DBNO and cancels death.
                    "VAMPIRISM_PROVIDER_OWNS_DBNO", false);
        }

        // An active resuscitation is a protected hand-off: ordinary damage
        // cannot finish the target while a rescuer is actively bringing them
        // back. Interruption is handled by the application lifecycle flow.
        if (event.condition() == PlayerCondition.RESUSCITATING) {
            return new Decision(Outcome.PROTECTED_BY_CUSTODY,
                    "RESUSCITATION_DAMAGE_PRESERVED", true);
        }

        boolean downedOrResuscitating = event.condition() == PlayerCondition.DOWNED
                || event.condition() == PlayerCondition.RESUSCITATING;
        if (downedOrResuscitating) {
            if (event.category() == DamageCategory.SECOND_WEAPON_HIT) {
                return new Decision(Outcome.STRAJA_DEATH,
                        "SECOND_WEAPON_HIT_FINISHES_UNRESTRAINED_DOWNED", false);
            }
            DamageBehavior behavior = policies.damageBehavior(event.category());
            if (behavior == DamageBehavior.CANCEL || behavior == DamageBehavior.PRESERVE_DOWNED) {
                return new Decision(Outcome.PROTECTED_BY_CUSTODY,
                        "DOWNED_DAMAGE_PRESERVED", true);
            }
            if (behavior == DamageBehavior.KILL) {
                return new Decision(Outcome.STRAJA_DEATH,
                        "CONFIGURED_DOWNED_DAMAGE_KILL", false);
            }
            return new Decision(Outcome.PROTECTED_BY_CUSTODY,
                    "DOWNED_DAMAGE_DOES_NOT_CREATE_SECOND_STATE", true);
        }

        DamageBehavior behavior = policies.damageBehavior(event.category());
        if (behavior == DamageBehavior.CANCEL) {
            return new Decision(Outcome.PROTECTED_BY_CUSTODY, "DAMAGE_CANCELLED_BY_POLICY", true);
        }
        if (behavior == DamageBehavior.KILL) {
            return new Decision(Outcome.VANILLA_DEATH, "DAMAGE_KILL_POLICY", false);
        }
        if (policies.downedEnabled) {
            return new Decision(Outcome.STRAJA_DOWNED, "STRAJA_OWNS_DOWNED_TRANSITION", true);
        }
        return new Decision(Outcome.VANILLA_DEATH, "STRAJA_DOWNED_DISABLED", false);
    }
}
