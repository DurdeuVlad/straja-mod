package com.dwurdy.straja.adapter.in.npc;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.domain.model.NpcContentId;
import org.junit.jupiter.api.Test;

/**
 * FUN-1 regression pin: every action id the Secretary delegation console can
 * emit must (a) be a valid {@link NpcContentId} for the provider GUI and (b)
 * round-trip through {@link NpcPlayerSurface#parseActionId} so a click
 * actually dispatches — not just renders.
 */
class DelegationSurfaceWiringTest {

    @Test
    void delegationOpsRoundTripThroughDispatch() {
        var mode = NpcPlayerSurface.parseActionId("delegation-mode:JOIN_PETITION");
        assertTrue(mode.isPresent());
        assertEquals("delegation-mode", mode.get().operation());
        assertEquals("JOIN_PETITION", mode.get().recordId());

        assertTrue(NpcPlayerSurface.parseActionId("delegation-window:AUDIENCE_REQUEST").isPresent());
        assertTrue(NpcPlayerSurface.parseActionId("delegation-revoke:SO-JOIN_PETITION-1").isPresent());
    }

    @Test
    void orderIssueOpsAreParameterizedByPrefix() {
        // The queue name rides inside the operation; lowercase keeps it a
        // valid content id (the namespace side forbids uppercase).
        var ref = NpcPlayerSurface.parseActionId("delegation-order-join_petition:FIERAR");
        assertTrue(ref.isPresent());
        assertEquals("delegation-order-join_petition", ref.get().operation());
        assertEquals("FIERAR", ref.get().recordId());
        assertDoesNotThrow(() -> NpcContentId.of("delegation-order-join_petition:FIERAR"));
    }

    @Test
    void malformedDelegationIdsStillRefuse() {
        assertTrue(NpcPlayerSurface.parseActionId("delegation-mode").isEmpty(),
                "no record id");
        assertTrue(NpcPlayerSurface.parseActionId("delegation-mode:X:Y").isEmpty(),
                "second colon rejected");
        assertTrue(NpcPlayerSurface.parseActionId("delegation-unknown:X").isEmpty(),
                "unregistered op");
        assertTrue(NpcPlayerSurface.parseActionId("delegation-mode:bad id!").isEmpty(),
                "record id charset enforced");
    }
}
