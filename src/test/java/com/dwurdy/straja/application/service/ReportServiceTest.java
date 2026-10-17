package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.ReportUseCase;
import com.dwurdy.straja.domain.model.ActivityReport;
import com.dwurdy.straja.domain.model.ActivityReports;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportServiceTest {
    private static final long DAY = 86_400_000L;

    private TestServer server;
    private StrajaPolicies policies;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private ReportService reports;
    private GuardService guards;
    private TestPlayer member;
    private TestPlayer comisar;

    @BeforeEach
    void setUp() {
        server = new TestServer();
        policies = Fakes.policies();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock, policies);
        players = new PlayerService(ctx);
        AuditService audit = new AuditService(ctx);
        reports = new ReportService(ctx, players, audit);
        guards = new GuardService(ctx, players, audit, new EquipmentService(ctx), () -> "boot");
        member = server.add("guard1");
        comisar = server.add("dwurdy");
        var st = ctx.players().read(member.uuid());
        st.rank = 2;
        ctx.players().write(member.uuid(), st);
    }

    @Test
    void submitPersistsAndAnchorsInterval() {
        assertTrue(reports.submit(member, "patrulat", "M1", "", ""));
        var store = ctx.reports().read();
        assertEquals(1, store.reports.size());
        var r = store.reports.get("R1");
        assertNotNull(r);
        assertEquals(ActivityReport.SUBMITTED, r.status);
        assertEquals(member.uuid().toString(), r.authorUuid);
        assertEquals(1, r.revision);
        // A filing restarts the interval — not overdue right after.
        assertFalse(ActivityReports.isOverdue(store, member.uuid().toString(), clock.nowMillis(),
                policies.reportIntervalDays * DAY));
    }

    @Test
    void doubleSubmitUpdatesSameRecord() {
        assertTrue(reports.submit(member, "v1", "", "", ""));
        assertTrue(reports.submit(member, "v2", "", "", ""));
        var store = ctx.reports().read();
        assertEquals(1, store.reports.size(), "double-click must not double-file");
        assertEquals("v2", store.reports.get("R1").activity);
        assertEquals(2, store.reports.get("R1").revision);
    }

    @Test
    void civilianCannotSubmit() {
        assertFalse(reports.submit(server.add("civ"), "x", "", "", ""));
    }

    @Test
    void reviewRequiresCommissionerAndAcceptsDecisions() {
        reports.submit(member, "patrulat", "", "", "");
        assertFalse(reports.review(member, "R1", "accept", ""));
        assertTrue(reports.review(comisar, "R1", "accept", ""));
        assertEquals(ActivityReport.ACCEPTED,
                ctx.reports().read().reports.get("R1").status);
    }

    @Test
    void returnedReportNeedsNoteAndReopensSubmission() {
        reports.submit(member, "patrulat", "", "", "");
        assertFalse(reports.review(comisar, "R1", "return", ""),
                "return without a note must be refused");
        assertTrue(reports.review(comisar, "R1", "return", "lipsesc incidentele"));
        var store = ctx.reports().read();
        assertEquals(ActivityReport.RETURNED, store.reports.get("R1").status);
        // Resubmission reopens as a new revision of the same record.
        assertTrue(reports.submit(member, "patrulat + incidente", "", "", ""));
        var updated = ctx.reports().read().reports.get("R1");
        assertEquals(ActivityReport.SUBMITTED, updated.status);
        assertEquals(2, updated.revision);
    }

    @Test
    void callInSetsComisarReviewAndBlocksResubmit() {
        reports.submit(member, "patrulat", "", "", "");
        assertTrue(reports.review(comisar, "R1", "call", "audiență"));
        assertEquals(ActivityReport.COMISAR_REVIEW,
                ctx.reports().read().reports.get("R1").status);
        assertFalse(reports.submit(member, "alt raport", "", "", ""));
    }

    @Test
    void overdueStartsOnlyAfterFullInterval() {
        // First contact stamps the anchor — a member is never instantly overdue.
        assertFalse(reports.overdueFor(member));
        clock.advance(policies.reportIntervalDays * DAY + 1);
        assertTrue(reports.overdueFor(member));
        // Filing clears the overdue state.
        reports.submit(member, "în sfârșit", "", "", "");
        assertFalse(reports.overdueFor(member));
    }

    @Test
    void overdueBlocksDutyOnlyWhenEnabled() {
        policies.reportBlockDutyWhenOverdue = true;
        clock.advance(policies.reportIntervalDays * DAY + 1);
        guards.startDuty(member);
        assertFalse(ctx.players().read(member.uuid()).duty);
        // With the flag off the same overdue member starts duty.
        policies.reportBlockDutyWhenOverdue = false;
        var setup = ctx.setup().read();
        var loc = new com.dwurdy.straja.domain.model.SetupData.Location();
        loc.dimension = member.dimension();
        loc.x = member.x();
        loc.y = member.y();
        loc.z = member.z();
        setup.locations.put("secretary", loc);
        for (int i = 1; i <= 4; i++) {
            var cp = new com.dwurdy.straja.domain.model.SetupData.Checkpoint();
            cp.id = "checkpoint_" + i;
            cp.x = (double) i;
            cp.y = 0d;
            cp.z = 0d;
            setup.checkpoints.add(cp);
        }
        ctx.setup().write(setup);
        guards.startDuty(member);
        assertTrue(ctx.players().read(member.uuid()).duty);
    }

    @Test
    void availableActionsExposeSubmitAndReviewByRole() {
        var memberActions = reports.availableActions(member);
        assertTrue(memberActions.stream().anyMatch(a -> a.action() == ReportUseCase.Action.SUBMIT));
        assertFalse(memberActions.stream().anyMatch(a -> a.action() == ReportUseCase.Action.REVIEW));
        reports.submit(member, "x", "", "", "");
        var comisarActions = reports.availableActions(comisar);
        assertTrue(comisarActions.stream().anyMatch(a -> a.action()
                == ReportUseCase.Action.REVIEW && "R1".equals(a.reportId())));
    }

    // ---------------------------------------------------------- FUN-4

    @Test
    void draftReflectsRecordedActivity() {
        reports.overdueFor(member); // stamp anchor + snapshot
        // A completed mission in-window.
        var missions = ctx.missions().read();
        var m = new com.dwurdy.straja.domain.model.Mission();
        m.id = "M9";
        m.objective = "patrulare centru";
        var who = new com.dwurdy.straja.domain.model.Mission.Identity();
        who.uuid = member.uuid().toString();
        who.name = member.name();
        m.assignees.add(who);
        m.completedAt = clock.nowMillis();
        missions.missions.add(m);
        ctx.missions().write(missions);
        // An incident led in-window.
        var incidents = ctx.incidents().read();
        var inc = new com.dwurdy.straja.domain.model.Incident();
        inc.id = "I4";
        inc.leadGuardUuid = member.uuid().toString();
        inc.createdAt = clock.nowMillis();
        incidents.incidents.add(inc);
        ctx.incidents().write(incidents);
        // An arrest + a fine in-window.
        var arrests = ctx.arrestRecords().read();
        var arrest = new com.dwurdy.straja.domain.model.ArrestRecord();
        arrest.arrestingGuardUuid = member.uuid().toString();
        arrest.startedAt = clock.nowMillis();
        arrests.records.add(arrest);
        ctx.arrestRecords().write(arrests);
        var fines = ctx.fines().read();
        var fine = new com.dwurdy.straja.domain.model.Fine();
        fine.issuerUuid = member.uuid().toString();
        fine.issuedAt = clock.nowMillis();
        fines.fines.add(fine);
        ctx.fines().write(fines);

        var draft = reports.draft(member);
        assertNotNull(draft);
        assertTrue(draft.missions().contains("M9"), "mission must appear: " + draft.missions());
        assertTrue(draft.incidents().contains("I4"), "incident must appear: " + draft.incidents());
        assertTrue(draft.activity().contains("Arestări efectuate: 1"), draft.activity());
        assertTrue(draft.activity().contains("Amenzi emise: 1"), draft.activity());
        // Out-of-window events must NOT appear: an older completed mission.
        var older = new com.dwurdy.straja.domain.model.Mission();
        older.id = "M_OLD";
        older.objective = "vechi";
        var oldWho = new com.dwurdy.straja.domain.model.Mission.Identity();
        oldWho.uuid = member.uuid().toString();
        older.assignees.add(oldWho);
        older.completedAt = draft.windowStart() - 1000;
        var again = ctx.missions().read();
        again.missions.add(older);
        ctx.missions().write(again);
        assertFalse(reports.draft(member).missions().contains("M_OLD"),
                "out-of-window mission must be excluded");
    }

    @Test
    void draftEmptyWindowIsHonest() {
        var draft = reports.draft(member);
        assertNotNull(draft);
        assertTrue(draft.activity().contains("Nu există activitate"),
                "empty window must admit it: " + draft.activity());
    }

    @Test
    void autoFileAtDeadlineFlagsSourceAndResetsInterval() {
        reports.overdueFor(member); // anchor
        clock.advance(policies.reportIntervalDays * DAY + 1);
        assertEquals(1, reports.autoFileSweep());
        var store = ctx.reports().read();
        var r = store.reports.get("R1");
        assertNotNull(r);
        assertEquals(ActivityReport.SUBMITTED, r.status);
        assertEquals(ActivityReport.AUTO_FILED, r.source);
        assertEquals(member.uuid().toString(), r.authorUuid);
        // The anchor reset — a second sweep in the same window files nothing.
        assertEquals(0, reports.autoFileSweep());
        // Audit marks the filing as institutional.
        assertTrue(ctx.audit().tail(50).stream().anyMatch(e -> "auto_filed".equals(e.details)));
        // Distinguishable in review: the pending list labels it.
        reports.listForReview(comisar);
        assertTrue(comisar.told("[AUTO]"),
                "review list must flag AUTO_FILED: " + comisar.messages);
    }

    @Test
    void autoFileSkipsOpenReportAndNonMember() {
        reports.overdueFor(member);
        reports.submit(member, "depus", "", "", "");
        clock.advance(policies.reportIntervalDays * DAY + 1);
        // Open SUBMITTED report → no auto-file over the pipeline.
        assertEquals(0, reports.autoFileSweep());
        // Fired member's overdue anchor → skipped.
        var fired = server.add("fired");
        var st = ctx.players().read(fired.uuid());
        st.rank = 2;
        st.fired = true;
        ctx.players().write(fired.uuid(), st);
        var store = ctx.reports().read();
        store.anchors.put(fired.uuid().toString(), clock.nowMillis() - policies.reportIntervalDays * DAY - 1);
        ctx.reports().write(store);
        assertEquals(0, reports.autoFileSweep());
    }

    @Test
    void autoFileRespectsConfig() {
        policies.reportsAutoFileOverdue = false;
        reports.overdueFor(member);
        clock.advance(policies.reportIntervalDays * DAY + 1);
        assertEquals(0, reports.autoFileSweep());
        assertTrue(ctx.reports().read().reports.isEmpty());
    }

    @Test
    void signedReportCarriesSignedSource() {
        reports.submit(member, "patrulat", "", "", "");
        assertEquals(ActivityReport.SIGNED, ctx.reports().read().reports.get("R1").source);
    }

    @Test
    void autoFileWithMissingSnapshotDoesNotInflateWindow() {
        // Legacy-upgrade path: an anchor from before FUN-4 has no snapshot.
        var st = ctx.players().read(member.uuid());
        st.serviceBlocks = 99;
        ctx.players().write(member.uuid(), st);
        var store = ctx.reports().read();
        store.anchors.put(member.uuid().toString(),
                clock.nowMillis() - policies.reportIntervalDays * DAY - 1);
        ctx.reports().write(store);
        assertEquals(1, reports.autoFileSweep());
        var r = ctx.reports().read().reports.get("R1");
        assertNotNull(r);
        // The sweep seeds the snapshot first — the filed draft must not
        // claim the member's lifetime 99 blocks as this window's delta.
        assertFalse(r.activity.contains("+99"),
                "missing snapshot must not inflate the window: " + r.activity);
    }

    @Test
    void resubmitOverAutoFiledFlipsSourceToSigned() {
        reports.overdueFor(member);
        clock.advance(policies.reportIntervalDays * DAY + 1);
        assertEquals(1, reports.autoFileSweep());
        // The member reviews the auto-filed record and signs a correction.
        assertTrue(reports.submit(member, "corectat", "", "", ""));
        var r = ctx.reports().read().reports.get("R1");
        assertEquals(ActivityReport.SIGNED, r.source);
        assertEquals("corectat", r.activity);
        assertEquals(2, r.revision);
    }

    @Test
    void draftFieldsStayInsideTheWireBound() {
        reports.overdueFor(member);
        // 60 verbose missions, each ~100 chars of objective — drafted text
        // must stay under the 2000-byte form bound.
        var missions = ctx.missions().read();
        for (int i = 0; i < 60; i++) {
            var m = new com.dwurdy.straja.domain.model.Mission();
            m.id = "M" + i;
            m.objective = "obiectiv lung și detaliat care consumă spațiu";
            var who = new com.dwurdy.straja.domain.model.Mission.Identity();
            who.uuid = member.uuid().toString();
            m.assignees.add(who);
            m.completedAt = clock.nowMillis();
            missions.missions.add(m);
        }
        ctx.missions().write(missions);
        var draft = reports.draft(member);
        int bytes = draft.missions().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(bytes <= 2000, "missions field exceeds wire bound: " + bytes);
    }

    @Test
    void serviceBlockDeltaIsWindowed() {
        var st = ctx.players().read(member.uuid());
        st.serviceBlocks = 10;
        ctx.players().write(member.uuid(), st);
        reports.overdueFor(member); // snapshot 10
        st = ctx.players().read(member.uuid());
        st.serviceBlocks = 25;
        ctx.players().write(member.uuid(), st);
        var draft = reports.draft(member);
        assertTrue(draft.activity().contains("+15"), "delta must be windowed: " + draft.activity());
        // Filing resets the snapshot — next window starts at 25.
        reports.submit(member, "ok", "", "", "");
        var next = reports.draft(member);
        assertFalse(next.activity().contains("+15"),
                "post-filing window must not count old blocks: " + next.activity());
    }
}
