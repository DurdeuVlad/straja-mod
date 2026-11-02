package com.dwurdy.straja.adapter.in.npc;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.domain.model.NpcContentId;
import org.junit.jupiter.api.Test;

/**
 * FUN-11 regression pin: every action id the parole surfaces can emit must
 * (a) be a valid {@link NpcContentId} for the desk tile and (b) round-trip
 * through {@link NpcPlayerSurface#parseActionId} so a rendered click actually
 * dispatches to the staleness gate — not just paints.
 */
class ParoleSurfaceWiringTest {

    @Test
    void everyParoleActionIdRoundTrips() {
        var review = NpcPlayerSurface.parseActionId("parole-review:PAR-1");
        assertTrue(review.isPresent());
        assertEquals("parole-review", review.get().operation());
        assertEquals("PAR-1", review.get().recordId());

        for (String id : new String[]{
                "parole-open:PAR-1", "parole-decide-paroled:PAR-1",
                "parole-decide-denied:PAR-1", "parole-decide-conditional:PAR-1",
                "parole-object:PAR-1", "parole-advocate-answer:accept",
                "parole-advocate-answer:decline"}) {
            var ref = NpcPlayerSurface.parseActionId(id);
            assertTrue(ref.isPresent(), id + " must parse");
            assertDoesNotThrow(() -> NpcContentId.of(id),
                    id + " must be a legal desk content id");
        }
    }

    @Test
    void paroleVerbsStayInsideTheParameterizedSpace() {
        // The desk revalidates by operation name — an unregistered parole
        // verb would dispatch with no staleness check.
        for (String op : new String[]{"parole-review", "parole-open",
                "parole-decide-paroled", "parole-decide-denied",
                "parole-decide-conditional", "parole-object",
                "parole-advocate-answer"}) {
            assertTrue(NpcPlayerSurface.parameterizedActionId(op, "PAR-9").isPresent(),
                    op + " must be a registered parameterized op");
        }
        assertTrue(NpcPlayerSurface.parameterizedActionId("parole-free", "PAR-9")
                        .isEmpty(),
                "invented parole verbs must never mint an id");
    }

    @Test
    void malformedParoleIdsStillRefuse() {
        assertTrue(NpcPlayerSurface.parseActionId("parole-review").isEmpty(),
                "no record id");
        assertTrue(NpcPlayerSurface.parseActionId("parole-review:X:Y").isEmpty(),
                "second colon rejected");
        assertTrue(NpcPlayerSurface.parseActionId("parole-review:bad id!").isEmpty(),
                "record id charset enforced");
        assertTrue(NpcPlayerSurface.parseActionId("parole-object:../etc").isEmpty(),
                "path-shaped ids can never reach the docket");
    }
}
