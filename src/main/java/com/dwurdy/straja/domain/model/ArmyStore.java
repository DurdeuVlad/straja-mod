package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persisted army state: one store for every station unit and its operations.
 * {@code memberIndex} (player uuid → unitId) is the one-unit-per-member
 * lookup; writers heal torn entries on contact — a stale index row pointing
 * at a roster that no longer lists the player is dropped, not trusted.
 */
public class ArmyStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public long storeRevision;
    public Map<String, ArmyUnit> units = new LinkedHashMap<>();
    public Map<String, ArmyOperation> operations = new LinkedHashMap<>();
    public Map<String, String> memberIndex = new LinkedHashMap<>();

    public Map<String, ArmyUnit> ensureUnits() {
        if (units == null) units = new LinkedHashMap<>();
        return units;
    }

    public Map<String, ArmyOperation> ensureOperations() {
        if (operations == null) operations = new LinkedHashMap<>();
        return operations;
    }

    public Map<String, String> ensureMemberIndex() {
        if (memberIndex == null) memberIndex = new LinkedHashMap<>();
        return memberIndex;
    }
}
