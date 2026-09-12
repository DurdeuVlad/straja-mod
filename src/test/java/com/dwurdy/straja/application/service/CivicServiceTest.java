package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
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
        setRank(lt, Rank.LIEUTENANT);
        setRank(senior, Rank.SENIOR);
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
        assertTrue(guard.told("Locotenent"));
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
        setRank(civ, Rank.JUNIOR);
        String result = rooms.assignAutomatically(civ);
        assertTrue(result.startsWith("ASSIGNED"));
        assertNotNull(rooms.assignedRoom(civ));
        // protection: stranger cannot break inside
        var stranger = server.add("stranger");
        assertTrue(rooms.protectBlock(stranger, "minecraft:overworld", 12, 61, 12));
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
        setRank(civ, Rank.JUNIOR);
        setRank(civ2, Rank.JUNIOR);
        setRank(civ3, Rank.JUNIOR);
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
        setRank(civ4, Rank.JUNIOR);
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
        setRank(civ, Rank.JUNIOR);
        setRank(civ2, Rank.JUNIOR);
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
        // junior guard can't sign; lt can
        assertFalse(archive.signSheet(guard, "A-1", "verificat"));
        assertTrue(archive.signSheet(lt, "A-1", "verificat"));
        var sheet = ctx.archive().read().sheets.get("A-1");
        assertEquals("SIGNED", sheet.status);
        assertEquals("lt1", sheet.signedBy.name);
    }

    @Test
    void archiveCopyConsumesCarbonAndNumbersCopies() {
        archive.grantArchivist(boss, guard, true);
        archive.createFolder(guard, "Dosar", null);
        archive.newSheet(guard, "D-1", "PROCES", "Foaie");
        archive.editSheet(guard, "A-1", "conținut");
        archive.submitSheet(guard, "A-1");
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
        archive.signSheet(lt, "A-1", "ok");
        assertTrue(archive.revokeSheet(lt, "A-1"));
        assertEquals("REVOKED", ctx.archive().read().sheets.get("A-1").status);
    }
}
