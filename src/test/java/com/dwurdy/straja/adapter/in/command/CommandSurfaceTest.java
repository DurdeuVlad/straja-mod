package com.dwurdy.straja.adapter.in.command;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandSurfaceTest {
    private static final Set<String> ROLEPLAY_ONLY = Set.of(
            "invite", "recruit", "recrute", "quiz",
            "start", "checkpoint", "special",
            "resign", "demisie", "rejoin",
            "salary", "coins", "food", "kit", "merit",
            "report", "message", "request", "inbox",
            "mission", "cuffs", "prison", "fine", "complaint", "room", "archive", "identity");

    @Test
    void gameplayLanesArePermissionThreeOnlyAtTheCommandBoundary() {
        for (String name : ROLEPLAY_ONLY) {
            assertTrue(StrajaCommands.CommandPolicy.isAdminOnly(name), name + " must be admin-gated");
            assertEquals(3, StrajaCommands.CommandPolicy.permissionLevel(name),
                    name + " must require OP 3");
        }
    }

    @Test
    void typedSetupAdminDiagnosticsMigrationTestAndNpcOperationsRemainAdminTyped() {
        for (String name : Set.of(
                "promote", "demote", "suspend", "fire", "reinstate",
                "set-checkpoint", "set-mission-time", "set-location", "setup",
                "migrate", "backup", "npc", "debug", "test")) {
            assertTrue(StrajaCommands.CommandPolicy.isAdminOnly(name), name + " must remain admin-typed");
        }
        for (String name : Set.of(
                "set-checkpoint", "set-mission-time", "set-location", "setup",
                "policy", "migrate", "npc", "debug", "test")) {
            assertEquals(4, StrajaCommands.CommandPolicy.permissionLevel(name),
                    name + " must require OP 4");
        }
        assertEquals(3, StrajaCommands.CommandPolicy.permissionLevel("help"));
        assertEquals(3, StrajaCommands.CommandPolicy.permissionLevel("identity"));
        assertEquals(4, StrajaCommands.CommandPolicy.permissionLevel("checkpoint add"));
        assertEquals(4, StrajaCommands.CommandPolicy.permissionLevel("checkpoint remove"));
    }

    @Test
    void backupIsAnAdminCommandAndPublicHelpDoesNotAdvertiseIt() {
        assertFalse(StrajaCommands.CommandPolicy.isAdminOnly("status"));
        assertFalse(StrajaCommands.CommandPolicy.isAdminOnly("rules"));
        assertTrue(StrajaCommands.CommandPolicy.isAdminOnly("backup"));
        String publicHelp = String.join("\n", StrajaCommands.CommandPolicy.helpLines(false));
        String adminHelp = String.join("\n", StrajaCommands.CommandPolicy.helpLines(true));
        assertFalse(publicHelp.contains("backup"));
        assertTrue(adminHelp.contains("backup"));
        assertTrue(adminHelp.contains("OP 4"));
        for (String name : ROLEPLAY_ONLY) {
            assertFalse(publicHelp.contains("/straja " + name), "public help advertises " + name);
        }
        assertTrue(publicHelp.contains("status"));
        assertTrue(publicHelp.contains("rules"));
    }

    @Test
    void stopIsPlayerFacingWithSecretaryGatingInsideTheUseCase() {
        // §7: free-duty ranks stop at will; patrol guards are directed back to
        // the Secretary inside GuardService.stopDuty — the command itself is open.
        assertFalse(StrajaCommands.CommandPolicy.isAdminOnly("stop"));
    }

    @Test
    void npcActionBoundaryIsNotAVisibilityBypassOrTypedGameplayAlias() {
        assertFalse(StrajaCommands.CommandPolicy.isAdminOnly("npc-action"));
        assertFalse(String.join("\n", StrajaCommands.CommandPolicy.helpLines(false))
                .contains("npc-action"));
    }
}
