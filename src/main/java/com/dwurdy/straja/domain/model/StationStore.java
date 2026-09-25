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
}
