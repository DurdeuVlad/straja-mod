package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class PersonnelStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public long storeRevision;
    public Map<String, PersonnelRecord> records = new LinkedHashMap<>();

    public PersonnelRecord get(String playerUuid) { return records.get(playerUuid); }
}
