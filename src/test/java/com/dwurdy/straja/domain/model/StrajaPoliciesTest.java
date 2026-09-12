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
}
