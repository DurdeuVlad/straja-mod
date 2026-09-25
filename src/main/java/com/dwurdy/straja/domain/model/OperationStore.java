package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class OperationStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public long storeRevision;
    public Map<String, OperationRecord> operations = new LinkedHashMap<>();
    public Map<String, String> idempotencyIndex = new LinkedHashMap<>();
}
