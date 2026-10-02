package com.dwurdy.straja.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.domain.model.*;
import com.dwurdy.straja.support.MemoryStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** LAW-001: round-trip, schema versioning, retention, and resilience for the law aggregates. */
class LawPersistenceTest {
    private Map<String, MemoryStore> memory;
    private StoreAccess access;

    @BeforeEach
    void setUp() {
        memory = new HashMap<>();
        access = name -> memory.computeIfAbsent(name, k -> new MemoryStore());
    }

    private LawCheckpointRecord fullCheckpoint() {
        var r = new LawCheckpointRecord();
        r.id = "border_north";
        r.name = "Poarta Nord";
        r.dimension = "minecraft:overworld";
        r.active = true;
        r.stage1 = LawBounds.of("minecraft:overworld", 0, 60, 0, 10, 64, 5);
        r.stage2 = LawBounds.of("minecraft:overworld", 0, 60, 6, 10, 64, 11);
        r.pushback = new PushbackPoint("minecraft:overworld", 5.5, 61, -2.5, 180f, 0, -0.4, 0);
        r.doors.add(new StoragePoint("minecraft:overworld", 5, 61, 0));
        r.doors.add(new StoragePoint("minecraft:overworld", 5, 62, 0));
        r.evidenceChests.add(new StoragePoint("minecraft:overworld", 20, 60, 20));
        r.evidenceChests.add(new StoragePoint("minecraft:overworld", 21, 60, 20));
        r.mode = CheckpointMode.ARREST;
        r.direction = CrossingDirection.OUTGOING;
        r.linkedCheckpointId = "border_south";
        r.gates.add(new GateLane("minecraft:overworld", 0, 0, 10, 0, 5, -3));
        r.boardZone = new BoardingZone("minecraft:overworld", -10, -10, 10, 10, 62);
        r.localIllegalItems.add("minecraft:iron_ore");
        r.localAllowedItems.add("minecraft:gold_ingot");
        r.bannedPlayerUuids.add("11111111-1111-1111-1111-111111111111");
        r.roleBans.add("MINER");
        r.roleCarryBans.put("MINER", List.of("minecraft:coal_ore", "minecraft:salt"));
        r.exemptions.add("ferryman");
        r.legacyBannedNames.add("old_name");
        return r;
    }

    @Test
    void lawCheckpointRoundTrips() {
        var repo = new SavedStores.LawCheckpoints(access);
        var store = new LawCheckpointStore();
        store.put(fullCheckpoint());
        store.globalIllegalItems().put("minecraft:gold_block", true);
        store.globalBans().add("badguy");
        store.globalExemptions().add("admin1");
        repo.write(store);

        var loaded = repo.read().checkpoint("border_north");
        assertNotNull(loaded);
        assertEquals("Poarta Nord", loaded.name);
        assertEquals(CheckpointMode.ARREST, loaded.mode);
        assertEquals(CrossingDirection.OUTGOING, loaded.direction);
        assertEquals("border_south", loaded.linkedCheckpointId);
        assertEquals(0.0, loaded.pushback.vx());
        assertEquals(-0.4, loaded.pushback.vy(), 0.0001);
        assertEquals(180f, loaded.pushback.yaw(), 0.01);
        assertEquals(2, loaded.doors.size());
        assertEquals(2, loaded.evidenceChests.size());
        assertEquals(1, loaded.gates.size());
        assertEquals(10, loaded.gates.get(0).bx(), 0.001);
        assertNotNull(loaded.boardZone);
        assertEquals(List.of("MINER"), loaded.roleBans);
        assertEquals(List.of("minecraft:coal_ore", "minecraft:salt"), loaded.roleCarryBans.get("MINER"));
        assertEquals("old_name", loaded.legacyBannedNames.get(0));
        // global policy + site override resolution
        var reloaded = repo.read();
        assertTrue(reloaded.isIllegal(loaded, "minecraft:iron_ore"), "site-local ban");
        assertFalse(reloaded.isIllegal(loaded, "minecraft:gold_ingot"), "site exception wins over global");
        assertTrue(reloaded.isIllegal(loaded, "minecraft:gold_block"), "global list");
        assertFalse(reloaded.isIllegal(null, "minecraft:dirt"), "unlisted is legal");
    }

