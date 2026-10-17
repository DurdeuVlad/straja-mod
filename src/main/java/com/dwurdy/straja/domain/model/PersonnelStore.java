package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class PersonnelStore {
    /** v2: remodelled career ladder — legacy enum names remap at parse time. */
    public static final int CURRENT_SCHEMA_VERSION = 2;
    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public long storeRevision;
    public Map<String, PersonnelRecord> records = new LinkedHashMap<>();

    public PersonnelRecord get(String playerUuid) { return records.get(playerUuid); }

    /** Restores the careerTrack = careerGrade.track() invariant after a
     *  lenient enum remap or a partial old-schema read. */
    public void normalize() {
        ensureRecords();
        for (PersonnelRecord record : records.values()) {
            if (record != null && record.careerGrade != null
                    && record.careerTrack != record.careerGrade.track()) {
                record.careerTrack = record.careerGrade.track();
            }
        }
    }
    /** Lazily re-creates {@code records} after a corrupt or partial read. */
    public Map<String, PersonnelRecord> ensureRecords() {
        if (records == null) records = new LinkedHashMap<>();
        return records;
    }

}
