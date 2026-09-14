package com.dwurdy.straja.adapter.in.compat;

import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OptionalModCompatibilityTest {
    @Test
    void noOptionalModsKeepNativeOwnership() {
        var profile = OptionalModCompatibility.fromLoadedIds(Set.of());
        var policies = new StrajaPolicies();

        OptionalModCompatibility.applyFailSafe(profile, policies);

        assertFalse(profile.hasOptionalMods());
        assertTrue(profile.nativeCarryAllowed());
        assertTrue(policies.downedEnabled);
    }

    @Test
    void incapacitatedAndVampirismDisableOnlyStrajaDownedOwnership() {
        var profile = OptionalModCompatibility.fromLoadedIds(
                Set.of(OptionalModCompatibility.INCAPACITATED, OptionalModCompatibility.VAMPIRISM));
        var policies = new StrajaPolicies();

        OptionalModCompatibility.applyFailSafe(profile, policies, true);

        assertTrue(profile.incapacitated());
        assertTrue(profile.vampirism());
        assertTrue(profile.nativeCarryAllowed());
        assertFalse(policies.downedEnabled);
    }

    @Test
    void readyVampirismProviderKeepsStrajaDownedForNonVampires() {
        var profile = OptionalModCompatibility.fromLoadedIds(
                Set.of(OptionalModCompatibility.VAMPIRISM));
        var policies = new StrajaPolicies();

        OptionalModCompatibility.applyFailSafe(profile, policies, true);

        assertFalse(profile.genericDownedConflict());
        assertTrue(policies.downedEnabled);
    }

    @Test
    void unavailableVampirismProviderFailsClosed() {
        var profile = OptionalModCompatibility.fromLoadedIds(
                Set.of(OptionalModCompatibility.VAMPIRISM));
        var policies = new StrajaPolicies();

        OptionalModCompatibility.applyFailSafe(profile, policies, false);

        assertFalse(policies.downedEnabled);
    }

    @Test
    void piggybackDisablesNativeCarryTriggerWithoutDisablingDownedPolicy() {
        var profile = OptionalModCompatibility.fromLoadedIds(Set.of(OptionalModCompatibility.PIGGYBACK));
        var policies = new StrajaPolicies();

        OptionalModCompatibility.applyFailSafe(profile, policies);

        assertFalse(profile.nativeCarryAllowed());
        assertTrue(policies.downedEnabled);
    }

    @Test
    void unrelatedModIdsAreIgnored() {
        var profile = OptionalModCompatibility.fromLoadedIds(Set.of("some_other_mod"));

        assertFalse(profile.hasOptionalMods());
        assertTrue(profile.loadedIds().isEmpty());
    }

    @Test
    void absentVampirismDoesNotAttemptToLoadItsProviderClass() {
        assertTrue(OptionalModCompatibility.loadProvider(
                OptionalModCompatibility.fromLoadedIds(Set.of())).isEmpty());
    }
}
