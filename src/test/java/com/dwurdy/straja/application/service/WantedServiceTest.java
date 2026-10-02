package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.BoloRecord;
import com.dwurdy.straja.domain.model.BoloStatus;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.PrisonerRegisterRecord;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LAW-007 tests — the authoritative wanted surface: BOLO + register FUGITIVE
 * + legacy marks, escort suspension, immediate aggro drop on cuff, and the
 * RESOLVED lifecycle on arrest/release.
 */
class WantedServiceTest {
    private static final String DIM = "minecraft:overworld";

    private TestServer server;
    private StrajaContext ctx;
    private PlayerService players;
    private PrisonService prison;
    private CustodyService custody;
    private BoloService bolos;
    private SeizureService seizure;
    private StorageService storage;
    private WantedService wanted;
    private TestNpcGuards npcGuards;
    private TestPlayer boss;
    private TestPlayer inmate;
    private TestPlayer officer;

    @BeforeEach
    void setup() {
        server = new TestServer();
        ctx = Fakes.context(server, new FixedClock(1_000_000L));
        ctx.policies().storageAggroPeriodTicks = 1;
        players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        seizure = new SeizureService(ctx, audit);
        bolos = new BoloService(ctx, players, audit);
        wanted = new WantedService(ctx, audit, bolos, custody);
        storage = new StorageService(ctx, players, audit, bolos, prison);
        storage.useCustody(custody);
        storage.useWanted(wanted);
        prison.useSeizure(seizure);
        prison.useBolos(bolos);
        custody.onRestraintApplied((o, t) -> {
            prison.onEscortStart(o, t);
            wanted.onCuffed(o, t);
        });
        npcGuards = (TestNpcGuards) ctx.npcGuards();
        boss = server.add("dwurdy"); // configured commissioner
        inmate = server.add("miner");
        officer = server.add("guard1");
        var ost = players.state(officer.uuid());
        ost.rank = Rank.GUARD.level();
        players.save(officer.uuid(), ost);
        inmate.x = 50; inmate.y = 60; inmate.z = 50;
        officer.x = 50; officer.y = 60; officer.z = 51;
    }

    private void boloOn(TestPlayer subject) {
        var bs = ctx.bolos().read();
        var record = new BoloRecord();
        record.id = "b-" + (bs.records.size() + 1);
        record.subjectUuid = subject.uuid().toString();
        record.subjectName = subject.name();
        record.status = BoloStatus.ACTIVE;
        bs.records.add(record);
        ctx.bolos().write(bs);
    }

    private void markFugitiveReg(TestPlayer subject) {
        var reg = ctx.prisonerRegister().read();
        var rec = new PrisonerRegisterRecord(subject.uuid().toString(), subject.name(), "test");
        rec.status = PrisonerStatus.FUGITIVE;
        reg.put(rec);
        ctx.prisonerRegister().write(reg);
    }

    private void cuffInmate() {
        officer.give(ItemSpec.of(CustodyService.CUFFS, 1));
        assertTrue(custody.applyCuffsDirect(officer, inmate, "surrender").ok());
    }

    // ---------------------------------------------------------- isWanted

    @Test
    void wantedSurfaceCoversBoloFugitiveAndLegacy() {
        assertFalse(wanted.isWanted(inmate.uuid()));
        boloOn(inmate);
        assertTrue(wanted.isWanted(inmate.uuid()));
    }

    @Test
    void fugitiveRegisterIsWantedEvenWithoutBolo() {
        markFugitiveReg(inmate);
        assertTrue(wanted.isWanted(inmate.uuid()));
    }

    @Test
    void cancelledBoloDoesNotUnpursueARegisterFugitive() {
        markFugitiveReg(inmate);
        boloOn(inmate);
        bolos.clearFor(inmate.uuid()); // admin cancel — custody truth remains
        assertTrue(wanted.isWanted(inmate.uuid()));
    }

    @Test
    void legacyWantedMarkCounts() {
        var reg = ctx.prisonerRegister().read();
        reg.legacyWantedUntil().put(inmate.uuid().toString(),
                ctx.clock().nowMillis() + 60_000);
        ctx.prisonerRegister().write(reg);
        assertTrue(wanted.isWanted(inmate.uuid()));
    }

    // --------------------------------------------------------- under escort

    @Test
    void cuffedWithOfficerNearIsUnderEscort() {
        assertFalse(wanted.isUnderEscort(inmate));
        cuffInmate();
        assertTrue(wanted.isUnderEscort(inmate));
    }

    @Test
    void cuffedAloneIsNotUnderEscort() {
        cuffInmate();
        officer.x = 500; // out of tether radius
        assertFalse(wanted.isUnderEscort(inmate));
    }

    // ----------------------------------------------------- aggro suspension

    @Test
    void cuffingDropsGuardAggroImmediately() {
        boloOn(inmate);
        var guardId = npcGuards.addGuard(52, 60, 52, 12);
        npcGuards.guards.get(guardId).lineOfSight.add(inmate.uuid());
        storage.tick();
        assertEquals(inmate.uuid(), npcGuards.guards.get(guardId).target);
        // The cuff lands — the hunt stops this tick, not on the next scan.
        cuffInmate();
        assertNull(npcGuards.guards.get(guardId).target);
        storage.tick();
        assertNull(npcGuards.guards.get(guardId).target); // stays cleared
    }

    @Test
    void fugitiveRegisterPlayerIsAggroTarget() {
        markFugitiveReg(inmate);
        var guardId = npcGuards.addGuard(52, 60, 52, 12);
        npcGuards.guards.get(guardId).lineOfSight.add(inmate.uuid());
        storage.tick();
        assertEquals(inmate.uuid(), npcGuards.guards.get(guardId).target);
    }

    @Test
    void guardIdentityCheckHonoursFaction() {
        var guardId = npcGuards.addGuard(52, 60, 52, 12);
        assertTrue(npcGuards.isGuardOf(guardId, 12));
        assertFalse(npcGuards.isGuardOf(guardId, 99));
        assertFalse(npcGuards.isGuardOf(inmate.uuid(), 12));
    }

    // ------------------------------------------------------------ lifecycle

    @Test
    void arrestResolvesActiveBolos() {
        boloOn(inmate);
        ((TestWorld) ctx.world()).room(DIM, -1, 59, -1, 6, 66, 6, -1, 61, 2);
        prison.createCell(boss, "celula_1", DIM, 0, 60, 0, 5, 65, 5);
        prison.arrest(inmate, null, 1, boss, null);
        assertEquals(PrisonerStatus.IN_CELL, reg(inmate).status);
        assertFalse(wanted.isWanted(inmate.uuid()));
        var bs = ctx.bolos().read();
        assertEquals(BoloStatus.RESOLVED, bs.records.get(0).status);
    }

    @Test
    void releaseClearsResidualMarks() {
        boloOn(inmate);
        markFugitiveReg(inmate);
        wanted.resolveReleased(inmate.uuid());
        var bs = ctx.bolos().read();
        assertEquals(BoloStatus.RESOLVED, bs.records.get(0).status);
        // The register FUGITIVE still counts as wanted — releaseSentence owns
        // the register transition; resolveReleased clears the marks layer.
        assertTrue(wanted.isWanted(inmate.uuid()));
    }

    private PrisonerRegisterRecord reg(TestPlayer p) {
        return ctx.prisonerRegister().read().prisoner(p.uuid().toString());
    }
}
