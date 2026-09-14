package com.dwurdy.straja.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Truth table for DC-003's single lethal-event precedence. */
class LethalEventResolverTest {
    @Test
    void ordinaryAndNonWeaponLethalsEnterStrajaDownedByDefault() {
        var policies = policies();
        assertEquals(LethalEventResolver.Outcome.STRAJA_DOWNED,
                resolve(state("ordinary"), request("ordinary", LethalEventResolver.SourceKind.WEAPON), policies).outcome());
        assertEquals(LethalEventResolver.Outcome.STRAJA_DOWNED,
                resolve(state("non-weapon"), request("non-weapon", LethalEventResolver.SourceKind.NON_WEAPON), policies).outcome());
    }

    @Test
    void exceptionalDamageAndExplicitHardKillAreTerminal() {
        var policies = policies();
        assertEquals(LethalEventResolver.Outcome.HARD_DEATH,
                resolve(state("exceptional"), request("exceptional", LethalEventResolver.SourceKind.EXCEPTIONAL), policies).outcome());
        var hardKill = new LethalEventResolver.Request(
                "hard", "execution", "actor", LethalEventResolver.SourceKind.EXCEPTIONAL,
                true, true, LethalEventResolver.ProviderSelection.none());
        assertEquals(LethalEventResolver.Outcome.HARD_DEATH,
                resolve(state("hard"), hardKill, policies).outcome());

        var restrained = state("restrained-hard");
        assertTrue(CustodyTransitionEngine.apply(restrained,
                CustodyTransition.of("cuffs", CustodyTransition.Action.APPLY_CUFFS,
                        1_000L, "guard"), policies).ok());
        assertEquals(LethalEventResolver.Outcome.STRAJA_CUSTODY_PROTECTION,
                resolve(restrained, hardKill, policies).outcome(),
                "established custody has precedence over execution events");
    }

    @Test
    void secondWeaponHitKillsOnlyAnUnrestrainedDownedPlayer() {
        var downed = state("downed");
        downed.condition = PlayerCondition.DOWNED;
        downed.downedDeadlineAt = 10_000L;
        var result = resolve(downed, request("second", LethalEventResolver.SourceKind.WEAPON), policies());
        assertEquals(LethalEventResolver.Outcome.HARD_DEATH, result.outcome());
        assertFalse(result.ownsEvent(), "vanilla death must remain uncancelled for a kill outcome");

        var restrained = state("restrained");
        assertTrue(CustodyTransitionEngine.apply(restrained,
                CustodyTransition.of("cuffs", CustodyTransition.Action.APPLY_CUFFS, 1_000L, "guard"),
                policies()).ok());
        var protectedHit = resolve(restrained,
                request("restrained-hit", LethalEventResolver.SourceKind.WEAPON), policies());
        assertEquals(LethalEventResolver.Outcome.STRAJA_CUSTODY_PROTECTION, protectedHit.outcome());
        assertTrue(protectedHit.ownsEvent());
    }

    @Test
    void vampireProviderWinsBeforeStrajaDownedButNotOverHardKill() {
        var vampire = request("vampire", LethalEventResolver.SourceKind.WEAPON).withProvider(
                new LethalEventResolver.ProviderSelection(StateProvider.VAMPIRISM, true));
        var result = resolve(state("vampire"), vampire, policies());
        assertEquals(LethalEventResolver.Outcome.VAMPIRISM_DBNO, result.outcome());
        assertEquals(StateProvider.VAMPIRISM, result.provider());
        assertTrue(result.ownsEvent());

        var hard = new LethalEventResolver.Request(
                "vampire-hard", "execution", "actor", LethalEventResolver.SourceKind.EXCEPTIONAL,
                true, true, new LethalEventResolver.ProviderSelection(StateProvider.VAMPIRISM, true));
        assertEquals(LethalEventResolver.Outcome.HARD_DEATH,
                resolve(state("vampire-hard"), hard, policies()).outcome());
    }

    @Test
    void providerAndHardKillPoliciesFailClosedWhenDisabledOrAbsent() {
        var policies = policies();
        policies.hardKillEnabled = false;
        policies.exceptionalDamageBehavior = "PRESERVE_DOWNED";
        var disabled = new LethalEventResolver.Request(
                "disabled-hard", "execution", "actor", LethalEventResolver.SourceKind.EXCEPTIONAL,
                true, true, LethalEventResolver.ProviderSelection.none());
        assertEquals(LethalEventResolver.Outcome.STRAJA_DOWNED,
                resolve(state("disabled-hard"), disabled, policies).outcome());
        assertEquals(LethalEventResolver.Outcome.STRAJA_DOWNED,
                resolve(state("absent-provider"), request("absent", LethalEventResolver.SourceKind.WEAPON), policies).outcome());
    }

