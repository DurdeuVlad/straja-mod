package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.domain.model.BoloStatus;
import com.dwurdy.straja.domain.model.CheckpointMode;
import com.dwurdy.straja.domain.model.CustodyStore;
import com.dwurdy.straja.domain.model.GateLane;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.domain.model.LawBounds;
import com.dwurdy.straja.domain.model.LawCheckpointRecord;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.PushbackPoint;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.StoragePoint;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import com.dwurdy.straja.support.MemoryStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LAW-004 acceptance coverage: seizure evidence/locker routing and
 * conservation invariants, fugitive marking on custody breach, game-mode
 * restore on release, and the AT8 escort contract (tether, gate bypass,
 * uncuff boundary rule, officer loss).
 */
class CustodySeizureServiceTest {
    private static final String DIM = "minecraft:overworld";

    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private CustodyService custody;
    private PrisonService prison;
    private SeizureService seizure;
    private CheckpointService checkpoints;
    private StorageService storage;
    private BoloService bolos;
    private TestContainers containers;
    private TestDeepScan deepScan;
    private TestPlayer boss;
    private TestPlayer officer;
    private TestPlayer suspect;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        var players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        seizure = new SeizureService(ctx, audit);
        prison.useSeizure(seizure);
        bolos = new BoloService(ctx, players, audit);
        prison.useBolos(bolos);
        custody.onRestraintReleased(prison::onUncuffed);
        storage = new StorageService(ctx, players, audit, bolos, prison);
        storage.useCustody(custody);
        var personnel = new PersonnelService(new SavedStores.Personnel(
                name -> new MemoryStore()), clock, new Fakes.SeqIds());
        checkpoints = new CheckpointService(ctx, audit, prison, storage, bolos, personnel, custody);
        containers = (TestContainers) ctx.containers();
        deepScan = (TestDeepScan) ctx.deepScan();
        deepScan.server = server;
        boss = server.add("dwurdy");
        officer = server.add("guard1");
        var officerState = players.state(officer.uuid());
        officerState.rank = Rank.GUARD.level();
        players.save(officer.uuid(), officerState);
        officer.give(ItemSpec.of(CustodyService.CUFFS, 1));
        suspect = server.add("civ");
    }

    // ------------------------------------------------------------ helpers

    private void carry(TestPlayer p, String itemId, int count) {
        carry(p, itemId, count, Map.of());
    }

    private void carry(TestPlayer p, String itemId, int count, Map<String, String> data) {
        p.inventory.slots.set(0, new ItemView(itemId, count, 64, data));
    }

    private int carrySlot(TestPlayer p, int slot, String itemId, int count) {
        p.inventory.slots.set(slot, new ItemView(itemId, count, 64, Map.of()));
        return slot;
    }

    private int countIn(List<ItemView> slots, String itemId) {
        int total = 0;
        for (ItemView v : slots) if (v != null && v.id().equals(itemId)) total += v.count();
        return total;
    }

    private void globalBan(String itemId) {
        var store = ctx.lawCheckpoints().read();
        store.globalIllegalItems().put(itemId, true);
        ctx.lawCheckpoints().write(store);
    }

    private LawCheckpointRecord siteWithEvidence(String id) {
        var site = new LawCheckpointRecord();
        site.id = id;
        site.name = id;
        site.dimension = DIM;
        site.mode = CheckpointMode.ARREST;
        site.pushback = PushbackPoint.at(DIM, 0, 60, -10, 180f);
        return site;
    }

    private void saveSite(LawCheckpointRecord site) {
        var store = ctx.lawCheckpoints().read();
        store.put(site);
        ctx.lawCheckpoints().write(store);
    }

    private void addLockerPool(StoragePoint... points) {
        var data = ctx.prison().read();
        for (var p : points) {
            containers.placeContainer(p.dimension(), p.x(), p.y(), p.z());
            data.lockerPool.add(p);
        }
        ctx.prison().write(data);
    }

    // ------------------------------------------------------------ seizure routing

    @Test
    void seizureSplitsContrabandToEvidenceAndPersonalToLocker() {
        globalBan("minecraft:tnt");
        var site = siteWithEvidence("border");
        site.evidenceChests.add(new StoragePoint(DIM, 100, 60, 100));
        containers.placeContainer(DIM, 100, 60, 100);
        saveSite(site);
        addLockerPool(new StoragePoint(DIM, 200, 60, 200));

        carry(suspect, "minecraft:tnt", 3);
        suspect.inventory.slots.set(1, new ItemView("minecraft:bread", 5, 64, Map.of()));

        prison.arrest(suspect, null, 1, boss, "checkpoint:border", "contraband", "border");

        // inventory fully seized — the report book the prisoner keeps is not loot
        assertEquals(0, countIn(suspect.inventory.slots, "minecraft:tnt"), "tnt must be seized");
        assertEquals(0, countIn(suspect.inventory.slots, "minecraft:bread"), "bread must be seized");
        // contraband → evidence, personal → locker
        assertEquals(3, countIn(containers.slots.get("minecraft:overworld|100,60,100"), "minecraft:tnt"));
        assertEquals(5, countIn(containers.slots.get("minecraft:overworld|200,60,200"), "minecraft:bread"));
        var rec = ctx.prisonerRegister().read().prisoner(suspect.uuid().toString());
        assertNotNull(rec);
        assertTrue(rec.confiscatedFully, "all seized items placed — nothing left");
        assertEquals(1, rec.personalLocker.size());
        assertEquals(List.of("3 x minecraft:tnt"), rec.confiscatedSummary);
        assertFalse(suspect.books.isEmpty(), "the prisoner receives the seizure report book");
    }

    @Test
    void nestedContrabandMarksWholeContainerStack() {
        globalBan("minecraft:tnt");
        var site = siteWithEvidence("border");
        site.evidenceChests.add(new StoragePoint(DIM, 100, 60, 100));
        containers.placeContainer(DIM, 100, 60, 100);
        saveSite(site);
        // a shulker LOOKS clean but hides tnt inside → classified contraband
        carry(suspect, "minecraft:shulker_box", 1,
                Map.of("contains", "minecraft:tnt"));

        prison.arrest(suspect, null, 1, boss, "checkpoint:border", "nested", "border");

        assertEquals(1, countIn(containers.slots.get("minecraft:overworld|100,60,100"),
                "minecraft:shulker_box"),
                "a stack hiding contraband goes to evidence as a whole");
    }

    @Test
    void evidenceOverflowSpillsToNextChestThenDrops() {
        globalBan("minecraft:tnt");
        var site = siteWithEvidence("border");
        site.evidenceChests.add(new StoragePoint(DIM, 100, 60, 100));
        site.evidenceChests.add(new StoragePoint(DIM, 101, 60, 100));
        // chest A: one slot, already full of stone; chest B: one free slot
        containers.slotCount = 1;
        containers.placeContainer(DIM, 100, 60, 100);
        containers.put(DIM, 100, 60, 100, "minecraft:stone", 64);
        containers.placeContainer(DIM, 101, 60, 100);
        saveSite(site);

        carry(suspect, "minecraft:tnt", 80); // 64 fits B, 16 must drop at A
        prison.arrest(suspect, null, 1, boss, "checkpoint:border", "overflow", "border");

        assertEquals(0, countIn(containers.slots.get("minecraft:overworld|100,60,100"), "minecraft:tnt"),
                "full chest A must not absorb tnt");
        assertEquals(64, countIn(containers.slots.get("minecraft:overworld|101,60,100"), "minecraft:tnt"));
        assertTrue(containers.drops.stream().anyMatch(d -> d.contains("minecraft:tnt x16")),
                "the remainder spills into the world at evidence — never voided");
        var rec = ctx.prisonerRegister().read().prisoner(suspect.uuid().toString());
        assertFalse(rec.confiscatedFully, "dropped overflow means confiscatedFully=false");
    }

    @Test
    void lockerOverflowSpillsToEvidence() {
        var site = siteWithEvidence("border");
        site.evidenceChests.add(new StoragePoint(DIM, 100, 60, 100));
        containers.placeContainer(DIM, 100, 60, 100);
        saveSite(site);
        // locker pool: one chest with a single slot already full
        containers.slotCount = 1;
        var locker = new StoragePoint(DIM, 200, 60, 200);
        containers.placeContainer(DIM, 200, 60, 200);
        containers.put(DIM, 200, 60, 200, "minecraft:dirt", 64);
        var data = ctx.prison().read();
        data.lockerPool.add(locker);
        ctx.prison().write(data);

        carry(suspect, "minecraft:bread", 10); // legal goods, locker full → evidence
        prison.arrest(suspect, null, 1, boss, "checkpoint:border", "locker overflow", "border");

        assertEquals(10, countIn(containers.slots.get("minecraft:overworld|100,60,100"),
                "minecraft:bread"), "locker overflow lands in the evidence chain");
        assertTrue(containers.drops.isEmpty(), "nothing dropped while evidence absorbs it");
    }

    @Test
    void arrestWithoutDestinationsNeverVoidsItems() {
        // no evidence chests, no locker pool — items drop at the prisoner,
        // visible and recoverable, but never disappear.
        carry(suspect, "minecraft:bread", 12);
        prison.arrest(suspect, null, 1, boss, null);

        assertFalse(containers.drops.isEmpty());
        assertTrue(containers.drops.get(0).contains("minecraft:bread x12"));
        var rec = ctx.prisonerRegister().read().prisoner(suspect.uuid().toString());
        assertFalse(rec.confiscatedFully);
    }

    // ------------------------------------------------------------ AT4: immutable snapshot

    @Test
    void arrestSnapshotRetainsTheExactArrestMoment() {
        var site = siteWithEvidence("border");
        site.stage2 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        saveSite(site);
        globalBan("minecraft:tnt");
        deepScan.inventories.put(suspect.uuid,
                List.of(new com.dwurdy.straja.domain.model.SnapshotItem(
                        "main:0", "minecraft:tnt", 2, "{tag:1}", "tnt")));

        suspect.x = 5; suspect.y = 60; suspect.z = -5;
        forceScan(); // seed prev-position
        suspect.z = 5;
        forceScan();

        var rec = ctx.prisonerRegister().read().prisoner(suspect.uuid().toString());
        assertNotNull(rec, "the arrest must book a register record");
        assertFalse(rec.arrestSnapshot.isEmpty(), "AT4: permanent snapshot retained");
        assertEquals("minecraft:tnt", rec.arrestSnapshot.get(0).itemId);
        assertEquals("{tag:1}", rec.arrestSnapshot.get(0).componentsTag,
                "snapshot keeps component data, not just ids");
    }

    // ------------------------------------------------------------ release

    @Test
    void releaseRestoresLockerBelongingsAndGameMode() {
        var site = siteWithEvidence("border");
        site.evidenceChests.add(new StoragePoint(DIM, 100, 60, 100));
        containers.placeContainer(DIM, 100, 60, 100);
        saveSite(site);
        addLockerPool(new StoragePoint(DIM, 200, 60, 200));
        ((TestWorld) ctx.world()).room(DIM, -1, 59, -1, 6, 66, 6, -1, 61, 2);
        assertTrue(prison.createCell(boss, "celula_1", DIM, 0, 60, 0, 5, 65, 5));
        carry(suspect, "minecraft:bread", 7);
        suspect.gameMode = "survival";

        prison.arrest(suspect, null, 1, boss, "checkpoint:border", "petty", "border");
        assertEquals("adventure", suspect.gameMode);
        assertTrue(countIn(containers.slots.get("minecraft:overworld|200,60,200"), "minecraft:bread") == 7);

        assertTrue(prison.release(boss, suspect, "served"));

        assertEquals(7, countIn(suspect.inventory.slots, "minecraft:bread"),
                "locker belongings return to the player");
        assertTrue(containers.slots.get("minecraft:overworld|200,60,200").stream()
                .allMatch(ItemView::isEmpty), "locker drained after release");
        assertEquals("survival", suspect.gameMode, "release restores the prior game mode");
        var rec = ctx.prisonerRegister().read().prisoner(suspect.uuid().toString());
        assertEquals(PrisonerStatus.RELEASED, rec.status);
        assertTrue(rec.personalLocker.isEmpty(), "locker reservation clears on release");
    }

    @Test
    void pendingLockersDeliverOnLoginForOfflineRelease() {
        // prisoner was released offline; locker keys were parked under pendingLockers
        var reg = ctx.prisonerRegister().read();
        reg.reserveLockers(suspect.uuid().toString(), List.of("minecraft:overworld|200,60,200"));
        ctx.prisonerRegister().write(reg);
        containers.placeContainer(DIM, 200, 60, 200);
        containers.put(DIM, 200, 60, 200, "minecraft:bread", 9);

        seizure.deliverPendingLockers(suspect);

        assertEquals(9, countIn(suspect.inventory.slots, "minecraft:bread"));
        assertTrue(ctx.prisonerRegister().read()
                .pendingLockers().isEmpty(), "pending lockers drain after delivery");
    }

    // ------------------------------------------------------------ AT8: escort

    private void cuffSuspect() {
        officer.teleport(DIM, suspect.x, suspect.y, suspect.z);
        assertTrue(custody.applyCuffsDirect(officer, suspect, "arrest").ok());
    }

    @Test
    void cuffedSuspectCannotSprint() {
        cuffSuspect();
        suspect.sprinting = true;
        custody.tick();
        assertFalse(suspect.sprinting, "a cuffed suspect cannot sprint (AT8)");
    }

    @Test
    void tetherDragsSuspectBackBeyondLeash() {
        cuffSuspect();
        officer.teleport(DIM, 0, 60, 0);
        suspect.teleport(DIM, 9, 60, 0); // past the 4.5 leash, under the 14 clamp
        custody.tick();
        assertTrue(suspect.vx < -0.3, "the tether drags the suspect toward the officer");
    }

    @Test
    void tetherTeleportsSuspectOnContactLoss() {
        cuffSuspect();
        officer.teleport(DIM, 0, 60, 0);
        suspect.teleport(DIM, 20, 60, 0); // beyond the clamp — teleport snatch
        custody.tick();
        assertEquals(0, suspect.x, 1e-6, "past the clamp the suspect is teleported to the officer");
    }

    @Test
    void officerLossLogsEscapeWindowButKeepsCuffs() {
        cuffSuspect();
        officer.online = false;
        custody.tick();
        assertTrue(custody.isCuffed(suspect), "the restraint survives an officer loss");
        var record = ctx.custody().read().cuffed.get(suspect.uuid().toString());
        assertNotNull(record.escortLostAt, "the escape window is stamped");
        // second tick does not re-log
        long stamp = record.escortLostAt;
        clock.advance(2_000);
        custody.tick();
        assertEquals(stamp, ctx.custody().read().cuffed.get(suspect.uuid().toString()).escortLostAt);
    }

    @Test
    void escortedSuspectBypassesDenyGateOnlyBesideOfficer() {
        var site = new LawCheckpointRecord();
        site.id = "gate";
        site.name = "gate";
        site.dimension = DIM;
        site.mode = CheckpointMode.DENY;
        site.pushback = PushbackPoint.at(DIM, 0, 60, -10, 180f);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        saveSite(site);
        // mark the suspect hunted — without an escort the gate repels them
        var watch = ctx.storage().read();
        watch.markThief(suspect.uuid.toString(),
                new com.dwurdy.straja.domain.model.ThiefRecord(50, null, clock.nowMillis()));
        ctx.storage().write(watch);

        cuffSuspect();
        // officer adjacent → bypass
        suspect.x = 5; suspect.y = 60; suspect.z = -5;
        officer.teleport(DIM, 5, 60, -5);
        forceScan(); // seed
        suspect.z = 5;
        officer.teleport(DIM, 5, 60, 4); // escorts across — inside the 3b radius
        forceScan();
        assertTrue(ctx.inspectionLedger().read().entries().stream()
                        .noneMatch(e -> suspect.uuid.toString().equals(e.playerUuid)),
                "an escorted suspect crosses untouched — no ledger line for them");
        assertEquals(5, suspect.z, 1e-6, "no repel beside the escorting officer");

        // officer far away → the same crossing repels
        suspect.z = -5;
        officer.teleport(DIM, 50, 60, 50);
        forceScan(); // reseed with no officer beside the suspect
        suspect.z = 5;
        forceScan();
        assertEquals(-10, suspect.z, 1e-6, "unescorted hunted suspect is repelled");
        assertTrue(ctx.inspectionLedger().read().entries().stream()
                        .anyMatch(e -> suspect.uuid.toString().equals(e.playerUuid)
                                && e.outcome == com.dwurdy.straja.domain.model.CrossingOutcome.DENY),
                "the repelled crossing is ledgered as a denial");
    }

    @Test
    void uncuffInsideCustodyKeepsPrisonerOutsideMarksFugitive() {
        // arrest first so the suspect carries a sentence and a cell
        ((TestWorld) ctx.world()).room(DIM, -1, 59, -1, 6, 66, 6, -1, 61, 2);
        assertTrue(prison.createCell(boss, "celula_1", DIM, 0, 60, 0, 5, 65, 5));
        prison.arrest(suspect, null, 1, boss, null);
        cuffSuspect();

        // uncuff inside the cell → stays IN_CELL
        assertTrue(custody.emergencyRelease(boss, suspect));
        var rec = ctx.prisonerRegister().read().prisoner(suspect.uuid().toString());
        assertEquals(PrisonerStatus.IN_CELL, rec.status,
                "uncuff inside custody preserves the IN_CELL status");
        assertTrue(bolos.active().isEmpty());

        // re-cuff, walk outside, uncuff → fugitive + BOLO
        cuffSuspect();
        suspect.teleport(DIM, 500, 64, 500);
        assertTrue(custody.emergencyRelease(boss, suspect));
        rec = ctx.prisonerRegister().read().prisoner(suspect.uuid().toString());
        assertEquals(PrisonerStatus.FUGITIVE, rec.status,
                "uncuff outside custody marks the prisoner fugitive");
        assertFalse(bolos.active().isEmpty(), "a system BOLO tracks the escape");
        assertEquals(BoloStatus.ACTIVE, bolos.active().get(0).status);
    }

    @Test
    void escortedPrisonerLeavingCellDoesNotBecomeFugitive() {
        ((TestWorld) ctx.world()).room(DIM, -1, 59, -1, 6, 66, 6, -1, 61, 2);
        assertTrue(prison.createCell(boss, "celula_1", DIM, 0, 60, 0, 5, 65, 5));
        prison.arrest(suspect, null, 1, boss, null);
        cuffSuspect();
        // officer walks the cuffed prisoner out of the cell — under escort,
        // not an escape
        officer.teleport(DIM, 30, 60, 30);
        suspect.teleport(DIM, 32, 60, 30);
        clock.advance(2_000);
        prison.tick();
        var rec = ctx.prisonerRegister().read().prisoner(suspect.uuid().toString());
        assertNotEquals(PrisonerStatus.FUGITIVE, rec.status,
                "a cuffed prisoner under escort is not a fugitive");
    }

    private void forceScan() {
        server.tick = (server.tick / 5 + 1) * 5;
        checkpoints.tick();
    }
}
