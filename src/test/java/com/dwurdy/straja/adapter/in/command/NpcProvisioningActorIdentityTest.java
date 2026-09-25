package com.dwurdy.straja.adapter.in.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcProvisioningActorIdentityTest {
    @Test
    void playerAuditActorUsesStableUuid() {
        String playerId = UUID.randomUUID().toString();

        assertEquals(playerId, NpcProvisioningActorIdentity.fromSource("PlayerName", playerId));
    }

    @Test
    void remoteSourceStaysDistinctAndServerConsoleGetsConsoleIdentity() {
        assertEquals("Rcon", NpcProvisioningActorIdentity.fromSource("Rcon", ""));
        assertEquals("console", NpcProvisioningActorIdentity.fromSource("Server", ""));
        assertThrows(IllegalArgumentException.class,
                () -> NpcProvisioningActorIdentity.fromSource(" ", ""));
        assertThrows(IllegalArgumentException.class,
                () -> NpcProvisioningActorIdentity.fromSource("player", "not-a-uuid"));
    }
}