    @Test
    void ordinaryNonWeaponAndExceptionalPoliciesRemainIndependent() {
        var policies = policies();
        policies.ordinaryDamageBehavior = "ALLOW";
        policies.nonWeaponDamageBehavior = "KILL";
        policies.exceptionalDamageBehavior = "PRESERVE_DOWNED";

        assertEquals(LethalEventResolver.Outcome.VANILLA_DEATH,
                resolve(state("ordinary"), request("ordinary-policy", LethalEventResolver.SourceKind.WEAPON), policies).outcome());
        assertEquals(LethalEventResolver.Outcome.HARD_DEATH,
                resolve(state("non-weapon"), request("non-weapon-policy", LethalEventResolver.SourceKind.NON_WEAPON), policies).outcome());
        var restrained = state("exceptional");
        assertTrue(CustodyTransitionEngine.apply(restrained,
                CustodyTransition.of("rope", CustodyTransition.Action.APPLY_ROPE, 1_000L, "actor"), policies).ok());
        assertEquals(LethalEventResolver.Outcome.STRAJA_CUSTODY_PROTECTION,
                resolve(restrained, request("exceptional-policy", LethalEventResolver.SourceKind.EXCEPTIONAL), policies).outcome());
    }

    @Test
    void activeResuscitationPreservesTargetFromNormalFollowUpDamage() {
        var state = state("resuscitating");
        state.condition = PlayerCondition.RESUSCITATING;
        state.resuscitatorId = "rescuer";
        state.resuscitationDeadlineAt = 10_000L;
        state.pausedDownedRemainingMs = 5_000L;
        var decision = resolve(state,
                request("resuscitating-hit", LethalEventResolver.SourceKind.WEAPON), policies());

        assertEquals(LethalEventResolver.Outcome.STRAJA_CUSTODY_PROTECTION, decision.outcome());
        assertEquals("RESUSCITATION_DAMAGE_PRESERVED", decision.code());
        assertTrue(decision.cancelVanillaDeath());
    }

    @Test
    void normalDamageAgainstDownedTargetCannotCreateAnotherOutcome() {
        var downed = state("downed-nonweapon");
        downed.condition = PlayerCondition.DOWNED;
        downed.downedDeadlineAt = 10_000L;
        var decision = resolve(downed,
                request("downed-nonweapon", LethalEventResolver.SourceKind.NON_WEAPON), policies());

        assertEquals(LethalEventResolver.Outcome.STRAJA_CUSTODY_PROTECTION, decision.outcome());
        assertTrue(decision.cancelVanillaDeath());
    }

    @Test
    void repeatedEventsAreIdempotentAndDoNotChangeOwnership() {
        var downed = state("repeat-down");
        downed.lastLethalEventId = "same";
        downed.lastLethalOutcome = LethalEventResolver.Outcome.STRAJA_DOWNED.name();
        downed.lastLethalProvider = StateProvider.NATIVE;
        var protectedReplay = resolve(downed,
                request("same", LethalEventResolver.SourceKind.WEAPON), policies());
        assertEquals(LethalEventResolver.Outcome.DUPLICATE, protectedReplay.outcome());
        assertTrue(protectedReplay.idempotent());
        assertTrue(protectedReplay.ownsEvent());

        var deadReplay = state("repeat-death");
        deadReplay.lastLethalEventId = "dead";
        deadReplay.lastLethalOutcome = LethalEventResolver.Outcome.HARD_DEATH.name();
        var deathReplay = resolve(deadReplay,
                request("dead", LethalEventResolver.SourceKind.EXCEPTIONAL), policies());
        assertEquals(LethalEventResolver.Outcome.DUPLICATE, deathReplay.outcome());
        assertTrue(deathReplay.idempotent());
        assertFalse(deathReplay.ownsEvent());

        var olderReplay = state("older-replay");
        olderReplay.condition = PlayerCondition.DOWNED;
        olderReplay.downedDeadlineAt = 10_000L;
        olderReplay.lastLethalEventId = "newer";
        olderReplay.lastLethalOutcome = LethalEventResolver.Outcome.HARD_DEATH.name();
        olderReplay.rememberLethalEvent("older", true);
        var old = resolve(olderReplay,
                request("older", LethalEventResolver.SourceKind.WEAPON), policies());
        assertEquals(LethalEventResolver.Outcome.DUPLICATE, old.outcome());
        assertTrue(old.idempotent());
        assertTrue(old.ownsEvent(), "older protected events must remain owned on replay");
    }

    @Test
    void nonLethalInputProducesNoTerminalOutcome() {
        var request = new LethalEventResolver.Request(
                "not-lethal", "arrow", "actor", LethalEventResolver.SourceKind.WEAPON,
                false, false, LethalEventResolver.ProviderSelection.none());
        assertEquals(LethalEventResolver.Outcome.NOT_LETHAL,
                resolve(state("target"), request, policies()).outcome());
    }

    private static LethalEventResolver.Decision resolve(
            CustodyState state, LethalEventResolver.Request request, StrajaPolicies policies) {
        return LethalEventResolver.resolve(state, request, policies);
    }

    private static LethalEventResolver.Request request(String id, LethalEventResolver.SourceKind kind) {
        return new LethalEventResolver.Request(id, "test", "actor", kind,
                true, false, LethalEventResolver.ProviderSelection.none());
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
        policies.downedDurationSeconds = 60;
        policies.unconsciousCustodyDurationSeconds = 60;
        return policies;
    }
}
