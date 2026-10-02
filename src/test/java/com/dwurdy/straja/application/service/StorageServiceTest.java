package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.BoloRecord;
import com.dwurdy.straja.domain.model.BoloStatus;
import com.dwurdy.straja.domain.model.StoragePoint;
import com.dwurdy.straja.domain.model.StorageWatchStore;
import com.dwurdy.straja.domain.model.StorageZone;
import com.dwurdy.straja.domain.model.StorageSetup;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Straja Storage parity tests — zone theft triggers, chest-drain attribution,
 * deposit routing, guard aggro and rep pinning, pick flows.
 */
class StorageServiceTest {
    private static final String DIM = "minecraft:overworld";

    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private StorageService storage;
    private TestContainers containers;
    private TestNpcGuards npcGuards;
    private TestPlayer admin;
    private TestPlayer civ;
    private TestPlayer member;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        containers = (TestContainers) ctx.containers();
        npcGuards = (TestNpcGuards) ctx.npcGuards();
        ctx.policies().storageChestPollTicks = 1;
        ctx.policies().storageAggroPeriodTicks = 1;
        ctx.policies().storageEnforcePeriodTicks = 1;
        var players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        var custody = new CustodyService(ctx, players, audit);
        var prison = new PrisonService(ctx, players, audit, custody);
        var bolos = new BoloService(ctx, players, audit);
        storage = new StorageService(ctx, players, audit, bolos, prison);
        admin = server.add("admin");
        civ = server.add("civ1");
        member = server.add("guard1");
        ctx.factions().joinTeam(member, "Straja");
        civ.x = 2; civ.y = 60; civ.z = 2;
    }

    private void configureZone() {
        var s = ctx.storage().read();
        s.setup(new StorageSetup(null,
                new StorageZone(DIM, 0, 59, 0, 5, 65, 5), List.of()));
        ctx.storage().write(s);
    }

    // ------------------------------------------------------------- triggers

    @Test
    void breakingWatchedBlockInZoneFlagsThiefAndPinsRep() {
        configureZone();
        npcGuards.factionPoints.computeIfAbsent(civ.uuid().toString(), k -> new java.util.HashMap<>())
                .put(12, 500);
        storage.onBlockBroken(civ, DIM, 2, 60, 2, "minecraft:gold_block");
        assertTrue(storage.isThief(civ.uuid()));
        assertEquals(81, storage.owedUnits(civ.uuid()));
        assertEquals(0, npcGuards.factionPoints(civ.uuid(), 12));
        assertTrue(npcGuards.questLog.contains("start-team:Straja:2035"));
        assertTrue(admin.messages.stream().anyMatch(m -> m.contains("straja.storage.theft_title")));
    }

    @Test
    void unwatchedBlocksAndOutsideZoneAreIgnored() {
        configureZone();
        storage.onBlockBroken(civ, DIM, 2, 60, 2, "minecraft:stone");
        storage.onBlockBroken(civ, DIM, 100, 60, 100, "minecraft:gold_block");
        assertFalse(storage.isThief(civ.uuid()));
    }

    @Test
    void creativeAndExemptPlayersAreNeverFlagged() {
        configureZone();
        civ.gameMode = "creative";
        storage.onBlockBroken(civ, DIM, 2, 60, 2, "minecraft:gold_block");
        assertFalse(storage.isThief(civ.uuid()));
        civ.gameMode = "survival";
        ctx.policies().storageExemptPlayers.add("civ1");
        storage.onBlockBroken(civ, DIM, 2, 60, 2, "minecraft:gold_block");
        assertFalse(storage.isThief(civ.uuid()));
    }

    @Test
    void itemPickupInZoneFlagsWithStackValue() {
        configureZone();
        storage.onItemPickedUp(civ, DIM, 2, 60, 2, "minecraft:gold_ingot", 5);
        assertTrue(storage.isThief(civ.uuid()));
        assertEquals(45, storage.owedUnits(civ.uuid()));
    }

    @Test
    void placingWatchedBlockRepaysAndRestoresRep() {
        configureZone();
        npcGuards.factionPoints.computeIfAbsent(civ.uuid().toString(), k -> new java.util.HashMap<>())
                .put(12, 500);
        storage.onBlockBroken(civ, DIM, 2, 60, 2, "minecraft:gold_block");
        storage.onBlockPlaced(civ, DIM, 2, 60, 2, "minecraft:gold_block");
        assertFalse(storage.isThief(civ.uuid()));
        assertEquals(500, npcGuards.factionPoints(civ.uuid(), 12));
        assertTrue(npcGuards.questLog.contains("finish-team:Straja:2035"));
    }

    // --------------------------------------------------------- chest polling

    @Test
    void chestDrainFlagsNearestEligibleSuspect() {
        var chest = new StoragePoint(DIM, 10, 60, 10);
        var s = ctx.storage().read();
        s.setup(new StorageSetup(null, null, List.of(chest)));
        ctx.storage().write(s);
        containers.put(DIM, 10, 60, 10, "minecraft:gold_ingot", 10);
        civ.x = 11; civ.y = 60; civ.z = 10;
        storage.tick(); // seeds the cache
        containers.slots.get(chest.key()).set(0, com.dwurdy.straja.application.port.out.ItemView.EMPTY);
        storage.tick();
        assertTrue(storage.isThief(civ.uuid()));
        assertEquals(90, storage.owedUnits(civ.uuid()));
    }

    @Test
    void alliedHandCloserThanSuspectVetoesAttribution() {
        var chest = new StoragePoint(DIM, 10, 60, 10);
        var s = ctx.storage().read();
        s.setup(new StorageSetup(null, null, List.of(chest)));
        ctx.storage().write(s);
        ctx.policies().storageAlliedTeams.add("Merchant");
        var merchant = server.add("trader");
        ctx.factions().joinTeam(merchant, "Merchant");
        merchant.x = 10; merchant.y = 60; merchant.z = 10; // closer than civ
        containers.put(DIM, 10, 60, 10, "minecraft:gold_ingot", 10);
        storage.tick();
        containers.slots.get(chest.key()).set(0, com.dwurdy.straja.application.port.out.ItemView.EMPTY);
        storage.tick();
        assertFalse(storage.isThief(civ.uuid()));
        assertFalse(storage.isThief(merchant.uuid()));
    }

    @Test
    void returnedGoodsCreditNearestFlaggedPayer() {
        configureZone();
        storage.onBlockBroken(civ, DIM, 2, 60, 2, "minecraft:gold_block");
        var chest = new StoragePoint(DIM, 2, 60, 3);
        var s = ctx.storage().read();
        s.setup(s.setup().addChest(chest));
        ctx.storage().write(s);
        containers.placeContainer(DIM, 2, 60, 3);
        storage.tick();
        containers.insert(DIM, 2, 60, 3, "minecraft:gold_ingot", 9);
        storage.tick();
        assertFalse(storage.isThief(civ.uuid()));
    }

    // ---------------------------------------------------------------- deposit

    @Test
    void depositMergesThenFillsAndDropsOverflow() {
        containers.placeContainer(DIM, 50, 60, 50);
        containers.slotCount = 1;
        containers.placeContainer(DIM, 51, 60, 51);
        containers.slotCount = 27;
        var s = ctx.storage().read();
        s.setup(new StorageSetup(new StoragePoint(DIM, 51, 60, 51), null, List.of()));
        ctx.storage().write(s);
        var result = storage.deposit("civ1", "minecraft:gold_ingot", 100);
        assertTrue(result.success());
        assertEquals(64, result.inserted());
        assertEquals(36, result.leftover());
        assertEquals(1, containers.drops.size());
    }

    @Test
    void depositRefusesUnknownItemOrMissingDest() {
        var r1 = storage.deposit("civ1", "minecraft:dirt", 10);
        assertFalse(r1.success());
        var r2 = storage.deposit("civ1", "minecraft:gold_ingot", 10);
        assertFalse(r2.success());
    }

    @Test
    void depositIntoWatchedDestDoesNotFlagBystander() {
        var dest = new StoragePoint(DIM, 51, 60, 51);
        var s = ctx.storage().read();
        s.setup(new StorageSetup(dest, null, List.of(dest))); // dest is also watched
        ctx.storage().write(s);
        containers.placeContainer(DIM, 51, 60, 51);
        // Deposit lands between boot and the first poll — pending must fold
        // into the baseline, not be attributed to the nearest player.
        var r = storage.deposit("merchant", "minecraft:gold_ingot", 20);
        assertTrue(r.success());
        civ.x = 51; civ.y = 60; civ.z = 52;
        storage.tick();
        assertFalse(storage.isThief(civ.uuid()));
        // And a deposit between polls is not credited to a flagged payer...
        containers.insert(DIM, 51, 60, 51, "minecraft:gold_ingot", 5);
        storage.tick();
        assertFalse(storage.isThief(civ.uuid()));
    }

    @Test
    void thiefDeathClearsFlagAndRestoresRep() {
        configureZone();
        npcGuards.factionPoints.computeIfAbsent(civ.uuid().toString(), k -> new java.util.HashMap<>())
                .put(12, 750);
        storage.onBlockBroken(civ, DIM, 2, 60, 2, "minecraft:gold_block");
        storage.onPlayerDeath(civ);
        assertFalse(storage.isThief(civ.uuid()));
        assertEquals(750, npcGuards.factionPoints(civ.uuid(), 12));
        assertTrue(civ.told("straja.storage.thief_died"));
    }

    // ----------------------------------------------------------------- aggro

    @Test
    void aggroScanTargetsFlaggedThiefAndReleasesOnClear() {
        configureZone();
        var guardId = npcGuards.addGuard(3, 60, 3, 12);
        storage.onBlockBroken(civ, DIM, 2, 60, 2, "minecraft:gold_block");
        npcGuards.guards.get(guardId).lineOfSight.add(civ.uuid());
        storage.tick();
        assertEquals(civ.uuid(), npcGuards.guards.get(guardId).target);
        storage.onBlockPlaced(civ, DIM, 2, 60, 2, "minecraft:gold_block");
        storage.tick();
        assertNull(npcGuards.guards.get(guardId).target);
    }

    @Test
    void boloWantedPlayerIsAggroTargetWithoutTheft() {
        var bs = ctx.bolos().read();
        var record = new BoloRecord();
        record.id = "b-1";
        record.subjectUuid = civ.uuid().toString();
        record.status = BoloStatus.ACTIVE;
        bs.records.add(record);
        ctx.bolos().write(bs);
        var guardId = npcGuards.addGuard(3, 60, 3, 12);
        npcGuards.guards.get(guardId).lineOfSight.add(civ.uuid());
        storage.tick();
        assertEquals(civ.uuid(), npcGuards.guards.get(guardId).target);
    }

    @Test
    void guardsIgnoreCleanPlayers() {
        var guardId = npcGuards.addGuard(3, 60, 3, 12);
        npcGuards.guards.get(guardId).lineOfSight.add(civ.uuid());
        storage.tick();
        assertNull(npcGuards.guards.get(guardId).target);
    }

    // ------------------------------------------------------------------ misc

    @Test
    void strajaMemberLoginJoinsHuntWhileThievesActive() {
        configureZone();
        storage.onBlockBroken(civ, DIM, 2, 60, 2, "minecraft:gold_block");
        npcGuards.questLog.clear();
        storage.onLogin(member);
        assertTrue(npcGuards.questLog.stream().anyMatch(l -> l.startsWith("start-player:" + member.uuid())));
        npcGuards.questLog.clear();
        storage.onLogin(civ);
        assertTrue(npcGuards.questLog.isEmpty());
    }

    @Test
    void pickFlowSetsDestZoneAndChests() {
        containers.placeContainer(DIM, 20, 60, 20);
        assertTrue(storage.setPickMode(admin, "dest"));
        assertTrue(storage.onPickClick(admin, DIM, 20, 60, 20));
        assertEquals(new StoragePoint(DIM, 20, 60, 20), ctx.storage().read().setup().dest());

        assertTrue(storage.setPickMode(admin, "zone"));
        assertTrue(storage.onPickClick(admin, DIM, 0, 59, 0));
        assertTrue(storage.onPickClick(admin, DIM, 5, 65, 5));
        assertNotNull(ctx.storage().read().setup().zone());

        assertTrue(storage.setPickMode(admin, "chest"));
        assertTrue(storage.onPickClick(admin, DIM, 20, 60, 20));
        assertEquals(1, ctx.storage().read().setup().chests().size());

        assertTrue(storage.setPickMode(admin, "off"));
        assertNull(storage.pickMode(admin.uuid()));
        assertFalse(storage.onPickClick(admin, DIM, 20, 60, 20));
    }

    @Test
    void nonContainerPickIsRefusedWithRemedy() {
        assertTrue(storage.setPickMode(admin, "dest"));
        assertTrue(storage.onPickClick(admin, DIM, 30, 60, 30));
        assertTrue(admin.told("nu este un container"));
        assertNull(ctx.storage().read().setup().dest());
    }

    @Test
    void storeSurvivesRoundTrip() {
        configureZone();
        storage.onBlockBroken(civ, DIM, 2, 60, 2, "minecraft:gold_block");
        StorageWatchStore reloaded = ctx.storage().read();
        assertTrue(reloaded.isThief(civ.uuid().toString()));
        assertEquals(81, reloaded.thief(civ.uuid().toString()).owed());
    }
}
