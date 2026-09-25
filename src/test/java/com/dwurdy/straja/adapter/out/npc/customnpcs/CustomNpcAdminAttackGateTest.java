package com.dwurdy.straja.adapter.out.npc.customnpcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomNpcAdminAttackGateTest {
    private static final UUID OPERATOR = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private static final UUID HOST = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final String CUSTOM_NPC = "customnpcs:customnpc";

    @Test
    void authorizedWandClaimsExactServerTargetOncePerGesture() {
        var gate = new CustomNpcAdminAttackGate();
        var first = gate.decide(OPERATOR, HOST, CUSTOM_NPC, true, true, true, 100);

        assertEquals(CustomNpcAdminAttackGate.Action.OPEN, first.action());
        assertEquals(HOST, first.hostId());
        assertEquals(CustomNpcAdminAttackGate.Action.CANCEL,
                gate.decide(OPERATOR, HOST, CUSTOM_NPC, true, true, true, 100).action());
        assertEquals(CustomNpcAdminAttackGate.Action.CANCEL,
                gate.decide(OPERATOR, HOST, CUSTOM_NPC, true, true, true, 101).action());
        assertEquals(CustomNpcAdminAttackGate.Action.OPEN,
                gate.decide(OPERATOR, HOST, CUSTOM_NPC, true, true, true, 102).action());
    }

    @Test
    void nonAdminAndWrongToolCannotClaimOrOpen() {
        var gate = new CustomNpcAdminAttackGate();
        var nonAdmin = gate.decide(OPERATOR, HOST, CUSTOM_NPC, true, false, true, 100);
        assertEquals(CustomNpcAdminAttackGate.Action.PASS, nonAdmin.action());
        assertNull(nonAdmin.hostId());
        assertEquals(CustomNpcAdminAttackGate.Action.PASS,
                gate.decide(OPERATOR, HOST, CUSTOM_NPC, true, false, true, 101).action());
    }

    @Test
    void onlyExactCustomNpcTypeIsClaimed() {
        var gate = new CustomNpcAdminAttackGate();
        for (String type : new String[] {"minecraft:villager", "customnpcs:chair",
                "customnpcs:customnpc_projectile"}) {
            assertEquals(CustomNpcAdminAttackGate.Action.PASS,
                    gate.decide(OPERATOR, HOST, type, true, true, true, 100).action());
        }
        assertEquals(CustomNpcAdminAttackGate.Action.PASS,
                gate.decide(OPERATOR, HOST, null, true, true, true, 100).action());
    }

    @Test
    void deletedOrUnloadedTargetAndUnavailableProviderNeverOpen() {
        var gate = new CustomNpcAdminAttackGate();
        assertEquals(CustomNpcAdminAttackGate.Action.CANCEL,
                gate.decide(OPERATOR, HOST, CUSTOM_NPC, false, true, true, 100).action());
        assertEquals(CustomNpcAdminAttackGate.Action.CANCEL,
                gate.decide(OPERATOR, HOST, CUSTOM_NPC, true, true, false, 101).action());
        assertEquals(CustomNpcAdminAttackGate.Action.OPEN,
                gate.decide(OPERATOR, HOST, CUSTOM_NPC, true, true, true, 102).action());
    }

    @Test
    void logoutClearsDuplicateGuard() {
        var gate = new CustomNpcAdminAttackGate();
        gate.decide(OPERATOR, HOST, CUSTOM_NPC, true, true, true, 100);
        gate.forget(OPERATOR);
        assertEquals(CustomNpcAdminAttackGate.Action.OPEN,
                gate.decide(OPERATOR, HOST, CUSTOM_NPC, true, true, true, 100).action());
    }
}
