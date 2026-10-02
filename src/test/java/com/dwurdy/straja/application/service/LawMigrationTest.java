package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.support.Fakes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** LAW-001: legacy KubeJS law-enforcement blobs → native stores. */
class LawMigrationTest {
    private StrajaContext ctx;
    private MigrationService migration;

    @BeforeEach
    void setup() {
        ctx = Fakes.context(new Fakes.TestServer(), new Fakes.FixedClock(1_000_000));
        migration = new MigrationService(ctx, new AuditService(ctx));
    }

    private static final String CP_CFG = """
        {"contraband":{"minecraft:gold_ingot":true,"minecraft:raw_iron":true},
         "banned":{"badguy":true},"exempt":{"ferryman":true},
         "sites":{
           "port_nord":{
             "denyTarget":{"dim":"minecraft:overworld","x":5.5,"y":61.0,"z":-2.5,"yaw":180.0},
             "doors":[{"dim":"minecraft:overworld","x":5,"y":61,"z":0},
                      {"dim":"minecraft:overworld","x":5,"y":62,"z":0}],
             "evidence":[{"dim":"minecraft:overworld","x":20,"y":60,"z":20}],
             "gates":[{"a":{"x":0,"z":0},"b":{"x":10,"z":0},"from":{"x":5,"z":-3},"dim":"minecraft:overworld"}],
             "board":{"dim":"minecraft:overworld","x1":-10,"z1":-10,"x2":10,"z2":10,"y":62},
             "cb":{"minecraft:iron_ore":true,"minecraft:gold_ingot":false},
             "exempt":{"dockworker":true},
             "link":"port_sud"},
           "port_sud":{"denyTarget":{"dim":"minecraft:overworld","x":5.5,"y":61.0,"z":20.5,"yaw":0.0}}}}
        """;

    @Test
    void checkpointCfgImportsSitesAndGlobals() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("portCheckpointCfg", CP_CFG);
        var report = migration.migrateServer(data);
        assertTrue(report.ok(), () -> String.join(" | ", report.lines()));

