package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.BoloRecord;
import com.dwurdy.straja.domain.model.BoloStatus;
import com.dwurdy.straja.domain.model.Fine;
import com.dwurdy.straja.domain.model.FineTask;
import com.dwurdy.straja.domain.model.Incident;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SURR-1 — predarea voluntară: the wanted turn themselves in and the
 * institution resolves the record on its own — debts settle in one
 * withdrawal, violent marks book a discounted sentence through the same
 * path a patrol arrest takes.
 */
class SurrenderServiceTest {
    private static final String DIM = "minecraft:overworld";

    private TestServer server;
    private StrajaContext ctx;
    private PlayerService players;
    private PrisonService prison;
    private CustodyService custody;
    private BoloService bolos;
    private WantedService wanted;
    private FineService fines;
    private SurrenderService surrenders;
    private TestCurrency currency;
    private TestPlayer boss;
    private TestPlayer suspect;
    private TestPlayer bystander;

    @BeforeEach
    void setup() {
        server = new TestServer();
        ctx = Fakes.context(server, new FixedClock(1_000_000L));
        players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        bolos = new BoloService(ctx, players, audit);
        prison.useBolos(bolos);
        prison.useSeizure(new SeizureService(ctx, audit));
        wanted = new WantedService(ctx, audit, bolos, custody);
        fines = new FineService(ctx, players, audit, prison);
        surrenders = new SurrenderService(ctx, audit, wanted, custody, prison, fines, bolos);
        currency = (TestCurrency) ctx.currency();
        boss = server.add("dwurdy"); // configured commissioner — cell creation authority
        suspect = server.add("hotul");
        bystander = server.add("nevinovat");
        suspect.x = 50; suspect.y = 60; suspect.z = 50;
    }

    private BoloRecord boloOn(TestPlayer subject, String linkedFineId) {
        var bs = ctx.bolos().read();
        var record = new BoloRecord();
        record.id = "b-" + (bs.records.size() + 1);
        record.subjectUuid = subject.uuid().toString();
        record.subjectName = subject.name();
        record.status = BoloStatus.ACTIVE;
        record.linkedFineId = linkedFineId == null ? "" : linkedFineId;
        bs.records.add(record);
        ctx.bolos().write(bs);
        return record;
    }

    private Fine issueFine(TestPlayer subject, int amount) {
        var data = ctx.fines().read();
        var fine = new Fine();
        fine.id = "F" + (data.fines.size() + 1);
        fine.target = subject.name();
        fine.targetUuid = subject.uuid().toString();
        fine.amount = amount;
        fine.status = "ISSUED";
        data.fines.add(fine);
        ctx.fines().write(data);
        return fine;
    }

    private void cell() {
        ((TestWorld) ctx.world()).room(DIM, -1, 59, -1, 6, 66, 6, -1, 61, 2);
        prison.createCell(boss, "celula_1", DIM, 0, 60, 0, 5, 65, 5);
    }

    private FineTask taskFor(Fine fine, TestPlayer subject) {
        var data = ctx.fines().read();
        var task = new FineTask();
        task.id = "FM-" + fine.id;
        task.kind = "FINE_RECOVERY";
        task.fineId = fine.id;
        task.target = subject.name();
        task.targetUuid = subject.uuid().toString();
        task.status = "OPEN";
        data.tasks.add(task);
        ctx.fines().write(data);
        return task;
    }

    private void linkBolo(String field, String id) {
        var bs = ctx.bolos().read();
        var record = bs.records.get(bs.records.size() - 1);
        switch (field) {
            case "fine" -> record.linkedFineId = id;
            case "task" -> record.linkedArrestTaskId = id;
            case "incident" -> record.linkedIncidentId = id;
            default -> throw new IllegalArgumentException(field);
        }
        ctx.bolos().write(bs);
    }

    // ------------------------------------------------------------ eligibility

    @Test
    void cleanPlayerCannotSurrender() {
        assertFalse(surrenders.surrender(bystander));
        assertTrue(bystander.lastMessage().contains("căutat"));
    }

    @Test
    void cuffedSuspectCannotSurrenderTwice() {
        boloOn(suspect, null);
        assertTrue(custody.applyCuffsSystem(suspect, "test").ok());
        assertFalse(surrenders.surrender(suspect));
        assertTrue(suspect.lastMessage().contains("custodie"));
    }

    @Test
    void policyOffRefuses() {
        ctx.policies().surrenderEnabled = false;
        boloOn(suspect, null);
        assertFalse(surrenders.surrender(suspect));
        assertTrue(suspect.lastMessage().contains("dezactivată"));
    }

    // ------------------------------------------------------------- debt path

