package com.dwurdy.straja.adapter.in.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Source-level pins mirroring {@code ArchitectureBoundaryTest}: the desk view
 * model stays Minecraft-free, the menu uses the vanilla chest type, clicks
 * never mint chat tokens, and the chat fallback remains wired in NpcRoles.
 */
class NpcDeskMenuSurfaceTest {
    private static final Path SRC = Path.of("src/main/java/com/dwurdy/straja");
    private static final Path MENU = SRC.resolve("adapter/in/menu");
    private static final List<String> FORBIDDEN = List.of(
            "net.minecraft.", "net.neoforged.", "com.mojang.");

    /** View-model files that must compile against zero platform imports. */
    private static final List<String> PURE_FILES = List.of(
            "DeskIcon.java", "DeskButton.java", "DeskPage.java",
            "DeskPageSource.java", "DeskLayout.java", "DeskNavigation.java",
            "DeskActionStyle.java", "DeskSections.java");

    @Test
    void pureViewModelStaysFreeOfPlatformImports() throws IOException {
        var violations = new java.util.ArrayList<String>();
        for (String name : PURE_FILES) {
            Path file = MENU.resolve(name);
            assertTrue(Files.exists(file), name + " must exist");
            int line = 0;
            for (String text : Files.readAllLines(file)) {
                line++;
                if (FORBIDDEN.stream().anyMatch(text::contains)) {
                    violations.add(name + ":" + line + " -> " + text.trim());
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "pure desk model imports platform classes:\n"
                        + String.join("\n", violations));
    }

    @Test
    void deskMenuIsAVanillaChestMenuThatNeverMovesItems() throws IOException {
        String menu = Files.readString(MENU.resolve("DeskMenu.java"));
        assertTrue(menu.contains("extends ChestMenu"),
                "the desk surface must be a vanilla ChestMenu");
        assertTrue(menu.contains("MenuType.GENERIC_9x"),
                "only MenuType.GENERIC_9x* renders on unmodded clients");
        assertFalse(menu.contains("super.clicked("),
                "clicked must fully swallow so no item ever moves");
        assertTrue(menu.contains("ItemStack.EMPTY"),
                "quickMoveStack must stay empty");
    }

    @Test
    void menuClicksNeverMintChatTokens() throws IOException {
        try (var files = Files.walk(MENU)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(file);
                assertFalse(src.contains("issueActionToken")
                                || src.contains("issueActionCommand"),
                        file.getFileName() + " must not mint chat tokens — "
                                + "menu clicks dispatch performAction directly");
            }
        }
    }

    @Test
    void interactRoutesThroughTheDeskMenuAndKeepsChatFallback() throws IOException {
        String roles = Files.readString(SRC.resolve("adapter/in/npc/NpcRoles.java"));
        assertTrue(roles.contains("DeskMenuService.tryOpen"),
                "interact must offer the desk menu first");
        assertTrue(roles.contains("sendGuidance(player, ordered.surface())"),
                "the chat surface stays the fallback when the menu declines");
    }

    @Test
    void configFlagIsDeclared() throws IOException {
        String config = Files.readString(SRC.resolve("config/StrajaServerConfig.java"));
        assertTrue(config.contains("NPC_MENUS_ENABLED"));
        assertTrue(config.contains("npcMenus"));
    }

    /**
     * Issue-4 completes the desk rollout: all eight player-facing routes —
     * Receptionist, Secretary, Instructor (TRAINER plus its RECRUITER
     * alias), Jailer, Inspector, Archivist and Armorer — open the chest
     * menu; only UNKNOWN stays on the chat fallback.
     */
    @Test
    void enabledRoutesCoverExactlyTheShippedDesks() {
        assertEquals(java.util.Set.of(
                        com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface.RoleRoute.RECEPTIONIST,
                        com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface.RoleRoute.SECRETARY,
                        com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface.RoleRoute.TRAINER,
                        com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface.RoleRoute.RECRUITER,
                        com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface.RoleRoute.JAILER,
                        com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface.RoleRoute.INSPECTOR,
                        com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface.RoleRoute.ARCHIVIST,
                        com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface.RoleRoute.ARMORER),
                DeskMenuService.ENABLED_ROUTES);
        // The recruiter role id folds into the Instructor desk through routing.
        assertTrue(DeskMenuService.ENABLED_ROUTES.contains(
                com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface.routeFor("recruiter")));
        assertTrue(DeskMenuService.ENABLED_ROUTES.contains(
                com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface.routeFor("secretary")),
                "Secretary opens the sectioned desk from Issue 4");
        assertFalse(DeskMenuService.ENABLED_ROUTES.contains(
                com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface.RoleRoute.UNKNOWN),
                "an unconfigured NPC keeps the chat fallback");
    }
}
