package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.ComplaintRoleplayUseCase;
import com.dwurdy.straja.application.port.in.FineRoleplayUseCase;
import com.dwurdy.straja.application.port.out.WorldGateway;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** M6 civic systems: fines, appeals, complaints, rooms, archive — pure domain. */
class CivicServiceTest {
    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private AuditService audit;
    private PrisonService prison;
    private FineService fines;
    private ComplaintService complaints;
    private RoomService rooms;
    private ArchiveService archive;
    private TestPlayer boss;
    private TestPlayer lt;
    private TestPlayer senior;
    private TestPlayer guard;
    private TestPlayer civ;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        audit = new AuditService(ctx);
        var custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        fines = new FineService(ctx, players, audit, prison);
        complaints = new ComplaintService(ctx, players, audit);
        rooms = new RoomService(ctx, players, audit, ctx.world());
        archive = new ArchiveService(ctx, players, audit);
        boss = server.add("dwurdy");           // default commissioner name
        lt = server.add("lt1");
        senior = server.add("senior1");
        guard = server.add("guard1");
        civ = server.add("civ1");
        setRank(lt, Rank.INSPECTOR);
        setRank(senior, Rank.SERGENT);
        setRank(guard, Rank.GUARD);
        setDuty(guard, true);
        setDuty(lt, true);
        setDuty(senior, true);
        // receptionist + office locations for location-gated flows
        var setup = ctx.setup().read();
        var reception = new com.dwurdy.straja.domain.model.SetupData.Location();
        reception.x = 0; reception.y = 0; reception.z = 0;
        setup.locations.put("receptionist", reception);
        setup.locations.put("secretary", reception);
        setup.locations.put("commissionerOffice", reception);
        ctx.setup().write(setup);
        civ.x = 0; civ.y = 0; civ.z = 0;
        boss.x = 0; boss.y = 0; boss.z = 0;
        guard.x = 0; guard.y = 0; guard.z = 0;
        lt.x = 0; lt.y = 0; lt.z = 0;
        senior.x = 0; senior.y = 0; senior.z = 0;
    }

    private void setRank(TestPlayer p, Rank rank) {
        var s = players.state(p.uuid());
        s.rank = rank.level();
        players.save(p.uuid(), s);
    }

    private void setDuty(TestPlayer p, boolean on) {
        var s = players.state(p.uuid());
        s.duty = on;
        players.save(p.uuid(), s);
    }

    private void giveItem(TestPlayer p, String id, int count) {
        p.give(ItemSpec.of(id, count));
    }

    private com.dwurdy.straja.domain.model.Fine fine(String id) {
        return ctx.fines().read().find(id);
    }

    // ------------------------------------------------------------ fines

    @Test
    void fineWriteRequiresBookAndCapability() {
        assertFalse(fines.writeDraft(guard, civ, 25, "lege", "descriere"));
        assertTrue(guard.told("Registrul de Amenzi"));
        giveItem(guard, "straja:fine_book", 1);
        assertTrue(fines.writeDraft(guard, civ, 25, "lege", "descriere"));
        assertTrue(fines.draftText(guard).contains("civ1"));
    }

    @Test
    void fineWriteRejectsNonStandardAmount() {
        giveItem(guard, "straja:fine_book", 1);
        assertFalse(fines.writeDraft(guard, civ, 13, "lege", "descriere"));
        assertTrue(guard.told("standard"));
    }

    @Test
    void fineCannotTargetHigherOrEqualRank() {
        giveItem(guard, "straja:fine_book", 1);
        assertFalse(fines.writeDraft(guard, boss, 25, "lege", "descriere")); // commissioner
        assertFalse(fines.writeDraft(guard, lt, 25, "lege", "descriere"));  // higher rank
        assertFalse(fines.writeDraft(guard, guard, 25, "lege", "d"));       // same rank
    }

    @Test
    void commissionerCanWriteAndIssueFineToCivilian() {
        // The Commissioner holds no guard rank (rank 0); the same/higher-rank
        // guard check must not block them from fining a civilian.
        giveItem(boss, "straja:fine_book", 1);
        assertTrue(fines.writeDraft(boss, civ, 25, "lege", "descriere"));
        assertTrue(fines.issueFromDraft(boss, civ));
        assertEquals("ISSUED", fine("F1").status);
    }

    @Test
    void fineIssueDeliversNoticeAndIsIdempotent() {
        giveItem(guard, "straja:fine_book", 1);
        assertTrue(fines.writeDraft(guard, civ, 25, "lege", "descriere"));
        assertTrue(fines.issueFromDraft(guard, civ));
        var fine = fine("F1");
        assertEquals("ISSUED", fine.status);
        assertEquals(1, civ.inventory().countOf("straja:fine_notice"));
        // re-issue after re-writing identical draft -> duplicate detected, not duplicated
        assertTrue(fines.writeDraft(guard, civ, 25, "lege", "descriere"));
        assertTrue(fines.issueFromDraft(guard, civ));
        assertEquals(1, ctx.fines().read().fines.size());
        assertTrue(guard.told("există deja"));
    }

    @Test
    void fineIssueFailsToDeliveryFailedWhenInventoryFull() {
        giveItem(guard, "straja:fine_book", 1);
        for (int i = 0; i < civ.inventory().slots(); i++) {
            civ.inventory.slots.set(i, new com.dwurdy.straja.application.port.out.ItemView("minecraft:stone", 64, 64, java.util.Map.of()));
        }
        assertTrue(fines.writeDraft(guard, civ, 25, "lege", "descriere"));
        assertFalse(fines.issueFromDraft(guard, civ));
        assertEquals("DELIVERY_FAILED", fine("F1").status);
    }

    @Test
    void finePayWithdrawsCoinsAndCompletesTasks() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, civ, 25, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        var currency = (TestCurrency) ctx.currency();
        currency.balance = 100;
        assertTrue(fines.pay(civ, "F1"));
        assertEquals("PAID", fine("F1").status);
        assertEquals(75, currency.balance);
        assertTrue(civ.told("plătită"));
    }

    @Test
    void finePayInsufficientFundsKeepsStatus() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, civ, 25, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        ((TestCurrency) ctx.currency()).balance = 10;
        assertFalse(fines.pay(civ, "F1"));
        assertEquals("ISSUED", fine("F1").status);
    }

    private void shrinkGrace(String id, long graceMs) {
        var data = ctx.fines().read();
        data.find(id).onlineGraceMs = graceMs;
        ctx.fines().write(data);
    }

    @Test
    void fineEscalatesAfterOnlineGrace() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, civ, 25, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        shrinkGrace("F1", 5_000);
        clock.advance(3_000);
        fines.tick();
        clock.advance(3_000);
        fines.tick();
        var fine = fine("F1");
        assertEquals("ESCALATED", fine.status);
        assertEquals("FM-F1", fine.taskId);
        var task = ctx.fines().read().findTask("FM-F1");
        assertNotNull(task);
        assertEquals("OPEN", task.status);
        assertTrue(civ.told("escaladată"));
    }

    @Test
    void fineEscalationPausedWhileOffline() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, civ, 25, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        shrinkGrace("F1", 5_000);
        civ.online = false;
        for (int i = 0; i < 10; i++) {
            clock.advance(1_000);
            fines.tick();
        }
        assertEquals("ISSUED", fine("F1").status);
        assertEquals(0, fine("F1").onlineElapsedMs);
    }

    @Test
    void recoveryTaskPresentRefuseArrest() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, civ, 25, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        shrinkGrace("F1", 5_000);
        clock.advance(6_000);
        fines.tick();
        assertEquals("ESCALATED", fine("F1").status);
        assertTrue(fines.acceptTask(guard, "FM-F1"));
        assertTrue(fines.completeTask(guard, "FM-F1"));   // civ at reception (0,0,0)
        assertEquals("ARREST_PENDING", fine("F1").status);
        assertTrue(fines.refusePayment(civ, "FM-F1"));
        assertTrue(fines.arrest(guard, "FM-F1", null));
        var fine = fine("F1");
        assertEquals("IN_SENTENCE", fine.status);
        var sentence = prison.activeSentence(civ);
        assertNotNull(sentence);
        assertEquals("F1", sentence.fineId);
    }

    @Test
    void arrestBlockedBeforeExplicitRefusal() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, civ, 25, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        shrinkGrace("F1", 5_000);
        clock.advance(6_000);
        fines.tick();
        fines.acceptTask(guard, "FM-F1");
        fines.completeTask(guard, "FM-F1");
        assertFalse(fines.arrest(guard, "FM-F1", null));
        assertTrue(guard.told("refuzul explicit"));
    }

    @Test
    void hearingWarrantRequiresAuthorityAndDedupes() {
        assertFalse(fines.issueHearingWarrant(guard, civ, "motiv"));      // guard rank too low
        assertTrue(guard.told("Inspector"));
        assertTrue(fines.issueHearingWarrant(lt, civ, "motiv audiere"));  // active lt ok
        var task = ctx.fines().read().tasks.get(0);
        assertEquals("HEARING_WARRANT", task.kind);
        assertEquals("OPEN", task.status);
        assertEquals("commissionerOffice", task.destination);
        assertFalse(fines.issueHearingWarrant(boss, civ, "alt motiv"));   // active warrant exists
        assertFalse(fines.issueHearingWarrant(boss, boss, "motiv"));      // can't target commissioner
        assertTrue(civ.told("mandat de audiere"));
    }

    @Test
    void jailerAssaultCreatesUrgentMissionAndUpgradesSeverity() {
        assertNull(fines.createJailerAssaultMission(guard, "temnicer", "WOUNDED")); // guards can't trigger
        var task = fines.createJailerAssaultMission(civ, "temnicer", "WOUNDED");
        assertNotNull(task);
        assertEquals("JAILER_ASSAULT", task.kind);
        assertEquals(250, task.injuryAmount);
        // second hit upgrades WOUNDED -> KILLED
        var upgraded = fines.createJailerAssaultMission(civ, "temnicer", "KILLED");
        assertEquals(task.id, upgraded.id);
        assertEquals("KILLED", upgraded.jailerOutcome);
        assertEquals(500, upgraded.injuryAmount);
        assertTrue(civ.told("mandat de arest"));
    }

    @Test
    void jailerAssaultArrestPaysAliveBounty() {
        var task = fines.createJailerAssaultMission(civ, "temnicer", "WOUNDED");
        assertTrue(fines.acceptTask(guard, task.id));
        var currency = (TestCurrency) ctx.currency();
        int before = currency.balance;
        assertTrue(fines.completeTask(guard, task.id));
        assertEquals("ARRESTED", ctx.fines().read().findTask(task.id).status);
        // 250 base × aliveMultiplier 4 = 1000 (at the maximum clamp)
        assertEquals(before + 1000, currency.balance);
        assertTrue(guard.told("Recompensă"));
    }

    @Test
    void suspectKilledPaysReducedBountyAndClosesTask() {
        var task = fines.createJailerAssaultMission(civ, "temnicer", "WOUNDED");
        assertTrue(fines.acceptTask(guard, task.id));
        // a non-assignee guard kills the suspect -> nothing happens
        fines.suspectKilled(senior, civ);
        assertEquals("OPEN", ctx.fines().read().findTask(task.id).status);
        var currency = (TestCurrency) ctx.currency();
        int before = currency.balance;
        fines.suspectKilled(guard, civ);
        var stored = ctx.fines().read().findTask(task.id);
        assertEquals("SUSPECT_KILLED", stored.status);
        // 250 × deathMultiplier 1 = 250
        assertEquals(before + 250, currency.balance);
    }

    @Test
    void fineRefusalArrestPaysMinorDividerBounty() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, civ, 100, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        shrinkGrace("F1", 5_000);
        clock.advance(6_000);
        fines.tick();
        fines.acceptTask(guard, "FM-F1");
        fines.completeTask(guard, "FM-F1");
        fines.refusePayment(civ, "FM-F1");
        var currency = (TestCurrency) ctx.currency();
        int before = currency.balance;
        assertTrue(fines.arrest(guard, "FM-F1", null));
        // minor: 100 / divider 5 = 20 × alive 4 = 80 → above the 25 minimum
        assertEquals(before + 80, currency.balance);
    }

    @Test
    void arrestRewardHonorsDailyCap() {
        ctx.policies().arrestMaxDailyPayout = 100;
        var t1 = fines.createJailerAssaultMission(civ, "temnicer", "WOUNDED");
        assertTrue(fines.acceptTask(guard, t1.id));
        var currency = (TestCurrency) ctx.currency();
        fines.suspectKilled(guard, civ);
        // 250 × death 1 = 250, capped to the remaining 100 of the day
        assertEquals(100, currency.lastAmount);
        var t2 = fines.createJailerAssaultMission(civ, "temnicer2", "WOUNDED");
        assertTrue(fines.acceptTask(guard, t2.id));
        int calls = currency.depositCalls;
        fines.suspectKilled(guard, civ);
        assertEquals(calls, currency.depositCalls, "daily cap exhausted — no further payout");
        assertEquals("SUSPECT_KILLED", ctx.fines().read().findTask(t2.id).status);
    }

    @Test
    void repeatJailerAssaultAfterSuspectDeathCreatesNewTask() {
        var task = fines.createJailerAssaultMission(civ, "temnicer", "WOUNDED");
        fines.acceptTask(guard, task.id);
        fines.suspectKilled(guard, civ);
        assertEquals("SUSPECT_KILLED", ctx.fines().read().findTask(task.id).status);
        // a second assault by the same player must open a fresh task, not
        // resurrect the closed one
        var second = fines.createJailerAssaultMission(civ, "temnicer", "WOUNDED");
        assertNotEquals(task.id, second.id);
        assertEquals("OPEN", second.status);
    }

    @Test
    void arrestRewardIsIdempotentPerTaskAndOfficer() {
        var task = fines.createJailerAssaultMission(civ, "temnicer", "WOUNDED");
        assertTrue(fines.acceptTask(guard, task.id));
        var currency = (TestCurrency) ctx.currency();
        fines.suspectKilled(guard, civ);
        int calls = currency.depositCalls;
        // replay: a surviving receipt must not pay twice
        fines.suspectKilled(guard, civ);
        assertEquals(calls, currency.depositCalls);
    }

    @Test
    void failedArrestRewardIsDeliveredOnReconnect() {
        var task = fines.createJailerAssaultMission(civ, "temnicer", "WOUNDED");
        assertTrue(fines.acceptTask(guard, task.id));
        var currency = (TestCurrency) ctx.currency();
        currency.available = false;
        fines.suspectKilled(guard, civ); // task closes but the bounty delivery fails
        assertEquals("SUSPECT_KILLED", ctx.fines().read().findTask(task.id).status);
        currency.available = true;
        int balance = currency.balance;
        fines.recoverOnLogin(guard);
        assertEquals(balance + 250, currency.balance,
                "login recovery retries the interrupted bounty once the provider is back");
        int calls = currency.depositCalls;
        fines.recoverOnLogin(guard);
        assertEquals(calls, currency.depositCalls,
                "a surviving receipt makes recovery idempotent");
        fines.recoverOnLogin(senior);
        assertEquals(calls, currency.depositCalls,
                "another officer cannot claim the recorded bounty");
    }

    @Test
    void paidArrestRewardIsNotRedeliveredOnReconnect() {
        var task = fines.createJailerAssaultMission(civ, "temnicer", "WOUNDED");
        assertTrue(fines.acceptTask(guard, task.id));
        var currency = (TestCurrency) ctx.currency();
        fines.suspectKilled(guard, civ);
        int calls = currency.depositCalls;
        fines.recoverOnLogin(guard);
        assertEquals(calls, currency.depositCalls,
                "an already-paid bounty is skipped silently on login");
    }

    // ------------------------------------------------------------ appeals

    @Test
    void appealFilesAndFreezesFine() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, civ, 25, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        assertTrue(fines.appeal(civ, "F1", "nu sunt vinovat"));
        var fine = fine("F1");
        assertEquals("APPEAL_PENDING", fine.status);
        assertNotNull(fine.appeal);
        assertEquals("PENDING", fine.appeal.status);
        // escalation paused while pending
        clock.advance(999_999_999L);
        fines.tick();
        assertEquals("APPEAL_PENDING", fine.status);
    }

    @Test
    void appealReviewVoidWaivesFine() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, civ, 25, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        fines.appeal(civ, "F1", "motiv");
        assertFalse(fines.reviewAppeal(guard, "F1", "void", null, null)); // issuer = guard can't judge own fine
        assertTrue(fines.reviewAppeal(lt, "F1", "void", null, null));
        assertEquals("WAIVED", fine("F1").status);
        assertEquals("VOID", fine("F1").appeal.decision);
    }

    @Test
    void appealReviewReduceRequiresLowerStandardAmount() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, civ, 100, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        fines.appeal(civ, "F1", "motiv");
        assertFalse(fines.reviewAppeal(lt, "F1", "reduce", 250, null)); // not lower
        assertFalse(fines.reviewAppeal(lt, "F1", "reduce", 13, null));  // not standard
        assertTrue(fines.reviewAppeal(lt, "F1", "reduce", 50, null));
        assertEquals(50, fine("F1").amount);
        assertEquals("ISSUED", fine("F1").status);
    }

    @Test
    void appealDeadlineAutoWaives() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, civ, 25, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        fines.appeal(civ, "F1", "motiv");
        clock.advance(6L * 24 * 60 * 60 * 1000); // > 5 real days
        fines.tick();
        var fine = fine("F1");
        assertEquals("WAIVED", fine.status);
        assertEquals("AUTO_WAIVED", fine.appeal.decision);
    }

    @Test
    void appealAbuseBlocksAfterLimit() {
        giveItem(guard, "straja:fine_book", 1);
        for (int i = 0; i < 5; i++) {
            fines.writeDraft(guard, civ, 25, "lege", "d" + i);
            fines.issueFromDraft(guard, civ);
            String id = "F" + (i + 1);
            assertTrue(fines.appeal(civ, id, "motiv"));
            fines.reviewAppeal(lt, id, "void", null, null);
        }
        // 6th appeal attempt -> blocked
        fines.writeDraft(guard, civ, 25, "lege", "d6");
        fines.issueFromDraft(guard, civ);
        assertFalse(fines.appeal(civ, "F6", "motiv"));
        assertTrue(civ.told("blocate temporar"));
    }

    // ------------------------------------------------------------ complaints

    @Test
    void complaintLifecycleToReward() {
        server.add("raufacator");
        assertTrue(complaints.submit(civ, "raufacator", "furt", "mi-a furat itemi"));
        assertEquals(1, ctx.complaints().read().complaints.size());
        assertTrue(complaints.claim(senior, "C1"));
        assertTrue(complaints.mobilize(senior, "C1", guard));
        assertTrue(complaints.join(guard, "C1"));
        assertTrue(complaints.report(senior, "C1", "probe confirmate"));
        assertTrue(complaints.complainantDecision(civ, "C1", "confirm", null));
        assertTrue(complaints.review(lt, "C1", "approve", 100));
        var complaint = ctx.complaints().read().complaints.get(0);
        assertEquals("CLOSED", complaint.status);
        assertEquals("RESOLVED", complaint.resolution);
        assertEquals("PAID", complaint.rewardStatus);
        // reward split: lead(senior) + joined(guard) = 50 each
        assertTrue(senior.told("50 monede"));
        assertTrue(guard.told("50 monede"));
    }

    @Test
    void complaintRequiresReceptionistToSubmit() {
        civ.x = 100;
        assertFalse(complaints.submit(civ, "x", "furt", "descriere"));
        assertTrue(civ.told("recepționistă"));
    }

    @Test
    void complaintDismissClosesWithoutReward() {
        server.add("x");
        complaints.submit(civ, "x", "furt", "descriere");
        complaints.claim(senior, "C1");
        complaints.report(senior, "C1", "raport");
        complaints.complainantDecision(civ, "C1", "confirm", null);
        assertTrue(complaints.review(lt, "C1", "dismiss", null));
        var complaint = ctx.complaints().read().complaints.get(0);
        assertEquals("CLOSED", complaint.status);
        assertEquals("UNFOUNDED", complaint.resolution);
        assertEquals("NONE", complaint.rewardStatus);
    }

    private boolean hasComplaintAction(TestPlayer p,
            ComplaintRoleplayUseCase.Action action,
            String complaintId) {
        return complaints.availableActions(p).stream()
                .anyMatch(a -> a.action() == action && complaintId.equals(a.complaintId()));
    }

    @Test
    void complaintSubmitBindsOnlineAccusedUuid() {
        var accused = server.add("raufacator");
        assertTrue(complaints.submit(civ, " raufacator ", "furt", "mi-a furat itemi"));
        var complaint = ctx.complaints().read().complaints.get(0);
        assertEquals(accused.uuid().toString(), complaint.accusedUuid);
        assertEquals("raufacator", complaint.accused,
                "the stored name comes from the resolved player, not the raw input");
        var auditEntry = ctx.audit().tail(50).stream()
                .filter(e -> "complaint_submit".equals(e.action)).findFirst().orElseThrow();
        assertEquals(accused.uuid().toString(), auditEntry.targetUuid,
                "the submit audit binds the resolved accused uuid");
    }

    @Test
    void complaintSubmitRejectsUnknownOfflineOrSelfAccused() {
        assertFalse(complaints.submit(civ, "ghost", "furt", "d"));
        var offline = server.add("offguy");
        offline.online = false;
        assertFalse(complaints.submit(civ, "offguy", "furt", "d"));
        assertFalse(complaints.submit(civ, "civ1", "furt", "d"), "self-target is rejected");
        assertTrue(ctx.complaints().read().complaints.isEmpty());
    }

    @Test
    void complaintSubmitRejectsOversizedFields() {
        server.add("raufacator");
        assertFalse(complaints.submit(civ, "r".repeat(81), "furt", "d"));
        assertFalse(complaints.submit(civ, "raufacator", "c".repeat(81), "d"));
        assertFalse(complaints.submit(civ, "raufacator", "furt",
                "d".repeat(ctx.policies().complaintMaxDescriptionLength + 1)));
        assertTrue(ctx.complaints().read().complaints.isEmpty(),
                "oversized fields must be rejected, not silently truncated");
    }

    @Test
    void complaintSubmitRejectsWrongDimension() {
        server.add("raufacator");
        civ.dimension = "minecraft:nether";
        assertFalse(complaints.submit(civ, "raufacator", "furt", "d"),
                "a configured location dimension must gate the check");
        civ.dimension = "minecraft:overworld";
        assertTrue(complaints.submit(civ, "raufacator", "furt", "d"));
    }

    @Test
    void complaintClaimReportReviewRequireSecretary() {
        server.add("raufacator");
        complaints.submit(civ, "raufacator", "furt", "d");
        senior.x = 100;
        assertFalse(complaints.claim(senior, "C1"), "claim requires the secretary");
        assertFalse(complaints.claim(civ, "C1"), "capability check still applies");
        senior.x = 0;
        assertTrue(complaints.claim(senior, "C1"));
        senior.x = 100;
        assertFalse(complaints.report(senior, "C1", "raport"), "report requires the secretary");
        senior.x = 0;
        assertTrue(complaints.report(senior, "C1", "raport"));
        boss.x = 100;
        assertFalse(complaints.review(boss, "C1", "approve", 50), "review requires the secretary");
        boss.x = 0;
        assertTrue(complaints.review(boss, "C1", "approve", 50));
    }

    @Test
    void complaintClaimFirstInvestigatorWins() {
        server.add("raufacator");
        complaints.submit(civ, "raufacator", "furt", "d");
        assertTrue(complaints.claim(senior, "C1"));
        assertFalse(complaints.claim(lt, "C1"), "a second investigator cannot take the lead");
        assertEquals(senior.uuid().toString(),
                ctx.complaints().read().find("C1").leadUuid);
    }

    @Test
    void complaintJoinLeaveFlow() {
        server.add("raufacator");
        complaints.submit(civ, "raufacator", "furt", "d");
        complaints.claim(senior, "C1");
        assertTrue(complaints.mobilize(senior, "C1", guard));
        guard.x = 100;
        assertFalse(complaints.join(guard, "C1"), "join requires the secretary or receptionist");
        guard.x = 0;
        assertTrue(complaints.join(guard, "C1"));
        assertTrue(complaints.leave(guard, "C1"));
        assertFalse(hasComplaintAction(guard,
                ComplaintRoleplayUseCase.Action.LEAVE, "C1"));
    }

    @Test
    void complainantConfirmTransitionsToUnderReview() {
        server.add("raufacator");
        complaints.submit(civ, "raufacator", "furt", "d");
        complaints.claim(senior, "C1");
        complaints.report(senior, "C1", "raport");
        assertTrue(complaints.confirm(civ, "C1"));
        assertEquals("UNDER_REVIEW", ctx.complaints().read().find("C1").status);
    }

    @Test
    void withdrawalRequiresNonblankBoundedReason() {
        server.add("raufacator");
        complaints.submit(civ, "raufacator", "furt", "d");
        complaints.claim(senior, "C1");
        complaints.report(senior, "C1", "raport");
        assertFalse(complaints.withdraw(civ, "C1", "   "));
        assertFalse(complaints.withdraw(civ, "C1", "x".repeat(241)));
        assertEquals("REPORT_SUBMITTED", ctx.complaints().read().find("C1").status,
                "invalid withdrawal reasons must not mutate the case");
        assertTrue(complaints.withdraw(civ, "C1", "  nu mai doresc  "));
        var complaint = ctx.complaints().read().find("C1");
        assertEquals("WITHDRAWN", complaint.status);
        assertEquals("nu mai doresc", complaint.withdrawReason);
        assertFalse(complaints.withdraw(civ, "C1", "din nou"),
                "a stale duplicate withdrawal must be rejected");
    }

    @Test
    void complaintProjectionEmitsRoleAwareActions() {
        server.add("raufacator");
        assertTrue(hasComplaintAction(civ,
                ComplaintRoleplayUseCase.Action.SUBMIT, ""));
        assertFalse(hasComplaintAction(civ,
                ComplaintRoleplayUseCase.Action.LIST, ""));
        complaints.submit(civ, "raufacator", "furt", "d");
        assertTrue(hasComplaintAction(senior,
                ComplaintRoleplayUseCase.Action.LIST, ""));
        assertTrue(hasComplaintAction(senior,
                ComplaintRoleplayUseCase.Action.CLAIM, "C1"));
        complaints.claim(senior, "C1");
        assertFalse(hasComplaintAction(lt,
                ComplaintRoleplayUseCase.Action.CLAIM, "C1"),
                "another investigator cannot re-claim a led case");
        assertTrue(hasComplaintAction(senior,
                ComplaintRoleplayUseCase.Action.REPORT, "C1"));
        complaints.report(senior, "C1", "raport");
        assertFalse(hasComplaintAction(senior,
                ComplaintRoleplayUseCase.Action.REPORT, "C1"));
        assertTrue(hasComplaintAction(civ,
                ComplaintRoleplayUseCase.Action.CONFIRM, "C1"));
        assertTrue(hasComplaintAction(civ,
                ComplaintRoleplayUseCase.Action.WITHDRAW, "C1"));
        assertTrue(hasComplaintAction(lt,
                ComplaintRoleplayUseCase.Action.REVIEW, "C1"));
        complaints.confirm(civ, "C1");
        complaints.review(lt, "C1", "approve", 50);
        assertFalse(hasComplaintAction(civ,
                ComplaintRoleplayUseCase.Action.CONFIRM, "C1"));
        assertFalse(hasComplaintAction(lt,
                ComplaintRoleplayUseCase.Action.REVIEW, "C1"),
                "closed cases expose no actions");
    }

    @Test
    void complaintLimitsClampToFormProtocol() {
        var limits = complaints.limits();
        assertEquals(240, limits.withdrawalReason());
        assertEquals(Math.min(ctx.policies().complaintMaxDescriptionLength, 2000),
                limits.description());
        assertEquals(Math.min(ctx.policies().complaintMaxEvidenceLength, 2000),
                limits.evidence());
        ctx.policies().complaintMaxDescriptionLength = 9000;
        assertEquals(2000, complaints.limits().description());
    }

    // ------------------------------------------------------------ fine roleplay port

    private boolean hasFineAction(TestPlayer p, FineRoleplayUseCase.Action action, String recordId) {
        return fines.availableActions(p).stream()
                .anyMatch(a -> a.action() == action && recordId.equals(a.recordId()));
    }

    @Test
    void fineDraftFormResolvesOnlineTargetByName() {
        giveItem(guard, "straja:fine_book", 1);
        assertFalse(fines.writeDraft(guard, "ghost", 25, "lege", "descriere"));
        var offline = server.add("off1");
        offline.online = false;
        assertFalse(fines.writeDraft(guard, "off1", 25, "lege", "d"));
        assertFalse(fines.writeDraft(guard, "guard1", 25, "lege", "d"), "self-target rejected");
        assertFalse(fines.writeDraft(guard, "lt1", 25, "lege", "d"), "higher rank rejected");
        assertTrue(fines.writeDraft(guard, "civ1", 25, "lege", "descriere"));
        var draft = ctx.fines().read().drafts.get(guard.uuid().toString());
        assertEquals(civ.uuid().toString(), draft.targetUuid,
                "the form binds the resolved uuid, never a client-supplied one");
        assertTrue(fines.issueFromDraft(guard, civ));
        assertEquals("ISSUED", fine("F1").status);
        // re-issue with a fresh draft stays idempotent
        fines.writeDraft(guard, "civ1", 25, "lege", "descriere");
        assertTrue(fines.issueFromDraft(guard, civ));
        assertEquals(1, ctx.fines().read().fines.size());
    }

    @Test
    void finePayAppealRefuseReviewLocationAndDimensionGates() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, "civ1", 25, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        var currency = (TestCurrency) ctx.currency();
        currency.balance = 25;
        civ.dimension = "minecraft:nether";
        assertFalse(fines.pay(civ, "F1"), "wrong dimension rejects payment at equal coordinates");
        civ.dimension = "minecraft:overworld";
        civ.x = 100;
        assertFalse(fines.appeal(civ, "F1", "motiv"), "appeal requires the receptionist");
        civ.x = 0;
        assertTrue(fines.appeal(civ, "F1", "motiv"));
        lt.x = 100;
        assertFalse(fines.reviewAppeal(lt, "F1", "uphold", null, "motiv"),
                "appeal review requires the reviewer at the receptionist");
        lt.x = 0;
        lt.dimension = "minecraft:nether";
        assertFalse(fines.reviewAppeal(lt, "F1", "uphold", null, "motiv"),
                "wrong dimension rejects the review");
        lt.dimension = "minecraft:overworld";
        assertTrue(fines.reviewAppeal(lt, "F1", "uphold", null, ""));
        assertTrue(fines.pay(civ, "F1"));
        assertEquals("PAID", fine("F1").status);
    }

    @Test
    void fineRefusalRequiresTargetAtReceptionist() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, "civ1", 25, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        shrinkGrace("F1", 5_000);
        clock.advance(6_000);
        fines.tick();
        fines.acceptTask(guard, "FM-F1");
        fines.completeTask(guard, "FM-F1");
        civ.x = 100;
        assertFalse(fines.refusePayment(civ, "FM-F1"),
                "the citizen must be at the receptionist to refuse");
        civ.dimension = "minecraft:nether";
        civ.x = 0;
        assertFalse(fines.refusePayment(civ, "FM-F1"),
                "the citizen must share the configured dimension");
        civ.dimension = "minecraft:overworld";
        assertTrue(fines.refusePayment(civ, "FM-F1"));
    }

    @Test
    void finePayBindsPersistedTargetNotNoticeId() {
        var other = server.add("civ2");
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, "civ1", 25, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        var currency = (TestCurrency) ctx.currency();
        currency.balance = 100;
        assertFalse(fines.pay(other, "F1"),
                "a forged or shared notice id cannot authorize another citizen's fine");
    }

    @Test
    void fineProjectionEmitsCitizenAndReviewerActions() {
        giveItem(guard, "straja:fine_book", 1);
        assertTrue(hasFineAction(guard, FineRoleplayUseCase.Action.DRAFT_WRITE, ""));
        assertFalse(hasFineAction(civ, FineRoleplayUseCase.Action.DRAFT_WRITE, ""));
        assertFalse(hasFineAction(guard, FineRoleplayUseCase.Action.DRAFT_STATUS, ""));
        fines.writeDraft(guard, "civ1", 25, "lege", "descriere");
        assertTrue(hasFineAction(guard, FineRoleplayUseCase.Action.DRAFT_STATUS, ""));
        fines.issueFromDraft(guard, civ);
        assertFalse(hasFineAction(guard, FineRoleplayUseCase.Action.DRAFT_STATUS, ""));
        assertTrue(hasFineAction(civ, FineRoleplayUseCase.Action.PAY, "F1"));
        assertTrue(hasFineAction(civ, FineRoleplayUseCase.Action.APPEAL, "F1"));
        assertFalse(hasFineAction(civ, FineRoleplayUseCase.Action.REVIEW_APPEAL, "F1"));
        assertTrue(fines.appeal(civ, "F1", "motiv"));
        assertFalse(hasFineAction(civ, FineRoleplayUseCase.Action.APPEAL, "F1"),
                "an active appeal removes the action");
        assertFalse(hasFineAction(civ, FineRoleplayUseCase.Action.PAY, "F1"),
                "payment is frozen while the appeal is pending");
        assertTrue(hasFineAction(lt, FineRoleplayUseCase.Action.LIST_APPEALS, ""));
        assertTrue(hasFineAction(lt, FineRoleplayUseCase.Action.REVIEW_APPEAL, "F1"));
        assertFalse(hasFineAction(guard, FineRoleplayUseCase.Action.REVIEW_APPEAL, "F1"),
                "the issuer cannot review their own fine");
        assertTrue(fines.reviewAppeal(lt, "F1", "uphold", null, ""));
        assertFalse(hasFineAction(lt, FineRoleplayUseCase.Action.REVIEW_APPEAL, "F1"),
                "a decided appeal is no longer reviewable");
        assertTrue(hasFineAction(civ, FineRoleplayUseCase.Action.PAY, "F1"));
    }

    @Test
    void fineTaskProjectionAndArrestRewardClaim() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, "civ1", 100, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        shrinkGrace("F1", 5_000);
        clock.advance(6_000);
        fines.tick();
        assertTrue(hasFineAction(guard, FineRoleplayUseCase.Action.LIST_TASKS, ""));
        assertTrue(hasFineAction(guard, FineRoleplayUseCase.Action.ACCEPT_TASK, "FM-F1"));
        assertFalse(hasFineAction(civ, FineRoleplayUseCase.Action.ACCEPT_TASK, "FM-F1"));
        assertFalse(fines.acceptTask(civ, "FM-F1"));
        assertTrue(fines.acceptTask(guard, "FM-F1"));
        assertFalse(hasFineAction(guard, FineRoleplayUseCase.Action.ACCEPT_TASK, "FM-F1"));
        assertTrue(hasFineAction(guard, FineRoleplayUseCase.Action.COMPLETE_TASK, "FM-F1"));
        assertTrue(fines.completeTask(guard, "FM-F1"));
        assertFalse(hasFineAction(guard, FineRoleplayUseCase.Action.COMPLETE_TASK, "FM-F1"));
        assertTrue(hasFineAction(civ, FineRoleplayUseCase.Action.REFUSE, "FM-F1"));
        assertTrue(fines.refusePayment(civ, "FM-F1"));
        assertFalse(hasFineAction(civ, FineRoleplayUseCase.Action.REFUSE, "FM-F1"));
        assertTrue(hasFineAction(guard, FineRoleplayUseCase.Action.ARREST_TASK, "FM-F1"));
        var currency = (TestCurrency) ctx.currency();
        currency.available = false;
        assertTrue(fines.arrest(guard, "FM-F1", null));
        assertTrue(hasFineAction(guard, FineRoleplayUseCase.Action.CLAIM_TASK_REWARD, "FM-F1"),
                "an unpaid receipt keeps the claim action available");
        currency.available = true;
        int balance = currency.balance;
        assertTrue(fines.claimTaskReward(guard, "FM-F1"));
        assertTrue(currency.balance > balance, "the recovered deposit pays after the outage");
        assertFalse(hasFineAction(guard, FineRoleplayUseCase.Action.CLAIM_TASK_REWARD, "FM-F1"));
        int deposits = currency.depositCalls;
        assertTrue(fines.claimTaskReward(guard, "FM-F1"),
                "a duplicate claim reports already paid instead of failing");
        assertEquals(deposits, currency.depositCalls, "the receipt prevents a second payout");
        assertFalse(fines.claimTaskReward(senior, "FM-F1"),
                "a non-assignee cannot claim the reward");
        assertFalse(fines.claimTaskReward(civ, "FM-F1"));
    }

    @Test
    void hearingWarrantResolvesTargetByNameAndDedupes() {
        assertTrue(fines.issueHearingWarrant(lt, "civ1", "motiv audiere"));
        assertFalse(fines.issueHearingWarrant(lt, "ghost", "motiv"), "unknown target rejected");
        assertFalse(fines.issueHearingWarrant(lt, "civ1", "alt motiv"), "active warrant deduped");
        assertTrue(hasFineAction(lt, FineRoleplayUseCase.Action.HEARING_WARRANT, ""));
        assertFalse(hasFineAction(guard, FineRoleplayUseCase.Action.HEARING_WARRANT, ""));
        assertTrue(hasFineAction(boss, FineRoleplayUseCase.Action.HEARING_WARRANT, ""));
    }

    @Test
    void appealReviewRejectsOversizedReasonWithoutMutation() {
        giveItem(guard, "straja:fine_book", 1);
        fines.writeDraft(guard, "civ1", 25, "lege", "descriere");
        fines.issueFromDraft(guard, civ);
        fines.appeal(civ, "F1", "motiv");
        assertFalse(fines.reviewAppeal(lt, "F1", "uphold", null, "x".repeat(241)),
                "an oversized decision reason is rejected, not truncated");
        var stored = fine("F1");
        assertEquals("APPEAL_PENDING", stored.status);
        assertEquals("PENDING", stored.appeal.status);
        assertTrue(fines.reviewAppeal(lt, "F1", "uphold", null, ""),
                "a blank reason stays allowed for compatibility");
        assertEquals("UPHELD", fine("F1").appeal.status);
    }

    @Test
    void fineLimitsClampToFormProtocol() {
        var limits = fines.limits();
        assertEquals(Math.min(ctx.policies().fineMaxLawLength, 2000), limits.law());
        assertEquals(Math.min(ctx.policies().fineMaxDescriptionLength, 2000), limits.description());
        assertEquals(Math.min(ctx.policies().appealMaxReasonLength, 2000), limits.appealReason());
        assertEquals(240, limits.reviewReason());
        assertEquals(240, limits.warrantReason());
        ctx.policies().fineMaxDescriptionLength = 9000;
        assertEquals(2000, fines.limits().description());
    }

    // ------------------------------------------------------------ rooms

    @Test
    void roomDiscoveryAssignsAndProtects() {
        var world = (TestWorld) ctx.world();
        world.room("minecraft:overworld", 10, 60, 10, 14, 64, 14, 10, 61, 12);
        boss.x = 12; boss.y = 61; boss.z = 12;
        var room = rooms.discover(boss, "camera_test");
        assertNotNull(room);
        assertEquals("camera_test", room.id);
        assertTrue(room.hasDoor);
        assertFalse(world.signs.isEmpty());
        // junior+ eligible gets the room
        setRank(civ, Rank.STAGIAR);
        String result = rooms.assignAutomatically(civ);
        assertTrue(result.startsWith("ASSIGNED"));
        assertNotNull(rooms.assignedRoom(civ));
        // protection: stranger cannot break inside
        var stranger = server.add("stranger");
        assertTrue(rooms.protectBlock(stranger, "minecraft:overworld", 12, 61, 12));
        assertFalse(rooms.protectBlock(civ, "minecraft:overworld", 12, 61, 12));
    }

    @Test
    void occupiedRoomProtectsContainerAndPlacementPositions() {
        var world = (TestWorld) ctx.world();
        world.room("minecraft:overworld", 10, 60, 10, 14, 64, 14, 10, 61, 12);
        boss.x = 12; boss.y = 61; boss.z = 12;
        assertNotNull(rooms.discover(boss, "camera_protected"));
        setRank(civ, Rank.STAGIAR);
        assertTrue(rooms.assignAutomatically(civ).startsWith("ASSIGNED"));

        var stranger = server.add("container_griefer");
        assertTrue(rooms.protectBlock(stranger, "minecraft:overworld", 12, 61, 12));
        assertTrue(rooms.protectBlock(stranger, "minecraft:overworld", 12, 61, 13));
        assertFalse(rooms.protectBlock(civ, "minecraft:overworld", 12, 61, 12));
    }

    @Test
    void roomWaitlistFifoReservesForOffline() {
        var world = (TestWorld) ctx.world();
        world.room("minecraft:overworld", 10, 60, 10, 14, 64, 14, 10, 61, 12);
        boss.x = 12; boss.y = 61; boss.z = 12;
        rooms.discover(boss, "camera_1");
        var civ2 = server.add("civ2");
        var civ3 = server.add("civ3");
        setRank(civ, Rank.STAGIAR);
        setRank(civ2, Rank.STAGIAR);
        setRank(civ3, Rank.STAGIAR);
        // civ takes the only room; civ2 and civ3 queue
        assertTrue(rooms.assignAutomatically(civ).startsWith("ASSIGNED"));
        assertTrue(rooms.assignAutomatically(civ2).startsWith("WAITING:1"));
        assertTrue(rooms.assignAutomatically(civ3).startsWith("WAITING:2"));
        // civ releases; the freed room goes to the first queued player
        assertTrue(rooms.releaseFor(civ));
        assertNotNull(rooms.assignedRoom(civ2));
        assertNull(rooms.assignedRoom(civ3));
        // FIFO: an offline first entry keeps the next free room reserved
        civ3.online = false;
        var civ4 = server.add("civ4");
        setRank(civ4, Rank.STAGIAR);
        assertTrue(rooms.assignAutomatically(civ4).startsWith("WAITING:2"));
        rooms.releaseFor(civ2);
        rooms.processWaitlist();
        assertNull(rooms.assignedRoom(civ4)); // reserved for offline civ3
        civ3.online = true;
        rooms.processWaitlist();
        assertNotNull(rooms.assignedRoom(civ3));
        assertNull(rooms.assignedRoom(civ4)); // no more free rooms
    }

    @Test
    void waitlistEntriesExpireAfterRetentionDays() {
        var world = (TestWorld) ctx.world();
        world.room("minecraft:overworld", 10, 60, 10, 14, 64, 14, 10, 61, 12);
        boss.x = 12; boss.y = 61; boss.z = 12;
        rooms.discover(boss, "camera_1");
        var civ2 = server.add("civ2");
        setRank(civ, Rank.STAGIAR);
        setRank(civ2, Rank.STAGIAR);
        civ2.online = false;
        // civ takes the only room; offline civ2 queues first
        assertTrue(rooms.assignAutomatically(civ).startsWith("ASSIGNED"));
        assertTrue(rooms.assignAutomatically(civ2).startsWith("WAITING:1"));
        // civ2's entry ages past the retention window
        var data = ctx.rooms().read();
        data.waitlist.get(0).queuedAt =
                clock.nowMillis() - (ctx.policies().roomWaitlistRetentionDays + 1L) * 86_400_000L;
        ctx.rooms().write(data);
        // the stale entry is pruned and no longer reserves the freed room
        rooms.processWaitlist();
        assertTrue(ctx.rooms().read().waitlist.isEmpty());
    }

    @Test
    void roomAssignmentWaitlistAndProtectionAreStable() {
        var world = (TestWorld) ctx.world();
        world.room("minecraft:overworld", 10, 60, 10, 14, 64, 14, 10, 61, 12);
        boss.x = 12; boss.y = 61; boss.z = 12;
        rooms.discover(boss, "camera_1");
        var civ2 = server.add("civ2");
        setRank(civ, Rank.STAGIAR);
        setRank(civ2, Rank.STAGIAR);
        assertTrue(rooms.assignAutomatically(civ).startsWith("ASSIGNED"));
        // repeated waits stay a single entry
        assertTrue(rooms.assignAutomatically(civ2).startsWith("WAITING:1"));
        assertTrue(rooms.assignAutomatically(civ2).startsWith("WAITING:1"));
        assertEquals(1, ctx.rooms().read().waitlist.size());
        // protection: unrelated blocks stay free, strangers are blocked inside
        var stranger = server.add("stranger");
        assertFalse(rooms.protectBlock(stranger, "minecraft:overworld", 500, 61, 500));
        assertTrue(rooms.protectBlock(stranger, "minecraft:overworld", 12, 61, 12));
        assertFalse(rooms.protectBlock(civ, "minecraft:overworld", 12, 61, 12));
    }

    @Test
    void roomMarkerSelectionIsCommissionerOnly() {
        rooms.markerSelect(civ, "minecraft:overworld", 1, 60, 1);
        assertTrue(civ.told("Comisaru'"));
        assertTrue(ctx.rooms().read().selections.isEmpty());
        rooms.markerSelect(boss, "minecraft:overworld", 1, 60, 1);
        assertTrue(ctx.rooms().read().selections.containsKey(boss.uuid().toString()));
    }

    @Test
    void roomRejectsOpenWall() {
        var world = (TestWorld) ctx.world();
        // boundary shell missing a wall block -> OPEN_WALL
        for (int x = 10; x <= 14; x++) for (int y = 60; y <= 64; y++) for (int z = 10; z <= 14; z++) {
            boolean boundary = x == 10 || x == 14 || y == 60 || y == 64 || z == 10 || z == 14;
            if (boundary && !(x == 10 && (y == 61 || y == 62) && z == 12) && !(x == 14 && y == 61 && z == 12)) {
                world.set("minecraft:overworld", x, y, z, new WorldGateway.BlockInfo("minecraft:stone", false, false, true));
            }
        }
        world.set("minecraft:overworld", 10, 61, 12, new WorldGateway.BlockInfo("minecraft:oak_door", false, true, false));
        world.set("minecraft:overworld", 10, 62, 12, new WorldGateway.BlockInfo("minecraft:oak_door", false, true, false));
        boss.x = 12; boss.y = 61; boss.z = 12;
        var room = rooms.discover(boss, "camera_open");
        assertNull(room);
        assertTrue(boss.told("depășește")); // flood fill escapes → TOO_LARGE
    }

    // ------------------------------------------------------------ archive

    @Test
    void archiveFolderSheetSignFlow() {
        assertTrue(archive.grantArchivist(boss, guard, true));
        assertTrue(archive.createFolder(guard, "Dosar Test", "Straja"));
        assertTrue(archive.newSheet(guard, "D-1", "PROCES", "Prima foaie"));
        assertTrue(archive.editSheet(guard, "A-1", "conținut act"));
        assertTrue(archive.setRecipients(guard, "A-1", "civ1, civ2"));
        assertTrue(archive.submitSheet(guard, "A-1"));
        assertEquals("PENDING_SIGNATURE", ctx.archive().read().sheets.get("A-1").status);
        // junior guard can't sign; lt can — but only while carrying the stamp
        assertFalse(archive.signSheet(guard, "A-1", "verificat"));
        giveItem(lt, "straja:archive_stamp", 1);
        assertTrue(archive.signSheet(lt, "A-1", "verificat"));
        var sheet = ctx.archive().read().sheets.get("A-1");
        assertEquals("SIGNED", sheet.status);
        assertEquals("lt1", sheet.signedBy.name);
        assertEquals(1, lt.inventory().countOf("straja:archive_stamp"), "stamp is not consumed");
    }

    @Test
    void archiveCopyConsumesCarbonAndNumbersCopies() {
        archive.grantArchivist(boss, guard, true);
        archive.createFolder(guard, "Dosar", null);
        archive.newSheet(guard, "D-1", "PROCES", "Foaie");
        archive.editSheet(guard, "A-1", "conținut");
        archive.submitSheet(guard, "A-1");
        giveItem(lt, "straja:archive_stamp", 1);
        archive.signSheet(lt, "A-1", "ok");
        giveItem(guard, "straja:carbon_paper", 3);
        assertTrue(archive.copySheet(guard, "A-1", 2, "civ1,civ2"));
        assertEquals(1, guard.inventory().countOf("straja:carbon_paper"));
        var store = ctx.archive().read();
        assertEquals(2, store.copies.size());
        assertEquals(Integer.valueOf(1), store.copies.get(0).copyNumber);
        assertEquals(Integer.valueOf(2), store.copies.get(1).copyNumber);
        // both targets online -> delivered
        assertEquals("DELIVERED", store.copies.get(0).status);
        assertEquals(1, civ.inventory().countOf("straja:archive_document"));
    }

    @Test
    void archiveCopyAbortsWhenCarbonMissing() {
        archive.grantArchivist(boss, guard, true);
        archive.createFolder(guard, "Dosar", null);
        archive.newSheet(guard, "D-1", "PROCES", "Foaie");
        archive.editSheet(guard, "A-1", "conținut");
        archive.submitSheet(guard, "A-1");
        giveItem(lt, "straja:archive_stamp", 1);
        archive.signSheet(lt, "A-1", "ok");
        assertFalse(archive.copySheet(guard, "A-1", 2, "civ1"));
        assertTrue(ctx.archive().read().copies.isEmpty());
    }

    @Test
    void archiveEnvelopeConsumesSourceOnlyAfterCapacityCheck() {
        archive.grantArchivist(boss, guard, true);
        archive.createFolder(guard, "Dosar", null);
        archive.newSheet(guard, "D-1", "PROCES", "Foaie");
        archive.editSheet(guard, "A-1", "conținut");
        archive.submitSheet(guard, "A-1");
        giveItem(lt, "straja:archive_stamp", 1);
        archive.signSheet(lt, "A-1", "ok");
        giveItem(guard, "straja:official_envelope", 1);
        assertTrue(archive.packEnvelope(guard, "A-1", "civ1"));
        assertEquals(0, guard.inventory().countOf("straja:official_envelope"));
        var copy = ctx.archive().read().copies.get(0);
        assertEquals("DELIVERED", copy.status);
        assertEquals("CONSUMED", copy.sourceState);
    }

    @Test
    void archiveRevokeMarksSheet() {
        archive.grantArchivist(boss, guard, true);
        archive.createFolder(guard, "Dosar", null);
        archive.newSheet(guard, "D-1", "PROCES", "Foaie");
        archive.editSheet(guard, "A-1", "conținut");
        archive.submitSheet(guard, "A-1");
        giveItem(lt, "straja:archive_stamp", 1);
        archive.signSheet(lt, "A-1", "ok");
        assertTrue(archive.revokeSheet(lt, "A-1"));
        assertEquals("REVOKED", ctx.archive().read().sheets.get("A-1").status);
    }

    @Test
    void archiveSignRequiresStampAndRejectsOversizedReason() {
        archive.grantArchivist(boss, guard, true);
        archive.createFolder(guard, "Dosar", null);
        archive.newSheet(guard, "D-1", "PROCES", "Foaie");
        archive.editSheet(guard, "A-1", "conținut");
        archive.submitSheet(guard, "A-1");
        // a signer without the physical stamp cannot sign
        assertFalse(archive.signSheet(lt, "A-1", "verificat"));
        assertEquals("PENDING_SIGNATURE", ctx.archive().read().sheets.get("A-1").status);
        giveItem(lt, "straja:archive_stamp", 1);
        assertFalse(archive.signSheet(lt, "A-1", "x".repeat(241)),
                "oversized reason is rejected, not truncated");
        assertEquals("PENDING_SIGNATURE", ctx.archive().read().sheets.get("A-1").status);
        assertTrue(archive.signSheet(lt, "A-1", "verificat"));
        assertEquals(1, lt.inventory().countOf("straja:archive_stamp"));
    }

    @Test
    void archiveSetRecipientsPinsUuidAndDedupes() {
        archive.grantArchivist(boss, guard, true);
        archive.createFolder(guard, "Dosar", null);
        archive.newSheet(guard, "D-1", "PROCES", "Foaie");
        assertTrue(archive.setRecipients(guard, "A-1", "civ1, CIV1, ghost"));
        var sheet = ctx.archive().read().sheets.get("A-1");
        assertEquals(2, sheet.recipients.size(), "uuid/name duplicates collapse");
        var civRecipient = sheet.recipients.stream()
                .filter(r -> "civ1".equalsIgnoreCase(r.name)).findFirst().orElseThrow();
        assertEquals(civ.uuid().toString(), civRecipient.uuid,
                "online recipient gets its UUID pinned");
        var ghost = sheet.recipients.stream()
                .filter(r -> "ghost".equalsIgnoreCase(r.name)).findFirst().orElseThrow();
        assertEquals("", ghost.uuid, "unresolvable recipient stays name-only");
    }

    @Test
    void archiveIssueFolderGrantsPersistedReaderAndDeliversItem() {
        archive.grantArchivist(boss, guard, true);
        archive.createFolder(guard, "Dosar", null);
        // self / offline / unknown targets rejected
        assertFalse(archive.issueFolder(guard, "D-1", "guard1"));
        var offline = server.add("civ9");
        offline.online = false;
        assertFalse(archive.issueFolder(guard, "D-1", "civ9"));
        assertFalse(archive.issueFolder(guard, "D-1", "ghost"));
        assertTrue(archive.issueFolder(guard, "D-1", "civ1"));
        assertEquals(1, civ.inventory().countOf("straja:archive_folder"));
        var folder = ctx.archive().read().folders.get("D-1");
        assertTrue(folder.readers.stream().anyMatch(r -> civ.uuid().toString().equals(r.uuid)),
                "recipient is persisted as a reader with pinned UUID");
        // persisted grant, not item metadata, authorizes reads
        archive.readFolder(civ, "D-1");
        assertTrue(civ.told("D-1"));
    }

    @Test
    void archiveItemMetadataAloneCannotAuthorizeReads() {
        archive.grantArchivist(boss, guard, true);
        archive.createFolder(guard, "Dosar", null);
        archive.newSheet(guard, "D-1", "PROCES", "Foaie");
        // civ holds a forged folder/document reference but has no persisted grant
        civ.give(ItemSpec.of("straja:archive_folder", 1).withData("ArchiveFolderId", "D-1"));
        civ.give(ItemSpec.of("straja:archive_document", 1).withData("ArchiveDocumentId", "A-1"));
        archive.readFolder(civ, "D-1");
        assertTrue(civ.told("nu ai acces"));
        archive.readSheet(civ, "A-1");
        assertTrue(civ.told("nu există sau nu ai acces"));
    }

    @Test
    void archiveIssueDocumentDeliversOnceAndPinsRecipient() {
        archive.grantArchivist(boss, guard, true);
        archive.createFolder(guard, "Dosar", null);
        archive.newSheet(guard, "D-1", "PROCES", "Foaie");
        archive.editSheet(guard, "A-1", "conținut");
        archive.submitSheet(guard, "A-1");
        giveItem(lt, "straja:archive_stamp", 1);
        archive.signSheet(lt, "A-1", "ok");
        // unsigned/other sheets and unauthorized issuers fail
        assertFalse(archive.issueDocument(civ, "A-1", "guard1"));
        assertTrue(archive.issueDocument(guard, "A-1", "civ1"));
        assertEquals(1, civ.inventory().countOf("straja:archive_document"));
        var sheet = ctx.archive().read().sheets.get("A-1");
        assertTrue(sheet.recipients.stream().anyMatch(
                r -> civ.uuid().toString().equals(r.uuid)),
                "target persisted as recipient with pinned UUID");
        assertTrue(ctx.archive().read().copies.stream().anyMatch(c ->
                "DELIVERED".equals(c.status) && civ.uuid().toString().equals(c.recipientUuid)),
                "a DELIVERED record pins the target UUID");
        // duplicate issuance must not deliver another item
        assertFalse(archive.issueDocument(guard, "A-1", "civ1"));
        assertEquals(1, civ.inventory().countOf("straja:archive_document"));
        // the persisted grant alone authorizes the read
        archive.readSheet(civ, "A-1");
        assertTrue(civ.told("A-1"));
    }

    @Test
    void archivePendingDeliveryIsUuidBoundIdempotentAndMalformedSafe() {
        archive.grantArchivist(boss, guard, true);
        archive.createFolder(guard, "Dosar", null);
        archive.newSheet(guard, "D-1", "PROCES", "Foaie");
        archive.editSheet(guard, "A-1", "conținut");
        archive.submitSheet(guard, "A-1");
        giveItem(lt, "straja:archive_stamp", 1);
        archive.signSheet(lt, "A-1", "ok");
        civ.online = false;
        giveItem(guard, "straja:carbon_paper", 1);
        assertTrue(archive.copySheet(guard, "A-1", 1, "civ1"));
        var pending = ctx.archive().read().copies.get(0);
        assertEquals("PENDING", pending.status);
        // a copy pinned to a different UUID can never be claimed by civ1
        var store = ctx.archive().read();
        var foreign = new com.dwurdy.straja.domain.model.ArchiveStore.Copy();
        foreign.id = "CP-X";
        foreign.originalId = "A-1";
        foreign.recipientName = "civ1";
        foreign.recipientKey = "civ1";
        foreign.recipientUuid = java.util.UUID.randomUUID().toString();
        foreign.status = "PENDING";
        store.copies.add(foreign);
        // malformed copies sit alongside and must be discarded safely
        var broken = new com.dwurdy.straja.domain.model.ArchiveStore.Copy();
        broken.id = "CP-BAD";
        broken.originalId = "";
        broken.recipientUuid = civ.uuid().toString();
        broken.status = "PENDING";
        store.copies.add(broken);
        store.copies.add(null);
        ctx.archive().write(store);

        civ.online = true;
        assertDoesNotThrow(() -> archive.deliverPending(civ));
        store = ctx.archive().read();
        assertEquals("DELIVERED", store.copies.get(0).status);
        assertEquals(1, civ.inventory().countOf("straja:archive_document"),
                "exactly one delivery for the UUID-matching copy");
        assertTrue(store.copies.stream().noneMatch(c -> c != null && "CP-X".equals(c.id)
                        && "DELIVERED".equals(c.status)),
                "a copy pinned to another UUID is never delivered to civ1");
        assertTrue(store.copies.stream().noneMatch(c -> c == null || "CP-BAD".equals(c.id)),
                "malformed copies are discarded");
        archive.deliverPending(civ);
        assertEquals(1, civ.inventory().countOf("straja:archive_document"), "no duplicate delivery");
    }

    @Test
    void archivePendingDeliveryNeverDeliversRevokedSheet() {
        archive.grantArchivist(boss, guard, true);
        archive.createFolder(guard, "Dosar", null);
        archive.newSheet(guard, "D-1", "PROCES", "Foaie");
        archive.editSheet(guard, "A-1", "conținut");
        archive.submitSheet(guard, "A-1");
        giveItem(lt, "straja:archive_stamp", 1);
        archive.signSheet(lt, "A-1", "ok");
        civ.online = false;
        giveItem(guard, "straja:carbon_paper", 1);
        archive.copySheet(guard, "A-1", 1, "civ1");
        archive.revokeSheet(lt, "A-1");
        civ.online = true;
        archive.deliverPending(civ);
        assertEquals(0, civ.inventory().countOf("straja:archive_document"),
                "a revoked sheet's pending copy must never deliver");
        assertEquals("FAILED", ctx.archive().read().copies.get(0).status);
    }

    @Test
    void archiveProjectionFollowsStateAndStaleStatesDisappear() {
        // everyone can list; only authorized archivists create folders
        assertTrue(archive.availableActions(civ).stream()
                .anyMatch(a -> a.action() == com.dwurdy.straja.application.port.in
                        .ArchiveRoleplayUseCase.Action.LIST));
        assertFalse(archive.availableActions(civ).stream()
                .anyMatch(a -> a.action() == com.dwurdy.straja.application.port.in
                        .ArchiveRoleplayUseCase.Action.CREATE_FOLDER));
        archive.grantArchivist(boss, guard, true);
        var create = archive.availableActions(guard);
        assertTrue(create.stream().anyMatch(a -> a.action() ==
                com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.CREATE_FOLDER));
        archive.createFolder(guard, "Dosar", null);
        var afterFolder = archive.availableActions(guard);
        assertTrue(afterFolder.stream().anyMatch(a -> a.action() ==
                        com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.READ_FOLDER
                        && "D-1".equals(a.recordId())));
        assertTrue(afterFolder.stream().anyMatch(a -> a.action() ==
                        com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.ISSUE_FOLDER
                        && "D-1".equals(a.recordId())));
        assertTrue(afterFolder.stream().anyMatch(a -> a.action() ==
                        com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase.Action.NEW_SHEET
                        && "D-1".equals(a.recordId())));
        archive.newSheet(guard, "D-1", "PROCES", "Foaie");
        var draft = archive.availableActions(guard);
        assertTrue(draft.stream().anyMatch(a -> a.action().name().equals("EDIT_SHEET")));
        assertTrue(draft.stream().anyMatch(a -> a.action().name().equals("SET_RECIPIENTS")));
        assertFalse(draft.stream().anyMatch(a -> a.action().name().equals("SUBMIT_SHEET")),
                "SUBMIT_SHEET requires nonblank content");
        archive.editSheet(guard, "A-1", "conținut");
        assertTrue(archive.availableActions(guard).stream()
                .anyMatch(a -> a.action().name().equals("SUBMIT_SHEET")));
        archive.submitSheet(guard, "A-1");
        // signer needs the physical stamp before SIGN_SHEET appears
        assertFalse(archive.availableActions(lt).stream()
                .anyMatch(a -> a.action().name().equals("SIGN_SHEET")));
        giveItem(lt, "straja:archive_stamp", 1);
        assertTrue(archive.availableActions(lt).stream()
                .anyMatch(a -> a.action().name().equals("SIGN_SHEET")));
        archive.signSheet(lt, "A-1", "ok");
        var signed = archive.availableActions(guard);
        assertTrue(signed.stream().anyMatch(a -> a.action().name().equals("ISSUE_DOCUMENT")));
        assertTrue(signed.stream().anyMatch(a -> a.action().name().equals("REVOKE_SHEET")));
        assertFalse(signed.stream().anyMatch(a -> a.action().name().equals("COPY_SHEET")),
                "COPY_SHEET requires carbon paper in hand");
        giveItem(guard, "straja:carbon_paper", 1);
        assertTrue(archive.availableActions(guard).stream()
                .anyMatch(a -> a.action().name().equals("COPY_SHEET")));
        archive.revokeSheet(lt, "A-1");
        assertFalse(archive.availableActions(guard).stream()
                .anyMatch(a -> a.action().name().equals("ISSUE_DOCUMENT")
                        || a.action().name().equals("COPY_SHEET")),
                "revoked sheets lose copy/issue actions");
    }

    @Test
    void archiveLimitsClampToProtocolCaps() {
        var limits = archive.limits();
        assertTrue(limits.title() > 0 && limits.title() <= 2000);
        assertTrue(limits.content() > 0 && limits.content() <= 2000);
        assertEquals(240, limits.signatureReason());
        assertEquals(80, limits.target());
    }
}
