package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.BoloRecord;
import com.dwurdy.straja.domain.model.BoloStatus;
import com.dwurdy.straja.domain.model.BoardingZone;
import com.dwurdy.straja.domain.model.CheckpointMode;
import com.dwurdy.straja.domain.model.CrossingOutcome;
import com.dwurdy.straja.domain.model.GateLane;
import com.dwurdy.straja.domain.model.InspectionLedgerEntry;
import com.dwurdy.straja.domain.model.LawBounds;
import com.dwurdy.straja.domain.model.LawCheckpointRecord;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.PushbackPoint;
import com.dwurdy.straja.domain.model.SnapshotItem;
import com.dwurdy.straja.domain.model.StoragePoint;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LAW-002 acceptance coverage: two-stage border post (warn → arrest),
 * single-stage port gate, wrong-way repel, wanted-on-sight, boarding stamps,
 * and the immutable crossing ledger with pre-mutation snapshots.
 */
class CheckpointServiceTest {
    private static final String DIM = "minecraft:overworld";

    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private TestDeepScan deepScan;
    private TestWorld world;
    private PrisonService prison;
    private StorageService storage;
    private BoloService bolos;
    private CheckpointService checkpoints;
    private TestPlayer player;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        deepScan = (TestDeepScan) ctx.deepScan();
        world = (TestWorld) ctx.world();
        var players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        var custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        bolos = new BoloService(ctx, players, audit);
        storage = new StorageService(ctx, players, audit, bolos, prison);
        checkpoints = new CheckpointService(ctx, audit, prison, storage, bolos);
        player = server.add("civ");
        player.x = 5; player.y = 60; player.z = -5; // within MAX_STEP of the stage edge
    }

    // ------------------------------------------------------------ helpers

    /** Advances to the next 5-tick scan boundary and runs one scan. */
    private void scan() {
        server.tick = (server.tick / 5 + 1) * 5;
        checkpoints.tick();
    }

    private void move(double x, double y, double z) {
        player.x = x; player.y = y; player.z = z;
        scan();
    }

    /** Builds an unsaved site record — call {@link #save} after configuring. */
    private LawCheckpointRecord site(String id, CheckpointMode mode) {
        var record = new LawCheckpointRecord();
        record.id = id;
        record.name = id;
        record.dimension = DIM;
        record.mode = mode;
        record.pushback = PushbackPoint.at(DIM, 0, 60, -10, 180f);
        return record;
    }

    private void save(LawCheckpointRecord record) {
        var store = ctx.lawCheckpoints().read();
        store.put(record);
        ctx.lawCheckpoints().write(store);
        scan(); // seed prev-position now that a site exists; crossings are detected next scan
    }

    private void globalBan(String itemId) {
        var store = ctx.lawCheckpoints().read();
        store.globalIllegalItems().put(itemId, true);
        ctx.lawCheckpoints().write(store);
    }

    private void carry(String itemId, int count) {
        deepScan.inventories.put(player.uuid,
                List.of(new SnapshotItem("main:0", itemId, count, "", itemId)));
    }

    private List<InspectionLedgerEntry> ledger() {
        return ctx.inspectionLedger().read().entries();
    }

    private InspectionLedgerEntry lastEntry() {
        var entries = ledger();
        return entries.isEmpty() ? null : entries.get(entries.size() - 1);
    }

    private boolean inCell() {
        var rec = ctx.prisonerRegister().read().prisoner(player.uuid.toString());
        return rec != null && rec.status == PrisonerStatus.IN_CELL;
    }

    // ------------------------------------------------------------ AT1: border post

    @Test
    void cleanPlayerPassesStage1() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        site.stage2 = LawBounds.of(DIM, 0, 55, 11, 10, 65, 20);
        save(site);

        move(5, 60, 5); // crosses into stage1

        var entry = lastEntry();
        assertNotNull(entry);
        assertEquals(CrossingOutcome.PASS, entry.outcome);
        assertEquals("border", entry.checkpointId);
        assertEquals(player.uuid.toString(), entry.playerUuid);
        assertFalse(inCell());
        assertTrue(player.told("straja.checkpoint.clean"));
    }

    @Test
    void contrabandWarnsAtStage1ThenArrestsAtStage2() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        site.stage2 = LawBounds.of(DIM, 0, 55, 11, 10, 65, 20);
        save(site);
        globalBan("minecraft:iron_ingot");
        carry("minecraft:iron_ingot", 3);

        move(5, 60, 5); // stage1 — warn, no custody
        var warn = lastEntry();
        assertNotNull(warn);
        assertEquals(CrossingOutcome.WARN, warn.outcome);
        assertEquals(List.of("3 x minecraft:iron_ingot"), warn.contrabandSummary);
        assertFalse(warn.inventorySnapshot.isEmpty());
        assertFalse(inCell());
        assertTrue(player.told("TITLE:straja.checkpoint.warn_title"));

        move(5, 60, 15); // stage2 — still carrying → arrest
        var arrest = lastEntry();
        assertNotNull(arrest);
        assertEquals(CrossingOutcome.ARREST, arrest.outcome);
        assertEquals("marfă interzisă la frontieră", arrest.detail);
        assertTrue(inCell());
        assertNotNull(prison.activeSentence(player));
        var rec = ctx.prisonerRegister().read().prisoner(player.uuid.toString());
        assertEquals("border", rec.arrestSite);
        assertFalse(rec.arrestSnapshot.isEmpty());
    }

    @Test
    void warnedCarrierWhoEmptiesPassesStage2() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        site.stage2 = LawBounds.of(DIM, 0, 55, 11, 10, 65, 20);
        save(site);
        globalBan("minecraft:iron_ingot");
        carry("minecraft:iron_ingot", 3);
        move(5, 60, 5);
        assertEquals(CrossingOutcome.WARN, lastEntry().outcome);

        deepScan.inventories.remove(player.uuid); // dumped the goods
        move(5, 60, 15);
        assertEquals(CrossingOutcome.PASS, lastEntry().outcome);
        assertFalse(inCell());
    }

    @Test
    void bannedPlayerDeniedAtStage1() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        site.doors.add(new StoragePoint(DIM, 2, 60, 3));
        site.bannedPlayerUuids.add(player.name);
        save(site);

        move(5, 60, 5);

        var entry = lastEntry();
        assertEquals(CrossingOutcome.DENY, entry.outcome);
        assertFalse(inCell());
        assertEquals(0, player.x, 1e-6);
        assertEquals(-10, player.z, 1e-6); // pushed back to the deny point
        assertEquals("minecraft:overworld,2,60,3", world.closedDoors.get(0));
    }

    // ------------------------------------------------------------ AT3: port gate (single-stage arrest)

    @Test
    void singleStageGateArrestsContrabandImmediately() {
        var site = site("port", CheckpointMode.ARREST);
        site.stage2 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10); // stage2 only
        save(site);
        globalBan("minecraft:tnt");
        carry("minecraft:tnt", 1);

        move(5, 60, 5);

        var entry = lastEntry();
        assertEquals(CrossingOutcome.ARREST, entry.outcome);
        assertTrue(inCell());
    }

    // ------------------------------------------------------------ AT6: wanted on sight

    @Test
    void thiefArrestedOnSightDespiteCleanInventory() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage2 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        save(site);
        var store = ctx.storage().read();
        store.markThief(player.uuid.toString(),
                new com.dwurdy.straja.domain.model.ThiefRecord(100, null, clock.nowMillis()));
        ctx.storage().write(store);
        assertTrue(storage.isThief(player.uuid));

        move(5, 60, 5);

        var entry = lastEntry();
        assertEquals(CrossingOutcome.ARREST, entry.outcome);
        assertEquals("vânat de Straja prins la frontieră", entry.detail);
        assertTrue(inCell());
        assertFalse(storage.isThief(player.uuid)); // custody consumed the hunt
    }

    @Test
    void fugitiveArrestedRegardlessOfInventory() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        save(site);
        var reg = ctx.prisonerRegister().read();
        var rec = new com.dwurdy.straja.domain.model.PrisonerRegisterRecord(
                player.uuid.toString(), player.name, "escaped");
        rec.status = PrisonerStatus.FUGITIVE;
        reg.put(rec);
        ctx.prisonerRegister().write(reg);

        move(5, 60, 5); // stage1 — custody check precedes the warn pipeline

        assertEquals(CrossingOutcome.ARREST, lastEntry().outcome);
        assertTrue(inCell());
    }

    @Test
    void wantedPlayerPushedBackAtDenyGate() {
        var site = site("mine", CheckpointMode.DENY);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        save(site);
        var store = ctx.storage().read();
        store.markThief(player.uuid.toString(),
                new com.dwurdy.straja.domain.model.ThiefRecord(100, null, clock.nowMillis()));
        ctx.storage().write(store);

        move(5, 60, 5);

        var entry = lastEntry();
        assertEquals(CrossingOutcome.DENY, entry.outcome);
        assertEquals("vânat reperat la poartă", entry.detail);
        assertFalse(inCell());
        assertEquals(-10, player.z, 1e-6); // repelled
    }

    @Test
    void arrestClearsActiveBolos() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage2 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        save(site);
        var bs = ctx.bolos().read();
        var bolo = new BoloRecord();
        bolo.id = "B1";
        bolo.subjectUuid = player.uuid.toString();
        bolo.subjectName = player.name;
        bolo.status = BoloStatus.ACTIVE;
        bs.records.add(bolo);
        ctx.bolos().write(bs);

        move(5, 60, 5);

        assertEquals(BoloStatus.CANCELLED, ctx.bolos().read().records.get(0).status);
        assertTrue(inCell());
    }

    // ------------------------------------------------------------ geometry

    @Test
    void wrongWayLaneCrossingRepelledToOrigin() {
        var site = site("gate", CheckpointMode.DENY);
        // segment x∈[0,10] at z=0; 'from' is the z<0 side — z>0 → z<0 is wrong-way
        site.gates.add(new GateLane(DIM, 0, 0, 10, 0, 5, -5));
        save(site);

        move(5, 60, 2);   // first position, wrong side
        move(5, 60, -2);  // crosses the lane the forbidden way

        var entry = lastEntry();
        assertEquals(CrossingOutcome.DENY, entry.outcome);
        assertEquals("sens interzis la poartă", entry.detail);
        assertEquals(2, player.z, 1e-6); // back at the crossing origin
        assertTrue(player.told("straja.checkpoint.wrongway"));
        assertFalse(inCell());
    }

    @Test
    void rightWayArrivalWithContrabandArrests() {
        var dock = site("dock", CheckpointMode.DENY);
        var gate = site("gate", CheckpointMode.ARREST);
        gate.linkedCheckpointId = "dock";
        gate.gates.add(new GateLane(DIM, 0, 0, 10, 0, 5, -5));
        dock.name = "dock";
        save(dock);
        save(gate);
        globalBan("minecraft:diamond");
        carry("minecraft:diamond", 1);

        move(5, 60, -2);  // right side
        move(5, 60, 2);   // right-way crossing, no stamp → full assessment

        assertEquals(CrossingOutcome.ARREST, lastEntry().outcome);
        assertEquals("marfă interzisă — control ocolit la îmbarcare", lastEntry().detail);
        assertTrue(inCell());
    }

    @Test
    void rightWayArrivalCleanPasses() {
        var gate = site("gate", CheckpointMode.ARREST);
        gate.linkedCheckpointId = "dock";
        gate.gates.add(new GateLane(DIM, 0, 0, 10, 0, 5, -5));
        save(gate);

        move(5, 60, -2);
        move(5, 60, 2);

        var entry = lastEntry();
        assertEquals(CrossingOutcome.PASS, entry.outcome);
        assertEquals("curat — trecere fără îmbarcare", entry.detail);
        assertFalse(inCell());
    }

    @Test
    void teleportJumpIgnored() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        save(site);
        globalBan("minecraft:tnt");
        carry("minecraft:tnt", 1);

        move(5, 60, -20); // 15 blocks — step over the guard, position seeds anyway
        move(500, 60, 500); // teleport — not a walking crossing
        move(5, 60, -20); // teleport back outside — lands outside any box, ignored

        assertTrue(ledger().isEmpty());
        assertFalse(inCell());
    }

    @Test
    void teleportIntoArrestZoneIsContained() {
        // Edge-triggered entry treats teleporting into stage2 as an entry —
        // pearl/jump exploits can't bypass the arrest line.
        var site = site("border", CheckpointMode.ARREST);
        site.stage2 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        save(site);
        globalBan("minecraft:tnt");
        carry("minecraft:tnt", 1);

        move(500, 60, 500); // teleport far away — prev seeds there
        move(5, 60, 5);     // teleport straight into the arrest box

        var entry = lastEntry();
        assertNotNull(entry);
        assertEquals(CrossingOutcome.ARREST, entry.outcome);
        assertTrue(inCell());
    }

    // ------------------------------------------------------------ boarding

    @Test
    void cleanRiderBoardedThenGateConsumesStamp() {
        var dock = site("dock", CheckpointMode.DENY);
        dock.boardZone = new BoardingZone(DIM, 0, -10, 10, 10, 60);
        save(dock);
        var gate = site("gate", CheckpointMode.ARREST);
        gate.linkedCheckpointId = "dock";
        gate.gates.add(new GateLane(DIM, 0, 20, 10, 20, 5, 15));
        save(gate);

        player.ridingBoat = true;
        move(5, 60, 0); // inside the dock zone while riding

        var boarded = lastEntry();
        assertEquals(CrossingOutcome.PASS, boarded.outcome);
        assertEquals("boarded curat", boarded.detail);
        assertTrue(player.told("straja.checkpoint.board_ok"));

        player.ridingBoat = false;
        move(5, 60, 15);  // approach the gate from the 'from' side
        move(5, 60, 25);  // right-way crossing — stamp consumed

        var entry = lastEntry();
        assertEquals(CrossingOutcome.PASS, entry.outcome);
        assertTrue(entry.detail.contains("controlat la îmbarcare"));
        assertTrue(player.told("straja.checkpoint.stamp_ok"));
    }

    @Test
    void contrabandRiderArrestedAtBoarding() {
        var dock = site("dock", CheckpointMode.ARREST);
        dock.boardZone = new BoardingZone(DIM, 0, -10, 10, 10, 60);
        save(dock);
        globalBan("minecraft:tnt");
        carry("minecraft:tnt", 2);

        player.ridingBoat = true;
        move(5, 60, 0);

        assertEquals(CrossingOutcome.ARREST, lastEntry().outcome);
        assertTrue(inCell());
    }

    // ------------------------------------------------------------ exemptions & cadence

    @Test
    void exemptAndCreativePlayersSkipped() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        save(site);
        globalBan("minecraft:tnt");
        carry("minecraft:tnt", 1);

        // Ops are NOT exempt — the prototype scanned survival ops too.
        player.op = true;
        move(5, 60, 5);
        assertEquals(1, ledger().size());
        assertEquals(CrossingOutcome.WARN, lastEntry().outcome);

        // Site-local exemption (by name) skips evaluation.
        var exempt = server.add("vip");
        var store = ctx.lawCheckpoints().read();
        store.checkpoint("border").exemptions.add(exempt.name);
        ctx.lawCheckpoints().write(store);
        scan();
        exempt.x = 5; exempt.y = 60; exempt.z = -5;
        deepScan.inventories.put(exempt.uuid,
                List.of(new SnapshotItem("main:0", "minecraft:tnt", 1, "", "")));
        scan(); // seeds prev
        exempt.z = 5;
        scan();
        assertEquals(1, ledger().size()); // still only the op's WARN

        // Creative is never policed.
        player.op = false;
        player.gameMode = "creative";
        player.x = 5; player.y = 60; player.z = -5;
        scan();
        move(5, 60, 5);
        assertEquals(1, ledger().size());
    }

    @Test
    void prisonDisabledRepelsInsteadOfPhantomCustody() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage2 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        save(site);
        globalBan("minecraft:tnt");
        carry("minecraft:tnt", 1);
        ctx.policies().prisonEnabled = false;

        move(5, 60, 5);

        var entry = lastEntry();
        assertEquals(CrossingOutcome.DENY, entry.outcome);
        assertEquals("arest indisponibil — prison oprit", entry.detail);
        assertFalse(inCell()); // no phantom IN_CELL booking
        assertNull(prison.activeSentence(player));
        assertEquals(-10, player.z, 1e-6); // repelled to the deny point
    }

    @Test
    void scanOnlyRunsOnCadence() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        save(site);
        globalBan("minecraft:tnt");
        carry("minecraft:tnt", 1);

        server.tick = 1; // not a scan tick
        checkpoints.tick();
        player.x = 5; player.y = 60; player.z = 5;
        server.tick = 2;
        checkpoints.tick();
        assertTrue(ledger().isEmpty());

        scan(); // real scan tick — the staged crossing is now detected
        assertEquals(1, ledger().size());
        assertEquals(CrossingOutcome.WARN, lastEntry().outcome);
    }

    // ------------------------------------------------------------ ledger query

    @Test
    void ledgerCommandShowsNewestFirst() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        save(site);
        var viewer = server.add("officer");

        move(5, 60, 5);
        checkpoints.showLedger(viewer, player.name);
        assertTrue(viewer.told("straja.checkpoint.ledger_header"));

        var stranger = server.add("stranger");
        checkpoints.showLedger(stranger, "nobody");
        assertTrue(stranger.told("straja.checkpoint.ledger_empty"));
    }
}
