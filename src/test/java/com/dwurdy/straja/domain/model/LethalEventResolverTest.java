package com.dwurdy.straja.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Truth-table coverage for the single-owner lethal event boundary. */
class LethalEventResolverTest {
    private final StrajaPolicies policies = new StrajaPolicies();

    @Test
    void normalLethalEventBelongsToStrajaDowned() {
        var decision = resolve(PlayerCondition.ALIVE, CustodyStatus.FREE,
                RestraintStatus.NONE, DamageCategory.ORDINARY, false, false, false);

        assertEquals(LethalEventResolver.Outcome.STRAJA_DOWNED, decision.outcome());
        assertTrue(decision.cancelVanillaDeath());
    }

    @Test
    void establishedCustodyProtectsFromOrdinaryLethalDamage() {
        var decision = resolve(PlayerCondition.CONSCIOUS_RESTRAINED,
                CustodyStatus.ARRESTED, RestraintStatus.CUFFED,
                DamageCategory.ORDINARY, false, false, false);

        assertEquals(LethalEventResolver.Outcome.PROTECTED_BY_CUSTODY, decision.outcome());
        assertTrue(decision.cancelVanillaDeath());
    }

    @Test
    void explicitHardKillIsTheOnlyConfiguredCustodyException() {
        var decision = resolve(PlayerCondition.UNCONSCIOUS_CUSTODY,
                CustodyStatus.ARRESTED, RestraintStatus.CUFFED,
                DamageCategory.EXCEPTIONAL, true, false, false);

        assertEquals(LethalEventResolver.Outcome.HARD_KILL, decision.outcome());
        assertTrue(!decision.cancelVanillaDeath());
    }

    @Test
    void eligibleVampireOwnsDbnoBeforeStraja() {
        var decision = resolve(PlayerCondition.ALIVE, CustodyStatus.FREE,
                RestraintStatus.NONE, DamageCategory.ORDINARY, false, true, false);

        assertEquals(LethalEventResolver.Outcome.VAMPIRISM_DBNO, decision.outcome());
        assertTrue(decision.cancelVanillaDeath());
    }

    @Test
    void existingVampireDbnoPreservesProviderState() {
        var decision = resolve(PlayerCondition.ALIVE, CustodyStatus.FREE,
                RestraintStatus.NONE, DamageCategory.ORDINARY, false, true, true);

        assertEquals(LethalEventResolver.Outcome.VAMPIRISM_PRESERVE, decision.outcome());
        assertTrue(decision.cancelVanillaDeath());
    }

    @Test
    void secondWeaponHitFinishesUnrestrainedDownedTarget() {
        var decision = resolve(PlayerCondition.DOWNED, CustodyStatus.FREE,
                RestraintStatus.NONE, DamageCategory.SECOND_WEAPON_HIT,
                false, false, false);

        assertEquals(LethalEventResolver.Outcome.STRAJA_DEATH, decision.outcome());
        assertTrue(!decision.cancelVanillaDeath());
    }

    @Test
    void activeResuscitationPreservesTargetFromNormalFollowUpDamage() {
        var decision = resolve(PlayerCondition.RESUSCITATING, CustodyStatus.FREE,
                RestraintStatus.NONE, DamageCategory.SECOND_WEAPON_HIT,
                false, false, false);

        assertEquals(LethalEventResolver.Outcome.PROTECTED_BY_CUSTODY, decision.outcome());
        assertEquals("RESUSCITATION_DAMAGE_PRESERVED", decision.code());
        assertTrue(decision.cancelVanillaDeath());
    }

    @Test
    void normalDamageAgainstDownedTargetCannotCreateAnotherOutcome() {
        var decision = resolve(PlayerCondition.DOWNED, CustodyStatus.FREE,
                RestraintStatus.NONE, DamageCategory.NON_WEAPON, false, false, false);

        assertEquals(LethalEventResolver.Outcome.PROTECTED_BY_CUSTODY, decision.outcome());
        assertTrue(decision.cancelVanillaDeath());
    }

    @Test
    void disabledDownedPolicyFallsThroughToVanillaDeath() {
        policies.downedEnabled = false;
        var decision = resolve(PlayerCondition.ALIVE, CustodyStatus.FREE,
                RestraintStatus.NONE, DamageCategory.ORDINARY, false, false, false);

        assertEquals(LethalEventResolver.Outcome.VANILLA_DEATH, decision.outcome());
        assertTrue(!decision.cancelVanillaDeath());
    }

    private LethalEventResolver.Decision resolve(
            PlayerCondition condition,
            CustodyStatus custody,
            RestraintStatus restraint,
            DamageCategory category,
            boolean hardKill,
            boolean vampireEligible,
            boolean vampireDbnoActive) {
        return LethalEventResolver.resolve(new LethalEventResolver.Context(
                condition, custody, restraint, category, hardKill,
                vampireEligible, vampireDbnoActive), policies);
    }
}
