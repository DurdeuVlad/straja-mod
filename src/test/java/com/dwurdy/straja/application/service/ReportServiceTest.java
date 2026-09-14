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
}
