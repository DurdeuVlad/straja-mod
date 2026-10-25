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
                "archive-folder-read:D-1", "join-read:JR-1"}) {
            assertTrue(DeskActionStyle.lore(id).stream()
                            .anyMatch(line -> line.contains("chat")),
                    id + " must say the result goes to chat");
        }
    }

    /**
     * Live-view actions swap desk pages — the tile must promise the page,
     * never a chat dump the player no longer sees.
     */
    @Test
    void pageActionsAnnounceTheDeskPage() {
        for (String id : new String[]{"trades-list", "dispatch-board",
                "army-status", "army-history", "v2-professional-work",
                "parole-status", "parole-docket", "parole-review:PAR-1"}) {
            assertTrue(DeskActionStyle.lore(id).contains("Deschide pagina."),
                    id + " must announce the desk page");
        }
    }

    /**
     * Parole verbs: filing and advocacy open forms, bail + hearing verbs
     * gate behind DA/NU, the civil answers execute immediately.
     */
    @Test
    void paroleActionsClassifyHonesty() {
        for (String id : new String[]{"parole-file", "parole-appeal", "parole-advocate"}) {
            assertTrue(DeskActionStyle.lore(id).contains("Deschide formularul."),
                    id + " must announce the native form");
        }
        for (String id : new String[]{"parole-settle", "parole-open:PAR-1",
                "parole-decide-paroled:PAR-1", "parole-decide-denied:PAR-1",
                "parole-decide-conditional:PAR-1"}) {
            assertTrue(DeskActionStyle.needsConfirm(id),
                    id + " must gate behind the DA/NU page");
        }
        assertFalse(DeskActionStyle.needsConfirm("parole-object:PAR-1"));
        assertFalse(DeskActionStyle.needsConfirm("parole-advocate-answer:accept"));
        assertFalse(DeskActionStyle.needsConfirm("parole-checkin"));
        assertFalse(DeskActionStyle.consequence("parole-settle").isEmpty());
        assertFalse(DeskActionStyle.consequence("parole-decide-paroled:PAR-1").isEmpty());
    }

    /**
     * The army leave is a two-step action: the desk confirm page must dispatch
     * {@code army-leave-confirm} on DA — re-running {@code army-leave} would
     * land on the ask step again instead of executing the removal.
     */
    @Test
    void armyLeaveConfirmExecutesTheConfirmAction() {
        assertEquals("army-leave-confirm",
                DeskActionStyle.confirmExecId("army-leave"));
        assertEquals("army-status",
                DeskActionStyle.confirmExecId("army-status"),
                "ordinary actions execute themselves");
        assertTrue(DeskActionStyle.consequence("army-leave").stream()
                .anyMatch(line -> line.contains("anulează")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fine-pay:F-1", "fine-refuse:F-2",
            "complaint-confirm:C-1", "room-release", "army-leave",
            "custody-accept:C7", "custody-refuse:C7", "custody-release:uuid-1",
            "armory-buy:baton", "armory-reserve:sword",
            "evidence-deposit:E-1", "evidence-return:E-2",
            "archive-sheet-submit:A-1", "archive-sheet-revoke:A-1"})
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
            "arrest-handoff", "quiz-answer", "inspect-documents",
            "archive-folder-create", "archive-folder-issue:D-1",
            "archive-sheet-new:D-1", "archive-sheet-edit:A-1",
            "archive-recipients:A-1", "archive-sheet-sign:A-1",
            "archive-sheet-copy:A-1", "archive-sheet-envelope:A-1",
            "archive-sheet-issue:A-1", "archive-folder-read:D-1",
            "archive-sheet-read:A-1", "archive-list", "evidence-list",
            "evidence-case-view", "evidence-transfer:E-1",
            "evidence-destroy:E-1", "armory-status", "v2-document-status",
            "v2-equipment-status", "v2-professional-work", "duty-kit"})
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

    /** Issue-3 desks: no Archivist/Armorer button renders bare paper. */
    @ParameterizedTest
    @ValueSource(strings = {
            "archive-list", "archive-folder-create", "v2-document-status",
            "evidence-list", "evidence-case-view",
            "archive-folder-read:D-1", "archive-folder-issue:D-1",
            "archive-sheet-new:D-1", "archive-sheet-read:A-1",
            "archive-sheet-edit:A-1", "archive-recipients:A-1",
            "archive-sheet-submit:A-1", "archive-sheet-sign:A-1",
            "archive-sheet-copy:A-1", "archive-sheet-envelope:A-1",
            "archive-sheet-issue:A-1", "archive-sheet-revoke:A-1",
            "evidence-deposit:E-1", "evidence-return:E-1",
            "evidence-transfer:E-1", "evidence-destroy:E-1",
            "armory-status", "v2-equipment-status", "v2-professional-work",
            "duty-kit", "armory-buy:baton", "armory-reserve:sword"})
    void issueThreeDesksNeverShowGenericPaper(String actionId) {
        var icon = DeskActionStyle.icon("Eticheta", actionId);
        assertFalse(icon.itemId().isBlank());
        assertTrue(!"minecraft:paper".equals(icon.itemId()),
                actionId + " deserves a themed icon: " + icon.itemId());
    }

    /** Issue-4 desk: no Secretary/Comisar button renders bare paper. */
    @ParameterizedTest
    @ValueSource(strings = {
            "mission-list", "mission-carnet", "mission-draft-write",
            "mission-draft-status", "mission-template-issue:T-1",
            "mission-join:M-1", "mission-report:M-1", "mission-fail:M-1",
            "v2-mission-claim:V2-7", "v2-personnel-status", "v2-campaign-status",
            "v2-professional-work", "guard-status",
            "duty-start", "duty-stop", "work-start", "duty-salary", "duty-coins",
            "duty-food", "duty-kit", "duty-checkpoint:CP-2",
            "resignation-start", "resignation-confirm", "resignation-cancel",
            "rejoin", "complaint-list", "complaint-claim:C-1",
            "complaint-review:C-1", "fine-draft-write", "fine-draft-status",
            "fine-task-list", "fine-task-accept:FT-1", "fine-task-arrest:FT-1",
            "fine-warrant", "report-submit",
            "report-review-list", "report-review:R-1", "audience-request",
            "audience-review-list", "audience-review:A-1",
            "petition-request", "petition-read:P-1", "petition-review:P-1",
            "join-read:JR-1", "join-review:JR-1", "parole-review:PH-1",
            "incident-list", "incident-accept:I-1", "incident-resolve:I-1",
            "dispatch-board", "bolo-list", "bolo-create", "bolo-cancel:B-1",
            "admin-personnel", "admin-roster", "admin-authorize",
            "admin-policies", "admin-policy-set", "admin-emergency-status",
            "admin-emergency-start", "admin-emergency-end",
            "admin-dossier:uuid-1", "admin-promote:uuid-1",
            "admin-suspend:uuid-1", "admin-fire:uuid-1",
            "admin-reinstate:uuid-1", "delegation-ledger",
            "delegation-mode:JOIN_PETITION", "delegation-window:AUDIENCE_REQUEST",
            "delegation-revoke:SO-1", "delegation-order-join_petition:ANY",
            "reputation-view", "reputation-correction", "arrest-record-admin",
            "evidence-list"})
    void issueFourDesksNeverShowGenericPaper(String actionId) {
        var icon = DeskActionStyle.icon("Eticheta", actionId);
        assertFalse(icon.itemId().isBlank());
        assertTrue(!"minecraft:paper".equals(icon.itemId()),
                actionId + " deserves a themed icon: " + icon.itemId());
    }

    @Test
    void archivistAndArmorerIconsMatchTheActionSemantics() {
        assertEquals("straja:archive_folder",
                DeskActionStyle.itemFor("archive-folder-read:D-1"));
        assertEquals("straja:archive_folder",
                DeskActionStyle.itemFor("archive-folder-issue:D-1"));
        assertEquals("straja:archive_document",
                DeskActionStyle.itemFor("archive-sheet-read:A-1"));
        assertEquals("straja:carbon_paper",
                DeskActionStyle.itemFor("archive-sheet-edit:A-1"));
        assertEquals("straja:carbon_paper",
                DeskActionStyle.itemFor("archive-sheet-copy:A-1"));
        assertEquals("straja:official_envelope",
                DeskActionStyle.itemFor("archive-sheet-envelope:A-1"));
        assertEquals("straja:official_document",
                DeskActionStyle.itemFor("archive-sheet-issue:A-1"));
        assertEquals("straja:seal_stamp",
                DeskActionStyle.itemFor("archive-sheet-submit:A-1"));
        assertEquals("straja:archive_stamp",
                DeskActionStyle.itemFor("archive-sheet-sign:A-1"));
        assertEquals("minecraft:barrier",
                DeskActionStyle.itemFor("archive-sheet-revoke:A-1"));
        assertEquals("straja:evidence_bag",
                DeskActionStyle.itemFor("evidence-deposit:E-1"));
        assertEquals("straja:evidence_bag",
                DeskActionStyle.itemFor("evidence-transfer:E-1"));
        assertEquals("straja:confiscation_receipt",
                DeskActionStyle.itemFor("evidence-return:E-1"));
        assertEquals("minecraft:barrier",
                DeskActionStyle.itemFor("evidence-destroy:E-1"));
        assertEquals("minecraft:gold_ingot",
                DeskActionStyle.itemFor("armory-buy:baton"));
        assertEquals("straja:iou_note",
                DeskActionStyle.itemFor("armory-reserve:sword"));
        assertEquals("straja:baton", DeskActionStyle.itemFor("armory-status"));
        assertEquals("straja:guard_uniform", DeskActionStyle.itemFor("duty-kit"));
    }

    /** Sheet submit/revoke and evidence custody moves are one-way — spell it. */
    @Test
    void issueThreeConsequencesSpellTheOutcome() {
        assertTrue(DeskActionStyle.consequence("archive-sheet-submit:A-1").stream()
                .anyMatch(line -> line.contains("semnat")));
        assertTrue(DeskActionStyle.consequence("archive-sheet-revoke:A-1").stream()
                .anyMatch(line -> line.contains("revocat")));
        assertTrue(DeskActionStyle.consequence("evidence-deposit:E-1").stream()
                .anyMatch(line -> line.contains("depozitul")));
        assertTrue(DeskActionStyle.consequence("evidence-return:E-1").stream()
                .anyMatch(line -> line.contains("proprietarului")));
        assertTrue(DeskActionStyle.consequence("armory-buy:baton").stream()
                .anyMatch(line -> line.contains("monede")));
        assertTrue(DeskActionStyle.consequence("armory-reserve:sword").stream()
                .anyMatch(line -> line.contains("rechiziție")));
    }

    @Test
    void issueThreeRoleHeadersUseTheGuiThemeFallbackChain() {
        assertEquals("straja:archive_folder",
                DeskActionStyle.header("archivist", "Arhivă", "x").itemId());
        assertEquals("straja:baton",
                DeskActionStyle.header("armorer", "Armurier", "x").itemId());
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

    /**
     * Issue-5 desks: the admin-tool rows — wand assign/rename/skin/remove/
     * record, survey stamps, cell confirm — never render bare paper, the
     * removal gate carries its consequence, and text outputs announce the
     * chat dump.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "tool-cell-confirm", "tool-survey-all",
            "tool-npc-assign:uuid-1-receptionist", "tool-npc-rename:uuid-1",
            "tool-npc-skin:uuid-1", "tool-npc-remove-confirm:uuid-1",
            "tool-npc-record:uuid-1", "tool-survey-stamp:hq"})
    void issueFiveToolRowsNeverShowGenericPaper(String actionId) {
        var icon = DeskActionStyle.icon("Eticheta", actionId);
        assertFalse(icon.itemId().isBlank());
        assertTrue(!"minecraft:paper".equals(icon.itemId()),
                actionId + " deserves a themed icon: " + icon.itemId());
    }

    @Test
    void toolRemovalIsConfirmGatedAndSpellsTheConsequence() {
        assertTrue(DeskActionStyle.needsConfirm("tool-npc-remove-confirm:uuid-1"));
        assertTrue(DeskActionStyle.lore("tool-npc-remove-confirm:uuid-1")
                .contains("Cere confirmare."));
        assertTrue(DeskActionStyle.consequence("tool-npc-remove-confirm:uuid-1").stream()
                .anyMatch(line -> line.contains("anula")));
        assertEquals("minecraft:barrier",
                DeskActionStyle.itemFor("tool-npc-remove-confirm:uuid-1"));
        assertEquals("minecraft:barrier",
                DeskActionStyle.itemFor("tool-npc-remove:uuid-1"));
        assertEquals("straja:survey_rod",
                DeskActionStyle.itemFor("tool-survey-stamp:hq"));
        assertEquals("straja:npc_wand",
                DeskActionStyle.itemFor("tool-npc-assign:uuid-1-jailer"));
        assertEquals("straja:survey_rod", DeskActionStyle.itemFor("tool-survey-all"));
    }

    @Test
    void toolChatOutputsAnnounceTheChatResult() {
        // Only the NPC record dump reads rather than writes — the only
        // tool op whose result lands in chat.
        assertTrue(DeskActionStyle.lore("tool-npc-record:uuid-1").stream()
                        .anyMatch(line -> line.contains("chat")),
                "tool-npc-record must say the result goes to chat");
        // Mutating tool ops must not mislabel as chat output: cell creation,
        // location stamping, and NPC reassignment all write state.
        for (String id : new String[]{"tool-cell-confirm", "tool-survey-all",
                "tool-npc-assign:uuid-1-jailer", "tool-survey-stamp:hq"}) {
            assertTrue(DeskActionStyle.lore(id).contains("Se execută imediat."),
                    id + " mutates and must say so");
        }
        // Record-scoped page rows announce the page, not an execution.
        assertTrue(DeskActionStyle.lore("parole-review:PH-1")
                .contains("Deschide pagina."));
        assertTrue(DeskActionStyle.lore("tool-npc-rename:uuid-1")
                .contains("Deschide formularul."));
        assertTrue(DeskActionStyle.lore("tool-npc-skin:uuid-1")
                .contains("Deschide formularul."));
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
