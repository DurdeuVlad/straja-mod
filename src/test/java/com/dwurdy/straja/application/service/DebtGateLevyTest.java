package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.Fine;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.LaborCampRecord;
import com.dwurdy.straja.domain.model.PrisonerRegisterRecord;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.Sentence;
import com.dwurdy.straja.domain.model.StoragePoint;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #235 — DEBT-2 locker levy + release debt gate: coin extraction order
 * (live inventory → personal locker → pending locker), capped value-first
 * pull with exact-change, LEVY contributions, the written receipt/denial/
 * transfer books, and the CAMP|CELL release gate with a forced bypass.
 */
class DebtGateLevyTest {
    private static final String DIM = "minecraft:overworld";

    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private PrisonService prison;
    private CustodyService custody;
    private SeizureService seizure;
    private LaborCampService camps;
    private FineService fines;
    private DebtService debt;
    private TestCurrency currency;
    private TestContainers containers;
    private TestPlayer boss;
    private TestPlayer jailer;
    private TestPlayer inmate;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        seizure = new SeizureService(ctx, audit);
        prison.useSeizure(seizure);
        debt = new DebtService(ctx, audit);
        debt.useSeizure(seizure);
        prison.useDebt(debt);
        camps = new LaborCampService(ctx, players, audit);
        prison.useCamps(camps);
        fines = new FineService(ctx, players, audit, prison);
        fines.useDebt(debt);
        currency = (TestCurrency) ctx.currency();
        containers = (TestContainers) ctx.containers();
        ((TestDeepScan) ctx.deepScan()).server = server;
        boss = server.add("dwurdy"); // configured commissioner
        jailer = server.add("jailer1");
        var jst = players.state(jailer.uuid());
        jst.rank = Rank.GUARD.level();
        players.save(jailer.uuid(), jst);
        inmate = server.add("debtor");
    }

    // ------------------------------------------------------------ helpers

    private String inmateUuid() {
        return inmate.uuid().toString();
    }

    /** Books the inmate into custody: ACTIVE sentence + register record. */
    private Sentence jail(PrisonerStatus status) {
        var data = ctx.prison().read();
        var s = new Sentence();
        s.id = "S-" + inmate.name();
        s.target = inmate.name();
        s.targetUuid = inmateUuid();
        s.status = "ACTIVE";
        s.sentenceDays = 1;
        s.remainingActiveMs = 60_000L;
        s.createdAt = clock.now;
        s.lastTickAt = clock.now;
        s.lastActivityAt = clock.now;
        data.sentences.add(s);
        ctx.prison().write(data);
        var reg = ctx.prisonerRegister().read();
        var rec = new PrisonerRegisterRecord(inmateUuid(), inmate.name(), "test");
        rec.status = status;
        reg.put(rec);
        ctx.prisonerRegister().write(reg);
        return s;
    }

    private PrisonerRegisterRecord rec() {
        return ctx.prisonerRegister().read().prisoner(inmateUuid());
    }

    /** Personal locker on the record holding {@code count} of a coin item. */
    private StoragePoint lockerWith(String itemId, int count) {
        var point = new StoragePoint(DIM, 200, 60, 200);
        containers.put(DIM, 200, 60, 200, itemId, count);
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(inmateUuid());
        rec.personalLocker.add(point);
        ctx.prisonerRegister().write(reg);
        return point;
    }

    /** Pending-locker reservation (released-offline style) holding coins. */
    private void pendingLockerWith(String itemId, int count) {
        containers.put(DIM, 300, 60, 300, itemId, count);
        var reg = ctx.prisonerRegister().read();
        reg.reserveLockers(inmateUuid(), java.util.List.of("minecraft:overworld|300,60,300"));
        ctx.prisonerRegister().write(reg);
    }

    private int chestCount(StoragePoint point, String itemId) {
        int total = 0;
        var slots = containers.slots.get(point.key());
        if (slots == null) return 0;
        for (var v : slots) {
            if (v != null && itemId.equals(v.id())) total += v.count();
        }
        return total;
    }

    private void addFine(String id, int amount, long issuedAt, String status) {
        var data = ctx.fines().read();
        var fine = new Fine();
        fine.id = id;
        fine.target = inmate.name();
        fine.targetUuid = inmateUuid();
        fine.issuer = jailer.name();
        fine.issuerUuid = jailer.uuid().toString();
        fine.law = "lege";
        fine.description = "d";
        fine.amount = amount;
        fine.status = status;
        fine.issuedAt = issuedAt;
        data.fines.add(fine);
        ctx.fines().write(data);
    }

    private LaborCampRecord camp() {
        var existing = ctx.laborCamps().read().camp("mine");
        if (existing != null) return existing;
        assertTrue(camps.register(boss, "mine", "Cariera", "0,50,0", "100,80,100"));
        var camp = ctx.laborCamps().read().camp("mine");
        camp.intakeSpawn = new StoragePoint(DIM, 10, 60, 10);
        camp.releaseSpawn = new StoragePoint(DIM, 5, 60, -20);
        camp.dormitorySpawn = new StoragePoint(DIM, 20, 60, 20);
        var store = ctx.laborCamps().read();
        store.put(camp);
        ctx.laborCamps().write(store);
        return camp;
    }

    // ------------------------------------------------------------ levy

    @Test
    void levySweepsLiveThenLockerThenPendingInOrder() {
        jail(PrisonerStatus.IN_CELL);
        addFine("F1", 60, 100, "IN_SENTENCE");
        currency.balance = 30;             // live coins acquired during custody
        lockerWith("test:brass", 5);       // 50 units in the seized locker
        pendingLockerWith("test:bronze", 10); // 10 units parked offline

        int applied = debt.levy(inmateUuid());

        assertEquals(60, applied);
        assertEquals(0, debt.outstandingDebt(inmateUuid()));
        // live pocket drained first, then exactly three brass from the
        // locker — the pending reservation stays untouched past the cap.
        assertEquals(0, currency.balance);
        assertEquals(2, chestCount(new StoragePoint(DIM, 200, 60, 200), "test:brass"),
                "the locker loses only the coins the debt needed");
        assertEquals(10, chestCount(new StoragePoint(DIM, 300, 60, 300), "test:bronze"),
                "pending-locker coins are a last resort — untouched at the cap");
    }

    @Test
    void levyAppliesOldestFirstWithLevySource() {
        jail(PrisonerStatus.IN_CELL);
        addFine("F2", 40, 200, "IN_SENTENCE");
        addFine("F1", 40, 100, "IN_SENTENCE"); // older — paid first despite the id order
        lockerWith("test:brass", 6); // 60 units against an 80-unit ledger

        assertEquals(60, debt.levy(inmateUuid()),
                "the levy stops at the coins actually available");

        var store = ctx.fines().read();
        var f1 = store.find("F1");
        var f2 = store.find("F2");
        assertEquals("PAID", f1.status, "oldest fine covers first");
        assertEquals(20, f2.paidAmount, "the remainder lands on the newest fine");
        assertEquals("LEVY", f1.contributions.get(0).source);
        assertEquals("LEVY", f2.contributions.get(0).source);
        assertEquals(0, chestCount(new StoragePoint(DIM, 200, 60, 200), "test:brass"));
        assertEquals(20, debt.outstandingDebt(inmateUuid()));
    }

    @Test
    void levyCapsAtDebtAndLeavesSurplusCoins() {
        jail(PrisonerStatus.IN_CELL);
        addFine("F1", 15, 100, "ISSUED");
        lockerWith("test:brass", 4); // 40 units available, 15 owed

        assertEquals(15, debt.levy(inmateUuid()));
        var chest = new StoragePoint(DIM, 200, 60, 200);
        // One brass goes whole, a second is broken for the exact 15 —
        // its change (bronze) stays behind in the locker.
        assertEquals(2, chestCount(chest, "test:brass"));
        assertEquals(5, chestCount(chest, "test:bronze"));
    }

    @Test
    void levyIsSafeNoOpAtZeroDebtOrZeroCoins() {
        jail(PrisonerStatus.IN_CELL);
        assertEquals(0, debt.levy(inmateUuid()), "no debt — nothing to levy");
        addFine("F1", 25, 100, "ISSUED");
        assertEquals(0, debt.levy(inmateUuid()), "no coins anywhere — still a no-op");
        assertEquals(25, debt.outstandingDebt(inmateUuid()));
        assertTrue(inmate.books.isEmpty(), "no receipt when nothing was taken");
    }

    @Test
    void levyExtractsFromPendingLockersOfOfflineReleased() {
        // Released-while-offline: belongings are parked under pendingLockers.
        jail(PrisonerStatus.RELEASED);
        addFine("F1", 40, 100, "ISSUED");
        pendingLockerWith("test:brass", 5); // 50 units reserved

        inmate.online = false;
        assertEquals(40, debt.levy(inmateUuid()));
        assertEquals(1, chestCount(new StoragePoint(DIM, 300, 60, 300), "test:brass"));
        var notices = ctx.prisonerRegister().read().pendingNotices().get(inmateUuid());
        assertNotNull(notices, "an offline debtor's receipt is queued");
        assertTrue(notices.get(0).title.contains("sechestru"));
    }

    @Test
    void levyReceiptListsFinesAndRemainder() {
        jail(PrisonerStatus.IN_CELL);
        addFine("F1", 20, 100, "ISSUED");
        addFine("F2", 30, 200, "ISSUED");
        lockerWith("test:brass", 3); // 30 units — covers F1, dents F2

        debt.levy(inmateUuid());

        assertFalse(inmate.books.isEmpty(), "the prisoner gets the levy receipt");
        String book = inmate.books.get(inmate.books.size() - 1);
        assertTrue(book.contains("Proces-verbal de sechestru"));
        assertTrue(book.contains("30"), "the seized amount is on the receipt");
        assertTrue(book.contains("F1") && book.contains("F2"),
                "both fine applications are listed");
        assertTrue(book.contains("20"), "the remaining debt is stated");
    }

    // ------------------------------------------------------- issue trigger

    @Test
    void fineIssuedOnInCustodyPrisonerTriggersLevy() {
        jail(PrisonerStatus.IN_CELL);
        lockerWith("test:brass", 5); // 50 units seized at booking
        jailer.give(ItemSpec.of("straja:fine_book", 1));
        var amounts = ctx.policies().fineAllowedAmounts;
        int amount = amounts.isEmpty() ? 25 : amounts.get(0);
        assertTrue(fines.writeDraft(jailer, inmate, amount, "lege", "d"));
        assertTrue(fines.issueFromDraft(jailer, inmate));

        var fine = ctx.fines().read().fines.get(0);
        assertEquals(Math.min(amount, 50), fine.paidAmount,
                "the levy hits the fine as soon as it lands in custody");
        assertEquals("LEVY", fine.contributions.get(0).source);
        assertTrue(inmate.books.stream().anyMatch(b -> b.contains("sechestru")));
    }

    @Test
    void fineIssuedOnFreeCitizenDoesNotLevy() {
        // No custody record — the same issue never reaches the levy.
        jailer.give(ItemSpec.of("straja:fine_book", 1));
        int amount = ctx.policies().fineAllowedAmounts.isEmpty()
                ? 25 : ctx.policies().fineAllowedAmounts.get(0);
        assertTrue(fines.writeDraft(jailer, inmate, amount, "lege", "d"));
        assertTrue(fines.issueFromDraft(jailer, inmate));
        var fine = ctx.fines().read().fines.get(0);
        assertEquals(0, fine.paidAmount, "a free citizen is never levied");
        assertTrue(inmate.books.stream().noneMatch(b -> b.contains("sechestru")));
    }

    // ----------------------------------------------------- transfer levy

    @Test
    void campTransferLeviesBeforeDeparture() {
        camp();
        jail(PrisonerStatus.IN_CELL);
        addFine("F1", 30, 100, "IN_SENTENCE");
        lockerWith("test:brass", 4);

        assertTrue(prison.transferToCamp(jailer, inmate, "mine"));
        assertEquals(30, ctx.fines().read().find("F1").paidAmount,
                "the levy settles the ledger before the prisoner boards");
        assertEquals(PrisonerStatus.IN_CAMP, rec().status);
    }

    @Test
    void systemTransferToCampLeviesToo() {
        camp();
        jail(PrisonerStatus.IN_CELL);
        addFine("F1", 30, 100, "IN_SENTENCE");
        lockerWith("test:brass", 4);

        assertTrue(prison.systemTransferToCamp(inmateUuid(), "mine"));
        assertEquals(30, ctx.fines().read().find("F1").paidAmount);
        assertEquals(PrisonerStatus.IN_CAMP, rec().status);
    }

    // -------------------------------------------------------- release gate

    @Test
    void releasePassesExactlyAtThreshold() {
        ctx.policies().debtReleaseBlockThreshold = 50;
        jail(PrisonerStatus.IN_CELL);
        addFine("F1", 50, 100, "IN_SENTENCE");

        assertTrue(prison.release(jailer, inmate, "served"),
                "debt exactly at the threshold releases normally");
        assertEquals(PrisonerStatus.RELEASED, rec().status);
    }

    @Test
    void releaseBlockedOneOverThresholdInCellMode() {
        ctx.policies().debtReleaseBlockThreshold = 50;
        ctx.policies().debtOnBlocked = "CELL";
        jail(PrisonerStatus.IN_CELL);
        addFine("F1", 51, 100, "IN_SENTENCE");

        assertFalse(prison.release(jailer, inmate, "served"),
                "one unit over the threshold blocks the release");
        assertEquals(PrisonerStatus.IN_CELL, rec().status, "custody is kept");
        assertEquals("ACTIVE", ctx.prison().read().sentences.get(0).status);
        assertTrue(jailer.told("datorie"), "the releaser gets a keyed refusal");
        assertTrue(inmate.books.stream().anyMatch(b -> b.contains("Refuz de eliberare")
                        && b.contains("51") && b.contains("50")),
                "the denial statement names debt and threshold");
        assertTrue(ctx.audit().tail(20).stream().anyMatch(
                e -> "prison_release".equals(e.action) && "REFUSED".equals(e.result)),
                "the block is audited as a refusal");
    }

    @Test
    void releaseBlockedDivertsToDefaultCamp() {
        ctx.policies().debtOnBlocked = "CAMP";
        camp();
        jail(PrisonerStatus.IN_CELL);
        addFine("F1", 51, 100, "IN_SENTENCE");

        assertFalse(prison.release(jailer, inmate, "served"));
        var rec = rec();
        assertEquals(PrisonerStatus.IN_CAMP, rec.status,
                "a CAMP-mode block transfers instead of releasing");
        assertEquals("mine", rec.assignedCampId);
        assertEquals("ACTIVE", ctx.prison().read().sentences.get(0).status,
                "the sentence stays open — custody was never released");
        assertTrue(inmate.books.stream().anyMatch(b -> b.contains("Ordin de transfer")
                        && b.contains("Cariera")),
                "the transfer order names the camp");
        assertTrue(inmate.x == 10.5 && inmate.z == 10.5,
                "the prisoner is delivered at the camp intake");
    }

    @Test
    void blockedCampTransferDoesNotReenterGate() {
        // No camp registered: CAMP mode degrades to a custody refusal.
        ctx.policies().debtOnBlocked = "CAMP";
        jail(PrisonerStatus.IN_CELL);
        addFine("F1", 51, 100, "IN_SENTENCE");

        assertFalse(prison.release(jailer, inmate, "served"));
        assertEquals(PrisonerStatus.IN_CELL, rec().status,
                "no camp → the prisoner keeps cell custody like a CELL block");
        assertTrue(inmate.books.stream().anyMatch(b -> b.contains("Refuz de eliberare")));
    }

    @Test
    void commissionerReleaseIsTheForcedBypass() {
        jail(PrisonerStatus.IN_CELL);
        addFine("F1", 51, 100, "IN_SENTENCE");
        currency.balance = 80; // would be levied — the bypass must not touch it

        assertTrue(prison.release(boss, inmate, "command"),
                "an explicit admin release bypasses levy and gate");
        assertEquals("FORCED_RELEASE", ctx.prison().read().sentences.get(0).status);
        assertEquals(80, currency.balance, "forced release performs no levy");
        assertEquals(51, debt.outstandingDebt(inmateUuid()));
        assertEquals(PrisonerStatus.RELEASED, rec().status);
        assertTrue(ctx.audit().tail(50).stream().noneMatch(e -> "levy".equals(e.action)));
    }

    @Test
    void offlineReleaseByIdIsGatedToo() {
        ctx.policies().debtOnBlocked = "CELL";
        jail(PrisonerStatus.IN_CELL);
        addFine("F1", 51, 100, "IN_SENTENCE");
        inmate.online = false;

        assertFalse(prison.releaseById(jailer, inmateUuid()),
                "an offline release hits the same gate");
        assertEquals("ACTIVE", ctx.prison().read().sentences.get(0).status);
        var notices = ctx.prisonerRegister().read().pendingNotices().get(inmateUuid());
        assertNotNull(notices, "the offline prisoner's denial is queued");
        assertEquals("Refuz de eliberare", notices.get(0).title);
    }

    @Test
    void bailReleaseIsGatedByOtherDebt() {
        ctx.policies().debtOnBlocked = "CELL";
        jail(PrisonerStatus.IN_CELL);
        addFine("F9", 51, 100, "IN_SENTENCE"); // unrelated debt survives the bail
        assertFalse(prison.releaseForBail(inmateUuid()),
                "bail does not walk out with other debt over the threshold");
        assertEquals("ACTIVE", ctx.prison().read().sentences.get(0).status);
    }

    @Test
    void levyBeforeReleaseClearsDebtAndOpensGate() {
        ctx.policies().debtOnBlocked = "CELL";
        jail(PrisonerStatus.IN_CELL);
        addFine("F1", 51, 100, "IN_SENTENCE");
        lockerWith("test:brass", 6); // 60 units seized — enough to clear the debt

        assertTrue(prison.release(jailer, inmate, "served"),
                "the pre-release levy clears the debt so the gate opens");
        assertEquals(51, ctx.fines().read().find("F1").paidAmount);
        assertEquals(0, debt.outstandingDebt(inmateUuid()));
        assertEquals(PrisonerStatus.RELEASED, rec().status);
    }

    @Test
    void servedSentenceBlockedAtTickWhenDebtRemains() {
        ctx.policies().debtOnBlocked = "CELL";
        var s = jail(PrisonerStatus.IN_CELL);
        addFine("F1", 51, 100, "IN_SENTENCE");
        var data = ctx.prison().read();
        data.sentences.stream().filter(x -> s.id.equals(x.id))
                .forEach(x -> x.remainingActiveMs = 0); // served — the next tick tries to release
        ctx.prison().write(data);

        prison.tick();

        assertEquals("ACTIVE", ctx.prison().read().sentences.get(0).status,
                "the gate also blocks the automatic served release");
        assertTrue(inmate.books.stream().anyMatch(b -> b.contains("Refuz de eliberare")));
        assertEquals(PrisonerStatus.IN_CELL, rec().status);
    }

    @Test
    void gateDisabledLetsDebtorWalk() {
        ctx.policies().debtEnabled = false;
        jail(PrisonerStatus.IN_CELL);
        addFine("F1", 51, 100, "IN_SENTENCE");
        assertTrue(prison.release(jailer, inmate, "served"),
                "[debt] enabled=false disables the whole gate");
    }
}