    @Test
    void debtPathSettlesFinesAndClearsMarks() {
        var fine = issueFine(suspect, 120);
        boloOn(suspect, fine.id);
        currency.balance = 500;
        assertTrue(surrenders.surrender(suspect));
        assertEquals(380, currency.balance);
        assertEquals("PAID", ctx.fines().read().find(fine.id).status);
        assertEquals(BoloStatus.RESOLVED, ctx.bolos().read().records.get(0).status);
        assertFalse(wanted.isWanted(suspect.uuid()));
        assertTrue(suspect.messages.stream().anyMatch(m -> m.contains("achitate")));
    }

    @Test
    void insufficientFundsKeepsThemWanted() {
        var fine = issueFine(suspect, 120);
        boloOn(suspect, fine.id);
        currency.balance = 40;
        assertFalse(surrenders.surrender(suspect));
        assertEquals(40, currency.balance);
        assertEquals("ISSUED", ctx.fines().read().find(fine.id).status);
        assertTrue(wanted.isWanted(suspect.uuid()));
        assertTrue(suspect.lastMessage().contains("suficiente monede"));
    }

    @Test
    void taskLinkedBoloTakesTheDebtPath() {
        // Production writers trace fine warrants through the recovery task,
        // not linkedFineId — the surrender read must follow that hop.
        var fine = issueFine(suspect, 90);
        taskFor(fine, suspect);
        boloOn(suspect, null);
        linkBolo("task", "FM-" + fine.id);
        currency.balance = 100;
        assertTrue(surrenders.surrender(suspect));
        assertEquals("PAID", ctx.fines().read().find(fine.id).status);
        assertFalse(wanted.isWanted(suspect.uuid()));
    }

    @Test
    void incidentLinkedBoloTakesTheDebtPath() {
        var fine = issueFine(suspect, 90);
        var incidents = ctx.incidents().read();
        var incident = new Incident();
        incident.id = "INC-1";
        incident.linkedFineId = fine.id;
        incidents.incidents.add(incident);
        ctx.incidents().write(incidents);
        boloOn(suspect, null);
        linkBolo("incident", "INC-1");
        currency.balance = 100;
        assertTrue(surrenders.surrender(suspect));
        assertEquals("PAID", ctx.fines().read().find(fine.id).status);
        assertFalse(wanted.isWanted(suspect.uuid()));
    }

    @Test
    void staleFineLinkedBoloResolvesForFree() {
        var fine = issueFine(suspect, 120);
        var data = ctx.fines().read();
        data.find(fine.id).status = "PAID";
        ctx.fines().write(data);
        boloOn(suspect, fine.id);
        currency.balance = 0;
        assertTrue(surrenders.surrender(suspect));
        assertEquals(BoloStatus.RESOLVED, ctx.bolos().read().records.get(0).status);
        assertFalse(wanted.isWanted(suspect.uuid()));
    }

    // ----------------------------------------------------------- prison path

    @Test
    void violentMarkBooksIntoCellWithClemency() {
        cell();
        boloOn(suspect, null); // no fine trace — a violent mark
        suspect.x = 50; suspect.z = 50;
        assertTrue(surrenders.surrender(suspect));
        var sentence = ctx.prison().read().sentences.get(0);
        int expected = Math.max(1, ctx.policies().prisonDefaultSentenceDays * 75 / 100);
        assertEquals(expected, sentence.sentenceDays);
        assertEquals("ACTIVE", sentence.status);
        assertFalse(sentence.cellId.isBlank(), "a free cell must be claimed");
        assertTrue(ctx.custody().read().cuffed.containsKey(suspect.uuid().toString()),
                "restraints ride the sentence — releaseFromJail cleans them");
        assertEquals(BoloStatus.RESOLVED, ctx.bolos().read().records.get(0).status);
    }

    @Test
    void waitlistedSurrendererStaysRestrained() {
        boloOn(suspect, null); // violent mark, no cell anywhere
        assertTrue(surrenders.surrender(suspect));
        assertTrue(ctx.custody().read().cuffed.containsKey(suspect.uuid().toString()),
                "a waitlisted prisoner must stay in institutional cuffs");
        assertFalse(ctx.prison().read().sentences.isEmpty());
    }

    // --------------------------------------------------------------- offers

    @Test
    void tickOffersTheButtonOncePerCooldown() {
        boloOn(suspect, null);
        surrenders.tick();
        assertTrue(suspect.messages.stream().anyMatch(m -> m.contains("straja.surrender.offer")));
        assertFalse(bystander.messages.stream().anyMatch(m -> m.contains("straja.surrender.offer")));
        suspect.messages.clear();
        surrenders.tick(); // inside the cooldown — no re-offer
        assertTrue(suspect.messages.isEmpty());
    }
}
