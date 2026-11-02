package com.dwurdy.straja.adapter.in.npc;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * INST-2 regression pin: the frisk consent links must round-trip through
 * {@link NpcPlayerSurface#parseActionId} — an unregistered frisk verb dies
 * silently at the parse gate and every Acceptă/Refuză click "expires".
 */
class FriskSurfaceWiringTest {

    @Test
    void friskActionIdsRoundTrip() {
        for (String id : new String[]{"frisk-accept:F12", "frisk-decline:F12"}) {
            var ref = NpcPlayerSurface.parseActionId(id);
            assertTrue(ref.isPresent(), id + " must parse");
        }
        var accept = NpcPlayerSurface.parseActionId("frisk-accept:F12").orElseThrow();
        assertEquals("frisk-accept", accept.operation());
        assertEquals("F12", accept.recordId());
    }

    @Test
    void friskVerbsStayInsideTheParameterizedSpace() {
        for (String op : new String[]{"frisk-accept", "frisk-decline"}) {
            assertTrue(NpcPlayerSurface.parameterizedActionId(op, "F9").isPresent(),
                    op + " must be a registered parameterized op");
        }
        assertTrue(NpcPlayerSurface.parameterizedActionId("frisk-force", "F9")
                        .isEmpty(),
                "invented frisk verbs must never mint an id");
    }

    @Test
    void malformedFriskIdsStillRefuse() {
        assertTrue(NpcPlayerSurface.parseActionId("frisk-accept").isEmpty());
        assertTrue(NpcPlayerSurface.parseActionId("frisk-accept:F1:F2").isEmpty());
        assertTrue(NpcPlayerSurface.parseActionId("frisk-accept:bad id!").isEmpty());
        assertTrue(NpcPlayerSurface.parseActionId("frisk-decline:../etc").isEmpty());
    }
}
