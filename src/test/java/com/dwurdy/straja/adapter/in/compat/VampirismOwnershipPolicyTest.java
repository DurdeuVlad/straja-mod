package com.dwurdy.straja.adapter.in.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.domain.model.LethalEventResolver;
import org.junit.jupiter.api.Test;

class VampirismOwnershipPolicyTest {
    @Test
    void onlyPositiveLevelVampiresAreEligibleWhenDbnoIsInactive() {
        assertTrue(VampirismOwnershipPolicy.classify(true, 1, false).eligibleForDbno());
        assertFalse(VampirismOwnershipPolicy.classify(true, 0, false).eligibleForDbno());
        assertFalse(VampirismOwnershipPolicy.classify(false, 10, false).eligibleForDbno());
    }

    @Test
    void initialDbnoIsAnAllowedLivingDeathHandoff() {
        var state = VampirismOwnershipPolicy.classify(true, 4, false);

        assertEquals(VampirismOwnershipPolicy.IncomingDamageAction.ALLOW_PROVIDER_DBNO_HANDOFF,
                VampirismOwnershipPolicy.incomingDamageAction(state, false));
        assertFalse(VampirismOwnershipPolicy.cancelIncomingDamage(
                LethalEventResolver.Outcome.VAMPIRISM_DBNO));
    }

    @Test
    void activeDbnoPreservesProviderStateAndStakeStillPassesThrough() {
        var state = VampirismOwnershipPolicy.classify(true, 4, true);

        assertEquals(VampirismOwnershipPolicy.IncomingDamageAction.CANCEL_PROVIDER_DBNO_FOLLOW_UP,
                VampirismOwnershipPolicy.incomingDamageAction(state, false));
        assertEquals(VampirismOwnershipPolicy.IncomingDamageAction.PASS_THROUGH,
                VampirismOwnershipPolicy.incomingDamageAction(state, true));
        assertTrue(VampirismOwnershipPolicy.cancelIncomingDamage(
                LethalEventResolver.Outcome.VAMPIRISM_PRESERVE));
    }

}