    @Test
    void inspectionLedgerRoundTripsAndTrims() {
        var repo = new SavedStores.InspectionLedger(access);
        var store = new InspectionLedgerStore();
        var entry = new InspectionLedgerEntry("e1", 1000L, "border_north", "uuid-1", "mctpilot",
                CrossingDirection.INCOMING, CrossingOutcome.WARN);
        entry.contrabandSummary.add("3x minecraft:iron_ore");
        entry.inventorySnapshot.add(new SnapshotItem("main:5", "minecraft:iron_ore", 3, "{count:3}", "Iron Ore"));
        entry.inventorySnapshot.add(new SnapshotItem("main:9>2", "minecraft:gold_ingot", 1, null, "Gold Ingot"));
        entry.detail = "warned at stage 1";
        store.append(entry, 0);
        repo.write(store);

        var loaded = repo.read().entries().get(0);
        assertEquals("e1", loaded.id);
        assertEquals(CrossingOutcome.WARN, loaded.outcome);
        assertEquals("main:9>2", loaded.inventorySnapshot.get(1).slot, "nested slot path persists");
        assertEquals("{count:3}", loaded.inventorySnapshot.get(0).componentsTag);

        // retention trim
        var st = repo.read();
        for (int i = 0; i < 10; i++) st.append(new InspectionLedgerEntry("x" + i, i, "c", "u", "n",
                CrossingDirection.INCOMING, CrossingOutcome.PASS), 5);
        assertEquals(5, st.entries().size());
        assertEquals("x9", st.entries().get(4).id, "oldest trimmed first");
    }

    @Test
    void prisonerRegisterRoundTripsWithPendingLockers() {
        var repo = new SavedStores.PrisonerRegister(access);
        var store = new PrisonerRegisterStore();
        var rec = new PrisonerRegisterRecord("uuid-p1", "smuggler", "contraband");
        rec.status = PrisonerStatus.IN_CAMP;
        rec.sentenceDays = 3;
        rec.outstandingFines = 128;
        rec.arrestCount = 2;
        rec.laborAccount = 256;
        rec.arrestSnapshot.add(new SnapshotItem("main:0", "minecraft:iron_ore", 5, null, "Iron Ore"));
        rec.assignedCellId = "cell_1";
        rec.assignedCampId = "camp_salt";
        rec.personalLocker.add(new StoragePoint("minecraft:overworld", 1, 60, 1));
        rec.personalLocker.add(new StoragePoint("minecraft:overworld", 2, 60, 1));
        rec.arrestSite = "border_north";
        store.put(rec);
        store.reserveLockers("uuid-offline", List.of("pchest:3", "pchest:4"));
        repo.write(store);

        var loaded = repo.read();
        var got = loaded.prisoner("uuid-p1");
        assertEquals(PrisonerStatus.IN_CAMP, got.status);
        assertEquals(256, got.laborAccount);
        assertEquals(2, got.arrestCount);
        assertEquals(2, got.personalLocker.size());
        assertEquals(1, got.arrestSnapshot.size());
        assertEquals(List.of("pchest:3", "pchest:4"), loaded.drainPendingLockers("uuid-offline"));
        assertTrue(loaded.drainPendingLockers("uuid-offline").isEmpty(), "drain is once-only");
    }

    @Test
    void laborCampRoundTrips() {
        var repo = new SavedStores.LaborCamps(access);
        var store = new LaborCampStore();
        var camp = new LaborCampRecord();
        camp.id = "camp_salt";
        camp.name = "Salina";
        camp.boundary = LawBounds.of("minecraft:overworld", 100, 40, 100, 160, 80, 160);
        camp.quartermasterDeskId = "desk_qm";
        camp.exitCheckpointId = "camp_exit";
        camp.intakeSpawn = new StoragePoint("minecraft:overworld", 110, 60, 110);
        camp.releaseSpawn = new StoragePoint("minecraft:overworld", 100, 61, 99);
        camp.dormitorySpawn = new StoragePoint("minecraft:overworld", 105, 60, 105);
        camp.freedomMode = FreedomPriceMode.FINES_MULTIPLIER;
        camp.freedomFineMultiplier = 3.0;
        store.put(camp);
        repo.write(store);

        var loaded = repo.read().camp("camp_salt");
        assertEquals("desk_qm", loaded.quartermasterDeskId);
        assertEquals(FreedomPriceMode.FINES_MULTIPLIER, loaded.freedomMode);
        assertEquals(3.0, loaded.freedomFineMultiplier, 0.001);
        assertTrue(loaded.boundary.contains("minecraft:overworld", 130, 60, 130));
        assertFalse(loaded.boundary.contains("minecraft:overworld", 99, 60, 130));
    }

