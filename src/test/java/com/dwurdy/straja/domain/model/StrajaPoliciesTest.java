package com.dwurdy.straja.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Jailer damage truth table backing the adapter-side damage gate. */
class StrajaPoliciesTest {

    @Test
    void jailerDamageAllowedOnlyBlocksOnDutyGuards() {
        StrajaPolicies policies = new StrajaPolicies();
        policies.jailerGuardImmunity = false;
        assertTrue(policies.jailerDamageAllowed(false));
        assertTrue(policies.jailerDamageAllowed(true));
        policies.jailerGuardImmunity = true;
        assertTrue(policies.jailerDamageAllowed(false), "civilians always damage the jailer");
        assertFalse(policies.jailerDamageAllowed(true), "on-duty guards are immune-blocked");
    }

    @Test
    void securityDefaultsStayProductionSafe() {
        // These fields are wired to server config; the class defaults are the
        // last line of defence when a config value is absent, so they must
        // stay restrictive.
        StrajaPolicies p = new StrajaPolicies();
        assertTrue(p.debugLocalOnly, "debug must remain local-only by default");
        assertTrue(p.requireDebugDisabledOutsideLocal);
        assertTrue(p.requireCommissionerUuidOutsideLocal);
        assertTrue(p.requireRealCoinProviderOutsideLocal);
        assertTrue(p.missionQuickCreateLocalOnly);
        assertFalse(p.testCommandsEnabled, "test commands must default to off");
    }

    @Test
    void custodyDefaultsDescribeEveryControlAndRecoveryBoundary() {
        StrajaPolicies p = new StrajaPolicies();
        assertEquals(60, p.downedDurationSeconds);
        assertEquals(120, p.carryTransportDeadlineSeconds);
        assertEquals(30, p.resuscitationTimeoutSeconds);
        assertEquals(100, p.resuscitationProgressPercent);
        assertEquals(120, p.unconsciousCustodyDurationSeconds);
        assertEquals(300, p.jailDeliveryDeadlineSeconds);
        assertTrue(p.jailAutomaticRevivalEnabled);
        assertEquals("KILL", p.secondWeaponHitBehavior);
        assertEquals("PRESERVE_DOWNED", p.ordinaryDamageBehavior);
        assertEquals("PRESERVE_DOWNED", p.nonWeaponDamageBehavior);
        assertEquals("KILL", p.exceptionalDamageBehavior);
        assertTrue(p.criminalRopeEnabled);
        assertTrue(p.criminalCutterEnabled);
        assertTrue(p.policeCuffsEnabled);
        assertTrue(p.universalKeyEnabled);
        assertTrue(p.blackSackApplicationEnabled);
        assertTrue(p.blackSackRemovalEnabled);
        assertEquals("RETAIN", p.logoutRecoveryBehavior);
        assertEquals("RETAIN", p.restartRecoveryBehavior);
        assertEquals("CLEAR_ALL", p.deathRecoveryBehavior);
        assertEquals("RELEASE_TRANSPORT", p.dimensionChangeRecoveryBehavior);
        assertEquals("RELEASE_TRANSPORT", p.missingDestinationRecoveryBehavior);
        assertEquals(DamageBehavior.KILL,
                p.damageBehavior(DamageCategory.SECOND_WEAPON_HIT));
        assertEquals(DamageBehavior.KILL, p.damageBehavior(DamageCategory.EXCEPTIONAL));
        assertEquals(RecoveryBehavior.CLEAR_ALL, p.recoveryBehavior(RecoveryEvent.DEATH));
        p.deathRecoveryBehavior = "not-a-policy";
        assertEquals(RecoveryBehavior.CLEAR_ALL, p.recoveryBehavior(RecoveryEvent.DEATH),
                "invalid direct values fail closed to the safe death default");
    }

    // ------------------------------------------------------------ config encoding

    @Test
    void intMapRoundTripsAndSkipsMalformedEntries() {
        var defaults = new StrajaPolicies();
        var parsed = StrajaPolicies.parseIntMap(StrajaPolicies.formatIntMap(defaults.salaryPerHour));
        assertEquals(defaults.salaryPerHour, parsed);

        var tolerant = StrajaPolicies.parseIntMap(java.util.List.of(
                "1=20", "garbage", "=5", "3=", "x=y", "4=40"));
        assertEquals(java.util.Map.of(1, 20, 4, 40), tolerant);
    }

    @Test
    void kitsRoundTripPreservesRanksAndCounts() {
        var defaults = new StrajaPolicies();
        var parsed = StrajaPolicies.parseKits(StrajaPolicies.formatKits(defaults.kits));
        assertEquals(defaults.kits, parsed);
        assertTrue(parsed.get(2).stream().anyMatch(i -> i.id().equals("minecraft:iron_sword")));
    }

    @Test
    void equipmentRoundTripPreservesLabelsWithSpaces() {
        var defaults = new StrajaPolicies();
        var parsed = StrajaPolicies.parseEquipment(StrajaPolicies.formatEquipment(defaults.serviceEquipment));
        assertEquals(defaults.serviceEquipment, parsed);
        var cuffs = parsed.get(2).stream().filter(e -> e.key().equals("cuffs")).findFirst().orElseThrow();
        assertEquals("cătușe de serviciu", cuffs.label());
        assertEquals(100, cuffs.replacementCost());
    }

    @Test
    void quizRoundTripPreservesAllAnswers() {
        var defaults = new StrajaPolicies();
        var parsed = StrajaPolicies.parseQuiz(StrajaPolicies.formatQuiz(defaults.quiz));
        assertEquals(defaults.quiz.size(), parsed.size());
        var oath = parsed.stream().filter(q -> q.id().equals("juramant")).findFirst().orElseThrow();
        assertTrue(oath.accepts("disciplina"));
        assertTrue(oath.accepts("DISCIPLINA"), "answers normalize case");
        assertFalse(oath.accepts("onoare"));

        // malformed entries are skipped, not fatal
        var tolerant = StrajaPolicies.parseQuiz(java.util.List.of(
                "q1|0|intrebare?|da;yes", "broken", "|0||noanswer", "q2|x|q|a"));
        assertEquals(1, tolerant.size());
        assertEquals(java.util.List.of("da", "yes"), tolerant.get(0).answers());
    }

    @Test
    void formattedDefaultsAreLossless() {
        // The TOML defaults are serialized through the same format helpers, so
        // a fresh server must regenerate policies identical to the compiled ones.
        var defaults = new StrajaPolicies();
        assertEquals(defaults.promotionServiceBlocks,
                StrajaPolicies.parseIntMap(StrajaPolicies.formatIntMap(defaults.promotionServiceBlocks)));
        assertEquals(defaults.regearCost,
                StrajaPolicies.parseIntMap(StrajaPolicies.formatIntMap(defaults.regearCost)));
        assertEquals(defaults.sentenceDaysByAmount,
                StrajaPolicies.parseIntMap(StrajaPolicies.formatIntMap(defaults.sentenceDaysByAmount)));
        assertEquals(defaults.complaintRewardBySeverity,
                StrajaPolicies.parseIntMap(StrajaPolicies.formatIntMap(defaults.complaintRewardBySeverity)));
        assertEquals(defaults.trainingQuiz,
                StrajaPolicies.parseQuiz(StrajaPolicies.formatQuiz(defaults.trainingQuiz)));
    }
}
