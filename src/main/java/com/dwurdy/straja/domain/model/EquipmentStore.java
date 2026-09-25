package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class EquipmentStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public long storeRevision;
    public Map<String, EquipmentAsset> assets = new LinkedHashMap<>();
    public Map<String, EquipmentIssue> issues = new LinkedHashMap<>();
    public Map<String, EquipmentObligation> obligations = new LinkedHashMap<>();
    /** Return operation key -> quantity returned; prevents reconnect replay. */
    public Map<String, Long> returnOperations = new LinkedHashMap<>();
    /** Return operation key -> generated proof document ID. */
    public Map<String, String> returnProofs = new LinkedHashMap<>();
}
