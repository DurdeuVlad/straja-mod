package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** SavedData aggregate for labor camps (AT7). */
public final class LaborCampStore {
    public static final int CURRENT_SCHEMA = 1;

    public int schemaVersion = CURRENT_SCHEMA;
    private Map<String, LaborCampRecord> camps;

    public Map<String, LaborCampRecord> camps() {
        if (camps == null) camps = new LinkedHashMap<>();
        camps.values().removeIf(java.util.Objects::isNull);
        return camps;
    }

    public LaborCampRecord camp(String id) {
        return camps().get(id);
    }

    public void put(LaborCampRecord record) {
        camps().put(record.id, record);
    }

    public void remove(String id) {
        camps().remove(id);
    }
}