    @Test
    void merchantDeskRoundTripsWithTradeLedger() {
        var repo = new SavedStores.MerchantDesks(access);
        var store = new MerchantDeskStore();
        var desk = new MerchantDeskRecord();
        desk.id = "desk_qm";
        desk.npcUuid = "aaaaaaaa-0000-0000-0000-0000000000aa";
        desk.npcName = "Intendentul";
        desk.deskPos = new StoragePoint("minecraft:overworld", 30, 61, 30);
        desk.chests.add(new StoragePoint("minecraft:overworld", 30, 60, 31));
        desk.chests.add(new StoragePoint("minecraft:overworld", 31, 60, 31));
        desk.sellTable.put("minecraft:iron_ore", 5);
        desk.sellTable.put("minecraft:salt", 2);
        desk.creditsLaborAccount = true;
        store.put(desk);
        var trade = new TradeLedgerEntry("t1", 2000L, "desk_qm", "uuid-s");
        trade.itemsSold.put("minecraft:iron_ore", 32);
        trade.baseUnits = 160;
        trade.creditedToLabor = true;
        store.appendTrade(trade, 0);
        repo.write(store);

        var loaded = repo.read();
        assertEquals(Integer.valueOf(5), loaded.desk("desk_qm").priceOf("minecraft:iron_ore"));
        assertNull(loaded.desk("desk_qm").priceOf("minecraft:dirt"));
        assertTrue(loaded.desk("desk_qm").creditsLaborAccount);
        assertEquals(160, loaded.trades().get(0).baseUnits);
        assertTrue(loaded.trades().get(0).creditedToLabor);
    }

    @Test
    void schemaVersioningRejectsFutureAndKeepsMissing() {
        var repo = new SavedStores.LawCheckpoints(access);
        // schema from a newer mod version → reset, backed up
        access.store("law_checkpoints").put("json", "{\"schemaVersion\":99}");
        var loaded = repo.read();
        assertEquals(0, loaded.checkpoints().size(), "future schema resets to empty");
        assertNotNull(access.store("law_checkpoints").get("future_schema_backup"));
        // v0 payload without schemaVersion → treated as current
        access.store("law_checkpoints").put("json",
                "{\"checkpoints\":{\"c\":{\"id\":\"c\",\"name\":\"C\"}}}");
        var v0 = repo.read();
        assertEquals("C", v0.checkpoint("c").name);
        assertEquals(LawCheckpointStore.CURRENT_SCHEMA, v0.schemaVersion);
    }

    @Test
    void corruptAndPartialPayloadsHeal() {
        var repo = new SavedStores.InspectionLedger(access);
        access.store("inspection_ledger").put("json", "{not json");
        var loaded = repo.read();
        assertTrue(loaded.entries().isEmpty(), "corrupt payload → fresh store");
        assertNotNull(access.store("inspection_ledger").get("corrupt_backup"));
        // partial record — missing lists self-heal
        access.store("inspection_ledger").put("json", "{\"entries\":[{\"id\":\"e\",\"outcome\":\"ARREST\"}]}");
        var partial = repo.read();
        assertEquals(CrossingOutcome.ARREST, partial.entries().get(0).outcome);
        assertTrue(partial.entries().get(0).inventorySnapshot.isEmpty());
    }

    @Test
    void gateLaneGeometry() {
        // lane across x∈[0,10] at z=0; travelers legitimately come from z<0
        var lane = new GateLane("minecraft:overworld", 0, 0, 10, 0, 5, -3);
        // crossing z-2 → z+2 inside the lane = crossing
        assertTrue(lane.crosses(5, -2, 5, 2));
        assertFalse(lane.crosses(5, -2, 5, -1), "no crossing when segment stays on one side");
        assertFalse(lane.crosses(20, -2, 20, 2), "parallel crossing outside lane bounds");
        assertTrue(lane.wrongWay(5, 2), "started on the far side = wrong-way");
        assertFalse(lane.wrongWay(5, -2), "started on the allowed side");
        assertFalse(lane.wrongWay(5, 0), "exactly on the line = ambiguous, no punish");
        assertTrue(lane.near(4, -2, 6, 2, 3));
        assertFalse(lane.near(30, -2, 32, 2, 3), "far segment out of margin");
    }

    @Test
    void lawBoundsNormalizesCorners() {
        var b = LawBounds.of("minecraft:overworld", 10, 64, 5, 0, 60, 0);
        assertTrue(b.contains("minecraft:overworld", 5, 62, 3));
        assertFalse(b.contains("minecraft:overworld", 11, 62, 3));
        assertFalse(b.contains("minecraft:nether", 5, 62, 3));
    }

    @Test
    void coinTierValuesDeriveFromRatio() {
        assertArrayEquals(new int[]{1, 64, 4096, 262144},
                StrajaPolicies.coinTierValues(64, 0, 0, 0), "default 1:64 ladder");
        assertArrayEquals(new int[]{1, 100, 10000, 1000000},
                StrajaPolicies.coinTierValues(100, 0, 0, 0), "custom ratio shifts all tiers");
        assertArrayEquals(new int[]{1, 50, 5000, 250000},
                StrajaPolicies.coinTierValues(0, 50, 5000, 250000), "explicit overrides win wholesale");
        assertArrayEquals(new int[]{1, 64, 4096, 999},
                StrajaPolicies.coinTierValues(64, 0, 0, 999), "partial override for gold only");
    }
}
