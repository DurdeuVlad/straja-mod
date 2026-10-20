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

    /**
     * Issue-5: admin-tool surfaces open the desk first and keep the tokened
     * chat fallback — the same primary/fallback split the role desks use.
     */
    @Test
    void toolMenusOpenTheDeskBeforeFallingBackToChat() throws IOException {
        String roles = Files.readString(SRC.resolve("adapter/in/npc/NpcRoles.java"));
        assertTrue(roles.contains("DeskMenuService.openToolMenu(player, menu)"),
                "sendToolMenu must offer the desk list first");
        assertTrue(roles.contains("tryOpenToolPrompt"),
                "sendToolPrompt must offer the one-row desk list first");
        assertTrue(roles.contains("actionId.startsWith(\"tool-\")"),
                "only tool-* prompts may leave the chat surface — trades, "
                        + "dispatch and V2 offers keep their chat buttons");
        assertTrue(roles.contains("toolButton(player"),
                "the chat fallback still mints tool tokens when the desk declines");
    }

    /**
     * Issue-5: the wand's remove row must ride the in-GUI DA/NU page — the
     * chat path keeps its own two-step confirm, so the desk rewrites the row
     * to the confirm op and DeskActionStyle gates it.
     */
    @Test
    void toolRemovalRidesTheInGuiConfirmPage() throws IOException {
        String service = Files.readString(MENU.resolve("DeskMenuService.java"));
        assertTrue(service.contains("tool-npc-remove-confirm:"),
                "the desk rewrites tool-npc-remove to its confirm op");
        String style = Files.readString(MENU.resolve("DeskActionStyle.java"));
        assertTrue(style.contains("\"tool-npc-remove-confirm:\""),
                "the confirm op must be DA/NU-gated in the desk");
    }

    /**
     * Issue-5: a desk-provider-bound surface click mints and submits a
     * provider token through the bridge — never performAction, never a
     * 15-second chat token.
     */
    @Test
    void boundSurfaceClicksSubmitProviderTokens() throws IOException {
        String session = Files.readString(MENU.resolve("DeskMenuSession.java"));
        assertTrue(session.contains("SURFACE_PREFIX"),
                "surface rows must carry the desk:surface: nav prefix");
        assertTrue(session.contains("surfaceBridge.submit"),
                "surface clicks must submit through the provider bridge");
        assertFalse(session.contains("issueToolActionToken")
                        || session.contains("issueActionToken"),
                "the desk must not mint chat tokens for bound surfaces");
        String service = Files.readString(MENU.resolve("DeskMenuService.java"));
        assertTrue(service.contains("openBoundSurface"),
                "the bound-surface entry point lives on the menu service");
    }

    /**
     * Issue-5 pins the parity contract: a registry-bound foreign entity
     * (villager or other mod NPC) enters the same NpcRoles.interact route a
     * native StrajaNpcEntity does, and a desk-provider-bound host claims
     * the interact packet before the generic binding check swallows it.
     */
    @Test
    void foreignAndBoundNpcsReachTheSameDeskRoute() throws IOException {
        String events = Files.readString(SRC.resolve("adapter/in/event/StrajaEvents.java"));
        assertTrue(events.contains("NpcRoles.interact(registration.role()"),
                "registry-bound foreign NPCs must route through NpcRoles.interact");
        assertTrue(events.contains("deskProviderOwns"),
                "desk-provider bindings must claim the interact packet");
        assertTrue(events.contains("openBoundDeskSurface"),
                "the desk provider opens the chest menu for its bound hosts");
    }
}
