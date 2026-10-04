package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.Fine;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.Sentence;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #236 — DEBT-3 pooled contributions: /straja debt view + pay surfaces,
 * /straja bail delegated to the debt ledger (bounty-capture fine first),
 * contribution-credit books for the debtor, and the keyed refusals.
 */
class DebtContributionTest {
    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private PrisonService prison;
    private DebtService debt;
    private BountyService bounties;
    private TestCurrency currency;
    private TestPlayer guard;
    private TestPlayer inspector;
    private TestPlayer civ;
    private TestPlayer payer;
    private TestPlayer sponsor;
    private TestPlayer hunter;
    private TestPlayer crook;

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
        debt.usePlayers(players);
        prison.useDebt(debt);
        bounties = new BountyService(ctx, players, audit);
        bounties.usePrison(prison);
        bounties.useDebt(debt);
        prison.onArrest(bounties::onArrested);
        currency = (TestCurrency) ctx.currency();
        guard = server.add("guard1");
        inspector = server.add("inspector1");
        civ = server.add("civ1");
        payer = server.add("payer1");
        sponsor = server.add("sponsor1");
        hunter = server.add("hunter1");
        crook = server.add("crook1");
        var st = players.state(guard.uuid());
        st.rank = Rank.GUARD.level();
        st.duty = true;
        players.save(guard.uuid(), st);
        var ist = players.state(inspector.uuid());
        ist.rank = Rank.INSPECTOR.level();
        players.save(inspector.uuid(), ist);
    }

    private Fine addFine(TestPlayer target, String id, int amount, long issuedAt,
                         String status, String law) {
        var data = ctx.fines().read();
        var fine = new Fine();
        fine.id = id;
        fine.target = target.name();
        fine.targetUuid = target.uuid().toString();
        fine.issuer = guard.name();
        fine.issuerUuid = guard.uuid().toString();
        fine.law = law;
        fine.description = "d";
        fine.amount = amount;
        fine.status = status;
        fine.issuedAt = issuedAt;
        data.fines.add(fine);
        ctx.fines().write(data);
        return fine;
    }

    private String civUuid() {
        return civ.uuid().toString();
    }

    private Sentence bountySentence() {
        var sentence = new Sentence();
        sentence.id = "S-test-1";
        sentence.target = crook.name();
        sentence.targetUuid = crook.uuid().toString();
        sentence.arrestedBy = "jailer";
        sentence.arrestedByUuid = inspector.uuid().toString();
        sentence.status = "ACTIVE";
        return sentence;
    }

    private Fine bountyFine() {
        var record = ctx.bounties().read().records.get(0);
        return ctx.fines().read().find(record.linkedFineId);
    }

    // --------------------------------------------------------- contribute

    @Test
    void multiContributorCoverageFlipsPaid() {
        addFine(civ, "F1", 40, 100, "ISSUED", "furt");
        addFine(civ, "F2", 60, 200, "ISSUED", "daune");
        currency.balance = 100;
        var first = debt.payContribution(payer, civ.name(), 50, "CONTRIBUTION", null);
        assertEquals(2, first.size());
        assertEquals("F1", first.get(0).fineId());
        assertTrue(first.get(0).covered(), "F1 is covered by the first slice");
        assertEquals(10, first.get(1).applied(), "the leftover lands on F2");
        var second = debt.payContribution(sponsor, civ.name(), 60, "CONTRIBUTION", null);
        assertEquals(1, second.size());
        assertEquals(50, second.get(0).applied(),
                "the second contribution caps at the remaining balance");
        var data = ctx.fines().read();
        assertEquals("PAID", data.find("F2").status, "coverage flips the fine PAID");
        assertEquals(0, debt.outstandingDebt(civUuid()));
        assertEquals(0, currency.balance, "both payers' coins moved the ledger");
        var f2 = data.find("F2");
        assertEquals(2, f2.contributions.size(), "both contributors are on the ledger");
        assertEquals(payer.name(), f2.contributions.get(0).payer);
        assertEquals(sponsor.name(), f2.contributions.get(1).payer);
        assertEquals("CONTRIBUTION", f2.contributions.get(0).source);
    }

    @Test
    void overpayIsCappedNotLost() {
        addFine(civ, "F1", 30, 100, "ISSUED", "furt");
        currency.balance = 500;
        var out = debt.payContribution(payer, civ.name(), 200, "CONTRIBUTION", null);
        assertEquals(30, out.stream().mapToInt(DebtService.Allocation::applied).sum());
        assertEquals(470, currency.balance, "only the capped amount leaves the payer");
    }

    @Test
    void omittedAmountPaysTheFullRemainder() {
        addFine(civ, "F1", 25, 100, "ISSUED", "furt");
        addFine(civ, "F2", 35, 200, "ISSUED", "daune");
        currency.balance = 500;
        var out = debt.payContribution(payer, civ.name(), null, "CONTRIBUTION", null);
        assertEquals(60, out.stream().mapToInt(DebtService.Allocation::applied).sum());
        assertEquals(0, debt.outstandingDebt(civUuid()));
        assertEquals(440, currency.balance);
    }

    @Test
    void prisonerCanSelfPay() {
        addFine(civ, "F1", 25, 100, "ISSUED", "furt");
        currency.balance = 100;
        var out = debt.payContribution(civ, civ.name(), null, "CONTRIBUTION", null);
        assertFalse(out.isEmpty());
        assertEquals(75, currency.balance);
        var contribution = ctx.fines().read().find("F1").contributions.get(0);
        assertEquals(civ.name(), contribution.payer);
        assertEquals(civUuid(), contribution.payerUuid);
        assertFalse(civ.books.isEmpty(), "a self-payer still gets the credit book");
    }

    // ------------------------------------------------------------ refusals

    @Test
    void nonPositiveAmountIsRefused() {
        addFine(civ, "F1", 25, 100, "ISSUED", "furt");
        currency.balance = 100;
        assertTrue(debt.payContribution(payer, civ.name(), 0, "CONTRIBUTION", null).isEmpty());
        assertTrue(debt.payContribution(payer, civ.name(), -10, "CONTRIBUTION", null).isEmpty());
        assertEquals(100, currency.balance, "a refused amount withdraws nothing");
        assertTrue(payer.told("pozitivă"), "the refusal names the amount rule");
        assertTrue(ctx.fines().read().find("F1").contributions.isEmpty());
    }

    @Test
    void debtlessTargetIsRefused() {
        currency.balance = 100;
        assertTrue(debt.payContribution(payer, civ.name(), 10, "CONTRIBUTION", null).isEmpty());
        assertTrue(payer.told("datorii"), "the refusal names the missing debt");
    }

    @Test
    void insufficientCoinsSurfaceTheWithdrawFailure() {
        addFine(civ, "F1", 25, 100, "ISSUED", "furt");
        currency.balance = 10;
        assertTrue(debt.payContribution(payer, civ.name(), 20, "CONTRIBUTION", null).isEmpty());
        assertEquals(10, currency.balance, "a failed withdraw moves no coins");
        assertTrue(payer.told("nu a putut fi efectuată"), "the withdraw failure is surfaced");
        assertTrue(ctx.fines().read().find("F1").contributions.isEmpty());
    }

    // --------------------------------------------------------------- books

    @Test
    void onlineDebtorGetsTheContributionBook() {
        addFine(civ, "F1", 25, 100, "ISSUED", "furt");
        addFine(civ, "F2", 15, 200, "ISSUED", "daune");
        currency.balance = 100;
        debt.payContribution(payer, civ.name(), 30, "CONTRIBUTION", null);
        assertEquals(1, civ.books.size(), "the debtor is handed one credit book");
        var book = civ.books.get(0);
        assertTrue(book.contains("Înștiințare de plată"));
        assertTrue(book.contains(payer.name()), "the book names the payer");
        assertTrue(book.contains("30"), "the book names the paid amount");
        assertTrue(book.contains("F1") && book.contains("F2"),
                "the book lists the per-fine applications");
        assertTrue(book.contains("achitată"), "a covered fine is marked");
        assertTrue(book.contains("10"), "the new remaining balance is named");
        assertTrue(ctx.prisonerRegister().read().pendingNotices().isEmpty(),
                "an online debtor's book is not queued");
    }

    @Test
    void offlineDebtorsBookQueuesAndDrainsAtLogin() {
        addFine(civ, "F1", 25, 100, "ISSUED", "furt");
        civ.online = false;
        currency.balance = 100;
        debt.payContribution(payer, civ.name(), 25, "CONTRIBUTION", null);
        var queued = ctx.prisonerRegister().read().pendingNotices().get(civUuid());
        assertNotNull(queued, "an offline debtor's notice is queued");
        assertEquals(1, queued.size());
        assertEquals("Înștiințare de plată", queued.get(0).title);
        civ.online = true;
        prison.recoverOnLogin(civ);
        assertTrue(civ.books.stream().anyMatch(b -> b.contains("Înștiințare de plată")),
                "the queued book lands at login");
        assertTrue(ctx.prisonerRegister().read().pendingNotices().isEmpty(),
                "the queue is drained after delivery");
    }

    // ---------------------------------------------------------------- view

    @Test
    void officerReadsTheFullBreakdown() {
        addFine(civ, "F1", 40, 100, "ISSUED", "furt");
        addFine(civ, "F2", 60, 200, "IN_SENTENCE", "bounty_capture");
        var data = ctx.fines().read();
        data.find("F2").paidAmount = 20; // an earlier levy covered part of it
        ctx.fines().write(data);
        debt.showDebt(guard, civ.name());
        assertTrue(guard.told("F1 — furt: 40 monede rămase"),
                "the row shows id, law and remaining");
        assertTrue(guard.told("F2 — bounty_capture: 40 monede rămase"),
                "a partially paid row shows only the balance");
        assertTrue(guard.told("Total de plată restant: 80"));
    }

    @Test
    void ownDebtIsVisibleToCivilians() {
        addFine(civ, "F1", 40, 100, "ISSUED", "furt");
        debt.showDebt(civ, civ.name());
        assertTrue(civ.told("Total de plată restant: 40"));
        debt.showDebt(payer, civ.name());
        assertFalse(payer.told("Total de plată restant"),
                "a civilian cannot read another citizen's ledger");
        assertTrue(payer.told("ofițerii"), "the refusal points at the officer gate");
    }

    @Test
    void offlineTargetResolvesByNameAndUuid() {
        addFine(civ, "F1", 40, 100, "ISSUED", "furt");
        civ.online = false;
        debt.showDebt(guard, civ.name());
        assertTrue(guard.told("Total de plată restant: 40"),
                "the offline debtor's name resolves through the fine store");
        debt.showDebt(guard, civUuid());
        assertEquals(civUuid(), debt.resolveDebtorUuid(civ.name()),
                "name resolution lands on the same ledger uuid");
    }

    @Test
    void debtlessViewIsRefused() {
        debt.showDebt(guard, payer.name());
        assertTrue(guard.told("datorii"));
    }

    // ---------------------------------------------------------------- bail

    @Test
    void bailServesTheBountyFineBeforeOlderDebt() {
        addFine(crook, "F9", 50, 100, "ISSUED", "furt");
        var record = bounties.post(inspector, crook, 100, "stole");
        assertNotNull(record);
        bounties.onArrested(bountySentence()); // fine = 200, newer issuedAt
        currency.balance = 400;
        assertTrue(bounties.payBail(hunter, crook.name(), 200));
        var data = ctx.fines().read();
        var bailFine = data.find(ctx.bounties().read().find(record.id).linkedFineId);
        assertEquals("PAID", bailFine.status,
                "the bounty-capture fine is served first despite being newer");
        assertEquals(50, data.find("F9").remaining(),
                "the older general fine waits for the next slice");
        assertEquals("BAIL", bailFine.contributions.get(0).source);
        assertEquals(hunter.name(), bailFine.contributions.get(0).payer);
    }

    @Test
    void coveredBailReleasesTheSentence() {
        var record = bounties.post(inspector, crook, 128, "stole gold");
        assertNotNull(record);
        bounties.onArrested(bountySentence());
        var data = ctx.prison().read();
        data.sentences.add(bountySentence());
        ctx.prison().write(data);
        currency.balance = 256;
        assertTrue(bounties.payBail(hunter, crook.name()),
                "the omitted amount covers the full remainder");
        assertTrue(hunter.told("Eliberarea se procesează"),
                "the bail receipt survives the pooled path");
        assertEquals(0, currency.balance);
        var released = ctx.prison().read().sentences.stream()
                .filter(s -> "S-test-1".equals(s.id)).findFirst().orElseThrow();
        assertEquals("BAIL_PAID", released.releaseReason,
                "a covered bounty fine still ends the sentence");
        var contribution = bountyFine().contributions.get(0);
        assertEquals("BAIL", contribution.source);
    }

    @Test
    void partialBailKeepsThePrisoner() {
        var record = bounties.post(inspector, crook, 128, "stole gold");
        assertNotNull(record);
        bounties.onArrested(bountySentence());
        var data = ctx.prison().read();
        data.sentences.add(bountySentence());
        ctx.prison().write(data);
        currency.balance = 500;
        assertTrue(bounties.payBail(hunter, crook.name(), 100));
        var bailFine = bountyFine();
        assertEquals("IN_SENTENCE", bailFine.status);
        assertEquals(156, bailFine.remaining(), "the partial bail lands on the fine");
        assertFalse(hunter.told("Eliberarea se procesează"),
                "an uncovered bounty fine does not release");
        assertEquals("", ctx.prison().read().sentences.get(0).releaseReason);
    }

    @Test
    void bailOnDebtlessTargetIsRefused() {
        currency.balance = 300;
        assertFalse(bounties.payBail(hunter, crook.name()),
                "no debt — nothing to bail");
        assertEquals(300, currency.balance);
    }
}
