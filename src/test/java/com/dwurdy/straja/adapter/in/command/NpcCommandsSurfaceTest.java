package com.dwurdy.straja.adapter.in.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NpcCommandsSurfaceTest {
    private static final Path SOURCE =
            Path.of("src/main/java/com/dwurdy/straja/adapter/in/command/NpcCommands.java");

    @Test
    void commandPassesAuthenticatedSourceIdentityIntoProvisioningAudit() throws IOException {
        String src = Files.readString(SOURCE);
        assertTrue(src.contains("source.getEntity() instanceof net.minecraft.server.level.ServerPlayer player"));
        assertTrue(src.contains("player.getUUID().toString()"));
        assertTrue(src.contains("NpcProvisioningActorIdentity.fromSource(source.getTextName(), playerUuid)"));
        assertTrue(src.contains("bindCustomNpc(host, role, station, actorId)"));
    }
}
