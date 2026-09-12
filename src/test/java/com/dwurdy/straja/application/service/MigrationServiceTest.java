package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.adapter.out.migration.KubeJsNbtReader;
import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.support.Fakes;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MigrationServiceTest {
    private StrajaContext ctx;
    private Fakes.TestServer server;
    private MigrationService migration;

    @BeforeEach
    void setup() {
        server = new Fakes.TestServer();
        ctx = Fakes.context(server, new Fakes.FixedClock(1_000_000));
        migration = new MigrationService(ctx, new AuditService(ctx));
    }

    private static final String PLAYER_UUID = "f5d00d3a-2e62-3e34-8a6e-97e2022bdb2f";

    private Map<String, String> fixture() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("straja_setup", """
            {"checkpoints":{"checkpoint_1":{"dimension":"minecraft:overworld","x":25.0,"y":71.0,"z":-42.0,"missionMinutes":30.0}},
             "locations":{"receptionist":{"dimension":"minecraft:overworld","x":10.0,"y":65.0,"z":10.0}},
             "missionMinutes":{"checkpoint_1":30}}
            """);
        data.put("straja_fines", """
            {"nextId":3,"fines":[
              {"id":"F1","target":"mctpilot","targetUuid":"f5d00d3a-2e62-3e34-8a6e-97e2022bdb2f","amount":50,"status":"ISSUED","law":"huliganism","onlineGraceMs":16800000,"onlineElapsedMs":0},
              {"id":"F2","target":"x","targetUuid":"","amount":10,"status":"PAID"}],
             "tasks":[{"id":"FM-F1","kind":"FINE_RECOVERY","status":"OPEN","target":"mctpilot","fineId":"F1","assignees":[]}],
             "appealAbuse":{"mctpilot":{"attempts":[1789170000000],"blockedUntil":0}}}
            """);
        data.put("straja_prisons", """
            {"cells":[{"id":"celula_1","dimension":"minecraft:overworld","minX":1,"minY":60,"minZ":1,"maxX":3,"maxY":63,"maxZ":3}],
             "assignments":{"celula_1":{"target":"mctpilot","targetUuid":"f5d00d3a-2e62-3e34-8a6e-97e2022bdb2f","sentenceId":"S1","assignedAt":1}},
             "waitlist":[],
             "sentences":[{"id":"S1","target":"mctpilot","targetUuid":"f5d00d3a-2e62-3e34-8a6e-97e2022bdb2f","fineId":"F1","status":"ACTIVE","remainingActiveMs":1200000,"cellId":"celula_1","days":1}]}
            """);
        data.put("straja_rooms", """
            {"rooms":[{"id":"camera_1","order":1,"dimension":"minecraft:overworld","minX":40,"minY":60,"minZ":40,"maxX":44,"maxY":64,"maxZ":44,"hasDoor":true,"doorX":40,"doorY":61,"doorZ":42}],
             "assignments":{"camera_1":{"player":"mctpilot","playerUuid":"f5d00d3a-2e62-3e34-8a6e-97e2022bdb2f","assignedAt":1}},
             "waitlist":[{"player":"altul","playerUuid":"00000000-0000-0000-0000-000000000099","rank":1,"queuedAt":5,"notifiedPosition":0}]}
            """);
        data.put("straja_complaints", """
            {"nextId":2,"complaints":[
              {"id":"C1","complainant":"mctpilot","complainantUuid":"f5d00d3a-2e62-3e34-8a6e-97e2022bdb2f","accused":"guard1","category":"abuz","status":"SUBMITTED","createdAt":1}]}
            """);
        data.put("straja_archive", """
            {"nextFolderNumber":2,"nextSheetNumber":2,"nextCatalogNumber":1,"nextCopyNumber":1,
             "archivists":{"f5d00d3a-2e62-3e34-8a6e-97e2022bdb2f":"mctpilot"},
             "folders":{"D-1":{"id":"D-1","title":"Dosarul X","department":"Straja","owner":"mctpilot","ownerUuid":"f5d00d3a-2e62-3e34-8a6e-97e2022bdb2f","status":"OPEN","createdAt":1,"sheetIds":["A-1"],"catalogIds":[]}},
             "sheets":{"A-1":{"id":"A-1","folderId":"D-1","type":"PROCES","title":"Interogatoriu","content":"declarat","author":"mctpilot","authorUuid":"f5d00d3a-2e62-3e34-8a6e-97e2022bdb2f","status":"SIGNED","revision":1,"recipients":[],"createdAt":1,"updatedAt":2,"revoked":false}},
             "catalog":{},"copies":[],"operations":[],"pendingDeliveries":[]}
            """);
        data.put("straja_missions", """
            [{"id":"7","issuer":"dwurdy","issuerUuid":"u","status":"CLOSED","title":"patrula"}]
            """);
        data.put("straja_mission_drafts", """
            {"dwurdy":{"title":"patrula","scope":"oras","objective":"patrulare","reward":50,"deadlineMinutes":30}}
            """);
        data.put("straja_mission_reward_budgets", """
            {"41234:u":100}
            """);
        data.put("straja_cuffed_players", """
            {"f5d00d3a-2e62-3e34-8a6e-97e2022bdb2f":{"target":"mctpilot","targetUuid":"f5d00d3a-2e62-3e34-8a6e-97e2022bdb2f","issuer":"dwurdy","issuerUuid":"u","cuffedAt":1,"maxDistance":32,"reason":"arest","originalSelectedSlot":-1}}
            """);
        data.put("straja_audit", """
            {"entries":[{"id":"1-1","timestamp":1789170735422,"actorUuid":"u","actorName":"dwurdy","actorTier":"COMMISSIONER","action":"setup","targetUuid":"","targetName":"","result":"SUCCESS","reason":"","correlationId":"b-1","details":null}]}
            """);
        data.put("straja_debug_commissioner_uuid", "f5d00d3a-2e62-3e34-8a6e-97e2022bdb2f");
        return data;
    }

    @Test
    void migratesAllServerStores() {
        var report = migration.migrateServer(fixture());
        assertTrue(report.ok(), String.join("\n", report.lines()));

        var setup = ctx.setup().read();
        assertTrue(setup.checkpoints.stream().anyMatch(c -> c.id.equals("checkpoint_1") && c.x == 25.0));
        assertNotNull(setup.locations.get("receptionist"));

        var fines = ctx.fines().read();
        assertEquals(2, fines.fines.size());
        assertEquals("ISSUED", fines.fines.get(0).status);
        assertEquals(1, fines.tasks.size());
        assertEquals(3, fines.nextId);
        assertTrue(fines.appealAbuse.containsKey("mctpilot"));

        var prison = ctx.prison().read();
        assertEquals(1, prison.cells.size());
        assertEquals(1, prison.sentences.size());
        assertEquals("S1", prison.sentences.get(0).id);
        assertTrue(prison.assignments.containsKey("celula_1"));

        var rooms = ctx.rooms().read();
        assertEquals(1, rooms.rooms.size());
        assertEquals("camera_1", rooms.rooms.get(0).id);
        assertTrue(rooms.rooms.get(0).hasDoor);
        assertEquals(1, rooms.waitlist.size());

        var complaints = ctx.complaints().read();
        assertEquals(1, complaints.complaints.size());
        assertEquals("SUBMITTED", complaints.complaints.get(0).status);

        var archive = ctx.archive().read();
        assertEquals(1, archive.folders.size());
        assertEquals(1, archive.sheets.size());
        assertEquals("SIGNED", archive.sheets.get("A-1").status);
        assertTrue(archive.archivists.containsKey(PLAYER_UUID));
        assertEquals(2, archive.nextFolderNumber);

        var missions = ctx.missions().read();
        assertEquals(1, missions.missions.size());
        assertEquals("7", missions.missions.get(0).id);
        assertEquals(8, missions.nextId); // bumped past imported numeric id
        assertFalse(missions.drafts.isEmpty());
        assertEquals(100, missions.rewardBudgets.get("41234:u"));

        var custody = ctx.custody().read();
        assertEquals(1, custody.cuffed.size());
        assertEquals("mctpilot", custody.cuffed.get(PLAYER_UUID).target);

        var auditEntries = ctx.audit().entries();
        assertTrue(auditEntries.stream().anyMatch(e -> e.action.equals("setup") && e.details.contains("kubejs id=1-1")));
        assertTrue(auditEntries.stream().anyMatch(e -> e.action.equals("kubejs_migration")));
    }

    @Test
    void migrationIsIdempotent() {
        var first = migration.migrateServer(fixture());
        assertTrue(first.ok());
        var second = migration.migrateServer(fixture());
        assertTrue(second.ok(), String.join("\n", second.lines()));

        assertEquals(2, ctx.fines().read().fines.size());
        assertEquals(1, ctx.prison().read().sentences.size());
        assertEquals(1, ctx.rooms().read().rooms.size());
        assertEquals(1, ctx.complaints().read().complaints.size());
        assertEquals(1, ctx.archive().read().sheets.size());
        assertEquals(1, ctx.missions().read().missions.size());
        assertEquals(1, ctx.custody().read().cuffed.size());
    }

    @Test
    void migratesPlayerStateDraftAndArchivist() {
        UUID uuid = UUID.fromString(PLAYER_UUID);
        Map<String, String> player = new LinkedHashMap<>();
        player.put("KubeJSPersistentData.straja_state",
                "{\"rank\":2.0,\"invited\":true,\"quizIndex\":3.0,\"quizPassed\":true,\"duty\":false,\"unpaidSalary\":25.0}");
        player.put("KubeJSPersistentData.straja_fine_draft",
                "{\"target\":\"civ1\",\"targetUuid\":\"\",\"amount\":50,\"law\":\"huliganism\",\"description\":\"x\",\"writtenAt\":1}");
        player.put("KubeJSPersistentData.straja_archivist", "1");

        var report = migration.migratePlayer(uuid, player);
        assertTrue(report.ok(), String.join("\n", report.lines()));

        var state = ctx.players().read(uuid);
        assertEquals(2, state.rank);
        assertTrue(state.invited);
        assertTrue(state.quizPassed);
        assertEquals(25, state.unpaidSalary);

        var fines = ctx.fines().read();
        assertTrue(fines.drafts.containsKey(PLAYER_UUID));
        assertEquals("civ1", fines.drafts.get(PLAYER_UUID).target);

        assertTrue(ctx.archive().read().archivists.containsKey(PLAYER_UUID));
    }

    @Test
    void skipsCorruptEntriesWithoutFailingRest() {
        var data = fixture();
        data.put("straja_fines", "{not valid json");
        var report = migration.migrateServer(data);
        assertEquals(1, report.errors());
        // Other stores still migrated
        assertEquals(1, ctx.prison().read().sentences.size());
        assertEquals(1, ctx.rooms().read().rooms.size());
    }

    // ------------------------------------------------------------ real fixtures

    private Path fixtureRoot() {
        try {
            var url = getClass().getResource("/fixtures/kubejs_persistent_data.nbt");
            assertNotNull(url, "missing test fixture");
            return Path.of(url.toURI()).getParent();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void readsRealKubeJsPersistentDataNbt() throws Exception {
        var strings = KubeJsNbtReader.readStrings(fixtureRoot().resolve("kubejs_persistent_data.nbt"));
        assertTrue(strings.containsKey("straja_setup"), "expected straja_setup, got " + strings.keySet());
        assertTrue(strings.containsKey("straja_audit"));

        // and the whole map migrates cleanly
        var report = migration.migrateServer(strings);
        assertTrue(report.ok(), String.join("\n", report.lines()));
    }

    @Test
    void readsRealPlayerDatAndMigrates() throws Exception {
        var playerFile = fixtureRoot().resolve("playerdata").resolve(PLAYER_UUID + ".dat");
        assertTrue(java.nio.file.Files.exists(playerFile));
        var strings = KubeJsNbtReader.readStrings(playerFile);
        var stateKey = strings.keySet().stream()
                .filter(k -> k.endsWith("straja_state")).findFirst();
        assertTrue(stateKey.isPresent(), "no straja_state in " + strings.keySet());

        var report = migration.migratePlayer(UUID.fromString(PLAYER_UUID), strings);
        assertTrue(report.ok(), String.join("\n", report.lines()));
        var state = ctx.players().read(UUID.fromString(PLAYER_UUID));
        assertTrue(state.rank >= 0);
        assertTrue(state.invited || state.rank > 0); // real fixture: invited+rank 2
    }
}
