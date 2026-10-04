package com.dwurdy.straja.application.service;

import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.adapter.out.persistence.StoreAccess;
import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.Fine;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import com.dwurdy.straja.support.MemoryStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #234 — DEBT-1 debt ledger: paidAmount/contributions on Fine, oldest-first
 * allocation with per-application audit, capped contributions, FineService.pay
 * routed through the ledger, and pending written-book notices drained at login.
 */
class DebtServiceTest {
    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private PrisonService prison;
    private FineService fines;
    private DebtService debt;
    private TestCurrency currency;
    private TestPlayer guard;
    private TestPlayer civ;
    private TestPlayer payer;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        var custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        debt = new DebtService(ctx, audit);
        prison.useDebt(debt);
        fines = new FineService(ctx, players, audit, prison);
        fines.useDebt(debt);
        currency = (TestCurrency) ctx.currency();
        guard = server.add("guard1");
        civ = server.add("civ1");
        payer = server.add("payer1");
        var st = players.state(guard.uuid());
        st.rank = Rank.GUARD.level();
        st.duty = true;
        players.save(guard.uuid(), st);
        var setup = ctx.setup().read();
        var reception = new SetupData.Location();
        reception.x = 0; reception.y = 0; reception.z = 0;
        setup.locations.put("receptionist", reception);
        ctx.setup().write(setup);
        guard.x = 0; guard.y = 0; guard.z = 0;
        civ.x = 0; civ.y = 0; civ.z = 0;
        payer.x = 0; payer.y = 0; payer.z = 0;
    }

    private String civUuid() {
        return civ.uuid().toString();
    }

    private void addFine(String id, int amount, long issuedAt, String status) {
        var data = ctx.fines().read();
        var fine = new Fine();
        fine.id = id;
        fine.target = civ.name();
        fine.targetUuid = civUuid();
        fine.issuer = guard.name();
        fine.issuerUuid = guard.uuid().toString();
        fine.law = "lege";
        fine.description = "d";
        fine.amount = amount;
        fine.status = status;
        fine.issuedAt = issuedAt;
        data.fines.add(fine);
        ctx.fines().write(data);
    }

    // ---------------------------------------------------------- outstanding

    @Test
    void outstandingDebtSumsPayableStatusesOnly() {
        addFine("F1", 25, 100, "ISSUED");
        addFine("F2", 10, 200, "DELIVERY_FAILED");
        addFine("F3", 50, 300, "PAYMENT_REVIEW");
        addFine("F4", 100, 400, "ARREST_PENDING");
        addFine("F5", 250, 500, "IN_SENTENCE");
        addFine("F6", 500, 600, "GRACE_AFTER_SENTENCE");
        assertEquals(935, debt.outstandingDebt(civUuid()));
        // Closed or frozen fines never count toward the debt.
        addFine("F7", 10, 700, "PAID");
        addFine("F8", 10, 800, "WAIVED");
        addFine("F9", 10, 900, "CANCELLED");
        addFine("F10", 10, 1000, "APPEAL_PENDING");
        addFine("F11", 10, 1100, "ESCALATED");
        assertEquals(935, debt.outstandingDebt(civUuid()));
    }

    @Test
    void outstandingDebtSubtractsPaidAmount() {
        addFine("F1", 100, 100, "ISSUED");
        var data = ctx.fines().read();
        data.find("F1").paidAmount = 30;
        ctx.fines().write(data);
        assertEquals(70, debt.outstandingDebt(civUuid()));
    }

    // ------------------------------------------------------------ allocate

    @Test
    void allocateAppliesOldestFirstFlipsPaidAndAudits() {
        addFine("F1", 25, 300, "ISSUED");
        addFine("F2", 25, 100, "ISSUED");
        addFine("F3", 25, 200, "ISSUED");
        var out = debt.allocate(civUuid(), 60, payer.name(), payer.uuid().toString(), "CONTRIBUTION");
        assertEquals(3, out.size());
        assertEquals("F2", out.get(0).fineId(), "oldest issuedAt is covered first");
        assertEquals("F3", out.get(1).fineId());
        assertEquals("F1", out.get(2).fineId());
        assertTrue(out.get(0).covered());
        assertEquals(10, out.get(2).applied(), "the newest fine gets only the leftover");
        assertFalse(out.get(2).covered());
        var data = ctx.fines().read();
        assertEquals("PAID", data.find("F2").status);
        assertEquals("PAID", data.find("F3").status);
        assertEquals(clock.now, data.find("F2").paidAt.longValue(),
                "a covered fine stamps paidAt");
        var f1 = data.find("F1");
        assertEquals("ISSUED", f1.status);
        assertEquals(10, f1.paidAmount);
        assertEquals(15, f1.remaining());
        assertEquals(1, f1.contributions.size());
        var c = f1.contributions.get(0);
        assertEquals(payer.name(), c.payer);
        assertEquals(payer.uuid().toString(), c.payerUuid);
        assertEquals(10, c.amount);
        assertEquals(clock.now, c.at);
        assertEquals("CONTRIBUTION", c.source);
        var applications = ctx.audit().tail(50).stream()
                .filter(e -> "debt_apply".equals(e.action)).toList();
        assertEquals(3, applications.size(), "each application is audited");
    }

    @Test
    void allocateSkipsNonPayableFines() {
        addFine("F1", 25, 100, "APPEAL_PENDING");
        addFine("F2", 25, 200, "ESCALATED");
        var out = debt.allocate(civUuid(), 50, payer.name(), payer.uuid().toString(), "LEVY");
        assertTrue(out.isEmpty(), "non-payable fines cannot take allocations");
        var data = ctx.fines().read();
        assertEquals("APPEAL_PENDING", data.find("F1").status);
        assertEquals(0, data.find("F1").paidAmount);
        assertTrue(data.find("F1").contributions.isEmpty());
    }

    // ---------------------------------------------------------- contribute

    @Test
    void contributeCapsAtOutstandingDebt() {
        addFine("F1", 25, 100, "ISSUED");
        addFine("F2", 50, 200, "ISSUED");
        currency.balance = 200;
        var out = debt.contribute(payer, civUuid(), 500, "CONTRIBUTION");
        assertEquals(75, out.stream().mapToInt(DebtService.Allocation::applied).sum(),
                "the contribution caps at the outstanding debt");
        assertEquals(125, currency.balance, "only the capped amount is withdrawn");
        assertEquals("PAID", ctx.fines().read().find("F2").status);
        assertEquals(0, debt.outstandingDebt(civUuid()));
    }

    @Test
    void contributeRefusesAtZeroDebt() {
        currency.balance = 100;
        assertTrue(debt.contribute(payer, civUuid(), 25, "CONTRIBUTION").isEmpty(),
                "no debt — nothing to contribute to");
        assertEquals(100, currency.balance, "a refused contribution withdraws nothing");
        assertTrue(payer.told("datorii"), "the refusal names the missing debt");
    }

    @Test
    void contributeWithdrawalFailureLeavesLedgerUntouched() {
        addFine("F1", 25, 100, "ISSUED");
        currency.balance = 10;
        assertTrue(debt.contribute(payer, civUuid(), 25, "CONTRIBUTION").isEmpty());
        assertEquals(25, debt.outstandingDebt(civUuid()));
        assertEquals(10, currency.balance, "a failed withdraw moves no coins");
        assertTrue(ctx.fines().read().find("F1").contributions.isEmpty());
    }

    // ----------------------------------------------------------------- pay

    @Test
    void finePayRoutesThroughLedgerAsFinePay() {
        guard.give(ItemSpec.of("straja:fine_book", 1));
        assertTrue(fines.writeDraft(guard, civ, 25, "lege", "d"));
        assertTrue(fines.issueFromDraft(guard, civ));
        currency.balance = 100;
        assertTrue(fines.pay(civ, "F1"));
        var fine = ctx.fines().read().find("F1");
        assertEquals("PAID", fine.status);
        assertEquals(75, currency.balance);
        assertTrue(civ.told("plătită"));
        assertEquals(25, fine.paidAmount);
        assertEquals(1, fine.contributions.size(), "the payment lands on the ledger");
        var c = fine.contributions.get(0);
        assertEquals("FINE_PAY", c.source);
        assertEquals(civ.name(), c.payer);
        assertEquals(civ.uuid().toString(), c.payerUuid);
        assertEquals(25, c.amount);
    }

    @Test
    void finePayChargesOnlyTheRemainingBalance() {
        addFine("F1", 100, 100, "ISSUED");
        var data = ctx.fines().read();
        data.find("F1").paidAmount = 60; // an earlier levy covered most of it
        ctx.fines().write(data);
        currency.balance = 100;
        assertTrue(fines.pay(civ, "F1"));
        assertEquals(60, currency.balance, "reception collects only what is still owed");
        var fine = ctx.fines().read().find("F1");
        assertEquals("PAID", fine.status);
        assertEquals(100, fine.paidAmount);
        assertEquals("FINE_PAY", fine.contributions.get(0).source);
    }

    // ----------------------------------------------------------- legacy json

    @Test
    void legacyFineJsonWithoutLedgerFieldsLoadsClean() {
        Map<String, MemoryStore> memory = new HashMap<>();
        StoreAccess access = name -> memory.computeIfAbsent(name, k -> new MemoryStore());
        var repo = new SavedStores.Fines(access);
        access.store("fines").put("json",
                "{\"nextId\":2,\"fines\":[{\"id\":\"F1\",\"target\":\"civ1\","
                        + "\"targetUuid\":\"u1\",\"issuer\":\"g\",\"issuerUuid\":\"g1\","
                        + "\"law\":\"lege\",\"description\":\"d\",\"amount\":25,"
                        + "\"status\":\"ISSUED\",\"issuedAt\":5}]}");
        var loaded = repo.read().find("F1");
        assertEquals(0, loaded.paidAmount, "absent paidAmount reads as 0");
        assertNotNull(loaded.contributions);
        assertTrue(loaded.contributions.isEmpty(), "absent contributions read as empty");
        assertEquals(25, loaded.remaining());
    }

    // -------------------------------------------------------------- notices

    @Test
    void onlineNoticeDeliversTheWrittenBook() {
        debt.notifyPrisoner(civUuid(), null, "Somație", List.of("plătește F1"));
        assertEquals(1, civ.books.size(), "an online debtor gets the book directly");
        assertTrue(civ.books.get(0).contains("Somație"));
        assertTrue(ctx.prisonerRegister().read().pendingNotices().isEmpty(),
                "nothing is queued for an online debtor");
    }

    @Test
    void offlineNoticeQueuesAndDrainsOnLogin() {
        civ.online = false;
        debt.notifyPrisoner(civUuid(), null, "Somație", List.of("plătește F1"));
        var queued = ctx.prisonerRegister().read().pendingNotices().get(civUuid());
        assertNotNull(queued, "an offline debtor's notice is queued");
        assertEquals(1, queued.size());
        assertEquals("Somație", queued.get(0).title);
        assertEquals("Straja", queued.get(0).author);
        assertFalse(queued.get(0).pages.isEmpty());
        civ.online = true;
        prison.recoverOnLogin(civ);
        assertTrue(civ.books.stream().anyMatch(b -> b.contains("Somație")),
                "the queued book lands at login beside pending lockers");
        assertTrue(ctx.prisonerRegister().read().pendingNotices().isEmpty(),
                "the queue is drained after delivery");
    }
}
