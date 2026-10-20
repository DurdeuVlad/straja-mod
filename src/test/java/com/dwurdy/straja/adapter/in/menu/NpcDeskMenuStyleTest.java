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
            "complaint-confirm:C-1", "room-release",
            "custody-accept:C7", "custody-refuse:C7", "custody-release:uuid-1"})
    void immediateMutationsRequireTheConfirmPage(String actionId) {
        assertTrue(DeskActionStyle.needsConfirm(actionId));
        assertTrue(DeskActionStyle.lore(actionId).contains("Cere confirmare."));
        assertFalse(DeskActionStyle.consequence(actionId).isEmpty());
    }

    /**
     * Custody accept/refuse are immediate authority actions — accept cuffs the
     * clicker on the spot and refusing a surrender downs them — so both gate
     * behind the DA/NU page and spell out the consequence.
     */
    @Test
    void custodyDecisionsExplainTheConsequence() {
        assertTrue(DeskActionStyle.consequence("custody-accept:C7").stream()
                .anyMatch(line -> line.contains("încătușat")));
        assertTrue(DeskActionStyle.consequence("custody-refuse:S2").stream()
                .anyMatch(line -> line.contains("doboară")));
        assertTrue(DeskActionStyle.consequence("custody-release:uuid-1").stream()
                .anyMatch(line -> line.contains("eliberată")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"complaint-withdraw:C-1", "fine-appeal:F-1",
            "join-review:JR-1", "rules", "application-submit", "guard-status",
            "custody-wake-downed", "custody-remove-head-sack", "cuffs-item",
            "arrest-handoff", "quiz-answer", "inspect-documents"})
    void formAndReadOnlyActionsSkipTheConfirmPage(String actionId) {
        assertFalse(DeskActionStyle.needsConfirm(actionId),
                actionId + " must not be confirm-gated");
    }

    /** Issue-2 desks: no Trainer/Jailer/Inspector button renders bare paper. */
    @ParameterizedTest
    @ValueSource(strings = {
            "recruit", "quiz-answer", "training-progress", "training-manual",
            "training-promote", "v2-promotion-status",
            "cuffs-status", "prison-status", "arrest-record", "downed-status",
            "arrest-handoff", "cuffs-item", "custody-remove-head-sack",
            "custody-wake-downed", "custody-accept:C7", "custody-refuse:C7",
            "custody-release:uuid-1", "inspect-documents"})
    void issueTwoDesksNeverShowGenericPaper(String actionId) {
        var icon = DeskActionStyle.icon("Eticheta", actionId);
        assertFalse(icon.itemId().isBlank());
        assertTrue(!"minecraft:paper".equals(icon.itemId()),
                actionId + " deserves a themed icon: " + icon.itemId());
    }

    @Test
    void custodyRowsUseDistinctVerdictIcons() {
        assertEquals("minecraft:lime_dye", DeskActionStyle.itemFor("custody-accept:C7"));
        assertEquals("minecraft:barrier", DeskActionStyle.itemFor("custody-refuse:C7"));
        assertEquals("straja:cuff_key", DeskActionStyle.itemFor("custody-release:uuid-1"));
        assertEquals("straja:head_sack",
                DeskActionStyle.itemFor("custody-remove-head-sack"));
    }

    @Test
    void trainerAndInspectorIconsMatchTheDesk() {
        assertEquals("minecraft:writable_book", DeskActionStyle.itemFor("recruit"));
        assertEquals("straja:training_manual",
                DeskActionStyle.itemFor("training-progress"));
        assertEquals("minecraft:clock", DeskActionStyle.itemFor("downed-status"));
        assertEquals("minecraft:totem_of_undying",
                DeskActionStyle.itemFor("custody-wake-downed"));
        assertEquals("minecraft:spyglass",
                DeskActionStyle.itemFor("inspect-documents"));
    }

    @Test
    void instructorExamAndJailerStatusesAnnounceChatOutput() {
        for (String id : new String[]{"recruit", "training-progress",
                "v2-promotion-status", "cuffs-status", "prison-status",
                "arrest-record", "downed-status", "inspect-documents"}) {
            assertTrue(DeskActionStyle.lore(id).stream()
                            .anyMatch(line -> line.contains("chat")),
                    id + " must say the result goes to chat");
        }
        assertTrue(DeskActionStyle.lore("quiz-answer").contains("Deschide formularul."));
        assertTrue(DeskActionStyle.lore("arrest-handoff").contains("Deschide formularul."));
        assertTrue(DeskActionStyle.lore("cuffs-item").contains("Primești obiectul."));
        assertTrue(DeskActionStyle.lore("training-manual").contains("Primești obiectul."));
    }

    @Test
    void issueTwoRoleHeadersUseTheGuiThemeFallbackChain() {
        assertEquals("straja:fine_book",
                DeskActionStyle.header("trainer", "Instructor", "x").itemId());
        assertEquals("straja:fine_book",
                DeskActionStyle.header("recruiter", "Instructor", "x").itemId());
        assertEquals("straja:cuffs",
                DeskActionStyle.header("jailer", "Temniță", "x").itemId());
        assertEquals("straja:seal_stamp",
                DeskActionStyle.header("inspector", "Inspector de artefacte", "x").itemId());
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
