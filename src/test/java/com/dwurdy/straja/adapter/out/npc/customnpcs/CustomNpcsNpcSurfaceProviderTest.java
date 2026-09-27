package com.dwurdy.straja.adapter.out.npc.customnpcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.application.port.in.NpcProvisioningUseCase;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomNpcsNpcSurfaceProviderTest {
    @Test
    void oldOrClosedNativeGuiCannotPassCallbackGuard() {
        Object firstGui = new Object();
        Object replacementGui = new Object();
        NativePlayerGui playerApi = new NativePlayerGui(firstGui);

        assertTrue(CustomNpcsNpcSurfaceProvider.isCurrentAdminGui(playerApi, firstGui));
        playerApi.current = replacementGui;
        assertFalse(CustomNpcsNpcSurfaceProvider.isCurrentAdminGui(playerApi, firstGui));
        assertTrue(CustomNpcsNpcSurfaceProvider.isCurrentAdminGui(playerApi, replacementGui));
        playerApi.current = null;
        assertFalse(CustomNpcsNpcSurfaceProvider.isCurrentAdminGui(playerApi, replacementGui));
        playerApi.unavailable = true;
        assertFalse(CustomNpcsNpcSurfaceProvider.isCurrentAdminGui(playerApi, replacementGui));
        assertFalse(CustomNpcsNpcSurfaceProvider.isCurrentAdminGui(null, firstGui));
    }

    @Test
    void fallbackSelectorPagesEveryProfileInsideTheVisibleRows() {
        List<Integer> profiles = List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        assertEquals(List.of(0, 1, 2, 3, 4), CustomNpcsNpcSurfaceProvider.page(profiles, 0, 5));
        assertEquals(List.of(5, 6, 7, 8, 9), CustomNpcsNpcSurfaceProvider.page(profiles, 1, 5));
        assertEquals(List.of(10), CustomNpcsNpcSurfaceProvider.page(profiles, 2, 5));
        assertEquals(List.of(10), CustomNpcsNpcSurfaceProvider.page(profiles, 99, 5));
        assertEquals(List.of(0, 1, 2, 3), CustomNpcsNpcSurfaceProvider.page(profiles, -1, 4));
    }

    public static final class NativePlayerGui {
        private Object current;
        private boolean unavailable;

        NativePlayerGui(Object current) {
            this.current = current;
        }

        public Object getCustomGui() {
            if (unavailable) throw new IllegalStateException("native GUI unavailable");
            return current;
        }
    }

    @Test
    void selectorShowsStableIdAndAvailabilityReason() {
        NpcProvisioningUseCase.ProfileOption unavailable = option(false, "missing GUI capability");

        String label = CustomNpcsNpcSurfaceProvider.profileOptionLabel(unavailable, false, false);
        String[] hover = CustomNpcsNpcSurfaceProvider.profileOptionHoverText(unavailable, false);

        assertTrue(label.contains("Jailer"));
        assertTrue(label.contains("straja:jailer"));
        assertTrue(label.contains("unavailable"));
        assertTrue(List.of(hover).contains("Purpose: Custody and prison service"));
        assertTrue(List.of(hover).contains("Availability: Unavailable: missing GUI capability"));
    }

    @Test
    void selectorMarksCurrentProfileAndBlocksAllChoicesDuringRecovery() {
        NpcProvisioningUseCase.ProfileOption available = option(true, "");

        assertTrue(CustomNpcsNpcSurfaceProvider.profileOptionLabel(available, true, false)
                .contains("(current)"));
        assertTrue(CustomNpcsNpcSurfaceProvider.profileOptionLabel(available, false, true)
                .contains("unavailable"));
        assertTrue(List.of(CustomNpcsNpcSurfaceProvider.profileOptionHoverText(available, true))
                .contains("Availability: Unavailable: provider recovery is pending"));
    }

    @Test
    void confirmationListsEveryDialogueQuestAndActionReference() {
        NpcProvisioningUseCase.ProfileOption option = option(true, "");

        assertEquals(List.of(
                "Dialogue nodes (1):",
                "  straja.jailer.intro",
                "Quests (1):",
                "  straja.jailer.custody",
                "Actions (2):",
                "  straja.jailer.status",
                "  straja.jailer.release"),
                CustomNpcsNpcSurfaceProvider.profileReferenceRows(option));
    }

    @Test
    void selectorKeepsSafeUnassignAvailableDuringProviderRecovery() {
        assertEquals("Unassign",
                CustomNpcsNpcSurfaceProvider.unassignButtonLabel(false, ""));
        assertEquals("Cancel pending",
                CustomNpcsNpcSurfaceProvider.unassignButtonLabel(true, "BIND"));
        assertEquals("Cancel pending",
                CustomNpcsNpcSurfaceProvider.unassignButtonLabel(true, "PUBLISH"));
        assertEquals("Retry unassign",
                CustomNpcsNpcSurfaceProvider.unassignButtonLabel(true, "UNBIND"));
    }

    @Test
    void wrappedLabelsStayInsideTheirColumnWidth() {
        assertEquals(List.of("State your purpose at the", "desk."),
                CustomNpcsNpcSurfaceProvider.wrapText("State your purpose at the desk.", 30));
        assertEquals(List.of("short"), CustomNpcsNpcSurfaceProvider.wrapText("short", 30));
        assertEquals(List.of(""), CustomNpcsNpcSurfaceProvider.wrapText("", 30));
        assertEquals(List.of("abcdefghij", "klmnop"),
                CustomNpcsNpcSurfaceProvider.wrapText("abcdefghijklmnop", 10));
        assertEquals(List.of("one", "", "two"),
                CustomNpcsNpcSurfaceProvider.wrapText("one\n\ntwo", 30));
    }

    @Test
    void roleIconItemIdMapsEveryProfileFamilyAndFailsClosed() {
        assertEquals("straja:mission_carnet", GuiTheme.roleIconItemId("straja.reception.admission"));
        assertEquals("straja:archive_stamp", GuiTheme.roleIconItemId("straja.secretary.workflows"));
        assertEquals("straja:fine_book", GuiTheme.roleIconItemId("straja.instructor.admission"));
        assertEquals("straja:baton", GuiTheme.roleIconItemId("straja.armorer.orders"));
        assertEquals("straja:cuffs", GuiTheme.roleIconItemId("straja.jailer.custody"));
        assertEquals("straja:archive_folder", GuiTheme.roleIconItemId("straja.archivist.archive"));
        assertNull(GuiTheme.roleIconItemId("straja.unknown.profile"));
        assertNull(GuiTheme.roleIconItemId(null));
        assertNull(GuiTheme.roleIconItemId("straja.receptionist.fake"));
        assertNull(GuiTheme.roleIconItemId("straja.receptionX.y"));
    }

    @Test
    void iconTextureLandsUnderTheGuiTextureRoot() {
        assertEquals("straja:textures/gui/icons/state_ok.png", GuiTheme.iconTexture("state_ok"));
    }

    @Test
    void roleIconKeyReturnsTaxonomyNamesAndFailsClosed() {
        assertEquals("role_jailer", GuiTheme.roleIconKey("straja.jailer.custody"));
        assertEquals("role_receptionist", GuiTheme.roleIconKey("straja.reception.admission"));
        // Selector rows expose the npcProfileId (colon form) — every shipped
        // id must map, including trainer whose name differs from the role.
        for (String npcId : new String[] {
            "straja:receptionist", "straja:secretary", "straja:trainer",
            "straja:armorer", "straja:jailer", "straja:archivist",
        }) {
            assertTrue(GuiTheme.roleIconKey(npcId) != null,
                    "npcProfileId " + npcId + " has no role icon");
        }
        assertEquals("role_instructor", GuiTheme.roleIconKey("straja:trainer"));
        assertNull(GuiTheme.roleIconKey("straja.unknown.profile"));
        assertNull(GuiTheme.roleIconKey(null));
    }

    @Test
    void everyShippedIconKeyHasAnItemFallback() {
        String[] keys = {
            "role_receptionist", "role_secretary", "role_instructor", "role_armorer",
            "role_jailer", "role_archivist", "admin_selector", "act_assign",
            "act_unassign", "act_status", "act_audit", "act_cleanup", "act_input",
            "quest_active", "quest_new", "quest_done", "state_ok", "state_denied",
            "state_warn",
        };
        for (String key : keys) {
            String item = GuiTheme.iconItemFallback(key);
            assertTrue(item != null && item.startsWith("straja:"),
                    "missing item fallback for " + key);
        }
        assertNull(GuiTheme.iconItemFallback("no_such_icon"));
        assertNull(GuiTheme.iconItemFallback(null));
    }

    @Test
    void textureIconSetMatchesTheShippedTaxonomy() {
        for (String key : new String[] {
            "role_receptionist", "role_secretary", "role_instructor", "role_armorer",
            "role_jailer", "role_archivist", "act_assign", "act_unassign",
            "act_status", "act_audit", "act_cleanup", "act_input", "quest_active",
            "quest_new", "quest_done", "state_ok", "state_denied", "state_warn",
        }) {
            assertTrue(GuiTheme.hasTextureIcon(key), "missing PNG entry for " + key);
        }
        // Keys without a shipped PNG must fall back to the item renderer —
        // a missing texture renders nothing and throws no exception.
        assertFalse(GuiTheme.hasTextureIcon("admin_selector"));
        assertFalse(GuiTheme.hasTextureIcon(null));
        assertEquals("straja:npc_wand", GuiTheme.iconItemFallback("admin_selector"));
    }

    @Test
    void textureIconSetMatchesFilesCommittedUnderGuiIcons() throws Exception {
        java.util.Set<String> onDisk = new java.util.TreeSet<>();
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(
                java.nio.file.Path.of("src/main/resources/assets/straja/textures/gui/icons"))) {
            stream.map(p -> p.getFileName().toString())
                    .map(n -> n.replace(".png", ""))
                    .forEach(onDisk::add);
        }
        for (String key : onDisk) {
            assertTrue(GuiTheme.hasTextureIcon(key),
                    "shipped PNG " + key + " has no TEXTURED_ICONS entry");
        }
        for (String key : new String[] {
            "role_receptionist", "role_secretary", "role_instructor", "role_armorer",
            "role_jailer", "role_archivist", "act_assign", "act_unassign",
            "act_status", "act_audit", "act_cleanup", "act_input", "quest_active",
            "quest_new", "quest_done", "state_ok", "state_denied", "state_warn",
        }) {
            assertTrue(onDisk.contains(key), "TEXTURED_ICONS key " + key + " ships no PNG");
        }
        assertEquals(18, onDisk.size(), "unexpected files under textures/gui/icons");
    }

    @Test
    void questStatesMapToGlyphIconsAndDistinctColors() {
        for (NpcSurfaceSnapshot.QuestState state : NpcSurfaceSnapshot.QuestState.values()) {
            String key = GuiTheme.questIconKey(state);
            assertTrue(key != null && GuiTheme.iconItemFallback(key) != null,
                    "quest state " + state + " lacks a fallback item");
        }
        assertEquals("quest_new", GuiTheme.questIconKey(NpcSurfaceSnapshot.QuestState.AVAILABLE));
        assertEquals("quest_active", GuiTheme.questIconKey(NpcSurfaceSnapshot.QuestState.ACTIVE));
        assertEquals("quest_done", GuiTheme.questIconKey(NpcSurfaceSnapshot.QuestState.COMPLETED));
        assertEquals("state_denied", GuiTheme.questIconKey(NpcSurfaceSnapshot.QuestState.FAILED));
        assertEquals("state_warn", GuiTheme.questIconKey(NpcSurfaceSnapshot.QuestState.LOCKED));
        assertNull(GuiTheme.questIconKey(null));
        // Finished and failed quests must not share the active accent color.
        assertEquals(GuiTheme.COLOR_PAPER_DIM,
                GuiTheme.questLabelColor(NpcSurfaceSnapshot.QuestState.COMPLETED));
        assertEquals(GuiTheme.COLOR_SEAL_BRIGHT,
                GuiTheme.questLabelColor(NpcSurfaceSnapshot.QuestState.FAILED));
    }

    @Test
    void ellipsizeMiddleTruncatesInsideColumnBudget() {
        assertEquals("short", CustomNpcsNpcSurfaceProvider.ellipsize("short", 56));
        assertEquals("abc…wxyz",
                CustomNpcsNpcSurfaceProvider.ellipsize("abcdefghijklmnopqrstuvwxyz", 8));
        assertEquals(56, CustomNpcsNpcSurfaceProvider.ellipsize("x".repeat(80), 56).length());
        assertNull(CustomNpcsNpcSurfaceProvider.ellipsize(null, 10));
        assertEquals("f47ac10b…",
                CustomNpcsNpcSurfaceProvider.shortHostId("f47ac10b-58cc-4372-a567-0e02b2c3d479"));
        assertEquals("npc-1", CustomNpcsNpcSurfaceProvider.shortHostId("npc-1"));
    }

    @Test
    void reflectiveCompatUnboxesWiderPrimitives() {
        assertTrue(CustomNpcsNpcSurfaceProvider.compatible(
                new Class<?>[] {float.class}, new Object[] {1.0f}));
        assertTrue(CustomNpcsNpcSurfaceProvider.compatible(
                new Class<?>[] {double.class}, new Object[] {1.0d}));
        assertTrue(CustomNpcsNpcSurfaceProvider.compatible(
                new Class<?>[] {long.class}, new Object[] {1L}));
        assertTrue(CustomNpcsNpcSurfaceProvider.compatible(
                new Class<?>[] {int.class}, new Object[] {1}));
        assertFalse(CustomNpcsNpcSurfaceProvider.compatible(
                new Class<?>[] {float.class}, new Object[] {"x"}));
        assertFalse(CustomNpcsNpcSurfaceProvider.compatible(
                new Class<?>[] {char.class}, new Object[] {'x'}));
        assertFalse(CustomNpcsNpcSurfaceProvider.compatible(
                new Class<?>[] {long.class}, new Object[] {1}));
        assertFalse(CustomNpcsNpcSurfaceProvider.compatible(
                new Class<?>[] {int.class}, new Object[] {1L}));
    }

    private static NpcProvisioningUseCase.ProfileOption option(boolean enabled, String disabledReason) {
        return new NpcProvisioningUseCase.ProfileOption(
                "straja:jailer",
                "Jailer",
                "Custody and prison service",
                "jailer",
                "hq",
                1,
                enabled,
                disabledReason,
                List.of(NpcContentId.of("straja.jailer.intro")),
                List.of(NpcContentId.of("straja.jailer.custody")),
                List.of(NpcContentId.of("straja.jailer.status"), NpcContentId.of("straja.jailer.release")));
    }
}
