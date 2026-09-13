package com.dwurdy.straja.adapter.in.command;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandSurfaceTest {
    private static final Set<String> ROLEPLAY_ONLY = Set.of(
            "invite", "recruit", "recrute", "quiz",
            "start", "checkpoint", "stop", "special",
            "resign", "demisie", "rejoin",
            "salary", "coins", "food", "kit", "regear", "approve-regear",
            "report", "message", "request", "inbox",
            "mission", "cuffs", "prison", "fine", "complaint", "room", "archive");

    @Test
    void gameplayLanesArePermissionTwoOnlyAtTheCommandBoundary() {
        for (String name : ROLEPLAY_ONLY) {
            assertTrue(StrajaCommands.CommandPolicy.isAdminOnly(name), name + " must be admin-gated");
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
        for (String name : ROLEPLAY_ONLY) {
            assertFalse(publicHelp.contains("/straja " + name), "public help advertises " + name);
        }
        assertTrue(publicHelp.contains("status"));
        assertTrue(publicHelp.contains("rules"));
    }

    @Test
    void npcActionBoundaryIsNotAVisibilityBypassOrTypedGameplayAlias() {
        assertFalse(StrajaCommands.CommandPolicy.isAdminOnly("npc-action"));
        assertFalse(String.join("\n", StrajaCommands.CommandPolicy.helpLines(false))
                .contains("npc-action"));
    }
}
