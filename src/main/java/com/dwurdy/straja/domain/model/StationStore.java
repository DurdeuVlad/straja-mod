package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class StationStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public long storeRevision;
    public Map<String, Station> stations = new LinkedHashMap<>();

    public static StationStore defaults() {
        StationStore store = new StationStore();
        Station hq = new Station();
        hq.stationId = "hq";
        hq.displayName = "Headquarters";
        hq.headquarters = true;
        store.stations.put(hq.stationId, hq);
        return store;
    }
    /** Lazily re-creates {@code stations} after a corrupt or partial read. */
    public Map<String, Station> ensureStations() {
        if (stations == null) stations = new LinkedHashMap<>();
        return stations;
    }

}
