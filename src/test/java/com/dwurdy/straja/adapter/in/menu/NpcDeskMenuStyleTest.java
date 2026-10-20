package com.dwurdy.straja.adapter.in.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Icon/lore defaults: every action id resolves to a non-blank item id with
 * bounded Romanian lore, the confirm gate covers only immediate mutations,
 * and the unknown path always degrades to paper.
 */
class NpcDeskMenuStyleTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "application-submit", "trades-list", "trades-promote", "rules",
            "guard-status", "fine-list", "identity-request", "identity-list",
            "v2-personnel-status", "v2-promotion-status", "v2-document-status",
            "incident-report", "reputation-self", "room-status", "room-release",
            "faction-declare", "complaint-submit", "fine-pay:F-1",
            "fine-refuse:F-2", "fine-appeal:F-3", "complaint-confirm:C-1",
            "complaint-withdraw:C-2", "join-read:JR-1", "join-review:JR-2",
            "faq:root_receptionist", "faq:menu_entry_receptionist",
            "faq:answer_entry_join_receptionist", "not-a-real-action", ""})
    void everyActionIdResolvesAnItemAndLore(String actionId) {
        var icon = DeskActionStyle.icon("Eticheta", actionId);
        assertFalse(icon.itemId().isBlank(), "item id must never be blank");
        assertTrue(icon.itemId().contains(":"), "item id must be a registry id");
        assertTrue(icon.lore().size() <= 3,
                "per-action lore stays at 1–3 lines: " + icon.lore());
        assertTrue(icon.lore().stream().allMatch(line -> !line.isBlank()));
    }

    @Test
    void unknownActionFallsBackToPaperAndGenericLore() {
        var icon = DeskActionStyle.icon("X", "totally-unknown-action");
        assertEquals("minecraft:paper", icon.itemId());
        assertTrue(icon.lore().contains("Se execută imediat."));
    }

    @Test
    void formActionsAnnounceTheForm() {
        for (String id : new String[]{"incident-report", "complaint-submit",
                "fine-appeal:F-1", "join-review:JR-1", "trades-enroll:FIERAR",
                "complaint-withdraw:C-1"}) {
            assertTrue(DeskActionStyle.lore(id).contains("Deschide formularul."),
                    id + " must announce the native form");
        }
    }

    @Test
    void chatActionsAnnounceChatOutput() {
        for (String id : new String[]{"rules", "guard-status", "fine-list",
                "trades-list", "archive-folder-read:D-1", "join-read:JR-1"}) {
            assertTrue(DeskActionStyle.lore(id).stream()
                            .anyMatch(line -> line.contains("chat")),
                    id + " must say the result goes to chat");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"fine-pay:F-1", "fine-refuse:F-2",
            "complaint-confirm:C-1", "room-release"})
    void immediateMutationsRequireTheConfirmPage(String actionId) {
        assertTrue(DeskActionStyle.needsConfirm(actionId));
        assertTrue(DeskActionStyle.lore(actionId).contains("Cere confirmare."));
        assertFalse(DeskActionStyle.consequence(actionId).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"complaint-withdraw:C-1", "fine-appeal:F-1",
            "join-review:JR-1", "rules", "application-submit", "guard-status"})
    void formAndReadOnlyActionsSkipTheConfirmPage(String actionId) {
        assertFalse(DeskActionStyle.needsConfirm(actionId),
                actionId + " must not be confirm-gated");
    }

    @Test
    void roleHeaderUsesTheGuiThemeFallbackChain() {
        var reception = DeskActionStyle.header("receptionist", "Recepție",
                "Depui aici cererea de admitere, alegi regulamentul Străjii, "
                        + "verifici situația, amenzile, camera sau facțiunea nativă.");
        assertEquals("straja:mission_carnet", reception.itemId());
        assertEquals("Recepție", reception.name());
        assertTrue(reception.lore().size() >= 3,
                "guidance wraps into lore lines: " + reception.lore());
        assertTrue(reception.lore().stream().allMatch(
                line -> line.length() <= DeskLayout.LORE_WRAP));

        var unknown = DeskActionStyle.header("not-a-role", "Straja", "");
        assertEquals("minecraft:paper", unknown.itemId());
        assertTrue(unknown.lore().contains("Alege o acțiune."));
    }

    @Test
    void faqButtonsAnnounceTheirKind() {
        assertTrue(DeskActionStyle.lore("faq:menu_entry_receptionist")
                .contains("Deschide tema."));
        assertTrue(DeskActionStyle.lore("faq:answer_entry_join_receptionist")
                .contains("Afișează răspunsul."));
        assertEquals("minecraft:writable_book",
                DeskActionStyle.itemFor("faq:menu_entry_receptionist"));
    }
}