        var store = ctx.lawCheckpoints().read();
        var nord = store.checkpoint("port_nord");
        assertNotNull(nord);
        assertEquals("minecraft:overworld", nord.dimension);
        assertNotNull(nord.pushback);
        assertEquals(-2.5, nord.pushback.z(), 0.001);
        assertEquals(180f, nord.pushback.yaw(), 0.01);
        assertEquals(2, nord.doors.size());
        assertEquals(1, nord.evidenceChests.size());
        assertEquals(1, nord.gates.size());
        assertEquals(5, nord.gates.get(0).fromX(), 0.001);
        assertNotNull(nord.boardZone);
        assertEquals("port_sud", nord.linkedCheckpointId);
        assertTrue(nord.localIllegalItems.contains("minecraft:iron_ore"), "cb=true → extra ban");
        assertTrue(nord.localAllowedItems.contains("minecraft:gold_ingot"), "cb=false → site exception");
        assertTrue(nord.exemptions.contains("dockworker"));
        assertNotNull(store.checkpoint("port_sud"), "second site imported");
        assertTrue(Boolean.TRUE.equals(store.globalIllegalItems().get("minecraft:gold_ingot")));
        assertTrue(store.globalBans().contains("badguy"));
        assertTrue(store.globalExemptions().contains("ferryman"));
        assertTrue(store.isIllegal(nord, "minecraft:iron_ore"));
        assertFalse(store.isIllegal(nord, "minecraft:gold_ingot"), "site exception overrides global");
    }

    @Test
    void checkpointFlatLayoutMigratesToMainSite() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("portCheckpointCfg", """
            {"denyTarget":{"dim":"minecraft:overworld","x":1,"y":2,"z":3,"yaw":90},
             "doors":[{"dim":"minecraft:overworld","x":1,"y":2,"z":4}],
             "evidence":[{"dim":"minecraft:overworld","x":9,"y":9,"z":9}]}
            """);
        var report = migration.migrateServer(data);
        assertTrue(report.ok());
        var main = ctx.lawCheckpoints().read().checkpoint("main");
        assertNotNull(main, "flat config folds into site 'main' (prototype parity)");
        assertEquals(1, main.doors.size());
        assertEquals(1, main.evidenceChests.size());
    }

    @Test
    void checkpointMigrationIsIdempotent() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("portCheckpointCfg", CP_CFG);
        migration.migrateServer(data);
        migration.migrateServer(data); // second run
        assertEquals(2, ctx.lawCheckpoints().read().checkpoints().size(), "no duplicates on re-run");
    }

    @Test
    void prisonJailRegisterImports() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("strajaPrisonCfg", """
            {"jailTarget":{"dim":"minecraft:overworld","x":0,"y":60,"z":0,"yaw":0},
             "pcells":[{"a":{"dim":"minecraft:overworld","x":10,"y":60,"z":10},
                        "b":{"dim":"minecraft:overworld","x":11,"y":60,"z":10}}]}
            """);
        data.put("strajaPrisonJail", """
            {"jailed":{"smuggler":{"t":1700000000000,"reason":"contraband",
                "items":{"minecraft:gold_ingot":{"count":5,"name":"Gold Ingot"}},
                "confiscated":true,"status":"jailed","site":"port_nord",
                "arrests":2,"pchest":0,"jcell":3},
               "fugitiv":{"t":1700000001000,"reason":"evadare","status":"fugitive","arrests":1}},
             "fines":{"smuggler":128,"released_offline":64},
             "pendingChest":{"released_offline":0}}
            """);
        var report = migration.migrateServer(data);
        assertTrue(report.ok(), () -> String.join(" | ", report.lines()));

        var reg = ctx.prisonerRegister().read();
        String smugglerId = com.dwurdy.straja.domain.model.PrisonerRegisterStore.legacyUuid("smuggler");
        var rec = reg.prisoner(smugglerId);
        assertNotNull(rec, "offline-name prisoner keyed by deterministic UUID");
        assertEquals(PrisonerStatus.IN_CELL, rec.status);
        assertEquals("contraband", rec.detentionReason);
        assertEquals(2, rec.arrestCount);
        assertEquals(128, rec.outstandingFines, "fines map joined on name");
        assertEquals("jcell:3", rec.assignedCellId);
        assertEquals(2, rec.personalLocker.size(), "pchest index resolved to pair coords");
        assertEquals(10, rec.personalLocker.get(0).x());
        assertTrue(rec.confiscatedFully);
        assertEquals(1, rec.confiscatedSummary.size());
        assertEquals("port_nord", rec.arrestSite);

        String fugId = com.dwurdy.straja.domain.model.PrisonerRegisterStore.legacyUuid("fugitiv");
        var fugRec = reg.prisoner(fugId);
        assertEquals(PrisonerStatus.FUGITIVE, fugRec.status);
        assertTrue(fugRec.assignedCellId == null || fugRec.assignedCellId.isEmpty(),
                "absent jcell stays unassigned, not jcell:0");
        assertTrue(fugRec.personalLocker.isEmpty(), "absent pchest stays unassigned");

        // pendingChest stores a scalar pcells index → resolved to coordinate keys
        String releasedId = com.dwurdy.straja.domain.model.PrisonerRegisterStore.legacyUuid("released_offline");
        assertEquals(java.util.List.of("minecraft:overworld|10,60,10", "minecraft:overworld|11,60,10"),
                reg.drainPendingLockers(releasedId));
        // released players' outstanding fines survive via the legacy fine ledger
        assertEquals(64, (int) reg.legacyFines().get("released_offline"));
    }

    @Test
    void legacyPortCheckpointJailAliasReads() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("portCheckpointJail", """
            {"jailed":{"old_timer":{"t":1,"reason":"legacy","status":"jailed","arrests":1}},"fines":{}}
            """);
        assertTrue(migration.migrateServer(data).ok());
        String id = com.dwurdy.straja.domain.model.PrisonerRegisterStore.legacyUuid("old_timer");
        assertNotNull(ctx.prisonerRegister().read().prisoner(id));
    }

    @Test
    void storageCfgAndGoldFallbackImport() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("strajaStorageCfg", """
            {"dest":{"dim":"minecraft:overworld","x":50,"y":60,"z":50},
             "zone":{"dim":"minecraft:overworld","min":[0,50,0],"max":[20,70,20]},
             "chests":[{"dim":"minecraft:overworld","x":1,"y":60,"z":1},
                       {"dim":"minecraft:overworld","x":2,"y":60,"z":1}]}
            """);
        assertTrue(migration.migrateServer(data).ok());
        var setup = ctx.storage().read().setup();
        assertNotNull(setup.zone());
        assertTrue(setup.zone().contains("minecraft:overworld", 10, 60, 10));
        assertEquals(2, setup.chests().size());
        assertEquals(50, setup.dest().x());

        // gold-era key still ingests when the new key is absent
        var ctx2 = Fakes.context(new Fakes.TestServer(), new Fakes.FixedClock(1));
        var mig2 = new MigrationService(ctx2, new AuditService(ctx2));
        Map<String, String> gold = new LinkedHashMap<>();
        gold.put("strajaGoldCfg", """
            {"dest":{"dim":"minecraft:overworld","x":7,"y":60,"z":7},
             "vault":{"dim":"minecraft:overworld","min":[0,0,0],"max":[5,5,5]}}
            """);
        assertTrue(mig2.migrateServer(gold).ok());
        var s2 = ctx2.storage().read().setup();
        assertNotNull(s2.zone(), "legacy 'vault' field → zone");
        assertEquals(7, s2.dest().x());
    }

    @Test
    void storageImportDoesNotOverwriteExistingSetup() {
        var store = ctx.storage().read();
        store.setup(new com.dwurdy.straja.domain.model.StorageSetup(
                new com.dwurdy.straja.domain.model.StoragePoint("minecraft:overworld", 1, 1, 1),
                null, java.util.List.of()));
        ctx.storage().write(store);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("strajaStorageCfg", """
            {"dest":{"dim":"minecraft:overworld","x":50,"y":60,"z":50}}
            """);
        assertTrue(migration.migrateServer(data).ok());
        assertEquals(1, ctx.storage().read().setup().dest().x(), "existing config wins");
    }

    @Test
    void playerThiefAndCustodyFlagsImport() {
        UUID uuid = UUID.randomUUID();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("strajaThief", "true");
        data.put("strajaOwed", "128");
        data.put("strajaRepBackup", "7");
        data.put("cpJailed", "1");
        var report = migration.migratePlayer(uuid, data);
        assertTrue(report.ok(), () -> String.join(" | ", report.lines()));
        var thief = ctx.storage().read().thief(uuid.toString());
        assertNotNull(thief);
        assertEquals(128, thief.owed());
        assertEquals(7, thief.repBackup());
        var rec = ctx.prisonerRegister().read().prisoner(uuid.toString());
        assertNotNull(rec);
        assertEquals(PrisonerStatus.IN_CELL, rec.status);
    }
}
