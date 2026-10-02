package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.CheckpointMode;
import com.dwurdy.straja.domain.model.FreedomPriceMode;
import com.dwurdy.straja.domain.model.LaborCampRecord;
import com.dwurdy.straja.domain.model.LawBounds;
import com.dwurdy.straja.domain.model.LawCheckpointRecord;
import com.dwurdy.straja.domain.model.PrisonerRegisterRecord;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.PushbackPoint;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.SeizedStack;
import com.dwurdy.straja.domain.model.StoragePoint;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LAW-006 labor camps: registration/persistence, CAMP: arrest destination,
 * transfer state transitions, perimeter fugitive breach with escort
 * exemption, dormitory respawn, exit-gate confiscation, labor-account credit,
 * coin formatting, flat/multiplier freedom prices, automatic and manual
 * release, and restart persistence.
 */
class LaborCampServiceTest {
    private static final String DIM = "minecraft:overworld";
    private static final String ORE = "minecraft:raw_iron";

    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private PrisonService prison;
    private CustodyService custody;
    private BoloService bolos;
    private SeizureService seizure;
    private LaborCampService camps;
    private MerchantDeskService desks;
    private CheckpointService checkpoints;
    private TestPlayer boss;
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
        bolos = new BoloService(ctx, players, audit);
        prison.useBolos(bolos);
        camps = new LaborCampService(ctx, players, audit);
        prison.useCamps(camps);
        desks = new MerchantDeskService(ctx, players, audit);
        desks.useLabor(camps, prison);
        var personnel = new PersonnelService(
                new com.dwurdy.straja.adapter.out.persistence.SavedStores.Personnel(
                        name -> new com.dwurdy.straja.support.MemoryStore()),
                clock, new Fakes.SeqIds());
        var storage = new StorageService(ctx, players, audit, bolos, prison);
        storage.useCustody(custody);
        checkpoints = new CheckpointService(ctx, audit, prison, storage, bolos, personnel, custody);
        checkpoints.useSeizure(seizure);
        custody.onRestraintApplied(prison::onEscortStart);
        custody.onRestraintReleased(prison::onUncuffed);
        ((TestDeepScan) ctx.deepScan()).server = server;
        boss = server.add("dwurdy"); // configured commissioner
        inmate = server.add("miner");
        inmate.x = 50; inmate.y = 60; inmate.z = 50;
    }

    // ------------------------------------------------------------ helpers

    private LaborCampRecord registerCamp() {
        var existing = ctx.laborCamps().read().camp("mine");
        if (existing != null) return existing;
        assertTrue(camps.register(boss, "mine", "Cariera", "0,50,0", "100,80,100"));
        return ctx.laborCamps().read().camp("mine");
    }

    private LaborCampRecord campWithSpawns() {
        var camp = registerCamp();
        camp.intakeSpawn = new StoragePoint(DIM, 10, 60, 10);
        camp.releaseSpawn = new StoragePoint(DIM, 5, 60, -20);
        camp.dormitorySpawn = new StoragePoint(DIM, 20, 60, 20);
        var store = ctx.laborCamps().read();
        store.put(camp);
        ctx.laborCamps().write(store);
        return camp;
    }

    /** Arrests the inmate into the camp via the CAMP: site destination. */
    private void arrestToCamp() {
        campWithSpawns();
        var site = new LawCheckpointRecord();
        site.id = "gate";
        site.name = "Gate";
        site.dimension = DIM;
        site.mode = CheckpointMode.DENY;
        site.pushback = PushbackPoint.at(DIM, 0, 60, -10, 180f);
        site.arrestDestination = "CAMP:mine";
        var sites = ctx.lawCheckpoints().read();
        sites.put(site);
        ctx.lawCheckpoints().write(sites);
        prison.arrest(inmate, null, 1, boss, "checkpoint:gate", "furat", "gate");
    }

    private PrisonerRegisterRecord reg() {
        return ctx.prisonerRegister().read().prisoner(inmate.uuid().toString());
    }

    /** Store-backed mutation — reads are detached copies, write back. */
    private void mutateReg(java.util.function.Consumer<PrisonerRegisterRecord> edit) {
        var store = ctx.prisonerRegister().read();
        var rec = store.prisoner(inmate.uuid().toString());
        edit.accept(rec);
        ctx.prisonerRegister().write(store);
    }

    // ------------------------------------------------------- registration

    @Test
    void registerPersistsAndValidates() {
        assertTrue(camps.register(boss, "mine", "Cariera", "0,50,0", "100,80,100"));
        var camp = ctx.laborCamps().read().camp("mine");
        assertNotNull(camp);
        assertEquals("Cariera", camp.name);
        assertEquals(0, camp.boundary.minX());
        assertEquals(100, camp.boundary.maxX());
        // Reversed corners normalize.
        assertTrue(camps.register(boss, "quarry", "Q", "100,80,100", "0,50,0"));
        assertEquals(0, ctx.laborCamps().read().camp("quarry").boundary.minX());
        // Bad inputs refuse.
        assertFalse(camps.register(boss, "mine", "Dup", "0,0,0", "1,1,1"));
        assertFalse(camps.register(boss, "Bad Id!", "X", "0,0,0", "1,1,1"));
        assertFalse(camps.register(boss, "badc", "X", "0,0", "1,1,1"));
        // A civilian cannot register.
        assertFalse(camps.register(inmate, "camp2", "X", "0,0,0", "1,1,1"));
    }

    @Test
    void linkDeskAndExitValidateTargets() {
        registerCamp();
        assertFalse(camps.linkDesk(boss, "mine", "ghost"));
        assertFalse(camps.linkExit(boss, "mine", "ghost"));
        var dstore = ctx.merchantDesks().read();
        var desk = new com.dwurdy.straja.domain.model.MerchantDeskRecord();
        desk.id = "qm";
        dstore.put(desk);
        ctx.merchantDesks().write(dstore);
        var sites = ctx.lawCheckpoints().read();
        var site = new LawCheckpointRecord();
        site.id = "exit";
        sites.put(site);
        ctx.lawCheckpoints().write(sites);
        assertTrue(camps.linkDesk(boss, "mine", "qm"));
        assertTrue(camps.linkExit(boss, "mine", "exit"));
        var camp = ctx.laborCamps().read().camp("mine");
        assertEquals("qm", camp.quartermasterDeskId);
        assertEquals("exit", camp.exitCheckpointId);
    }

    // -------------------------------------------------------- coin math

    @Test
    void coinFormattingRespectsTierRatio() {
        // Default 1:64 ladder — 1g 32s 5br 12b round-trips exactly.
        long units = 262144L + 32L * 4096 + 5L * 64 + 12;
        assertEquals("1g 32s 5br 12b", camps.formatCoins(units));
        assertEquals("0b", camps.formatCoins(0));
        assertEquals(units, camps.parseCoins("1g 32s 5br 12b"));
        assertEquals(4096, camps.parseCoins("1s"));
        assertEquals(4096, camps.parseCoins("4096")); // raw base units
        assertEquals(-1, camps.parseCoins("abc"));
        assertEquals(-1, camps.parseCoins("1x"));
        // Non-default ratio: 10:1 tier ladder re-derives denominations.
        ctx.policies().coinTierRatio = 10;
        ctx.policies().coinItemIds.clear();
        assertEquals("1g 2s 3br 4b", camps.formatCoins(1234));
    }

    @Test
    void freedomPriceModes() {
        var camp = registerCamp();
        var rec = new PrisonerRegisterRecord(inmate.uuid().toString(), "miner", "test");
        // Flat: per-camp price beats the TOML default.
        assertEquals(4096, camps.freedomPrice(camp, rec));
        camp.freedomFlatPrice = 5000;
        assertEquals(5000, camps.freedomPrice(camp, rec));
        // Multiplier derives from live outstanding fines.
        camp.freedomMode = FreedomPriceMode.FINES_MULTIPLIER;
        camp.freedomFineMultiplier = 2.0;
        rec.outstandingFines = 1000;
        assertEquals(2000, camps.freedomPrice(camp, rec));
        rec.outstandingFines = 600; // external fine payment lowers the target
        assertEquals(1200, camps.freedomPrice(camp, rec));
    }

    // --------------------------------------------------------- transfer

    @Test
    void arrestWithCampDestinationLandsAtIntake() {
        arrestToCamp();
        var rec = reg();
        assertNotNull(rec);
        assertEquals(PrisonerStatus.IN_CAMP, rec.status);
        assertEquals("mine", rec.assignedCampId);
        // Delivered at intake, not a cell; no adventure mode (mining needs survival).
        assertEquals(10.5, inmate.x, 1e-6);
        assertEquals(60, inmate.y, 1e-6); // intake y
        assertEquals("survival", inmate.gameModeName());
        // Canonical custody is still JAILED — the register carries IN_CAMP.
        var state = ctx.custody().read().states.get(inmate.uuid().toString());
        assertNotNull(state);
        assertEquals(com.dwurdy.straja.domain.model.CustodyStatus.JAILED, state.custody);
        // No cell claim.
        var sentence = ctx.prison().read().sentences.get(0);
        assertEquals("", sentence.cellId);
    }

    @Test
    void transferMovesCellPrisonerToCamp() {
        ((TestWorld) ctx.world()).room(DIM, -1, 59, -1, 6, 66, 6, -1, 61, 2);
        prison.createCell(boss, "celula_1", DIM, 0, 60, 0, 5, 65, 5);
        prison.arrest(inmate, null, 1, boss, null);
        assertEquals(PrisonerStatus.IN_CELL, reg().status);
        assertFalse(ctx.prison().read().assignments.isEmpty());

        campWithSpawns();
        assertTrue(prison.transferToCamp(boss, inmate, "mine"));
        var rec = reg();
        assertEquals(PrisonerStatus.IN_CAMP, rec.status);
        assertEquals("mine", rec.assignedCampId);
        assertEquals(10.5, inmate.x, 1e-6); // intake spawn
        // The bunk is freed for the next prisoner.
        assertTrue(ctx.prison().read().assignments.isEmpty());
        // No fugitive flag from a legitimate transfer.
        assertTrue(ctx.bolos().read().records.isEmpty());
        // Idempotent re-transfer.
        assertFalse(prison.transferToCamp(boss, inmate, "mine"));
    }

    // -------------------------------------------------------- boundaries

    @Test
    void crossingCampBoundsMarksFugitive() {
        arrestToCamp();
        inmate.x = 200; inmate.z = 200; // outside the 0..100 perimeter
        prison.tick();
        var rec = reg();
        assertEquals(PrisonerStatus.FUGITIVE, rec.status);
        assertEquals(1, rec.escapeCount);
        assertFalse(ctx.bolos().read().records.isEmpty());
    }

    @Test
    void escortedPrisonerCrossingBoundsDoesNotEscape() {
        arrestToCamp();
        // Officer cuffs the inmate — register flips IN_CAMP -> ESCORTED.
        var officer = server.add("guard1");
        var st = players.state(officer.uuid());
        st.rank = Rank.GUARD.level();
        players.save(officer.uuid(), st);
        officer.give(com.dwurdy.straja.domain.model.ItemSpec.of(CustodyService.CUFFS, 1));
        officer.x = inmate.x; officer.y = inmate.y; officer.z = inmate.z;
        assertTrue(custody.applyCuffsDirect(officer, inmate, "surrender").ok());
        assertEquals(PrisonerStatus.ESCORTED, reg().status);
        // Walking out beside the officer is an escort, not an escape.
        inmate.x = 200; inmate.z = 200;
        officer.x = 200; officer.z = 200;
        prison.tick();
        assertEquals(PrisonerStatus.ESCORTED, reg().status);
    }

    @Test
    void campRespawnReturnsToDormitory() {
        arrestToCamp();
        inmate.x = 0; inmate.y = 100; inmate.z = 0; // died somewhere odd
        prison.onRespawn(inmate);
        assertEquals(20.5, inmate.x, 1e-6); // dormitorySpawn
        assertEquals(PrisonerStatus.IN_CAMP, reg().status);
        assertEquals(com.dwurdy.straja.domain.model.CustodyStatus.JAILED,
                ctx.custody().read().states.get(inmate.uuid().toString()).custody);
    }

    // -------------------------------------------------------- exit gate

    @Test
    void campExitGateConfiscatesBannedCargo() {
        var camp = campWithSpawns();
        // Exit site bans ORE for everyone + has an evidence chest.
        var site = new LawCheckpointRecord();
        site.id = "exit";
        site.name = "Exit";
        site.dimension = DIM;
        site.mode = CheckpointMode.DENY;
        site.pushback = PushbackPoint.at(DIM, 5, 60, -5, 0f);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        site.localIllegalItems.add(ORE);
        site.evidenceChests.add(new StoragePoint(DIM, 1, 60, -8));
        var sites = ctx.lawCheckpoints().read();
        sites.put(site);
        ctx.lawCheckpoints().write(sites);
        camp.exitCheckpointId = "exit";
        var campStore = ctx.laborCamps().read();
        campStore.put(camp);
        ctx.laborCamps().write(campStore);
        ((TestContainers) ctx.containers()).placeContainer(DIM, 1, 60, -8);

        // Inmate in camp custody carrying ore walks into the exit gate —
        // the gate sees it via deep scan, the confiscation drains the real
        // inventory.
        arrestToCamp();
        inmate.giveStack(ORE, 12, null);
        ((TestDeepScan) ctx.deepScan()).inventories.put(inmate.uuid(),
                List.of(new com.dwurdy.straja.domain.model.SnapshotItem(
                        "main:0", ORE, 12, "", ORE)));
        inmate.x = 5; inmate.y = 60; inmate.z = -5; // outside stage1, inside step range
        // First scan seeds prev; second scan crosses stage1 -> deny + confiscate.
        scan();
        inmate.x = 5; inmate.y = 60; inmate.z = 5;
        scan();

        assertEquals(0, inmate.inventory().countOf(ORE));
        var chest = (TestContainers) ctx.containers();
        assertEquals(12, chest.countUnits(DIM, 1, 60, -8, Map.of(ORE, 1)));
        assertEquals(PrisonerStatus.IN_CAMP, reg().status); // repelled, still in custody
    }

    private void scan() {
        server.tick = (server.tick / 5 + 1) * 5;
        checkpoints.tick();
    }

    // ---------------------------------------------------------- releases

    @Test
    void laborCreditAutoReleasesAtFlatPrice() {
        arrestToCamp();
        mutateReg(r -> r.laborAccount = 4096); // == default flat freedom price
        prison.tick();
        var rec = reg();
        assertEquals(PrisonerStatus.SERVED_LABOR, rec.status);
        assertEquals("survival", inmate.gameModeName());
        assertEquals(5.5, inmate.x, 1e-6); // releaseSpawn
        assertEquals(-19.5, inmate.z, 1e-6);
        // Sentence closed.
        var sentence = ctx.prison().read().sentences.get(0);
        assertEquals("SERVED", sentence.status);
        assertEquals("SERVED_LABOR", sentence.releaseReason);
    }

    @Test
    void quartermasterSaleCreditsAndTriggersRelease() {
        var camp = campWithSpawns();
        camp.freedomFlatPrice = 100;
        var campStore = ctx.laborCamps().read();
        campStore.put(camp);
        ctx.laborCamps().write(campStore);
        // Quartermaster desk inside the camp.
        var dstore = ctx.merchantDesks().read();
        var desk = new com.dwurdy.straja.domain.model.MerchantDeskRecord();
        desk.id = "qm";
        desk.dimension = DIM;
        desk.deskPos = new StoragePoint(DIM, 50, 60, 50);
        desk.sellTable.put(ORE, 10);
        desk.creditsLaborAccount = true;
        desk.chests.add(new StoragePoint(DIM, 51, 60, 50));
        dstore.put(desk);
        ctx.merchantDesks().write(dstore);
        ((TestContainers) ctx.containers()).placeContainer(DIM, 51, 60, 50);
        camp.quartermasterDeskId = "qm";

        arrestToCamp();
        inmate.giveStack(ORE, 10, null);
        inmate.x = 50; inmate.y = 60; inmate.z = 50;
        assertTrue(desks.sell(inmate, "qm", null, 0));
        // 10 ore x 10 = 100 base units == freedom price -> auto release.
        var rec = reg();
        assertEquals(PrisonerStatus.SERVED_LABOR, rec.status);
        assertEquals(5.5, inmate.x, 1e-6);
    }

    @Test
    void manualReleaseOverridesBalance() {
        arrestToCamp();
        mutateReg(r -> r.laborAccount = 0); // nowhere near the price
        assertTrue(prison.release(boss, inmate, "command"));
        assertEquals(PrisonerStatus.RELEASED, reg().status);
        // Manual release still exits through the camp release point.
        assertEquals(5.5, inmate.x, 1e-6);
    }

    @Test
    void restartPersistenceKeepsCampCustody() {
        arrestToCamp();
        // Simulated restart: fresh services against the same stores.
        var audit = new AuditService(ctx);
        var fresh = new PrisonService(ctx, players, audit, custody);
        fresh.useSeizure(seizure);
        fresh.useBolos(bolos);
        fresh.useCamps(new LaborCampService(ctx, players, audit));
        inmate.x = 60; inmate.y = 60; inmate.z = 60; // inside bounds, relogged elsewhere
        fresh.recoverOnLogin(inmate);
        assertEquals(PrisonerStatus.IN_CAMP, reg().status);
        assertEquals(10.5, inmate.x, 1e-6); // intake delivery on relog
    }

    @Test
    void uncuffedInsideCampKeepsCustody() {
        arrestToCamp();
        var officer = server.add("guard1");
        var st = players.state(officer.uuid());
        st.rank = Rank.GUARD.level();
        players.save(officer.uuid(), st);
        officer.give(com.dwurdy.straja.domain.model.ItemSpec.of(CustodyService.CUFFS, 1));
        officer.x = inmate.x; officer.y = inmate.y; officer.z = inmate.z;
        assertTrue(custody.applyCuffsDirect(officer, inmate, "surrender").ok());
        assertEquals(PrisonerStatus.ESCORTED, reg().status);
        // Uncuff inside the camp perimeter -> back to IN_CAMP, not fugitive.
        assertTrue(custody.emergencyRelease(boss, inmate));
        var rec = reg();
        assertEquals(PrisonerStatus.IN_CAMP, rec.status);
    }

    // -------------------------------------------- adversarial review round

    @Test
    void transferRestoresSurvivalForLabor() {
        ((TestWorld) ctx.world()).room(DIM, -1, 59, -1, 6, 66, 6, -1, 61, 2);
        prison.createCell(boss, "celula_1", DIM, 0, 60, 0, 5, 65, 5);
        prison.arrest(inmate, null, 1, boss, null);
        assertEquals("adventure", inmate.gameModeName());
        campWithSpawns();
        // A cell prisoner lands in adventure; camp labor needs real hands.
        assertTrue(prison.transferToCamp(boss, inmate, "mine"));
        assertEquals("survival", inmate.gameModeName());
    }

    @Test
    void zeroOrOverflowingFinesPriceFallsBackToFlat() {
        var camp = registerCamp();
        camp.freedomMode = FreedomPriceMode.FINES_MULTIPLIER;
        camp.freedomFineMultiplier = 2.0;
        var rec = new PrisonerRegisterRecord(inmate.uuid().toString(), "miner", "test");
        // No fines would price freedom at 0 — a dead release valve; the flat
        // price is the floor so buy-out always stays earnable.
        rec.outstandingFines = 0;
        assertEquals(4096, camps.freedomPrice(camp, rec));
        // A price past the int-capped account is unreachable — same fallback.
        rec.outstandingFines = Integer.MAX_VALUE;
        assertEquals(4096, camps.freedomPrice(camp, rec));
    }

    @Test
    void oversizedPriceTokensRefuseCleanly() {
        registerCamp();
        assertFalse(camps.setFreedomPrice(boss, "mine", "flat",
                "99999999999999999999"));
        assertFalse(camps.setFreedomPrice(boss, "mine", "flat",
                "99999999999999999999g"));
    }

    @Test
    void spawnMustBeSetFromInsideTheCampDimension() {
        registerCamp();
        boss.dimension = "minecraft:the_nether";
        assertFalse(camps.setSpawn(boss, "mine", "intake"));
        boss.dimension = DIM;
        assertTrue(camps.setSpawn(boss, "mine", "intake"));
    }

    @Test
    void vanishedCampRequeuesPrisonerToCellPath() {
        arrestToCamp();
        assertEquals(PrisonerStatus.IN_CAMP, reg().status);
        var camps_ = ctx.laborCamps().read();
        camps_.remove("mine");
        ctx.laborCamps().write(camps_);
        prison.tick();
        var rec = reg();
        // No box means no enforceable custody — the prisoner is requeued to
        // the ordinary cell path rather than roaming free as IN_CAMP.
        assertEquals(PrisonerStatus.IN_CELL, rec.status);
        assertEquals("", rec.assignedCampId);
        assertEquals("WAITING_CELL", ctx.prison().read().sentences.get(0).status);
    }

    @Test
    void rebookingClearsLaborAccountAndCampLink() {
        arrestToCamp();
        mutateReg(r -> r.laborAccount = 999999);
        assertTrue(prison.release(boss, inmate, "command"));
        assertEquals(PrisonerStatus.RELEASED, reg().status);
        // Re-arrest to the same camp: the old balance must not meet the
        // freedom price on the first tick.
        arrestToCamp();
        var rec = reg();
        assertEquals(PrisonerStatus.IN_CAMP, rec.status);
        assertEquals(0, rec.laborAccount);
        prison.tick();
        assertEquals(PrisonerStatus.IN_CAMP, reg().status); // still in custody
    }
}
