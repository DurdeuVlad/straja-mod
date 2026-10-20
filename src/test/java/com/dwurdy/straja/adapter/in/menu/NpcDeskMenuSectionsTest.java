package com.dwurdy.straja.adapter.in.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Issue-4 Secretary desk: the {@link DeskSections} taxonomy (each dynamic
 * family lands in its drawer, unknowns fall to {@code general}), the
 * {@code desk:section:<key>} nav ids that open drawers, the drawer buttons
 * a given action set produces, and the live duty hint on the header item.
 * Pure — no Minecraft classes.
 */
class NpcDeskMenuSectionsTest {
    private static final Path SESSION =
            Path.of("src/main/java/com/dwurdy/straja/adapter/in/menu/DeskMenuSession.java");

    @ParameterizedTest
    @ValueSource(strings = {"duty-start", "duty-stop", "duty-salary", "duty-coins",
            "duty-food", "duty-kit", "duty-roster", "duty-checkpoint:CP-2",
            "work-start", "resignation-start", "resignation-confirm",
            "resignation-cancel", "rejoin"})
    void dutyFamilyClassifiesAsDuty(String actionId) {
        assertEquals(DeskSections.DUTY, DeskSections.sectionFor(actionId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mission-list", "mission-draft-write", "mission-draft-status",
            "mission-draft-scope", "mission-draft-sign", "mission-draft-package",
            "mission-template-list", "mission-budget-adjust", "mission-template-issue:T-1",
            "mission-join:M-1", "mission-accept:M-1", "mission-decline:M-1",
            "mission-report:M-1", "mission-fail:M-1", "mission-complete:M-1",
            "mission-reward:M-1", "mission-reward-recover:M-1", "mission-invite:M-1",
            "v2-mission-claim:V2-7"})
    void missionFamilyClassifiesAsMissions(String actionId) {
        assertEquals(DeskSections.MISSIONS, DeskSections.sectionFor(actionId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"complaint-list", "complaint-claim:C-1", "complaint-join:C-1",
            "complaint-leave:C-1", "complaint-report:C-1", "complaint-review:C-1",
            "fine-draft-write", "fine-draft-status", "fine-task-list", "fine-warrant",
            "fine-task-accept:FT-1", "fine-task-complete:FT-1", "fine-task-arrest:FT-1",
            "fine-task-reward:FT-1", "fine-appeal-review:F-1",
            "report-submit", "report-status", "report-review-list", "report-review:R-1",
            "audience-request", "audience-status", "audience-review-list",
            "audience-review:A-1", "petition-request", "petition-read:P-1",
            "petition-review:P-1", "join-read:JR-1", "join-review:JR-1"})
    void recordQueuesClassifyAsRecords(String actionId) {
        assertEquals(DeskSections.RECORDS, DeskSections.sectionFor(actionId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"incident-list", "incident-report", "incident-accept:I-1",
            "incident-join:I-1", "incident-resolve:I-1", "dispatch-board",
            "dispatch-accept:D-1", "dispatch-decline:D-1", "bolo-list", "bolo-create",
            "bolo-cancel:B-1"})
    void operationalBoardClassifiesAsBoard(String actionId) {
        assertEquals(DeskSections.BOARD, DeskSections.sectionFor(actionId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin-personnel", "admin-roster", "admin-authorize",
            "admin-policies", "admin-policy-set", "admin-emergency-status",
            "admin-emergency-alert", "admin-emergency-start", "admin-emergency-end",
            "admin-dossier:uuid-1", "admin-promote:uuid-1", "admin-demote:uuid-1",
            "admin-suspend:uuid-1", "admin-fire:uuid-1", "admin-reinstate:uuid-1",
            "delegation-ledger", "delegation-mode:JOIN_PETITION",
            "delegation-window:AUDIENCE_REQUEST", "delegation-revoke:SO-1",
            "delegation-order-join_petition:ANY",
            "reputation-view", "reputation-correction", "arrest-record-admin",
            "evidence-list"})
    void commissionerConsoleClassifiesAsComisar(String actionId) {
        assertEquals(DeskSections.COMISAR, DeskSections.sectionFor(actionId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"guard-status", "mission-carnet", "v2-personnel-status",
            "v2-campaign-status", "v2-professional-work", "v2-document-status",
            "v2-equipment-status", "v2-promotion-status", "reputation-self",
            "arrest-record", "faq:root_secretary", "main:secretary",
            "more:secretary", "not-a-real-action", "", "parole-review:PH-1"})
    void everythingElseStaysOnTheRootPage(String actionId) {
        assertEquals(DeskSections.GENERAL, DeskSections.sectionFor(actionId),
                actionId + " is not a drawer family");
    }

    /** The carnet is a take-away item, not a workflow — it stays on the desk top. */
    @Test
    void missionCarnetIsTheOnlyMissionPrefixOverride() {
        assertEquals(DeskSections.GENERAL, DeskSections.sectionFor("mission-carnet"));
        assertEquals(DeskSections.MISSIONS, DeskSections.sectionFor("mission-list"));
    }

    @Test
    void nullAndBlankIdsClassifyAsGeneral() {
        assertEquals(DeskSections.GENERAL, DeskSections.sectionFor(null));
        assertEquals(DeskSections.GENERAL, DeskSections.sectionFor("   "));
    }

    @Test
    void drawerButtonsCoverOnlyNonEmptySectionsInOrder() {
        // A member-shaped action set: duty verbs, mission ops, one complaint
        // row, board actions — but no commissioner authority at all.
        var ids = List.of("duty-start", "duty-roster", "mission-list",
                "mission-draft-write", "complaint-list", "incident-list",
                "bolo-list", "mission-carnet", "guard-status",
                "faq:root_secretary", "v2-personnel-status");
        var drawers = DeskSections.drawerButtons(ids);
        var navIds = drawers.stream().map(DeskButton::actionId).toList();
        assertEquals(List.of("desk:section:duty", "desk:section:missions",
                        "desk:section:records", "desk:section:board"), navIds,
                "drawers render in ORDER and skip the empty comisar drawer");
        assertTrue(navIds.stream().noneMatch(id -> id.contains("comisar")),
                "no admin/delegation emissions — no Comisar drawer");
        var duty = drawers.get(0);
        assertEquals("Serviciu și tură", duty.icon().name());
        assertTrue(duty.icon().lore().stream().anyMatch(line -> line.contains("2 acțiuni")),
                "the drawer says how much is inside: " + duty.icon().lore());
        assertTrue(duty.icon().lore().stream().allMatch(line -> !line.isBlank()));
    }

    @Test
    void commissionerAuthorityOpensTheComisarDrawer() {
        var ids = List.of("admin-personnel", "admin-dossier:uuid-1",
                "admin-promote:uuid-1", "delegation-ledger",
                "delegation-mode:JOIN_PETITION", "reputation-view",
                "arrest-record-admin", "evidence-list", "guard-status");
        var navIds = DeskSections.drawerButtons(ids).stream()
                .map(DeskButton::actionId).toList();
        assertEquals(List.of("desk:section:comisar"), navIds,
                "authority emissions produce exactly the Comisar drawer");
    }

    @Test
    void navIdsRoundTripAndRejectJunk() {
        for (String section : DeskSections.ORDER) {
            assertEquals(section, DeskSections.sectionOfNav(DeskSections.navId(section)));
        }
        assertNull(DeskSections.sectionOfNav("desk:section:general"),
                "general is the root page, not a drawer");
        assertNull(DeskSections.sectionOfNav("desk:section:bogus"));
        assertNull(DeskSections.sectionOfNav("desk:back"));
        assertNull(DeskSections.sectionOfNav("fine-pay:F-1"));
        assertNull(DeskSections.sectionOfNav(null));
        assertNull(DeskSections.sectionOfNav("desk:section:"));
    }

    /**
     * The session routes a section click as in-menu navigation: the
     * sectionOfNav branch sits ahead of the generic {@code desk:} early
     * return, and the id can never reach performAction — it is not even a
     * parseable action id.
     */
    @Test
    void sectionNavNeverReachesDispatch() throws IOException {
        String session = Files.readString(SESSION);
        int sectionBranch = session.indexOf("DeskSections.sectionOfNav");
        int deskGuard = session.indexOf("actionId.startsWith(\"desk:\")");
        assertTrue(sectionBranch > 0, "the session must route desk:section: nav ids");
        assertTrue(deskGuard > sectionBranch,
                "section routing happens before the internal-nav early return");
        assertTrue(com.dwurdy.straja.adapter.in.npc.NpcPlayerSurface
                        .parseActionId(DeskSections.navId(DeskSections.DUTY)).isEmpty(),
                "a desk:section: id is not a dispatchable action id");
    }

    @Test
    void sectionContentPagesAtFortyFiveLikeEveryGrid() {
        // The Comisar drawer is the dense case: five admin ids per member.
        var ids = new ArrayList<String>();
        for (int i = 0; i < 18; i++) {
            String member = "member-" + i;
            ids.addAll(List.of("admin-dossier:" + member, "admin-promote:" + member,
                    "admin-demote:" + member, "admin-suspend:" + member,
                    "admin-fire:" + member));
        }
        var comisar = ids.stream()
                .filter(id -> DeskSections.COMISAR.equals(DeskSections.sectionFor(id)))
                .toList();
        assertEquals(90, comisar.size(), "every admin row stays in the drawer");
        var items = comisar.stream()
                .map(id -> new DeskButton(new DeskIcon("straja:seal_stamp", id, List.of()), id))
                .toList();
        var first = DeskLayout.grid("Secretariat — Comisar",
                DeskSections.header(DeskSections.COMISAR, comisar.size()), items, 0, true);
        var second = DeskLayout.grid("Secretariat — Comisar",
                DeskSections.header(DeskSections.COMISAR, comisar.size()), items, 1, true);
        assertEquals("Secretariat — Comisar — 1/2", first.title());
        assertEquals("admin-dossier:member-9", second.buttonAt(0).actionId());
        assertTrue(first.buttonAt(DeskLayout.NEXT_SLOT) != null);
        assertTrue(second.buttonAt(DeskLayout.PREV_SLOT) != null);
    }

    @Test
    void dutyHintReadsLiveStateFromEmittedActions() {
        assertEquals("În tură.",
                DeskSections.dutyHint(List.of("duty-stop", "duty-food")));
        assertEquals("În tură — checkpoint «CP-2» te așteaptă.",
                DeskSections.dutyHint(List.of("duty-checkpoint:CP-2", "duty-stop")));
        assertEquals("Liber — poți începe tura aici.",
                DeskSections.dutyHint(List.of("duty-start", "duty-salary")));
        assertEquals("Demisie în curs — confirmă sau anulează la timp.",
                DeskSections.dutyHint(List.of("resignation-confirm", "resignation-cancel")));
        assertEquals("Demisionat — te poți reîntoarce în Strajă.",
                DeskSections.dutyHint(List.of("rejoin")));
        assertEquals("", DeskSections.dutyHint(List.of("mission-list", "guard-status")));
        assertEquals("", DeskSections.dutyHint(List.of()));
        assertEquals("", DeskSections.dutyHint(null));
    }

    @Test
    void sectionPagesCarryARomanianLegend() {
        var header = DeskSections.header(DeskSections.RECORDS, 12);
        assertFalse(header.itemId().isBlank());
        assertEquals("Registre și dosare", header.name());
        assertTrue(header.lore().size() >= 2);
        assertTrue(header.lore().stream().anyMatch(line -> line.contains("12 acțiuni")));
        var single = DeskSections.header(DeskSections.BOARD, 1);
        assertTrue(single.lore().contains("1 acțiune."));
    }
}
