package com.dwurdy.straja.domain.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SavedData aggregate for the storage watch: picked geometry plus the thief
 * and wanted ledgers. Transient runtime caches (chest snapshots, pick modes,
 * pending deposits) deliberately live in the service, not here.
 */
public final class StorageWatchStore {
    private StorageSetup setup;
    private Map<String, ThiefRecord> thieves;

    public StorageSetup setup() {
        if (setup == null) setup = new StorageSetup(null, null, List.of());
        return setup;
    }

    public void setup(StorageSetup s) { setup = s == null ? new StorageSetup(null, null, List.of()) : s; }

    /** Null-safe: partial or corrupt JSON may leave the map unset. */
    public Map<String, ThiefRecord> thieves() {
        if (thieves == null) thieves = new HashMap<>();
        return thieves;
    }

    /** Null-record safe: corrupt JSON can leave a null value under a uuid key. */
    public boolean isThief(String uuid) {
        return thieves().get(uuid) != null;
    }

    public ThiefRecord thief(String uuid) {
        return thieves().get(uuid);
    }

    public void markThief(String uuid, ThiefRecord record) {
        thieves().put(uuid, record);
    }

    public void clearThief(String uuid) {
        thieves().remove(uuid);
    }
}
