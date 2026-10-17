package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class SettlementStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public long storeRevision;
    public Map<String, Settlement> settlements = new LinkedHashMap<>();
    public Map<String, String> settlementKeyIndex = new LinkedHashMap<>();
    /** Lazily re-creates {@code settlementKeyIndex} after a corrupt or partial read. */
    public Map<String, String> ensureSettlementKeyIndex() {
        if (settlementKeyIndex == null) settlementKeyIndex = new LinkedHashMap<>();
        return settlementKeyIndex;
    }

}
