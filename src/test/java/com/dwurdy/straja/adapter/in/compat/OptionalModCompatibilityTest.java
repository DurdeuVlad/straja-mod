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

        OptionalModCompatibility.applyFailSafe(profile, policies);

        assertTrue(profile.genericDownedConflict());
        assertTrue(profile.nativeCarryAllowed());
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
}
