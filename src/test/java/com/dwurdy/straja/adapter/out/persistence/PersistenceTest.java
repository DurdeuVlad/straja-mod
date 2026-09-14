package com.dwurdy.straja.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.domain.model.AuditEntry;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.support.MemoryStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Repository round-trip, corrupt-backup recovery and bounded retention. */
class PersistenceTest {
    private Map<String, MemoryStore> memory;
    private StoreAccess access;

    @BeforeEach
    void setUp() {
        memory = new HashMap<>();
        access = name -> memory.computeIfAbsent(name, k -> new MemoryStore());
    }

    @Test
    void playerStateRoundTrips() {
        var repo = new SavedPlayerStateRepository(access);
        UUID id = UUID.randomUUID();
        GuardState state = new GuardState();
        state.rank = 2;
        state.duty = true;
        state.unpaidSalary = 340;
        state.route.add("checkpoint_1");
        state.trainingPassed.put("guard_cuffs_consent", true);
        state.specializations.add("Instructor");
        repo.write(id, state);

        GuardState loaded = repo.read(id);
        assertEquals(2, loaded.rank);
        assertTrue(loaded.duty);
        assertEquals(340, loaded.unpaidSalary);
        assertEquals("checkpoint_1", loaded.route.get(0));
        assertEquals(Boolean.TRUE, loaded.trainingPassed.get("guard_cuffs_consent"));
        assertTrue(loaded.specializations.contains("Instructor"), "specializations persist");
    }

    @Test
    void legacyPlayerStateWithoutSpecializationsReads() {
        var repo = new SavedPlayerStateRepository(access);
        UUID id = UUID.randomUUID();
        // Serialized before the field existed — Gson leaves it null.
        access.store("players").put(id.toString(), "{\"rank\":3}");

        GuardState state = repo.read(id);
        assertEquals(3, state.rank);
        assertNotNull(state.specializations, "absent field re-inits to an empty set");
        state.specializations.add("Recrutor"); // must not NPE on first write-back
        repo.write(id, state);
        assertTrue(repo.read(id).specializations.contains("Recrutor"));
        assertEquals("NONE", state.applicationState, "absent application state normalizes");
    }

    @Test
    void corruptPlayerStateIsBackedUpAndReset() {
        var repo = new SavedPlayerStateRepository(access);
        UUID id = UUID.randomUUID();
        access.store("players").put(id.toString(), "{not-json!!");

        GuardState state = repo.read(id);
        assertEquals(0, state.rank); // fresh default
        String backup = access.store("players").get(id + "_corrupt_backup");
        assertEquals("{not-json!!", backup);
        assertNull(access.store("players").get(id.toString()));
    }

    @Test
    void corruptAggregateStoreIsBackedUp() {
        var repo = new SavedStores.Setup(access);
        access.store("setup").put("json", "{broken");
        SetupData data = repo.read();
        // resets to defaults: 4 seeded checkpoint slots, none placed
        assertEquals(4, data.checkpoints.size());
        assertTrue(data.checkpoints.stream().noneMatch(SetupData.Checkpoint::isPlaced));
        assertEquals("{broken", access.store("setup").get("corrupt_backup"));
    }

    @Test
    void auditIsBounded() {
        var repo = new SavedStores.Audit(access, 5);
        for (int i = 0; i < 10; i++) {
            AuditEntry entry = new AuditEntry();
            entry.action = "a" + i;
            repo.append(entry);
        }
        assertEquals(5, repo.entries().size());
        assertEquals("a9", repo.entries().get(4).action);
        assertEquals("a5", repo.tail(5).get(0).action);
    }

    @Test
    void aggregateStoresRoundTrip() {
        var missions = new SavedStores.Missions(access);
        var store = new com.dwurdy.straja.domain.model.MissionStore();
        var mission = new com.dwurdy.straja.domain.model.Mission();
        mission.id = "M1";
        mission.status = "ACCEPTED";
        mission.reward = 100;
        store.missions.add(mission);
        missions.write(store);

        var loaded = missions.read();
        assertEquals(1, loaded.missions.size());
        assertEquals("ACCEPTED", loaded.find("M1").status);
        assertEquals(100, loaded.find("M1").reward);
    }

    @Test
    void backupManifestCoversEverySourceStoreWithoutRecursion() {
        var stores = StrajaDataProvider.BackupPolicy.SOURCE_STORES;
        assertEquals(java.util.Set.of("setup", "audit", "inbox", "missions", "fines",
                "prisons", "rooms", "complaints", "custody", "archive", "npcs",
                "test", "players"), java.util.Set.copyOf(stores),
                "the snapshot must retain every persisted source store");
        assertFalse(stores.contains("backup"),
                "the backup store must never snapshot itself");
        assertEquals(stores.size(), java.util.Set.copyOf(stores).size(),
                "the manifest must not contain duplicates");
        assertTrue(StrajaDataProvider.BackupPolicy.RETENTION > 0,
                "snapshot retention must stay bounded");
    }
}
